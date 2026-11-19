package com.iacamara.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.RectF
import androidx.camera.core.ImageInfo
import androidx.camera.core.ImageProxy
import androidx.test.core.app.ApplicationProvider
import com.iacamara.app.ai.Detection
import com.iacamara.app.ai.Scene
import com.iacamara.app.ai.SceneBrain
import com.iacamara.app.camera.FrameAnalyzer
import com.iacamara.app.camera.FrameStats
import com.iacamara.app.camera.LiveExposure
import com.iacamara.app.processing.BurstKind
import com.iacamara.app.processing.BurstProcessor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import kotlin.math.log10
import kotlin.math.log2
import kotlin.math.pow
import kotlin.random.Random

/**
 * Recorrido completo con fotos reales y código de Android real (Robolectric):
 * fotómetro y color del analizador, decisión de escena, y el modo noche de punta a punta
 * (JPEG → decodificar → alinear → combinar), igual que en el teléfono.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class RealPhotoPipelineTest {
    private val report = StringBuilder()
    private fun line(s: String) { report.appendLine(s); println("SIM | $s") }
    private fun flush(name: String) = File("build/sim").apply { mkdirs() }.resolve(name).writeText(report.toString())

    private fun photo(name: String): Bitmap =
        BitmapFactory.decodeStream(javaClass.classLoader!!.getResourceAsStream("sim/$name"))!!.copy(Bitmap.Config.ARGB_8888, true)

    private fun pixels(b: Bitmap) = IntArray(b.width * b.height).also { b.getPixels(it, 0, b.width, 0, 0, b.width, b.height) }

    /** Modifica cada píxel (en espacio lineal) — para oscurecer, sobreexponer o teñir. */
    private fun transform(src: Bitmap, f: (Double, Int) -> Double): Bitmap {
        val px = pixels(src)
        for (i in px.indices) {
            val c = IntArray(3) { k ->
                val v = ((px[i] shr (16 - 8 * k)) and 0xFF) / 255.0
                (f(v.pow(2.2), k).coerceIn(0.0, 1.0).pow(1 / 2.2) * 255 + 0.5).toInt()
            }
            px[i] = (0xFF shl 24) or (c[0] shl 16) or (c[1] shl 8) or c[2]
        }
        return Bitmap.createBitmap(px, src.width, src.height, Bitmap.Config.ARGB_8888)
    }

    // ------------------------------------------------ ImageProxy YUV de prueba (como el de la cámara)

    private class Plane(private val buf: ByteBuffer, private val row: Int, private val pix: Int) : ImageProxy.PlaneProxy {
        override fun getRowStride() = row
        override fun getPixelStride() = pix
        override fun getBuffer(): ByteBuffer = buf
    }

    private fun yuvProxy(b: Bitmap): ImageProxy {
        val w = b.width and 1.inv()
        val h = b.height and 1.inv()
        val px = pixels(b)
        val y = ByteArray(w * h)
        val u = ByteArray((w / 2) * (h / 2))
        val v = ByteArray((w / 2) * (h / 2))
        for (yy in 0 until h) for (xx in 0 until w) {
            val p = px[yy * b.width + xx]
            val r = (p shr 16) and 0xFF; val g = (p shr 8) and 0xFF; val bl = p and 0xFF
            y[yy * w + xx] = (0.299 * r + 0.587 * g + 0.114 * bl).toInt().coerceIn(0, 255).toByte()
            if (yy % 2 == 0 && xx % 2 == 0) {
                val ci = (yy / 2) * (w / 2) + xx / 2
                u[ci] = (-0.169 * r - 0.331 * g + 0.5 * bl + 128).toInt().coerceIn(0, 255).toByte()
                v[ci] = (0.5 * r - 0.419 * g - 0.081 * bl + 128).toInt().coerceIn(0, 255).toByte()
            }
        }
        val planes = arrayOf<ImageProxy.PlaneProxy>(
            Plane(ByteBuffer.wrap(y), w, 1), Plane(ByteBuffer.wrap(u), w / 2, 1), Plane(ByteBuffer.wrap(v), w / 2, 1),
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

    private fun analyze(b: Bitmap): FrameStats {
        var stats: FrameStats? = null
        FrameAnalyzer(onStats = { stats = it }).analyze(yuvProxy(b))
        return stats!!
    }

    /** Lo que marcaría el fotómetro: pasos respecto al gris medio (igual que CameraEngine). */
    private fun meter(s: FrameStats) = -2.2f * log2(0.46f / s.meanLuma.coerceIn(0.01f, 0.99f))

    // ------------------------------------------------ 1. Fotómetro y color

    @Test fun fotometroYColorConFotosReales() {
        line("=== FOTÓMETRO Y COLOR (analizador real, fotos convertidas a YUV como la cámara) ===")
        for (name in listOf("astronaut.png", "chelsea.png", "coffee.png", "rocket.jpg", "hubble_deep_field.jpg")) {
            val s = analyze(photo(name))
            line("%-24s luz media %.2f  fotómetro %+5.1f EV  calidez %+.3f  cielo/luces %+.3f  altas luces %4.1f%%".format(
                name, s.meanLuma, meter(s), s.warmth, s.highlightWarmth, s.clippedHighlights * 100))
        }
        // Una foto bien expuesta oscurecida 2 pasos: el fotómetro debe marcar ≈ −2.
        val base = photo("chelsea.png")
        val m0 = meter(analyze(base))
        val m2 = meter(analyze(transform(base) { v, _ -> v / 4 }))
        line("chelsea oscurecida 2 pasos: fotómetro %+.2f → %+.2f (diferencia %.2f, ideal −2.00)".format(m0, m2, m2 - m0))
        assertEquals(-2.0, (m2 - m0).toDouble(), 0.5)

        // Sobreexpuesta 2 pasos: debe saltar el aviso de altas luces (> 3 %).
        val over = analyze(transform(base) { v, _ -> v * 4 })
        line("chelsea sobreexpuesta 2 pasos: altas luces %.1f%% (aviso si > 3%%)".format(over.clippedHighlights * 100))
        assertTrue(over.clippedHighlights > 0.03f)

        // Atardecer: cielo naranja sobre siluetas oscuras. Debe superar el umbral; las fotos normales no.
        val sunset = analyze(sunsetScene())
        line("atardecer sintético: cielo/luces %+.3f (umbral de la IA %.2f)".format(sunset.highlightWarmth, SceneBrain.SUNSET_SKY_WARMTH))
        assertTrue(sunset.highlightWarmth > SceneBrain.SUNSET_SKY_WARMTH)
        // Fotos con cielo de día o fondo claro: por debajo del umbral.
        for (name in listOf("astronaut.png", "rocket.jpg")) {
            assertTrue("$name no es un atardecer", analyze(photo(name)).highlightWarmth < SceneBrain.SUNSET_SKY_WARMTH)
        }
        flush("reporte_fotometro.txt")
    }

    /** Atardecer realista: degradado de cielo (naranja en el horizonte → violeta arriba), sol y tierra en silueta. */
    private fun sunsetScene(w: Int = 480, h: Int = 640): Bitmap {
        val px = IntArray(w * h) { i ->
            val x = i % w; val y = i / w
            val t = y / (h * 0.68)
            val (r, g, b) = if (t <= 1.0) {
                Triple(120 + 135 * t, 60 + 110 * t, 110 - 70 * t)
            } else Triple(25.0, 18.0, 22.0)
            val sun = kotlin.math.hypot((x - w * 0.55), (y - h * 0.6)) < w * 0.07
            val c = if (sun) Triple(255.0, 225.0, 160.0) else Triple(r, g, b)
            (0xFF shl 24) or (c.first.toInt().coerceIn(0, 255) shl 16) or (c.second.toInt().coerceIn(0, 255) shl 8) or c.third.toInt().coerceIn(0, 255)
        }
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
    }

    // ------------------------------------------------ 2. Escenas

    private fun runBrain(brain: SceneBrain, det: Detection, stats: FrameStats, live: LiveExposure, times: Int = 4) =
        (1..times).map { brain.update(det, stats, live, front = false) }.last()

    @Test fun escenasConFotosReales() {
        line("=== DECISIÓN DE ESCENA (lo que devolvería ML Kit + física real de cada foto) ===")
        val day = LiveExposure(100, 4_000_000L, 0.5f)
        fun case(name: String, photoName: String, det: Detection, live: LiveExposure, expected: Scene?, stats: FrameStats? = null, notExpected: Scene? = null) {
            val st = stats ?: analyze(photo(photoName))
            val (r, subj) = runBrain(SceneBrain(), det, st, live)
            line("%-30s → %-22s %3d%%  sujeto: %-8s  (por: %s)".format(
                name, r.scene.title, (r.confidence.coerceAtMost(1f) * 100).toInt(), subj?.label ?: "—", r.evidence.joinToString()))
            if (expected != null) assertEquals(name, expected, r.scene)
            if (notExpected != null) assertTrue(name, r.scene != notExpected)
        }
        case("astronauta (rostro)", "astronaut.png",
            Detection(listOf("Smile" to 0.9f, "Fun" to 0.6f), listOf(RectF(0.36f, 0.12f, 0.62f, 0.42f)), null), day, Scene.PORTRAIT)
        case("gato Chelsea", "chelsea.png",
            Detection(listOf("Cat" to 0.93f, "Pet" to 0.8f, "Whiskers" to 0.7f), emptyList(), RectF(0.15f, 0.05f, 0.85f, 0.95f)), day, Scene.PET)
        case("taza de café", "coffee.png",
            Detection(listOf("Coffee" to 0.82f, "Cup" to 0.8f, "Food" to 0.62f), emptyList(), RectF(0.2f, 0.1f, 0.8f, 0.7f)), day, Scene.FOOD)
        case("cielo nocturno (Hubble)", "hubble_deep_field.jpg",
            Detection(emptyList(), emptyList(), null), LiveExposure(3200, 66_000_000L, 0f), Scene.NIGHT)
        case("atardecer (cielo naranja)", "rocket.jpg",
            Detection(listOf("Sky" to 0.9f, "Cloud" to 0.7f), emptyList(), null), day, Scene.SUNSET,
            stats = analyze(sunsetScene()))
        // Cielo azul de día con tonos cálidos en primer plano: NO es atardecer.
        case("cohete, cielo azul de día", "rocket.jpg",
            Detection(listOf("Sky" to 0.9f, "Cloud" to 0.7f), emptyList(), null), day, expected = null, notExpected = Scene.SUNSET)
        case("flor a 10 cm", "coffee.png",
            Detection(listOf("Flower" to 0.8f, "Plant" to 0.7f), emptyList(), RectF(0.3f, 0.3f, 0.7f, 0.7f)),
            LiveExposure(100, 4_000_000L, 10f), Scene.MACRO)

        // Estabilidad: una sola lectura dudosa no debe cambiar la escena (sin parpadeo).
        val brain = SceneBrain()
        val cat = analyze(photo("chelsea.png"))
        val catDet = Detection(listOf("Cat" to 0.93f), emptyList(), RectF(0.2f, 0.1f, 0.8f, 0.9f))
        runBrain(brain, catDet, cat, day)
        val glitch = brain.update(Detection(emptyList(), emptyList(), null), cat, day, false).first
        val back = brain.update(catDet, cat, day, false).first
        line("estabilidad: mascota → (lectura vacía) → ${glitch.scene.title} → ${back.scene.title}")
        assertEquals(Scene.PET, glitch.scene)
        flush("reporte_escenas.txt")
    }

    // ------------------------------------------------ 3. Modo noche de punta a punta

    @Test fun modoNocheDePuntaAPunta() {
        line("=== MODO NOCHE DE PUNTA A PUNTA (JPEG real → BurstProcessor) ===")
        val gt = photo("coffee.png")
        val w = gt.width
        val h = gt.height
        val gtPx = pixels(gt)
        val rnd = Random(7)
        fun gauss(): Double {
            val u1 = rnd.nextDouble().coerceAtLeast(1e-12); val u2 = rnd.nextDouble()
            return kotlin.math.sqrt(-2 * kotlin.math.ln(u1)) * kotlin.math.cos(2 * Math.PI * u2)
        }
        val jpegs = (0 until 8).map { k ->
            val dx = if (k == 0) 0 else rnd.nextInt(-10, 11)
            val dy = if (k == 0) 0 else rnd.nextInt(-10, 11)
            val px = IntArray(w * h) { i ->
                val x = (i % w - dx).coerceIn(0, w - 1); val y = (i / w - dy).coerceIn(0, h - 1)
                val p = gtPx[y * w + x]
                val c = IntArray(3) { c ->
                    val lin = (((p shr (16 - 8 * c)) and 0xFF) / 255.0).pow(2.2) * 150
                    (((lin + kotlin.math.sqrt(lin) * gauss()) / 150 + 0.012 * gauss()).coerceAtLeast(0.0).pow(1 / 2.2) * 255).toInt().coerceIn(0, 255)
                }
                (0xFF shl 24) or (c[0] shl 16) or (c[1] shl 8) or c[2]
            }
            ByteArrayOutputStream().also { Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.JPEG, 95, it) }.toByteArray()
        }
        val progress = mutableListOf<Int>()
        val t0 = System.currentTimeMillis()
        val (result, count) = BurstProcessor(ApplicationProvider.getApplicationContext())
            .combine(jpegs, BurstKind.NIGHT, shadowLift = 0f) { done, _ -> progress += done }!!
        val ms = System.currentTimeMillis() - t0
        val single = BitmapFactory.decodeByteArray(jpegs[0], 0, jpegs[0].size)

        fun psnr(a: IntArray): Double {
            var se = 0.0; var n = 0
            for (y in 20 until h - 20) for (x in 20 until w - 20) {
                val i = y * w + x
                for (c in 0..2) { val d = ((a[i] shr (16 - 8 * c)) and 0xFF) - ((gtPx[i] shr (16 - 8 * c)) and 0xFF); se += d * d; n++ }
            }
            return 10 * log10(255.0 * 255 / (se / n))
        }
        val pS = psnr(pixels(single))
        val pM = psnr(pixels(result))
        line("tomas usadas: $count/8   progreso informado: $progress   tiempo: $ms ms (${w}×$h)")
        line("1 toma: %.1f dB   8 tomas combinadas: %.1f dB   mejora %+.1f dB".format(pS, pM, pM - pS))
        assertEquals(8, count)
        assertEquals((1..8).toList(), progress)
        assertTrue(pM > pS + 4)
        flush("reporte_noche_e2e.txt")
    }
}

