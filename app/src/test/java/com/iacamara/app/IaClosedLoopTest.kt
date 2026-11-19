package com.iacamara.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.RectF
import androidx.camera.core.ImageInfo
import androidx.camera.core.ImageProxy
import com.iacamara.app.ai.BrainInput
import com.iacamara.app.ai.PhotoBrain
import com.iacamara.app.ai.Scene
import com.iacamara.app.ai.Subject
import com.iacamara.app.camera.ExposureMath
import com.iacamara.app.camera.FrameAnalyzer
import com.iacamara.app.camera.FrameStats
import com.iacamara.app.camera.NormRect
import com.iacamara.app.processing.Align
import com.iacamara.app.processing.HdrMerger
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow

/**
 * Simulación de circuito cerrado del modo IA.
 *
 * Una "cámara virtual" se comporta como el automático de un teléfono (lleva el promedio de la
 * imagen a gris medio, más la compensación que pida la IA). La escena tiene luz real (lineal),
 * así que puede superar lo que el sensor guarda. En cada ciclo: la cámara toma → el analizador
 * real mide → el cerebro real decide → la cámara se ajusta. Se compara "cámara sola" vs. "con IA".
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class IaClosedLoopTest {
    private val outDir = File("build/sim").apply { mkdirs() }
    private val report = StringBuilder()
    private fun line(s: String) { report.appendLine(s); println("SIM | $s") }

    // ------------------------------------------------ escena y cámara virtual

    /** Escena en luz lineal (puede valer más de 1: más luz de la que cabe en el sensor). */
    private class Radiance(val w: Int, val h: Int, val r: FloatArray, val g: FloatArray, val b: FloatArray)

    private fun loadRadiance(name: String, light: (x: Float, y: Float, lin: Float) -> Float): Radiance {
        val bmp = BitmapFactory.decodeStream(javaClass.classLoader!!.getResourceAsStream("sim/$name"))!!
        val w = bmp.width
        val h = bmp.height
        val px = IntArray(w * h).also { bmp.getPixels(it, 0, w, 0, 0, w, h) }
        val r = FloatArray(w * h); val g = FloatArray(w * h); val b = FloatArray(w * h)
        for (i in px.indices) {
            val x = (i % w) / w.toFloat(); val y = (i / w) / h.toFloat()
            fun lin(c: Int) = (c / 255.0).pow(2.2).toFloat()
            val k = light(x, y, lin((px[i] shr 8) and 0xFF))
            r[i] = lin((px[i] shr 16) and 0xFF) * k
            g[i] = lin((px[i] shr 8) and 0xFF) * k
            b[i] = lin(px[i] and 0xFF) * k
        }
        return Radiance(w, h, r, g, b)
    }

    private fun develop(s: Radiance, gain: Float): Bitmap {
        val out = IntArray(s.w * s.h) { i ->
            fun enc(v: Float) = ((v * gain).coerceIn(0f, 1f).toDouble().pow(1 / 2.2) * 255 + 0.5).toInt()
            (0xFF shl 24) or (enc(s.r[i]) shl 16) or (enc(s.g[i]) shl 8) or enc(s.b[i])
        }
        return Bitmap.createBitmap(out, s.w, s.h, Bitmap.Config.ARGB_8888)
    }

    /** Automático de la cámara: busca la ganancia que deja el promedio en gris medio + compensación. */
    private fun autoExposure(s: Radiance, ev: Float): Float {
        val target = 0.46f * 2f.pow(ev / 2.2f)
        var lo = -12.0
        var hi = 12.0
        repeat(40) {
            val mid = (lo + hi) / 2
            val gain = exp(mid).toFloat()
            var sum = 0.0
            var n = 0
            for (i in 0 until s.w * s.h step 5) {
                val yv = 0.299f * s.r[i] + 0.587f * s.g[i] + 0.114f * s.b[i]
                sum += (yv * gain).coerceIn(0f, 1f).toDouble().pow(1 / 2.2); n++
            }
            if (sum / n < target) lo = mid else hi = mid
        }
        return exp((lo + hi) / 2).toFloat()
    }

    // ------------------------------------------------ analizador real sobre la imagen

    private class Plane(private val buf: ByteBuffer, private val row: Int) : ImageProxy.PlaneProxy {
        override fun getRowStride() = row
        override fun getPixelStride() = 1
        override fun getBuffer(): ByteBuffer = buf
    }

    private fun analyze(b: Bitmap, region: RectF?): FrameStats {
        val w = b.width and 1.inv(); val h = b.height and 1.inv()
        val px = IntArray(b.width * b.height).also { b.getPixels(it, 0, b.width, 0, 0, b.width, b.height) }
        val y = ByteArray(w * h); val u = ByteArray(w * h / 4); val v = ByteArray(w * h / 4)
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
        val planes = arrayOf<ImageProxy.PlaneProxy>(Plane(ByteBuffer.wrap(y), w), Plane(ByteBuffer.wrap(u), w / 2), Plane(ByteBuffer.wrap(v), w / 2))
        val proxy = object : ImageProxy {
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
        var stats: FrameStats? = null
        val fa = FrameAnalyzer(onStats = { stats = it })
        fa.region = region?.let { NormRect(it.left, it.top, it.right, it.bottom) }
        fa.analyze(proxy)
        return stats!!
    }

    // ------------------------------------------------ bucle IA

    private class Outcome(val ev: Float, val stats: FrameStats, val photo: Bitmap, val gain: Float, val hdr: Boolean, val why: String)

    private fun runIa(s: Radiance, scene: Scene, subject: Subject?, snowy: Boolean = false): Outcome {
        val brain = PhotoBrain()
        var ev = 0f
        var hdr = false
        var why = ""
        repeat(14) {
            val photo = develop(s, autoExposure(s, ev))
            val st = analyze(photo, subject?.box)
            val d = brain.decide(
                BrainInput(
                    scene = scene, subject = subject, subjectSpeed = 0f, gyro = 0.01f, steady = false, equivalentMm = 24f,
                    meanLuma = st.meanLuma, clippedHighlights = st.clippedHighlights, liveIso = 100,
                    aeShutterNs = 1_000_000_000L / 250, isoMin = 50, isoMax = 3200, priorityIso = 100,
                    shutterValues = ExposureMath.shutterValues(100_000L, 500_000_000L),
                    clippedArea = st.clippedArea, shadowArea = st.shadowArea,
                    subjectLuma = if (subject != null) st.subjectLuma else null, snowy = snowy, p98 = st.p98,
                ),
                null,
            )
            ev = d.ev
            hdr = d.hdr
            why = d.reasons.joinToString { "${it.value} (${it.why})" }
        }
        val gain = autoExposure(s, ev)
        val photo = develop(s, gain)
        return Outcome(ev, analyze(photo, subject?.box), photo, gain, hdr, why)
    }

    private fun plain(s: Radiance, subject: Subject?): Outcome {
        val gain = autoExposure(s, 0f)
        val photo = develop(s, gain)
        return Outcome(0f, analyze(photo, subject?.box), photo, gain, false, "")
    }

    private fun save(b: Bitmap, name: String) = File(outDir, name).outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }

    private fun regionLuma(b: Bitmap, r: RectF): Float {
        var s = 0.0; var n = 0
        for (y in (r.top * b.height).toInt() until (r.bottom * b.height).toInt()) for (x in (r.left * b.width).toInt() until (r.right * b.width).toInt()) {
            val p = b.getPixel(x, y); s += (0.299 * ((p shr 16) and 0xFF) + 0.587 * ((p shr 8) and 0xFF) + 0.114 * (p and 0xFF)) / 255; n++
        }
        return (s / n).toFloat()
    }

    /** Píxeles quemados de verdad: blanco puro en los tres canales (sin ninguna información). */
    private fun clippedPct(b: Bitmap): Float {
        var c = 0
        for (y in 0 until b.height step 2) for (x in 0 until b.width step 2) {
            val p = b.getPixel(x, y)
            if (((p shr 16) and 0xFF) >= 254 && ((p shr 8) and 0xFF) >= 254 && (p and 0xFF) >= 254) c++
        }
        return c * 100f / ((b.height / 2) * (b.width / 2))
    }

    // ------------------------------------------------ escenarios

    @Test fun modoIaEnEscenasDificiles() {
        line("=== MODO IA EN CIRCUITO CERRADO: cámara sola vs. con IA v2 ===")

        // 1) Retrato a contraluz: la persona delante de una ventana muy luminosa (6× más luz).
        val face = RectF(0.36f, 0.12f, 0.62f, 0.42f)
        val backlit = loadRadiance("astronaut.png") { x, y, _ ->
            val dx = (x - 0.5f) / 0.40f; val dy = (y - 0.62f) / 0.62f
            if (dx * dx + dy * dy < 1f) 1f else 6f
        }
        val subj = Subject(face, "Persona", true)
        val a0 = plain(backlit, subj)
        val a1 = runIa(backlit, Scene.PORTRAIT, subj)
        save(a0.photo, "ia_contraluz_camara_sola.png"); save(a1.photo, "ia_contraluz_con_ia.png")
        val f0 = regionLuma(a0.photo, face); val f1 = regionLuma(a1.photo, face)
        line("CONTRALUZ  cara: cámara sola %.2f → con IA %.2f (ideal ≈ 0.50)   compensación %+.1f EV   HDR recomendado: %s".format(f0, f1, a1.ev, a1.hdr))
        line("           decisiones: ${a1.why}")
        assertTrue("la cara debe quedar mejor expuesta", abs(f1 - 0.5f) < abs(f0 - 0.5f) && f1 > 0.40f)
        assertTrue(a1.hdr)

        // 1b) HDR sobre el contraluz: toma normal (cara bien) + toma 2 pasos más oscura (ventana).
        val normal = IntArray(backlit.w * backlit.h).also { a1.photo.getPixels(it, 0, backlit.w, 0, 0, backlit.w, backlit.h) }
        // Igual que el motor: bracket de 2 o 3 pasos según cuánto se quema.
        val ratio = com.iacamara.app.processing.BurstProcessor.hdrRatioFor(a1.stats.clippedArea)
        val underBmp = develop(backlit, a1.gain / ratio)
        val under = IntArray(backlit.w * backlit.h).also { underBmp.getPixels(it, 0, backlit.w, 0, 0, backlit.w, backlit.h) }
        val (dx, dy) = Align.estimateShift(Align.luma(normal), Align.lumaWithGain(under, ratio), backlit.w, backlit.h)
        val merger = HdrMerger(backlit.w, backlit.h)
        val hdrPx = merger.merge(normal, under, dx, dy, ratio)
        val hdrBmp = Bitmap.createBitmap(hdrPx, backlit.w, backlit.h, Bitmap.Config.ARGB_8888)
        save(hdrBmp, "ia_contraluz_hdr.png")
        line("HDR        píxeles quemados: %.1f%% → %.1f%%   cara: %.2f → %.2f   relación de luz medida %.2f (real %.2f)".format(
            clippedPct(a1.photo), clippedPct(hdrBmp), f1, regionLuma(hdrBmp, face), merger.measuredRatio, ratio))
        assertTrue(clippedPct(hdrBmp) < clippedPct(a1.photo) / 3)
        assertTrue(abs(regionLuma(hdrBmp, face) - f1) < 0.08f)

        // 2) Nieve: paisaje casi blanco. El automático lo deja gris; la IA lo expone "a la derecha".
        val snowRegion = RectF(0.05f, 0.05f, 0.95f, 0.55f)
        val snow = loadRadiance("rocket.jpg") { _, _, lin -> 0.0f + 1f } .let { base ->
            // Campo nevado sintético con textura de la foto y una franja de árboles oscuros.
            val r = FloatArray(base.w * base.h); val g = FloatArray(base.w * base.h); val b = FloatArray(base.w * base.h)
            for (i in r.indices) {
                val y = (i / base.w) / base.h.toFloat()
                val tex = 0.85f + 0.1f * (base.g[i] - 0.2f).coerceIn(-0.5f, 0.5f)
                val v = if (y in 0.62f..0.72f) 0.04f else tex
                r[i] = v * 0.97f; g[i] = v * 0.98f; b[i] = v
            }
            Radiance(base.w, base.h, r, g, b)
        }
        val s0 = plain(snow, null)
        val s1 = runIa(snow, Scene.LANDSCAPE, null, snowy = true)
        save(s0.photo, "ia_nieve_camara_sola.png"); save(s1.photo, "ia_nieve_con_ia.png")
        line("NIEVE      brillo de la nieve: cámara sola %.2f (gris) → con IA %.2f (blanca; ideal ≈ 0.85-0.92)   %+.1f EV".format(
            regionLuma(s0.photo, snowRegion), regionLuma(s1.photo, snowRegion), s1.ev))
        assertTrue(regionLuma(s1.photo, snowRegion) > 0.8f)
        assertTrue("la nieve no debe quemarse", clippedPct(s1.photo) < 2f)
        line("           nieve quemada (blanco puro): %.1f%%".format(clippedPct(s1.photo)))

        // 3) Habitación con una lámpara encendida en el cuadro: la lámpara se deja quemar.
        val lampRoom = loadRadiance("coffee.png") { x, y, _ ->
            val d = (x - 0.85f) * (x - 0.85f) + (y - 0.15f) * (y - 0.15f)
            if (d < 0.0065f) 400f else 0.35f
        }
        val l1 = runIa(lampRoom, Scene.GENERAL, null)
        val clipped = l1.stats.clippedHighlights
        val v1Ev = if (clipped > 0.02f) -(0.3f + ((clipped - 0.02f) * 15f).coerceAtMost(0.7f)) else 0f
        line("LÁMPARA    quemado: %.1f%% de píxeles pero %.1f%% del encuadre en zonas grandes → IA v2 %+.1f EV (IA v1 habría hecho %+.1f EV y oscurecido la habitación)".format(
            clipped * 100, l1.stats.clippedArea * 100, l1.ev, v1Ev))
        assertTrue(abs(l1.ev) < 0.2f)

        // 4) Paisaje con el cielo mucho más brillante que el suelo: proteger el cielo.
        val skyScene = loadRadiance("rocket.jpg") { _, y, _ -> if (y < 0.55f) 5f else 1f }
        val k0 = plain(skyScene, null)
        val k1 = runIa(skyScene, Scene.LANDSCAPE, null)
        save(k0.photo, "ia_cielo_camara_sola.png"); save(k1.photo, "ia_cielo_con_ia.png")
        line("CIELO      píxeles quemados: cámara sola %.1f%% → con IA %.1f%%   %+.1f EV   (${k1.why})".format(
            clippedPct(k0.photo), clippedPct(k1.photo), k1.ev))
        assertTrue(clippedPct(k1.photo) <= clippedPct(k0.photo))

        File(outDir, "reporte_ia_v2.txt").writeText(report.toString())
    }

    // ------------------------------------------------ amanecer

    /** Amanecer: cielo con degradado, brillo muy intenso en el horizonte, disco solar y tierra en silueta. */
    private fun sunriseScene(w: Int = 480, h: Int = 640): Radiance {
        val r = FloatArray(w * h); val g = FloatArray(w * h); val b = FloatArray(w * h)
        val horizon = 0.68f
        for (i in r.indices) {
            val x = (i % w) / w.toFloat(); val y = (i / w) / h.toFloat()
            if (y > horizon) {
                val tex = 0.008f + 0.004f * (((i * 2654435761L) ushr 28) and 7) / 7f
                r[i] = tex; g[i] = tex; b[i] = tex
                continue
            }
            val t = y / horizon // 0 arriba, 1 en el horizonte
            // Cielo: violeta arriba → naranja cerca del horizonte
            var rr = 0.04f + 0.9f * t * t
            var gg = 0.03f + 0.38f * t * t * t
            var bb = 0.10f + 0.05f * t
            // Resplandor alrededor de donde sale el sol (mucho más luz que el resto)
            val dx = (x - 0.55f) / 0.38f; val dy = (y - horizon) / 0.13f
            val glow = 28f * exp(-(dx * dx + dy * dy).toDouble()).toFloat()
            rr *= 1 + glow; gg *= 1 + glow * 1.15f; bb *= 1 + glow * 0.6f
            // Disco del sol asomando
            if (((x - 0.55f) * (x - 0.55f) * 0.5625f + (y - horizon) * (y - horizon)) < 0.0004f) { rr = 60f; gg = 50f; bb = 35f }
            r[i] = rr; g[i] = gg; b[i] = bb
        }
        return Radiance(w, h, r, g, b)
    }

    private fun saturation(b: Bitmap, rr: RectF): Pair<Float, Float> {
        var sat = 0.0; var white = 0; var n = 0
        for (y in (rr.top * b.height).toInt() until (rr.bottom * b.height).toInt()) for (x in (rr.left * b.width).toInt() until (rr.right * b.width).toInt()) {
            val p = b.getPixel(x, y)
            val r = (p shr 16) and 0xFF; val g = (p shr 8) and 0xFF; val bl = p and 0xFF
            val mx = maxOf(r, g, bl); val mn = minOf(r, g, bl)
            sat += if (mx == 0) 0.0 else (mx - mn).toDouble() / mx
            if (r >= 254 && g >= 254 && bl >= 254) white++
            n++
        }
        return (sat / n).toFloat() to white * 100f / n
    }

    @Test fun amanecerConservaColoresYSilueta() {
        line("=== AMANECER: brillo del horizonte y silueta ===")
        val scene = sunriseScene()
        val ia = runIa(scene, Scene.SUNSET, null)
        save(ia.photo, "amanecer_ia_sin_hdr.png")
        // Igual que el motor: bracket según cuánto se quema, y fusión HDR.
        val w = scene.w; val h = scene.h
        val ratio = com.iacamara.app.processing.BurstProcessor.hdrRatioFor(ia.stats.clippedArea)
        val normal = IntArray(w * h).also { ia.photo.getPixels(it, 0, w, 0, 0, w, h) }
        val under = IntArray(w * h).also { develop(scene, ia.gain / ratio).getPixels(it, 0, w, 0, 0, w, h) }
        val (dx, dy) = Align.estimateShift(Align.luma(normal), Align.lumaWithGain(under, ratio), w, h)
        val hdrBmp = Bitmap.createBitmap(HdrMerger(w, h).merge(normal, under, dx, dy, ratio), w, h, Bitmap.Config.ARGB_8888)
        save(hdrBmp, "amanecer_ia_con_hdr.png")

        // Zona del resplandor (sin el disco del sol) y zona de tierra.
        val glow = RectF(0.20f, 0.56f, 0.45f, 0.66f)
        val ground = RectF(0.05f, 0.75f, 0.95f, 0.95f)
        val (s0, w0) = saturation(ia.photo, glow)
        val (s1, w1) = saturation(hdrBmp, glow)
        line("decisión de la IA: %+.1f EV · HDR recomendado: %s · ${ia.why}".format(ia.ev, ia.hdr))
        line("resplandor  sin HDR: color %.0f%%, quemado %.0f%%   →   con HDR: color %.0f%%, quemado %.0f%%".format(s0 * 100, w0, s1 * 100, w1))
        line("silueta     brillo de la tierra: sin HDR %.3f → con HDR %.3f (debe seguir oscura)".format(regionLuma(ia.photo, ground), regionLuma(hdrBmp, ground)))
        assertTrue("el HDR debe recuperar el color del resplandor", s1 > s0 + 0.15f && w1 <= w0)
        assertTrue("la silueta no se aclara", abs(regionLuma(hdrBmp, ground) - regionLuma(ia.photo, ground)) < 0.02f)
        assertTrue("la IA debe pedir HDR en este amanecer", ia.hdr)
        File(outDir, "reporte_amanecer.txt").writeText(report.toString())
    }
}
