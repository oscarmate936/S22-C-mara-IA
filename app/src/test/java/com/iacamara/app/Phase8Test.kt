package com.iacamara.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.iacamara.app.processing.Affine
import com.iacamara.app.processing.Align
import com.iacamara.app.processing.Astro
import com.iacamara.app.processing.LocalTone
import com.iacamara.app.processing.LongExposureMerger
import com.iacamara.app.processing.RobustMerger
import com.iacamara.app.processing.Sharpen
import com.iacamara.app.processing.SuperRes
import com.iacamara.app.processing.TileAlign
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.Random
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** Fase 8: súper resolución, noche v2 (tono local), exposición larga y astrofotografía. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class Phase8Test {
    private val out = File("build/sim").apply { mkdirs() }
    private val report = StringBuilder()
    private fun line(s: String) { report.appendLine(s); println("SIM | $s") }
    private fun flush(name: String) = File(out, name).writeText(report.toString())

    private fun photo(name: String) = BitmapFactory.decodeStream(javaClass.classLoader!!.getResourceAsStream("sim/$name"))!!
    private fun px(b: Bitmap) = IntArray(b.width * b.height).also { b.getPixels(it, 0, b.width, 0, 0, b.width, b.height) }
    private fun bmp(p: IntArray, w: Int, h: Int) = Bitmap.createBitmap(p, w, h, Bitmap.Config.ARGB_8888)
    private fun save(p: IntArray, w: Int, h: Int, name: String) = File(out, name).outputStream().use { bmp(p, w, h).compress(Bitmap.CompressFormat.PNG, 100, it) }
    private fun side(a: IntArray, b: IntArray, w: Int, h: Int, name: String) {
        val o = IntArray((w * 2 + 8) * h)
        for (y in 0 until h) { System.arraycopy(a, y * w, o, y * (w * 2 + 8), w); System.arraycopy(b, y * w, o, y * (w * 2 + 8) + w + 8, w) }
        save(o, w * 2 + 8, h, name)
    }

    private fun ch(p: Int, c: Int) = (p shr (16 - 8 * c)) and 0xFF
    private fun lum(p: Int) = (ch(p, 0) * 2 + ch(p, 1) * 5 + ch(p, 2)) shr 3
    private fun rgb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)
    private fun psnr(a: IntArray, b: IntArray, w: Int, x0: Int, y0: Int, x1: Int, y1: Int): Double {
        var se = 0.0; var n = 0
        for (y in y0 until y1) for (x in x0 until x1) for (c in 0..2) { val d = ch(a[y * w + x], c) - ch(b[y * w + x], c); se += d * d; n++ }
        return if (se == 0.0) 99.0 else 10 * log10(255.0 * 255 / (se / n))
    }

    /** Muestra bilineal de una imagen ARGB. */
    private fun sample(p: IntArray, w: Int, h: Int, x: Float, y: Float): Int {
        val xx = x.coerceIn(0f, w - 1.001f); val yy = y.coerceIn(0f, h - 1.001f)
        val ix = xx.toInt(); val iy = yy.toInt(); val fx = xx - ix; val fy = yy - iy
        fun c(q: Int, k: Int) = ch(q, k).toFloat()
        val a = p[iy * w + ix]; val b = p[iy * w + ix + 1]; val cc = p[(iy + 1) * w + ix]; val d = p[(iy + 1) * w + ix + 1]
        fun m(k: Int) = (c(a, k) * (1 - fx) * (1 - fy) + c(b, k) * fx * (1 - fy) + c(cc, k) * (1 - fx) * fy + c(d, k) * fx * fy + 0.5f).toInt()
        return rgb(m(0), m(1), m(2))
    }

    // ------------------------------------------------------------- súper resolución

    @Test fun superResolucionConZoomDigital() {
        line("=== SÚPER RESOLUCIÓN (zoom digital 2×) ===")
        val src = photo("astronaut.png")
        val truthBmp = Bitmap.createScaledBitmap(src, 480, 480, true)
        val w = 480; val h = 480
        val truth = px(truthBmp)
        val rnd = Random(7)
        // Cada toma: el sensor (a mitad de resolución) ve la escena desplazada por el pulso,
        // con ruido, y la cámara la amplía 2× (zoom digital).
        fun frame(sx: Float, sy: Float): IntArray {
            val lw = w / 2; val lh = h / 2
            val low = IntArray(lw * lh) { i ->
                val x = i % lw; val y = i / lw
                var r = 0; var g = 0; var b = 0
                for (oy in 0..1) for (ox in 0..1) { val q = sample(truth, w, h, x * 2 + ox + sx, y * 2 + oy + sy); r += ch(q, 0); g += ch(q, 1); b += ch(q, 2) }
                val n = (rnd.nextGaussian() * 5).toInt()
                rgb(r / 4 + n, g / 4 + n, b / 4 + n)
            }
            return px(Bitmap.createScaledBitmap(bmp(low, lw, lh), w, h, true))
        }
        val shifts = listOf(0f to 0f) + List(7) { rnd.nextFloat() * 2f - 1f to rnd.nextFloat() * 2f - 1f }
        val frames = shifts.map { (x, y) -> frame(x, y) }
        val ref = frames[0]
        val m = RobustMerger(w, h, frames.size)
        m.add(ref)
        val refL = Align.luma(ref)
        for (k in 1 until frames.size) m.add(TileAlign.warp(frames[k], w, h, TileAlign.estimate(refL, Align.luma(frames[k]), w, h).transform))
        val merged = m.result()
        val amount = SuperRes.sharpenAmount(frames.size, 2f)
        val sr = Sharpen.apply(merged, w, h, amount)
        // Una sola toma, con el mejor enfoque posible para ella (comparación justa).
        val singleBest = listOf(0f, 0.3f, 0.6f, 1f).maxOf { a -> psnr(Sharpen.apply(ref, w, h, a), truth, w, 20, 20, w - 20, h - 20) }
        val single0 = psnr(ref, truth, w, 20, 20, w - 20, h - 20)
        val pSr = psnr(sr, truth, w, 20, 20, w - 20, h - 20)
        line("SUPER   1 toma: %.1f dB (con su mejor enfoque %.1f dB) · 8 tomas súper resolución: %.1f dB (enfoque %.2f)".format(single0, singleBest, pSr, amount))
        side(ref, sr, w, h, "superres_1_vs_8.png")
        assertTrue(pSr > singleBest + 1.0)
        flush("reporte_superres.txt")
    }

    // ------------------------------------------------------------- noche v2

    @Test fun nocheV2TonoLocalSinHalos() {
        line("=== NOCHE v2 · tono local ===")
        val src = Bitmap.createScaledBitmap(photo("chelsea.png"), 450, 300, true)
        val w = 450; val h = 300
        val t = px(src)
        // Escena nocturna: todo muy oscuro (1/20 de la luz) salvo una "ventana" iluminada.
        fun lin(v: Int) = (v / 255.0).pow(2.2)
        fun enc(v: Double) = ((v.coerceIn(0.0, 1.0)).pow(1 / 2.2) * 255 + 0.5).toInt()
        val wx0 = 300; val wy0 = 40; val wx1 = 430; val wy1 = 160
        val night = IntArray(w * h) { i ->
            val x = i % w; val y = i / w
            val k = if (x in wx0 until wx1 && y in wy0 until wy1) 0.9 else 0.05
            rgb(enc(lin(ch(t[i], 0)) * k), enc(lin(ch(t[i], 1)) * k), enc(lin(ch(t[i], 2)) * k))
        }
        val tone = LocalTone.apply(night, w, h)
        // Comparación: aclarado global (misma ganancia en toda la foto, como una curva).
        fun meanLin(p: IntArray, x0: Int, y0: Int, x1: Int, y1: Int): Double {
            var s = 0.0; var n = 0
            for (y in y0 until y1) for (x in x0 until x1) { s += lin(lum(p[y * w + x])); n++ }
            return s / n
        }
        val darkBefore = meanLin(night, 20, 60, 260, 280)
        val darkAfter = meanLin(tone, 20, 60, 260, 280)
        val lift = ln(darkAfter / darkBefore) / ln(2.0)
        val winBefore = meanLin(night, wx0 + 10, wy0 + 10, wx1 - 10, wy1 - 10)
        val winAfter = meanLin(tone, wx0 + 10, wy0 + 10, wx1 - 10, wy1 - 10)
        val winChange = ln(winAfter / winBefore) / ln(2.0)
        // Detalle conservado: correlación de la textura (luma menos su media local) con la original.
        fun detailCorr(): Double {
            var sab = 0.0; var saa = 0.0; var sbb = 0.0
            for (y in 62 until 278) for (x in 22 until 258) {
                fun hp(p: IntArray): Double { var m = 0.0; for (dy in -2..2) for (dx in -2..2) m += lum(p[(y + dy) * w + x + dx]); return lum(p[y * w + x]) - m / 25 }
                val a = hp(tone); val b = hp(t); sab += a * b; saa += a * a; sbb += b * b
            }
            return sab / sqrt(saa * sbb)
        }
        val corr = detailCorr()
        // Halos: junto a la ventana (zona oscura) el aclarado debe ser igual que lejos de ella.
        val near = meanLin(tone, wx0 - 12, wy1 + 2, wx0 - 2, wy1 + 40) / meanLin(night, wx0 - 12, wy1 + 2, wx0 - 2, wy1 + 40)
        val far = meanLin(tone, wx0 - 80, wy1 + 2, wx0 - 70, wy1 + 40) / meanLin(night, wx0 - 80, wy1 + 2, wx0 - 70, wy1 + 40)
        line("NOCHE2  zonas oscuras +%.1f pasos · ventana %+.2f pasos (no se quema) · textura conservada %.0f %% · halo: aclarado junto a la ventana %.0f %% del de lejos".format(lift, winChange, corr * 100, near / far * 100))
        side(night, tone, w, h, "noche_v2_tono_local.png")
        assertTrue(lift > 1.4)
        assertTrue(winChange < 0.3)
        assertTrue(corr > 0.85)
        assertTrue(near / far in 0.8..1.2)
        flush("reporte_noche_v2.txt")
    }

    // ------------------------------------------------------------- exposición larga

    @Test fun exposicionLargaSedaYEstelas() {
        line("=== EXPOSICIÓN LARGA ===")
        val w = 400; val h = 300
        val truth = px(Bitmap.createScaledBitmap(photo("rocket.jpg"), w, h, true))
        val rnd = Random(3)
        val n = 10
        val waterY = 240
        // Agua: textura que cambia en cada toma alrededor de un tono medio.
        val frames = (0 until n).map { k ->
            val dx = rnd.nextInt(7) - 3; val dy = rnd.nextInt(7) - 3 // pulso
            val f = IntArray(w * h) { i ->
                val x = i % w + dx; val y = i / w + dy
                val xx = x.coerceIn(0, w - 1); val yy = y.coerceIn(0, h - 1)
                if (yy >= waterY) { val v = 90 + rnd.nextInt(80); rgb(v / 2, v * 3 / 4, v) } else truth[yy * w + xx]
            }
            // Faro de un auto: recorre la foto; cada toma (obturación larga) lo ve como un trazo.
            val x0 = 40 + k * 32; val x1 = x0 + 26
            for (x in x0 until x1) for (oy in -2..2) {
                val sx = x - dx; val sy = 100 + oy - dy
                if (sx in 0 until w && sy in 0 until h) f[sy * w + sx] = rgb(255, 240, 200)
            }
            f
        }
        val m = LongExposureMerger(w, h)
        val refL = Align.luma(frames[0])
        m.add(frames[0])
        for (k in 1 until n) m.add(TileAlign.warp(frames[k], w, h, TileAlign.estimate(refL, Align.luma(frames[k]), w, h).transform))
        val res = m.result()
        var lit = 0; var total = 0
        val trailY = (0 until h).maxBy { y -> (60 until 340).sumOf { x -> lum(res[y * w + x]) } }
        for (x in 50 until 340) { total++; if (lum(res[trailY * w + x]) > 150) lit++ }
        fun std(p: IntArray, y0: Int, y1: Int): Double {
            var s = 0.0; var s2 = 0.0; var c = 0
            for (y in y0 until y1) for (x in 20 until w - 20) { val v = lum(p[y * w + x]).toDouble(); s += v; s2 += v * v; c++ }
            val mm = s / c; return sqrt(s2 / c - mm * mm)
        }
        val waterBefore = std(frames[0], waterY + 8, h - 8)
        val waterAfter = std(res, waterY + 8, h - 8)
        val bgSharp = com.iacamara.app.processing.Sharpness.of(Align.luma(res), w, h, intArrayOf(20, 150, 380, 230))
        val bgSharpIn = com.iacamara.app.processing.Sharpness.of(Align.luma(frames[0]), w, h, intArrayOf(20, 150, 380, 230))
        line("LARGA   estela continua en el %.0f %% del recorrido · agua: textura %.1f → %.1f (seda) · fondo nítido (%.0f %% de la nitidez de una toma)".format(lit * 100.0 / total, waterBefore, waterAfter, bgSharp / bgSharpIn * 100))
        side(frames[0], res, w, h, "exposicion_larga.png")
        assertTrue(lit * 1.0 / total > 0.85)
        assertTrue(waterAfter < waterBefore / 2.5)
        assertTrue(bgSharp > bgSharpIn * 0.8f)
        flush("reporte_larga.txt")
    }

    // ------------------------------------------------------------- astrofotografía

    private class StarField(val frames: List<IntArray>, val truthLine: Int, val stars: List<FloatArray>, val hot: List<Int>)

    private fun starField(w: Int, h: Int, n: Int): StarField {
        val rnd = Random(11)
        val groundTop = (h * 0.72f).toInt()
        val stars = List(160) { floatArrayOf(rnd.nextFloat() * w, rnd.nextFloat() * h * 0.7f, 40f + rnd.nextFloat() * 200f) }
        // Paisaje: la parte de abajo de la foto del cohete (torres y edificios), con poca luz.
        val rk = photo("rocket.jpg")
        val landBand = px(Bitmap.createScaledBitmap(Bitmap.createBitmap(rk, 0, (rk.height * 0.8f).toInt(), rk.width, (rk.height * 0.2f).toInt()), w, h - groundTop, true))
        val land = IntArray(w * h).also { System.arraycopy(landBand, 0, it, groundTop * w, landBand.size) }
        val hot = List(30) { rnd.nextInt(w) + (rnd.nextInt(groundTop - 10) + 5) * w }
        // Giro alrededor de un polo fuera de la foto + desplazamiento (la Tierra gira).
        val poleX = -300f; val poleY = -500f
        val frames = (0 until n).map { k ->
            val ang = Math.toRadians(0.05 * k).toFloat()
            val f = FloatArray(w * h) { 14f }
            for (s in stars) {
                val x = poleX + (s[0] - poleX) * cos(ang) - (s[1] - poleY) * sin(ang)
                val y = poleY + (s[0] - poleX) * sin(ang) + (s[1] - poleY) * cos(ang)
                for (dy in -3..3) for (dx in -3..3) {
                    val xx = x.toInt() + dx; val yy = y.toInt() + dy
                    if (xx in 0 until w && yy in 0 until h) f[yy * w + xx] += s[2] * exp(-((xx - x) * (xx - x) + (yy - y) * (yy - y)) / (2 * 1.1f * 1.1f))
                }
            }
            IntArray(w * h) { i ->
                val y = i / w
                val noise = (rnd.nextGaussian() * 4).toFloat()
                if (y >= groundTop) {
                    val q = land[i]
                    rgb(ch(q, 0) / 2 + noise.toInt(), ch(q, 1) / 2 + noise.toInt(), ch(q, 2) / 2 + noise.toInt())
                } else {
                    val v = (f[i] + noise).toInt()
                    rgb(v, v, (v * 1.1f).toInt())
                }
            }.also { p -> for (hp in hot) p[hp] = rgb(220, 220, 220) }
        }
        return StarField(frames, groundTop, stars, hot)
    }

    @Test fun astrofotografia() {
        line("=== ASTROFOTOGRAFÍA ===")
        val w = 640; val h = 420; val n = 10
        val sf = starField(w, h, n)
        val fr = sf.frames.map { it.copyOf() }
        var hotFixed = 0
        for (f in fr) hotFixed += Astro.removeHotPixels(f, w, h)
        val line0 = Astro.skyline(Align.luma(fr[0]), w, h)
        val lineErr = line0.map { abs(it - sf.truthLine) }.sorted()[w / 2]
        val skyEnd = line0.min()
        val refStars = Astro.detectStars(Align.luma(fr[0]), w, h, skyEnd)
        val sky = RobustMerger(w, h, n); val ground = RobustMerger(w, h, n)
        sky.add(fr[0]); ground.add(fr[0])
        var aligned = 0
        val naive = com.iacamara.app.processing.MeanStacker(w, h)
        naive.add(sf.frames[0])
        for (k in 1 until n) {
            ground.add(fr[k]); naive.add(sf.frames[k])
            val t: Affine = Astro.matchStars(refStars, Astro.detectStars(Align.luma(fr[k]), w, h, skyEnd)) ?: continue
            sky.add(Astro.warpCubic(fr[k], w, h, t)); aligned++
        }
        val res = Astro.composite(sky.result(), ground.result(), w, h, line0)
        val naiveRes = naive.result()
        // Estrellas: brillo máximo en su posición (puntuales) frente a sumar sin alinear (estelas).
        fun peaks(p: IntArray): Double {
            var s = 0.0; var c = 0
            for (st in sf.stars) {
                val x = st[0].toInt(); val y = st[1].toInt()
                if (x < 4 || y < 4 || x >= w - 4 || y >= skyEnd - 4 || st[2] < 120f) continue
                // Pico (media 3×3 alrededor del punto más brillante: no cuenta el ruido suelto).
                var bx = x; var by = y
                for (dy in -2..2) for (dx in -2..2) if (lum(p[(y + dy) * w + x + dx]) > lum(p[by * w + bx])) { bx = x + dx; by = y + dy }
                var m = 0
                for (dy in -1..1) for (dx in -1..1) m += lum(p[(by + dy) * w + bx + dx])
                s += m / 9.0; c++
            }
            return s / c
        }
        // Ruido del fondo del cielo y píxeles calientes que quedan.
        fun skyNoise(p: IntArray): Double {
            val v = ArrayList<Int>()
            for (y in 10 until skyEnd - 10 step 3) for (x in 10 until w - 10 step 3) {
                val near = sf.stars.any { abs(it[0] - x) < 9 && abs(it[1] - y) < 9 }
                if (!near) v += lum(p[y * w + x])
            }
            val m = v.average(); return sqrt(v.sumOf { (it - m) * (it - m) } / v.size)
        }
        val hotLeft = sf.hot.count { lum(res[it]) > 60 }
        val pA = peaks(res); val pN = peaks(naiveRes); val p1 = peaks(sf.frames[0])
        line("ASTRO   horizonte: error %d px · %d de %d tomas alineadas con las estrellas · píxeles calientes eliminados (%d restantes de %d)".format(lineErr, aligned, n - 1, hotLeft, sf.hot.size))
        line("ASTRO   estrellas: brillo %.0f (1 toma %.0f, sumar sin alinear %.0f = estelas) · ruido del cielo %.1f → %.1f".format(pA, p1, pN, skyNoise(sf.frames[0]), skyNoise(res)))
        side(sf.frames[0], res, w, h, "astro.png")
        assertTrue(lineErr < 10)
        assertTrue(aligned >= n - 2)
        assertTrue(pA > pN * 1.15) // puntuales, no estelas
        assertTrue(pA > p1 * 0.9) // tan brillantes como en una toma (sin el ruido)
        assertTrue(skyNoise(res) < skyNoise(sf.frames[0]) * 0.6)
        assertTrue(hotLeft == 0)
        flush("reporte_astro.txt")
    }

    @Test fun alineaEstrellasConGiro() {
        // Estrellas sintéticas giradas 0.4° y desplazadas (12.3, −7.6) px: se recupera la transformación.
        val rnd = Random(5)
        val ref = List(120) { Astro.Star(rnd.nextFloat() * 1000, rnd.nextFloat() * 700, 50f + rnd.nextFloat() * 100) }
        val a = Math.toRadians(0.4).toFloat()
        val cur = ref.map { s -> Astro.Star(cos(a) * s.x - sin(a) * s.y + 12.3f, sin(a) * s.x + cos(a) * s.y - 7.6f, s.flux) }.shuffled(rnd)
        val t = Astro.matchStars(ref.sortedByDescending { it.flux }, cur.sortedByDescending { it.flux })!!
        var maxErr = 0f
        for (s in ref) {
            val ex = cos(a) * s.x - sin(a) * s.y + 12.3f - t.mapX(s.x, s.y)
            val ey = sin(a) * s.x + cos(a) * s.y - 7.6f - t.mapY(s.x, s.y)
            maxErr = maxOf(maxErr, sqrt(ex * ex + ey * ey))
        }
        assertTrue("error $maxErr", maxErr < 0.05f)
        val (shutter, iso, frames) = Astro.plan(30_000_000_000L, 6400)
        assertTrue(shutter == 4_000_000_000L && iso == 1600 && frames in 7..15)
    }

    @Test fun tiemposATamanoGrande() {
        // Foto de 3000×2250 (similar a las del teléfono): cuánto tarda cada paso nuevo.
        val w = 3000; val h = 2250
        val p = px(Bitmap.createScaledBitmap(photo("rocket.jpg"), w, h, true))
        var t = System.currentTimeMillis()
        LocalTone.apply(p, w, h)
        val tTone = System.currentTimeMillis() - t
        t = System.currentTimeMillis()
        val m = LongExposureMerger(w, h); repeat(3) { m.add(p) }; m.result()
        val tLong = System.currentTimeMillis() - t
        t = System.currentTimeMillis()
        Sharpen.apply(p, w, h, 1.5f)
        val tSharp = System.currentTimeMillis() - t
        line("TIEMPOS 3000×2250: tono local ${tTone} ms · exposición larga (3 tomas) ${tLong} ms · enfoque ${tSharp} ms")
        assertTrue(tTone < 15000 && tLong < 15000)
        flush("reporte_tiempos_f8.txt")
    }
}
