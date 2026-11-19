package com.iacamara.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.iacamara.app.ai.FaceObs
import com.iacamara.app.ai.PhotoScore
import com.iacamara.app.ai.TasteCore
import com.iacamara.app.processing.Align
import com.iacamara.app.processing.Straighten
import com.iacamara.app.processing.ZoneMasks
import com.iacamara.app.processing.ZoneRetouch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.Random
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/** Fase 9: enderezado y perspectiva, retoque por zonas, calificación y aprender tu gusto. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class Phase9Test {
    private val out = File("build/sim").apply { mkdirs() }
    private val report = StringBuilder()
    private fun line(s: String) { report.appendLine(s); println("SIM | $s") }
    private fun flush(name: String) = File(out, name).writeText(report.toString())

    private fun photo(name: String, w: Int, h: Int) =
        IntArray(w * h).also { Bitmap.createScaledBitmap(BitmapFactory.decodeStream(javaClass.classLoader!!.getResourceAsStream("sim/$name"))!!, w, h, true).getPixels(it, 0, w, 0, 0, w, h) }
    private fun save(p: IntArray, w: Int, h: Int, name: String) = File(out, name).outputStream().use { Bitmap.createBitmap(p, w, h, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.PNG, 100, it) }
    private fun side(a: IntArray, b: IntArray, w: Int, h: Int, name: String) {
        val o = IntArray((w * 2 + 8) * h)
        for (y in 0 until h) { System.arraycopy(a, y * w, o, y * (w * 2 + 8), w); System.arraycopy(b, y * w, o, y * (w * 2 + 8) + w + 8, w) }
        save(o, w * 2 + 8, h, name)
    }
    private fun ch(p: Int, c: Int) = (p shr (16 - 8 * c)) and 0xFF
    private fun lum(p: Int) = (ch(p, 0) * 2 + ch(p, 1) * 5 + ch(p, 2)) shr 3

    // ------------------------------------------------------------- enderezado

    @Test fun enderezaYCorrigeLaPerspectiva() {
        line("=== ENDEREZADO Y PERSPECTIVA ===")
        val w = 640; val h = 480
        val src = photo("rocket.jpg", w, h)
        val e0 = Straighten.estimate(Align.luma(src), w, h)
        // Foto torcida 4°.
        val tilted = Straighten.apply(src, w, h, 4f, 0f)
        val e1 = Straighten.estimate(Align.luma(tilted), w, h)
        val fixed = Straighten.apply(tilted, w, h, e1.tiltDeg, 0f)
        val e2 = Straighten.estimate(Align.luma(fixed), w, h)
        line("GIRO    original %.2f° · torcida 4° → medida %.2f° (confianza %.2f, %d verticales) · tras enderezar %.2f°".format(e0.tiltDeg, e1.tiltDeg, e1.confidence, e1.verticalLines, e2.tiltDeg))
        side(tilted, fixed, w, h, "enderezado.png")
        assertTrue(abs(e0.tiltDeg) < 0.8f)
        assertTrue(abs(e1.tiltDeg + 4f) < 0.7f)
        assertTrue(e1.hasTilt)
        assertTrue(abs(e2.tiltDeg) < 0.6f)
        // Perspectiva: verticales que convergen (teléfono apuntando hacia arriba).
        val b0 = 0.00045f
        val keyed = Straighten.apply(src, w, h, 0f, b0, keystoneAmount = 1f)
        val k1 = Straighten.estimate(Align.luma(keyed), w, h)
        val unkeyed = Straighten.apply(keyed, w, h, k1.tiltDeg, k1.keystone, keystoneAmount = 1f)
        val k2 = Straighten.estimate(Align.luma(unkeyed), w, h)
        line("PERSP   la foto ya converge (%.5f, tomada hacia arriba) · añadida %.5f → medida %.5f · tras corregir %.5f (verticales paralelas)".format(e0.keystone, -b0, k1.keystone - e0.keystone, k2.keystone))
        side(keyed, unkeyed, w, h, "perspectiva.png")
        assertTrue(k1.hasKeystone)
        assertTrue(abs(k1.keystone - e0.keystone + b0) < b0 * 0.35f)
        assertTrue(abs(k2.keystone) < b0 * 0.35f)
        flush("reporte_enderezado.txt")
    }

    @Test fun sinLineasNoInventaUnGiro() {
        val w = 600; val h = 400
        val e = Straighten.estimate(Align.luma(photo("chelsea.png", w, h)), w, h)
        assertTrue("tilt ${e.tiltDeg} conf ${e.confidence}", !e.hasTilt || abs(e.tiltDeg) < 1f)
    }

    // ------------------------------------------------------------- retoque por zonas

    @Test fun zonasCieloPielYVegetacion() {
        line("=== RETOQUE POR ZONAS ===")
        val w = 600; val h = 450
        val rocket = photo("rocket.jpg", w, h)
        val m = ZoneMasks.compute(rocket, w, h)
        fun frac(mm: com.iacamara.app.processing.FloatMap, y0: Float, y1: Float): Float {
            var s = 0f; var n = 0
            for (y in (y0 * mm.h).toInt() until (y1 * mm.h).toInt()) for (x in 0 until mm.w) { s += mm[x, y]; n++ }
            return s / n
        }
        val skyTop = frac(m.sky, 0f, 0.3f); val skyBottom = frac(m.sky, 0.95f, 1f) // la plataforma y las luces (el horizonte de esta foto está muy abajo)
        save(IntArray(m.sky.w * m.sky.h) { val v = (m.sky.v[it] * 255).toInt(); (0xFF shl 24) or (v shl 16) or (v shl 8) or v }, m.sky.w, m.sky.h, "mascara_cielo.png")
        val res = ZoneRetouch.apply(rocket, w, h, m, ZoneRetouch.Params(skin = false, foliage = false, strength = 0.8f))
        fun sat(p: IntArray, y0: Int, y1: Int): Double {
            var s = 0.0; var n = 0
            for (y in y0 until y1) for (x in 0 until w step 2) { val q = p[y * w + x]; val mx = max(ch(q, 0), max(ch(q, 1), ch(q, 2))); val mn = min(ch(q, 0), min(ch(q, 1), ch(q, 2))); s += if (mx > 0) (mx - mn).toDouble() / mx else 0.0; n++ }
            return s / n
        }
        fun psnr(a: IntArray, b: IntArray, y0: Int, y1: Int): Double {
            var se = 0.0; var n = 0
            for (y in y0 until y1) for (x in 0 until w) for (c in 0..2) { val d = ch(a[y * w + x], c) - ch(b[y * w + x], c); se += d * d; n++ }
            return if (se == 0.0) 99.0 else 10 * log10(255.0 * 255 / (se / n))
        }
        val satBefore = sat(rocket, 10, 100); val satAfter = sat(res, 10, 100)
        val groundPsnr = psnr(rocket, res, (h * 0.85f).toInt(), h)
        line("CIELO   detectado: %.0f %% arriba, %.0f %% abajo · color del cielo %.2f → %.2f · suelo sin cambios (%.0f dB)".format(skyTop * 100, skyBottom * 100, satBefore, satAfter, groundPsnr))
        side(rocket, res, w, h, "zonas_cielo.png")
        assertTrue(skyTop > 0.6f)
        assertTrue(skyBottom < 0.35f)
        assertTrue(satAfter > satBefore * 1.08)
        assertTrue(groundPsnr > 33)

        // Piel: retrato oscuro (2.7 pasos por debajo) → la cara se aclara más que el resto.
        val aw = 512; val ah = 512
        val astro = photo("astronaut.png", aw, ah)
        fun darken(v: Int) = (((v / 255.0).pow(2.2) * 0.15).pow(1 / 2.2) * 255).toInt()
        val dark = IntArray(aw * ah) { i -> val q = astro[i]; (0xFF shl 24) or (darken(ch(q, 0)) shl 16) or (darken(ch(q, 1)) shl 8) or darken(ch(q, 2)) }
        val face = floatArrayOf(0.36f, 0.12f, 0.62f, 0.42f)
        val am = ZoneMasks.compute(dark, aw, ah, listOf(face))
        var skinIn = 0f; var nIn = 0
        for (y in (0.2f * am.skin.h).toInt() until (0.35f * am.skin.h).toInt()) for (x in (0.42f * am.skin.w).toInt() until (0.56f * am.skin.w).toInt()) { skinIn += am.skin[x, y]; nIn++ }
        val ar = ZoneRetouch.apply(dark, aw, ah, am, ZoneRetouch.Params(sky = false, foliage = false, strength = 1f))
        fun meanL(p: IntArray, x0: Float, y0: Float, x1: Float, y1: Float): Double {
            var s = 0.0; var n = 0
            for (y in (y0 * ah).toInt() until (y1 * ah).toInt()) for (x in (x0 * aw).toInt() until (x1 * aw).toInt()) { s += lum(p[y * aw + x]); n++ }
            return s / n
        }
        val faceGain = meanL(ar, 0.44f, 0.25f, 0.56f, 0.35f) / meanL(dark, 0.44f, 0.25f, 0.56f, 0.35f)
        val bgGain = meanL(ar, 0.0f, 0.6f, 0.25f, 0.95f) / meanL(dark, 0.0f, 0.6f, 0.25f, 0.95f)
        line("PIEL    piel detectada en la cara %.0f %% · cara oscura aclarada ×%.2f · fondo ×%.2f (solo la cara)".format(skinIn / nIn * 100, faceGain, bgGain))
        side(dark, ar, aw, ah, "zonas_piel.png")
        assertTrue(skinIn / nIn > 0.5f)
        assertTrue(faceGain > 1.12 && bgGain < 1.03)

        // Vegetación: un parche verde gana algo de color; lo gris no cambia.
        val gw = 200; val gh = 100
        val rnd = Random(1)
        val g = IntArray(gw * gh) { i -> val x = i % gw; val n = rnd.nextInt(20); if (x < 100) (0xFF shl 24) or ((60 + n) shl 16) or ((120 + n) shl 8) or (50 + n) else (0xFF shl 24) or ((120 + n) shl 16) or ((120 + n) shl 8) or (120 + n) }
        val gm = ZoneMasks.compute(g, gw, gh)
        val gr = ZoneRetouch.apply(g, gw, gh, gm, ZoneRetouch.Params(sky = false, skin = false, strength = 1f))
        fun greenSat(p: IntArray) = (0 until gh).sumOf { y -> (10 until 90).sumOf { x -> val q = p[y * gw + x]; (ch(q, 1) - ch(q, 0)).toDouble() } } / (gh * 80)
        val grey = (0 until gh).sumOf { y -> (110 until 190).sumOf { x -> abs(lum(gr[y * gw + x]) - lum(g[y * gw + x])).toDouble() } } / (gh * 80)
        line("VERDE   color de la vegetación %.0f → %.0f · zona gris: cambio %.1f niveles".format(greenSat(g), greenSat(gr), grey))
        assertTrue(greenSat(gr) > greenSat(g) * 1.05)
        assertTrue(grey < 2)
        flush("reporte_zonas.txt")
    }

    // ------------------------------------------------------------- calificación

    @Test fun calificacionDistingueFotosBuenasYMalas() {
        line("=== CALIFICACIÓN DE LA FOTO ===")
        val w = 600; val h = 400
        val good = photo("chelsea.png", w, h)
        val bmp = Bitmap.createBitmap(good, w, h, Bitmap.Config.ARGB_8888)
        val blurry = IntArray(w * h).also { Bitmap.createScaledBitmap(Bitmap.createScaledBitmap(bmp, w / 5, h / 5, true), w, h, true).getPixels(it, 0, w, 0, 0, w, h) }
        val rnd = Random(2)
        val noisy = IntArray(w * h) { i -> val q = good[i]; val n = (rnd.nextGaussian() * 14).toInt(); (0xFF shl 24) or ((ch(q, 0) + n).coerceIn(0, 255) shl 16) or ((ch(q, 1) + n).coerceIn(0, 255) shl 8) or (ch(q, 2) + n).coerceIn(0, 255) }
        val dark = IntArray(w * h) { i -> val q = good[i]; (0xFF shl 24) or ((ch(q, 0) / 6) shl 16) or ((ch(q, 1) / 6) shl 8) or (ch(q, 2) / 6) }
        val burnt = IntArray(w * h) { i -> val q = good[i]; (0xFF shl 24) or ((ch(q, 0) * 2).coerceAtMost(255) shl 16) or ((ch(q, 1) * 2).coerceAtMost(255) shl 8) or (ch(q, 2) * 2).coerceAtMost(255) }
        val rw = 640; val rh = 480
        val rocket = photo("rocket.jpg", rw, rh)
        val rocketTilted = Straighten.apply(rocket, rw, rh, 5f, 0f)
        val sGood = PhotoScore.score(PhotoScore.measure(good, w, h))
        val sBlur = PhotoScore.score(PhotoScore.measure(blurry, w, h))
        val sNoisy = PhotoScore.score(PhotoScore.measure(noisy, w, h))
        val sDark = PhotoScore.score(PhotoScore.measure(dark, w, h))
        val sBurnt = PhotoScore.score(PhotoScore.measure(burnt, w, h))
        val sRocket = PhotoScore.score(PhotoScore.measure(rocket, rw, rh))
        val sTilt = PhotoScore.score(PhotoScore.measure(rocketTilted, rw, rh))
        val closed = PhotoScore.score(PhotoScore.measure(good, w, h, listOf(FaceObs(0.3f, 0.2f, 0.6f, 0.7f, 0.1f, 0.2f))))
        fun d(r: PhotoScore.Result) = "${r.score} (${r.label})" + (r.tips.firstOrNull()?.let { " · ${it.detail}" } ?: "")
        line("NOTA    nítida: ${d(sGood)}")
        line("NOTA    movida: ${d(sBlur)}")
        line("NOTA    con ruido: ${d(sNoisy)}")
        line("NOTA    oscura: ${d(sDark)}")
        line("NOTA    quemada: ${d(sBurnt)}")
        line("NOTA    paisaje recto: ${d(sRocket)} · torcido 5°: ${d(sTilt)}")
        line("NOTA    ojos cerrados: ${d(closed)}")
        assertTrue(sGood.score >= 80)
        assertTrue(sBlur.score <= sGood.score - 20)
        assertTrue(sNoisy.score < sGood.score - 5)
        assertTrue(sDark.score < sGood.score - 8)
        assertTrue(sBurnt.score < sGood.score - 8)
        assertTrue(sTilt.score < sRocket.score && sTilt.tips.any { it.name == "Horizonte" })
        assertTrue(closed.tips.any { it.name == "Personas" })
        flush("reporte_calificacion.txt")
    }

    // ------------------------------------------------------------- aprender tu gusto

    @Test fun aprendeTuGusto() {
        val t = TasteCore()
        assertEquals(0f, t.evBias("PORTRAIT"), 0f) // sin datos, nada
        t.recordExposure("PORTRAIT", 0.7f)
        assertEquals(0f, t.evBias("PORTRAIT"), 0.21f) // una sola corrección pesa poco
        repeat(5) { t.recordExposure("PORTRAIT", 0.7f) }
        val b = t.evBias("PORTRAIT")
        assertTrue("bias $b", b in 0.3f..0.7f)
        // Otra escena sin datos propios: usa algo del gusto general, menos.
        assertTrue(t.evBias("FOOD") in 0f..b)
        // Correcciones extremas: tope ±0.7.
        repeat(12) { t.recordExposure("NIGHT", -2f) }
        assertTrue(t.evBias("NIGHT") >= -0.7f)
        // El gusto cambia: las correcciones recientes mandan.
        repeat(12) { t.recordExposure("PORTRAIT", -0.5f) }
        assertTrue(t.evBias("PORTRAIT") < 0f)
        t.recordPortrait(0.9f); t.recordPortrait(0.8f)
        assertEquals(0.85f, t.portraitStrength(0.6f), 0.01f)
        val r = TasteCore.parse(t.serialize())
        assertEquals(t.evBias("PORTRAIT"), r.evBias("PORTRAIT"), 1e-4f)
        assertEquals(t.portraitStrength(0.6f), r.portraitStrength(0.6f), 1e-4f)
    }
}
