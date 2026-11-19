package com.iacamara.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.PointF
import android.graphics.Rect
import android.graphics.RectF
import androidx.camera.core.ImageInfo
import androidx.camera.core.ImageProxy
import com.iacamara.app.ai.Detection
import com.iacamara.app.ai.FaceInfo
import com.iacamara.app.ai.Orient
import com.iacamara.app.ai.Scene
import com.iacamara.app.ai.SceneBrain
import com.iacamara.app.ai.Subject
import com.iacamara.app.camera.FrameAnalyzer
import com.iacamara.app.camera.FrameStats
import com.iacamara.app.camera.LiveExposure
import com.iacamara.app.camera.NormRect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.random.Random

/** Precisión de los motores de IA: orientación, flujo óptico, fusión de escenas y rostro. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class PrecisionTest {
    private val report = StringBuilder()
    private fun line(s: String) { report.appendLine(s); println("SIM | $s") }
    private fun flush(name: String) = File("build/sim").apply { mkdirs() }.resolve(name).writeText(report.toString())

    // ------------------------------------------------ 1. Orientación

    /** Gira de verdad una imagen (matriz) 90° × k en sentido horario y comprueba la conversión inversa. */
    @Test fun orientacionDevuelveLosPuntosAlMarcoVertical() {
        val w = 40
        val h = 30
        val rnd = Random(3)
        for (delta in listOf(0, 90, 180, 270)) {
            repeat(20) {
                val px = rnd.nextInt(w)
                val py = rnd.nextInt(h)
                // Imagen vertical con un único píxel marcado
                var img = Array(h) { y -> IntArray(w) { x -> if (x == px && y == py) 1 else 0 } }
                repeat(delta / 90) {
                    val ih = img.size
                    val iw = img[0].size
                    img = Array(iw) { y -> IntArray(ih) { x -> img[ih - 1 - x][y] } } // giro horario
                }
                val rh = img.size
                val rw = img[0].size
                var mx = 0
                var my = 0
                for (y in 0 until rh) for (x in 0 until rw) if (img[y][x] == 1) { mx = x; my = y }
                val (u, v) = Orient.toPortrait((mx + 0.5f) / rw, (my + 0.5f) / rh, delta)
                assertEquals("delta $delta x", (px + 0.5f) / w, u, 1e-4f)
                assertEquals("delta $delta y", (py + 0.5f) / h, v, 1e-4f)
            }
        }
        line("ORIENTACIÓN  80/80 puntos devueltos exactamente al marco vertical (0°, 90°, 180°, 270°)")
        flush("reporte_precision_orientacion.txt")
    }

    // ------------------------------------------------ 2. Flujo óptico

    private class Plane(private val buf: ByteBuffer, private val row: Int) : ImageProxy.PlaneProxy {
        override fun getRowStride() = row
        override fun getPixelStride() = 1
        override fun getBuffer(): ByteBuffer = buf
    }

    private fun proxyOf(px: IntArray, w: Int, h: Int): ImageProxy {
        val y = ByteArray(w * h) { i ->
            val p = px[i]
            (0.299 * ((p shr 16) and 0xFF) + 0.587 * ((p shr 8) and 0xFF) + 0.114 * (p and 0xFF)).toInt().toByte()
        }
        val planes = arrayOf<ImageProxy.PlaneProxy>(
            Plane(ByteBuffer.wrap(y), w), Plane(ByteBuffer.wrap(ByteArray(w * h / 4) { -128 }), w / 2),
            Plane(ByteBuffer.wrap(ByteArray(w * h / 4) { -128 }), w / 2),
        )
        return object : ImageProxy {
            override fun close() = Unit
            override fun getCropRect() = Rect(0, 0, w, h)
            override fun setCropRect(rect: Rect?) = Unit
            override fun getFormat() = ImageFormat.YUV_420_888
            override fun getHeight() = h
            override fun getWidth() = w
            override fun getPlanes() = planes
            override fun getImageInfo(): ImageInfo = throw UnsupportedOperationException()
            @androidx.camera.core.ExperimentalGetImage
            override fun getImage(): android.media.Image? = null
        }
    }

    private fun motionCase(name: String, panX: Float, panY: Float, subjX: Float, subjY: Float) {
        val bg = BitmapFactory.decodeStream(javaClass.classLoader!!.getResourceAsStream("sim/coffee.png"))!!
        val patchSrc = BitmapFactory.decodeStream(javaClass.classLoader!!.getResourceAsStream("sim/astronaut.png"))!!
        val bgPx = IntArray(bg.width * bg.height).also { bg.getPixels(it, 0, bg.width, 0, 0, bg.width, bg.height) }
        val faPx = IntArray(patchSrc.width * patchSrc.height).also { patchSrc.getPixels(it, 0, patchSrc.width, 0, 0, patchSrc.width, patchSrc.height) }
        val w = 480
        val h = 320
        val pw = 90
        val ph = 90
        var t = 0L
        val dt = 125_000_000L // 8 análisis por segundo
        var last: FrameStats? = null
        val fa = FrameAnalyzer(onStats = { last = it }, clock = { t }, minIntervalMs = 0)
        fa.portraitRotation = 0
        val globals = mutableListOf<Float>()
        val subjects = mutableListOf<Float>()
        for (k in 0 until 10) {
            // Fondo desplazado (paneo/pulso) con subpíxel por interpolación bilineal
            val ox = 40 + k * panX
            val oy = 30 + k * panY
            val sx0 = 150 + k * subjX
            val sy0 = 110 + k * subjY
            val px = IntArray(w * h) { i ->
                val x = i % w; val y = i / w
                val inPatch = x >= sx0 && x < sx0 + pw && y >= sy0 && y < sy0 + ph
                if (inPatch) {
                    sample(faPx, patchSrc.width, x - sx0 + 200f, y - sy0 + 120f)
                } else sample(bgPx, bg.width, x + ox, y + oy)
            }
            // La IA marca dónde estaba el sujeto (en el fotograma anterior).
            val px0 = 150 + (k - 1).coerceAtLeast(0) * subjX
            val py0 = 110 + (k - 1).coerceAtLeast(0) * subjY
            fa.region = NormRect(px0 / w, py0 / h, (px0 + pw) / w, (py0 + ph) / h)
            fa.analyze(proxyOf(px, w, h))
            if (k >= 1) { globals += last!!.globalMotion; last!!.subjectMotion?.let { subjects += it } }
            t += dt
        }
        val short = minOf(w, h) / 4f // el flujo trabaja a ¼ de resolución
        fun expected(dx: Float, dy: Float) = kotlin.math.hypot(dx / 4f, dy / 4f) / short / 0.125f
        val eg = expected(panX, panY)
        val es = expected(subjX, subjY)
        val mg = globals.average().toFloat()
        val ms = subjects.average().toFloat()
        line("FLUJO  %-26s global: medido %.3f / real %.3f anchos/s   sujeto: medido %.3f / real %.3f".format(name, mg, eg, ms, es))
        assertTrue("$name global", abs(mg - eg) <= 0.15f * eg + 0.01f)
        assertTrue("$name sujeto", abs(ms - es) <= 0.15f * es + 0.01f)
    }

    private fun sample(px: IntArray, w: Int, x: Float, y: Float): Int {
        val h = px.size / w
        val xx = x.coerceIn(0f, w - 1.001f); val yy = y.coerceIn(0f, h - 1.001f)
        val x0 = xx.toInt(); val y0 = yy.toInt(); val fx = xx - x0; val fy = yy - y0
        fun c(p: Int, s: Int) = (p shr s) and 0xFF
        val p00 = px[y0 * w + x0]; val p10 = px[y0 * w + x0 + 1]; val p01 = px[(y0 + 1) * w + x0]; val p11 = px[(y0 + 1) * w + x0 + 1]
        fun mix(s: Int) = (c(p00, s) * (1 - fx) * (1 - fy) + c(p10, s) * fx * (1 - fy) + c(p01, s) * (1 - fx) * fy + c(p11, s) * fx * fy + 0.5f).toInt()
        return (0xFF shl 24) or (mix(16) shl 16) or (mix(8) shl 8) or mix(0)
    }

    @Test fun flujoOpticoMideElMovimientoReal() {
        line("=== FLUJO ÓPTICO (8 análisis/s, sujeto = rostro que se mueve sobre un fondo) ===")
        motionCase("todo quieto", 0f, 0f, 0f, 0f)
        motionCase("pulso leve (1.5 px)", 1.5f, -1f, 0f, 0f)
        motionCase("persona caminando", 0f, 0f, 9f, 0f)
        motionCase("mascota corriendo + paneo", 3f, 0f, 22f, 4f)
        flush("reporte_precision_flujo.txt")
    }

    // ------------------------------------------------ 3. Fusión temporal de escenas

    @Test fun fusionTemporalDeEscenas() {
        line("=== FUSIÓN TEMPORAL DE ESCENAS (lecturas de IA con ruido real) ===")
        val rnd = Random(9)
        val day = LiveExposure(100, 4_000_000L, 0.5f)
        val stats = FrameStats(FloatArray(64), 0.45f, 0f, 0f)
        val brain = SceneBrain()
        val box = RectF(0.2f, 0.2f, 0.8f, 0.8f)
        fun noisyPet(): Detection {
            val r = rnd.nextFloat()
            return when {
                r < 0.25f -> Detection(emptyList(), emptyList(), box) // la IA no ve nada claro
                r < 0.40f -> Detection(listOf("Food" to 0.6f, "Cat" to 0.5f), emptyList(), box) // confusión
                else -> Detection(listOf("Cat" to 0.6f + rnd.nextFloat() * 0.35f), emptyList(), box)
            }
        }
        fun noisyFood(): Detection {
            val r = rnd.nextFloat()
            return if (r < 0.25f) Detection(emptyList(), emptyList(), box)
            else Detection(listOf("Food" to 0.6f + rnd.nextFloat() * 0.3f, "Dessert" to 0.55f), emptyList(), box)
        }
        var correct = 0
        var switches = 0
        var prev = Scene.GENERAL
        val seq = mutableListOf<Scene>()
        repeat(60) { k ->
            val sc = brain.update(noisyPet(), stats, day, false).first.scene
            if (k >= 4 && sc == Scene.PET) correct++
            if (sc != prev) { switches++; prev = sc }
            seq += sc
        }
        line("mascota con 25%% de lecturas vacías y 15%% confundidas con comida: aciertos %d/56 (tras 1.3 s), cambios de escena: %d".format(correct, switches))
        assertTrue(correct >= 53)
        assertTrue(switches <= 2)
        // Transición real: pasa a fotografiar un postre.
        var latency = -1
        repeat(20) { k ->
            val sc = brain.update(noisyFood(), stats, day, false).first.scene
            if (latency < 0 && sc == Scene.FOOD) latency = k + 1
        }
        line("al apuntar a un postre, cambia a «Comida» en %d lecturas (≈ %.1f s)".format(latency, latency / 3f))
        assertTrue(latency in 1..6)
        flush("reporte_precision_escenas.txt")
    }

    // ------------------------------------------------ 4. Rostro con puntos de referencia

    @Test fun rostroUsaOjosYPiel() {
        val box = RectF(0.36f, 0.12f, 0.62f, 0.42f)
        val s = Subject.fromFace(FaceInfo(box, PointF(0.45f, 0.24f), PointF(0.54f, 0.24f), PointF(0.495f, 0.36f), 7))
        line("ROSTRO  ancla en los ojos (%.3f, %.3f); zona de medición %s dentro de la cara: %s".format(
            s.anchorX, s.anchorY, s.meterBox.toShortString(), box.contains(s.meterBox)))
        assertEquals(0.495f, s.anchorX, 1e-4f)
        assertEquals(0.24f, s.anchorY, 1e-4f)
        assertTrue(box.contains(s.meterBox))
        assertTrue("la piel está por debajo de los ojos", s.meterBox.top > 0.24f)
        assertEquals(7, s.trackId)
        flush("reporte_precision_rostro.txt")
    }
}
