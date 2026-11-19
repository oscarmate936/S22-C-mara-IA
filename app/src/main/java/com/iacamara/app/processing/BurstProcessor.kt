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
    /** Ráfaga para elegir el mejor momento (nitidez, ojos abiertos, sonrisa). */
    MOMENT,
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

        // En HDR la última toma es la oscura: se procesa aparte.
        val normalCount = if (kind == BurstKind.HDR && frames.size >= 2) frames.size - 1 else frames.size
        // Alineación v2 (zonas, giro, subpíxel) + mezcla robusta.
        val merger = if (kind == BurstKind.NIGHT || kind == BurstKind.HDR) RobustMerger(w, h, normalCount) else null
        val focus = if (kind == BurstKind.MACRO) FocusStacker(w, h) else null
        merger?.add(buf)
        focus?.add(buf)
        onProgress(1, frames.size)

        for (k in 1 until normalCount) {
            val bmp = BitmapFactory.decodeByteArray(frames[k], 0, frames[k].size, opts) ?: continue
            if (bmp.width != w || bmp.height != h) { bmp.recycle(); continue }
            bmp.getPixels(buf, 0, w, 0, 0, w, h)
            bmp.recycle()
            val t = TileAlign.estimate(refLuma, Align.luma(buf), w, h).transform
            val aligned = TileAlign.warp(buf, w, h, t)
            merger?.add(aligned)
            focus?.add(aligned)
            onProgress(k + 1, frames.size)
        }

        var out = merger?.result(shadowLift) ?: focus!!.result()
        var used = merger?.count ?: focus!!.count
        if (kind == BurstKind.HDR && frames.size >= 2) {
            val last = frames.last()
            val under = BitmapFactory.decodeByteArray(last, 0, last.size, opts)
            if (under != null && under.width == w && under.height == h) {
                under.getPixels(buf, 0, w, 0, 0, w, h)
                under.recycle()
                val t = TileAlign.estimate(refLuma, Align.lumaWithGain(buf, hdrRatio), w, h).transform
                out = HdrMerger(w, h).merge(out, TileAlign.warp(buf, w, h, t), 0, 0, hdrRatio)
                used++
            }
            onProgress(frames.size, frames.size)
        }
        val result = Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888)
        return result to used
    }

    private fun save(bmp: Bitmap, firstJpeg: ByteArray, rotationDegrees: Int, kind: BurstKind, count: Int): Uri? {
        val suffix = when (kind) { BurstKind.NIGHT -> "_NOCHE"; BurstKind.MACRO -> "_MACRO"; BurstKind.HDR -> "_HDR"; BurstKind.MOMENT -> "_MOMENTO" }
        val what = when (kind) { BurstKind.NIGHT -> "noche"; BurstKind.MACRO -> "macro apilado"; BurstKind.HDR -> "HDR"; BurstKind.MOMENT -> "mejor momento" }
        return PhotoSaver.save(context, bmp, firstJpeg, rotationDegrees, suffix, "IA Cámara · $what ($count tomas)")
    }

    companion object {
        /** La toma oscura del HDR tiene 4× menos luz (2 pasos)… */
        const val HDR_RATIO = 4f

        /** …u 8× (3 pasos) si una zona grande está muy quemada (contraluz fuerte, ventana). */
        fun hdrRatioFor(clippedArea: Float): Float = if (clippedArea > 0.10f) 8f else HDR_RATIO
    }
}
