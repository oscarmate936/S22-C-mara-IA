package com.iacamara.app.processing

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.iacamara.app.ai.FaceObs
import com.iacamara.app.ai.TasteCore
import java.io.ByteArrayInputStream
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.math.max

/** Lo que se aplica al «Mejorar» una foto. */
data class EditParams(
    val straighten: Boolean = false,
    val perspective: Boolean = false,
    /** Medidos sobre la vista previa: inclinación (grados) y convergencia (por píxel de la vista previa). */
    val tiltDeg: Float = 0f,
    val keystonePreview: Float = 0f,
    val previewWidth: Int = 1,
    val zones: ZoneRetouch.Params = ZoneRetouch.Params(sky = false, skin = false, foliage = false),
) {
    val isNeutral get() = !straighten && !perspective && zones.isNeutral
}

/**
 * Editor con IA: enderezar, perspectiva y retoque por zonas, siempre desde la foto ORIGINAL
 * (guardada aparte la primera vez), así se puede volver a editar o restaurar sin perder calidad.
 */
object Editor {
    private fun dir(context: Context) = File(context.filesDir, "originales").apply { mkdirs() }
    private fun key(uri: Uri) = uri.lastPathSegment ?: uri.toString().hashCode().toString()
    private const val KEEP = 60

    fun hasOriginal(context: Context, uri: Uri) = File(dir(context), key(uri) + ".jpg").exists()

    /** Bytes de la foto original (la primera vez se guarda una copia). */
    fun original(context: Context, uri: Uri, store: Boolean = true): ByteArray? {
        val f = File(dir(context), key(uri) + ".jpg")
        if (f.exists()) return f.readBytes()
        val bytes = runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull() ?: return null
        if (store) {
            f.writeBytes(bytes)
            dir(context).listFiles()?.sortedByDescending { it.lastModified() }?.drop(KEEP)?.forEach { it.delete() }
        }
        return bytes
    }

    /** Decodifica derecha (según la orientación EXIF), con el lado mayor ≤ [maxSide]. */
    fun decodeUpright(bytes: ByteArray, maxSide: Int = Int.MAX_VALUE): Bitmap? {
        val b = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, b)
        var sample = 1
        while (max(b.outWidth, b.outHeight) / (sample * 2) >= maxSide) sample *= 2
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888 }) ?: return null
        val o = runCatching { ExifInterface(ByteArrayInputStream(bytes)).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        val deg = when (o) { ExifInterface.ORIENTATION_ROTATE_90 -> 90; ExifInterface.ORIENTATION_ROTATE_180 -> 180; ExifInterface.ORIENTATION_ROTATE_270 -> 270; else -> 0 }
        var out = if (deg != 0) Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(deg.toFloat()) }, true).also { bmp.recycle() } else bmp
        if (max(out.width, out.height) > maxSide) {
            val s = maxSide.toFloat() / max(out.width, out.height)
            out = Bitmap.createScaledBitmap(out, (out.width * s).toInt(), (out.height * s).toInt(), true)
        }
        return out
    }

    /** Rostros (normalizados) con ojos abiertos, para la piel y la calificación. */
    fun faces(bmp: Bitmap): List<FaceObs> {
        val det = FaceDetection.getClient(
            FaceDetectorOptions.Builder().setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
                .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL).setMinFaceSize(0.05f).build(),
        )
        return try {
            runCatching { Tasks.await(det.process(InputImage.fromBitmap(bmp, 0)), 5, TimeUnit.SECONDS) }.getOrNull().orEmpty().map { f ->
                val r = f.boundingBox
                FaceObs(
                    r.left / bmp.width.toFloat(), r.top / bmp.height.toFloat(), r.right / bmp.width.toFloat(), r.bottom / bmp.height.toFloat(),
                    listOfNotNull(f.leftEyeOpenProbability, f.rightEyeOpenProbability).minOrNull(), f.smilingProbability,
                )
            }
        } finally { det.close() }
    }

    fun render(px: IntArray, w: Int, h: Int, masks: ZoneMasks.Masks?, p: EditParams): IntArray {
        var out = if (p.zones.isNeutral) px else ZoneRetouch.apply(px, w, h, masks, p.zones)
        if (p.straighten || p.perspective) {
            val k = if (p.perspective) p.keystonePreview * p.previewWidth / w else 0f
            out = Straighten.apply(out, w, h, if (p.straighten) p.tiltDeg else 0f, k)
        }
        return out
    }

    /** Aplica la edición a resolución completa (desde la original) y reemplaza la foto. */
    fun save(context: Context, uri: Uri, p: EditParams, faces: List<FaceObs>): Boolean {
        val orig = original(context, uri) ?: return false
        if (p.isNeutral) return restore(context, uri)
        val bmp = decodeUpright(orig) ?: return false
        val w = bmp.width; val h = bmp.height
        val px = IntArray(w * h).also { bmp.getPixels(it, 0, w, 0, 0, w, h) }
        bmp.recycle()
        val masks = if (p.zones.sky || p.zones.skin || p.zones.foliage) ZoneMasks.compute(px, w, h, faces.map { floatArrayOf(it.left, it.top, it.right, it.bottom) }) else null
        val out = render(px, w, h, masks, p)
        val ob = Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888)
        return PhotoSaver.write(context, uri, ob, orig, 0, "IA Cámara · mejorada", forceOrientation = ExifInterface.ORIENTATION_NORMAL).also { ob.recycle() }
    }

    /** Vuelve a la foto tal como salió de la cámara. */
    fun restore(context: Context, uri: Uri): Boolean {
        val f = File(dir(context), key(uri) + ".jpg")
        if (!f.exists()) return true
        return runCatching { context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(f.readBytes()) } != null }.getOrDefault(false)
    }

    /**
     * Retoque automático al tomar la foto (si está activado): zonas con la intensidad y la calidez
     * de tu estilo. No endereza solo (eso recortaría la foto sin preguntarte).
     */
    fun autoRetouch(context: Context, uri: Uri, taste: TasteCore, scene: String): Boolean {
        val orig = original(context, uri) ?: return false
        val bmp = decodeUpright(orig) ?: return false
        val w = bmp.width; val h = bmp.height
        val small = decodeUpright(orig, 1024)
        val faces = small?.let { faces(it) }.orEmpty()
        small?.recycle()
        val px = IntArray(w * h).also { bmp.getPixels(it, 0, w, 0, 0, w, h) }
        bmp.recycle()
        val masks = ZoneMasks.compute(px, w, h, faces.map { floatArrayOf(it.left, it.top, it.right, it.bottom) })
        val p = ZoneRetouch.Params(strength = taste.zoneStrength(0.5f), warmth = taste.warmthBias(scene))
        val out = ZoneRetouch.apply(px, w, h, masks, p)
        val ob = Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888)
        return PhotoSaver.write(context, uri, ob, orig, 0, "IA Cámara · retoque por zonas", forceOrientation = ExifInterface.ORIENTATION_NORMAL).also { ob.recycle() }
    }
}
