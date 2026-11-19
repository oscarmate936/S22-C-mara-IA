package com.iacamara.app.processing

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.Segmentation
import com.google.mlkit.vision.segmentation.selfie.SelfieSegmenterOptions
import com.google.mlkit.vision.segmentation.subject.SubjectSegmentation
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenterOptions
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlin.math.ceil
import kotlin.math.max

/**
 * Modo retrato: la IA recorta al sujeto (persona, mascota u objeto), el borde se ajusta a la
 * foto (pelo, orejas), se estima qué está cerca y qué lejos, y el fondo se desenfoca como con un
 * lente luminoso. La foto original y el recorte se guardan aparte para poder cambiar la
 * intensidad del desenfoque (o quitarlo) después, desde el visor.
 */
class PortraitProcessor(private val context: Context) {

    class Result(val uri: Uri?, val subjectFound: Boolean, val subjectKind: String)

    fun process(jpeg: ByteArray, rotation: Int, strength: Float = DEFAULT_STRENGTH, onProgress: (String) -> Unit): Result {
        val opts = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
        val full = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, opts) ?: return Result(null, false, "")
        val w = full.width
        val h = full.height

        onProgress("Recortando al sujeto…")
        val (mask, kind) = segment(full, rotation) ?: (null to "")
        val coverage = mask?.mean() ?: 0f
        if (mask == null || coverage < 0.02f || coverage > 0.85f) {
            // Sin un sujeto claro: se guarda la foto normal (mejor eso que un recorte raro).
            val uri = PhotoSaver.save(context, full, jpeg, rotation, "", "IA Cámara")
            full.recycle()
            return Result(uri, false, kind)
        }

        onProgress("Ajustando el borde…")
        val refined = refineMask(full, mask)
        val px = IntArray(w * h).also { full.getPixels(it, 0, w, 0, 0, w, h) }
        full.recycle()

        onProgress("Desenfocando el fondo…")
        val out = render(px, w, h, refined, strength)
        val bmp = Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888)
        val uri = PhotoSaver.save(context, bmp, jpeg, rotation, "_RETRATO", "IA Cámara · retrato ($kind)")
        bmp.recycle()
        if (uri != null) runCatching { PortraitStore(context).put(uri, jpeg, rotation, refined, strength) }
            .onFailure { Log.w(TAG, "No se guardó el recorte para reeditar", it) }
        return Result(uri, true, kind)
    }

    /**
     * Recorte del sujeto en el marco sin girar de la foto. Primero el modelo general (personas,
     * mascotas, objetos; lo instala Google Play), si no está disponible, el de personas.
     */
    private fun segment(full: Bitmap, rotation: Int): Pair<FloatMap, String>? {
        val s = SEG_SIDE.toFloat() / max(full.width, full.height)
        val small = if (s < 1f) Bitmap.createScaledBitmap(full, (full.width * s).toInt(), (full.height * s).toInt(), true) else full
        // La IA ve la imagen enderezada (así reconoce mejor a las personas).
        val upright = if (rotation % 360 != 0) Bitmap.createBitmap(small, 0, 0, small.width, small.height, Matrix().apply { postRotate(rotation.toFloat()) }, true) else small
        try {
            val image = InputImage.fromBitmap(upright, 0)
            val subject = runCatching {
                val seg = SubjectSegmentation.getClient(SubjectSegmenterOptions.Builder().enableForegroundConfidenceMask().build())
                try {
                    val r = Tasks.await(seg.process(image), 8, TimeUnit.SECONDS)
                    val buf = r.foregroundConfidenceMask ?: return@runCatching null
                    buf.rewind()
                    FloatMap(upright.width, upright.height, FloatArray(upright.width * upright.height).also { buf.get(it) })
                } finally { seg.close() }
            }.onFailure { Log.i(TAG, "Recorte general no disponible: ${it.message}") }.getOrNull()
            if (subject != null && subject.mean() >= 0.02f) return subject.rotatedCw(360 - rotation) to "sujeto"

            val person = runCatching {
                val seg = Segmentation.getClient(
                    SelfieSegmenterOptions.Builder().setDetectorMode(SelfieSegmenterOptions.SINGLE_IMAGE_MODE).build(),
                )
                try {
                    val m = Tasks.await(seg.process(image), 8, TimeUnit.SECONDS)
                    val bb = m.buffer.order(ByteOrder.nativeOrder())
                    bb.rewind()
                    val fb = bb.asFloatBuffer()
                    FloatMap(m.width, m.height, FloatArray(m.width * m.height).also { fb.get(it) })
                } finally { seg.close() }
            }.onFailure { Log.w(TAG, "Recorte de personas falló", it) }.getOrNull() ?: return null
            return person.rotatedCw(360 - rotation) to "persona"
        } finally {
            if (upright !== small) upright.recycle()
            if (small !== full) small.recycle()
        }
    }

    companion object {
        private const val TAG = "IACamara"
        /** Lado mayor de la imagen que ve la IA de recorte. */
        const val SEG_SIDE = 1024
        /** Intensidad por defecto (0..1): equivale más o menos a un f/2 de cámara grande. */
        const val DEFAULT_STRENGTH = 0.6f

        /** Máscara refinada a la resolución de trabajo (media resolución en fotos grandes). */
        fun refineMask(full: Bitmap, coarse: FloatMap): FloatMap {
            val div = if (max(full.width, full.height) > 2400) 2 else 1
            val ws = full.width / div
            val hs = full.height / div
            val g = if (div == 1) full else Bitmap.createScaledBitmap(full, ws, hs, true)
            val gp = IntArray(ws * hs).also { g.getPixels(it, 0, ws, 0, 0, ws, hs) }
            if (g !== full) g.recycle()
            val luma = FloatArray(gp.size) { val q = gp[it]; ((((q shr 16) and 0xFF) * 2 + ((q shr 8) and 0xFF) * 5 + (q and 0xFF)) shr 3) / 255f }
            return MaskRefine.refine(coarse.resized(ws, hs), luma, radius = max(3, max(ws, hs) / 300))
        }

        /** Aplica el desenfoque con la intensidad dada (0 = foto original). */
        fun render(px: IntArray, w: Int, h: Int, mask: FloatMap, strength: Float): IntArray {
            val maxRadius = strength.coerceIn(0f, 1f) * 0.03f * max(w, h)
            if (maxRadius < 1f) return px.copyOf()
            val blur = DepthHint.blurMap(mask.resized(max(1, mask.w / 4), max(1, mask.h / 4)))
            // El fondo se calcula a menor resolución: el disco queda con ~10 px de radio, suave y rápido.
            val scale = max(2, ceil(maxRadius / 10f).toInt())
            return Bokeh.render(px, w, h, mask, blur, maxRadius, scale = scale, samples = 96)
        }
    }
}

/**
 * Guarda la foto original y el recorte de cada retrato (en la memoria privada de la app) para
 * poder cambiar el desenfoque después. Se conservan los más recientes.
 */
class PortraitStore(context: Context) {
    private val dir = File(context.filesDir, "retratos").apply { mkdirs() }

    class Entry(val jpeg: ByteArray, val rotation: Int, val mask: FloatMap, val strength: Float)

    private fun key(uri: Uri) = uri.lastPathSegment ?: uri.toString().hashCode().toString()

    fun has(uri: Uri) = File(dir, key(uri) + ".mask").exists()

    fun put(uri: Uri, jpeg: ByteArray, rotation: Int, mask: FloatMap, strength: Float) {
        val k = key(uri)
        File(dir, "$k.jpg").writeBytes(jpeg)
        DataOutputStream(GZIPOutputStream(File(dir, "$k.mask").outputStream().buffered())).use { o ->
            o.writeInt(MAGIC); o.writeInt(mask.w); o.writeInt(mask.h); o.writeInt(rotation); o.writeFloat(strength)
            o.write(ByteArray(mask.v.size) { (mask.v[it] * 255f + 0.5f).toInt().coerceIn(0, 255).toByte() })
        }
        trim()
    }

    fun get(uri: Uri): Entry? = runCatching {
        val k = key(uri)
        val jpeg = File(dir, "$k.jpg").takeIf { it.exists() }?.readBytes() ?: return null
        DataInputStream(GZIPInputStream(File(dir, "$k.mask").inputStream().buffered())).use { i ->
            if (i.readInt() != MAGIC) return null
            val w = i.readInt(); val h = i.readInt(); val rot = i.readInt(); val st = i.readFloat()
            val b = ByteArray(w * h).also { i.readFully(it) }
            Entry(jpeg, rot, FloatMap(w, h, FloatArray(w * h) { (b[it].toInt() and 0xFF) / 255f }), st)
        }
    }.getOrNull()

    fun setStrength(uri: Uri, strength: Float) {
        val e = get(uri) ?: return
        put(uri, e.jpeg, e.rotation, e.mask, strength)
    }

    fun remove(uri: Uri) {
        val k = key(uri)
        File(dir, "$k.jpg").delete(); File(dir, "$k.mask").delete()
    }

    private fun trim() {
        val masks = dir.listFiles { f -> f.name.endsWith(".mask") }?.sortedByDescending { it.lastModified() } ?: return
        for (f in masks.drop(KEEP)) {
            f.delete(); File(dir, f.name.removeSuffix(".mask") + ".jpg").delete()
        }
    }

    companion object {
        private const val MAGIC = 0x52455431 // "RET1"
        const val KEEP = 40

        /** Vuelve a generar el retrato con otra intensidad y reemplaza la foto de la galería. */
        fun rerender(context: Context, uri: Uri, strength: Float): Boolean {
            val store = PortraitStore(context)
            val e = store.get(uri) ?: return false
            val ok = if (strength < 0.02f) {
                // Sin efecto: la foto original tal cual salió de la cámara.
                runCatching { context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(e.jpeg) } != null }.getOrDefault(false)
            } else {
                val opts = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
                val full = BitmapFactory.decodeByteArray(e.jpeg, 0, e.jpeg.size, opts) ?: return false
                val w = full.width; val h = full.height
                val px = IntArray(w * h).also { full.getPixels(it, 0, w, 0, 0, w, h) }
                full.recycle()
                val out = PortraitProcessor.render(px, w, h, e.mask, strength)
                val bmp = Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888)
                PhotoSaver.write(context, uri, bmp, e.jpeg, e.rotation, "IA Cámara · retrato").also { bmp.recycle() }
            }
            if (ok) store.setStrength(uri, strength)
            return ok
        }
    }
}
