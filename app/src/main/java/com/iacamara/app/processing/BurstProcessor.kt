package com.iacamara.app.processing

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayInputStream
import java.text.SimpleDateFormat
import java.util.Locale

/** Tipo de toma múltiple. */
enum class BurstKind {
    NIGHT,
    MACRO,
    /** Varias tomas normales + una 2 pasos más oscura (siempre la última). */
    HDR,
}

/**
 * Convierte una ráfaga de JPEG en una sola foto: decodifica, alinea, combina y guarda
 * con los datos EXIF de la primera toma (orientación, ISO, focal…).
 */
class BurstProcessor(private val context: Context) {

    /**
     * @param rotationDegrees rotación que indicó la cámara para la primera toma.
     * @param onProgress (hechas, total)
     * @return Uri de la foto guardada, o null si falló.
     */
    fun process(
        frames: List<ByteArray>,
        kind: BurstKind,
        rotationDegrees: Int,
        shadowLift: Float,
        hdrRatio: Float = HDR_RATIO,
        onProgress: (Int, Int) -> Unit,
    ): Uri? {
        val (result, count) = combine(frames, kind, shadowLift, hdrRatio, onProgress) ?: return null
        return save(result, frames[0], rotationDegrees, kind, count).also { result.recycle() }
    }

    /** Decodifica, alinea y combina la ráfaga. Devuelve la imagen final y cuántas tomas se usaron. */
    fun combine(
        frames: List<ByteArray>,
        kind: BurstKind,
        shadowLift: Float,
        hdrRatio: Float = HDR_RATIO,
        onProgress: (Int, Int) -> Unit = { _, _ -> },
    ): Pair<Bitmap, Int>? {
        if (frames.isEmpty()) return null
        val opts = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
        val first = BitmapFactory.decodeByteArray(frames[0], 0, frames[0].size, opts) ?: return null
        val w = first.width
        val h = first.height
        val buf = IntArray(w * h)
        first.getPixels(buf, 0, w, 0, 0, w, h)
        first.recycle()
        val refLuma = Align.luma(buf)

        val mean = if (kind == BurstKind.NIGHT || kind == BurstKind.HDR) MeanStacker(w, h) else null
        val focus = if (kind == BurstKind.MACRO) FocusStacker(w, h) else null
        mean?.add(buf)
        focus?.add(buf)
        onProgress(1, frames.size)

        // En HDR la última toma es la oscura: se procesa aparte.
        val normalCount = if (kind == BurstKind.HDR && frames.size >= 2) frames.size - 1 else frames.size
        for (k in 1 until normalCount) {
            val bmp = BitmapFactory.decodeByteArray(frames[k], 0, frames[k].size, opts) ?: continue
            if (bmp.width != w || bmp.height != h) { bmp.recycle(); continue }
            bmp.getPixels(buf, 0, w, 0, 0, w, h)
            bmp.recycle()
            val (dx, dy) = Align.estimateShift(refLuma, Align.luma(buf), w, h)
            mean?.add(buf, dx, dy)
            focus?.add(buf, dx, dy)
            onProgress(k + 1, frames.size)
        }

        var out = mean?.result(shadowLift) ?: focus!!.result()
        var used = mean?.count ?: focus!!.count
        if (kind == BurstKind.HDR && frames.size >= 2) {
            val last = frames.last()
            val under = BitmapFactory.decodeByteArray(last, 0, last.size, opts)
            if (under != null && under.width == w && under.height == h) {
                under.getPixels(buf, 0, w, 0, 0, w, h)
                under.recycle()
                val (dx, dy) = Align.estimateShift(refLuma, Align.lumaWithGain(buf, hdrRatio), w, h)
                out = HdrMerger(w, h).merge(out, buf, dx, dy, hdrRatio)
                used++
            }
            onProgress(frames.size, frames.size)
        }
        val result = Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888)
        return result to used
    }

    private fun save(bmp: Bitmap, firstJpeg: ByteArray, rotationDegrees: Int, kind: BurstKind, count: Int): Uri? {
        val resolver = context.contentResolver
        val name = "IACAM_" + SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(System.currentTimeMillis()) +
            when (kind) { BurstKind.NIGHT -> "_NOCHE"; BurstKind.MACRO -> "_MACRO"; BurstKind.HDR -> "_HDR" }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/IA Camara")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return null
        try {
            resolver.openOutputStream(uri)?.use { bmp.compress(Bitmap.CompressFormat.JPEG, 95, it) }
            resolver.openFileDescriptor(uri, "rw")?.use { pfd ->
                val src = ExifInterface(ByteArrayInputStream(firstJpeg))
                val dst = ExifInterface(pfd.fileDescriptor)
                for (tag in COPY_TAGS) src.getAttribute(tag)?.let { dst.setAttribute(tag, it) }
                val srcOrientation = src.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_UNDEFINED)
                val orientation = if (srcOrientation > ExifInterface.ORIENTATION_NORMAL) srcOrientation else when (rotationDegrees) {
                    90 -> ExifInterface.ORIENTATION_ROTATE_90
                    180 -> ExifInterface.ORIENTATION_ROTATE_180
                    270 -> ExifInterface.ORIENTATION_ROTATE_270
                    else -> ExifInterface.ORIENTATION_NORMAL
                }
                dst.setAttribute(ExifInterface.TAG_ORIENTATION, orientation.toString())
                val what = when (kind) { BurstKind.NIGHT -> "noche"; BurstKind.MACRO -> "macro apilado"; BurstKind.HDR -> "HDR" }
                dst.setAttribute(ExifInterface.TAG_SOFTWARE, "IA Cámara · $what ($count tomas)")
                dst.saveAttributes()
            }
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            return uri
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            return null
        }
    }

    companion object {
        /** La toma oscura del HDR tiene 4× menos luz (2 pasos)… */
        const val HDR_RATIO = 4f

        /** …u 8× (3 pasos) si una zona grande está muy quemada (contraluz fuerte, ventana). */
        fun hdrRatioFor(clippedArea: Float): Float = if (clippedArea > 0.10f) 8f else HDR_RATIO
        private val COPY_TAGS = listOf(
            ExifInterface.TAG_MAKE, ExifInterface.TAG_MODEL, ExifInterface.TAG_DATETIME,
            ExifInterface.TAG_DATETIME_ORIGINAL, ExifInterface.TAG_DATETIME_DIGITIZED,
            ExifInterface.TAG_F_NUMBER, ExifInterface.TAG_FOCAL_LENGTH,
            ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM, ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY,
            ExifInterface.TAG_EXPOSURE_TIME, ExifInterface.TAG_WHITE_BALANCE, ExifInterface.TAG_FLASH,
        )
    }
}
