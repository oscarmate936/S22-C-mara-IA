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
    /** Escena con la que se tomó (para los límites de color). */
    val scene: String = "ALL",
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

    /**
     * Carga una foto de la galería DERECHA y nítida, con el lado mayor ≤ [side]. No se usa la
     * miniatura del sistema: en algunos teléfonos (S22) viene sin girar según la orientación EXIF
     * y a baja resolución, y la foto se veía de lado y borrosa.
     */
    fun load(context: Context, uri: Uri, side: Int): Bitmap? = runCatching {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
        decodeUpright(bytes, side)
    }.getOrNull()

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
        // El retoque nunca deja el color por encima del nivel de los profesionales.
        if (!p.zones.isNeutral) out = NaturalProfile.apply(out, w, h, calibrationScene(p.scene))
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

    /** Escena de la app → grupo de calibración (FiveK). */
    fun calibrationScene(scene: String): String = when (scene) {
        "SUNSET" -> "SUNSET"
        "LANDSCAPE", "BEACH" -> "LANDSCAPE"
        "CITY" -> "CITY"
        "PORTRAIT" -> "PORTRAIT"
        "NIGHT" -> "NIGHT"
        else -> "ALL"
    }

    /**
     * Al guardar cada foto: perfil natural (color sin saturar, al nivel de los profesionales) y,
     * si está activado, el retoque por zonas con tu estilo. No endereza solo (eso recortaría la
     * foto sin preguntarte). Si la foto ya está dentro de lo natural, no se toca.
     * @return true si se reescribió la foto.
     */
    fun finishPhoto(context: Context, uri: Uri, taste: TasteCore, scene: String, natural: Boolean, retouch: Boolean): Boolean {
        if (!natural && !retouch) return false
        val cal = calibrationScene(scene)
        val current = runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull() ?: return false
        val small = decodeUpright(current, 1024) ?: return false
        if (!retouch) {
            // ¿Hace falta? Solo si el color pasa del nivel de los profesionales para esta escena.
            val spx = IntArray(small.width * small.height).also { small.getPixels(it, 0, small.width, 0, 0, small.width, small.height) }
            val lim = Calibration.CHROMA[cal] ?: Calibration.CHROMA.getValue("ALL")
            if (NaturalProfile.chroma95(spx) <= lim.knee * 1.05f) { small.recycle(); return false }
        }
        val faces = if (retouch) faces(small) else emptyList()
        small.recycle()
        val orig = original(context, uri) ?: return false
        val bmp = decodeUpright(orig) ?: return false
        val w = bmp.width; val h = bmp.height
        var px = IntArray(w * h).also { bmp.getPixels(it, 0, w, 0, 0, w, h) }
        bmp.recycle()
        if (retouch) {
            val masks = ZoneMasks.compute(px, w, h, faces.map { floatArrayOf(it.left, it.top, it.right, it.bottom) })
            px = ZoneRetouch.apply(px, w, h, masks, ZoneRetouch.Params(strength = taste.zoneStrength(0.5f), warmth = taste.warmthBias(scene)))
        }
        if (natural) px = NaturalProfile.apply(px, w, h, cal)
        val ob = Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
        val what = listOfNotNull("perfil natural".takeIf { natural }, "retoque por zonas".takeIf { retouch }).joinToString(" + ")
        return PhotoSaver.write(context, uri, ob, orig, 0, "IA Cámara · $what", forceOrientation = ExifInterface.ORIENTATION_NORMAL).also { ob.recycle() }
    }
}
