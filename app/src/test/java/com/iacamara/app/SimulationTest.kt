package com.iacamara.app

import com.iacamara.app.processing.Align
import com.iacamara.app.processing.FocusStacker
import com.iacamara.app.processing.MeanStacker
import com.iacamara.app.processing.RobustMerger
import com.iacamara.app.processing.TileAlign
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Simulaciones con fotos reales: se recrean las condiciones físicas de una toma
 * (ruido del sensor con poca luz, pulso de la mano, objetos que se mueven, desenfoque
 * según la distancia) y se pasan por el MISMO código de procesamiento de la app.
 *
 * Los resultados (imágenes y métricas) se guardan en app/build/sim/.
 */
class SimulationTest {
    private val out = File("build/sim").apply { mkdirs() }
    private val report = StringBuilder()

    // ------------------------------------------------------------------ utilidades

    private class Img(val w: Int, val h: Int, val px: IntArray)

    // javax.imageio existe en la JVM de pruebas pero no en el classpath de compilación de Android:
    // se usa por reflexión.
    private val imageIO = Class.forName("javax.imageio.ImageIO")
    private val bufferedImage = Class.forName("java.awt.image.BufferedImage")

    private fun load(name: String): Img {
        val stream = javaClass.classLoader!!.getResourceAsStream("sim/$name")
        val bi = imageIO.getMethod("read", java.io.InputStream::class.java).invoke(null, stream)
        val w = bufferedImage.getMethod("getWidth").invoke(bi) as Int
        val h = bufferedImage.getMethod("getHeight").invoke(bi) as Int
        val px = IntArray(w * h)
        bufferedImage.getMethod(
            "getRGB", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType, IntArray::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
        ).invoke(bi, 0, 0, w, h, px, 0, w)
        for (i in px.indices) px[i] = px[i] or (0xFF shl 24)
        return Img(w, h, px)
    }

    private fun save(img: Img, name: String) {
        val typeRgb = bufferedImage.getField("TYPE_INT_RGB").getInt(null)
        val bi = bufferedImage.getConstructor(Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            .newInstance(img.w, img.h, typeRgb)
        bufferedImage.getMethod(
            "setRGB", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType, IntArray::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
        ).invoke(bi, 0, 0, img.w, img.h, img.px, 0, img.w)
        val rendered = Class.forName("java.awt.image.RenderedImage")
        imageIO.getMethod("write", rendered, String::class.java, File::class.java).invoke(null, bi, "png", File(out, name))
    }

    private fun ch(p: Int, c: Int) = (p shr (16 - 8 * c)) and 0xFF
    private fun rgb(r: Int, g: Int, b: Int) =
        (0xFF shl 24) or (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)

    /** PSNR en dB sobre la zona central (los bordes pierden datos al alinear). */
    private fun psnr(a: Img, b: Img, border: Int = 20): Double {
        var se = 0.0
        var n = 0
        for (y in border until a.h - border) for (x in border until a.w - border) {
            val i = y * a.w + x
            for (c in 0..2) { val d = ch(a.px[i], c) - ch(b.px[i], c); se += d * d; n++ }
        }
        val mse = se / n
        return if (mse == 0.0) 99.0 else 10 * log10(255.0 * 255.0 / mse)
    }

    /** Muestreo bilineal con desplazamiento (dx, dy) y giro (grados) alrededor del centro. */
    private fun warp(src: Img, dx: Double, dy: Double, deg: Double): Img {
        val a = Math.toRadians(deg)
        val ca = cos(a)
        val sa = sin(a)
        val cx = src.w / 2.0
        val cy = src.h / 2.0
        val o = IntArray(src.w * src.h)
        for (y in 0 until src.h) for (x in 0 until src.w) {
            // Punto de la escena que cae en el píxel (x, y) de esta toma.
            val rx = x - dx - cx
            val ry = y - dy - cy
            val sx = (ca * rx + sa * ry + cx).coerceIn(0.0, src.w - 1.001)
            val sy = (-sa * rx + ca * ry + cy).coerceIn(0.0, src.h - 1.001)
            val x0 = sx.toInt(); val y0 = sy.toInt()
            val fx = sx - x0; val fy = sy - y0
            val p00 = src.px[y0 * src.w + x0]; val p10 = src.px[y0 * src.w + x0 + 1]
            val p01 = src.px[(y0 + 1) * src.w + x0]; val p11 = src.px[(y0 + 1) * src.w + x0 + 1]
            val c = IntArray(3) { k ->
                val v = ch(p00, k) * (1 - fx) * (1 - fy) + ch(p10, k) * fx * (1 - fy) +
                    ch(p01, k) * (1 - fx) * fy + ch(p11, k) * fx * fy
                (v + 0.5).toInt()
            }
            o[y * src.w + x] = rgb(c[0], c[1], c[2])
        }
        return Img(src.w, src.h, o)
    }

    /**
     * Ruido realista de sensor con poca luz: ruido de fotones (Poisson) + ruido de lectura,
     * aplicado en el espacio lineal y luego codificado en gamma, como hace la cámara.
     * @param photons fotones por píxel a plena escala (menos = más oscuro / más ISO).
     */
    private fun sensorNoise(src: Img, photons: Double, rnd: Random): Img {
        val o = IntArray(src.px.size)
        for (i in src.px.indices) {
            val c = IntArray(3) { k ->
                val lin = (ch(src.px[i], k) / 255.0).pow(2.2)
                val e = lin * photons
                val noisy = (e + sqrt(max(e, 0.0)) * gauss(rnd)) / photons + 0.012 * gauss(rnd)
                (max(noisy, 0.0).pow(1 / 2.2) * 255 + 0.5).toInt()
            }
            o[i] = rgb(c[0], c[1], c[2])
        }
        return Img(src.w, src.h, o)
    }

    private fun gauss(r: Random): Double {
        val u1 = r.nextDouble().coerceAtLeast(1e-12)
        val u2 = r.nextDouble()
        return sqrt(-2 * kotlin.math.ln(u1)) * cos(2 * Math.PI * u2)
    }

    private fun applyLift(src: Img, lift: Float): Img {
        val st = MeanStacker(src.w, src.h)
        st.add(src.px)
        return Img(src.w, src.h, st.result(lift))
    }

    private fun line(s: String) { report.appendLine(s); println("SIM | $s") }

    private fun flush(name: String) = File(out, name).writeText(report.toString())

    // ------------------------------------------------------------------ 1. Modo noche

    private fun nightCase(photo: String, photons: Double, rotationDeg: Double, ghost: Boolean, seed: Int): Pair<Double, Double> {
        val gt = load(photo)
        val rnd = Random(seed)
        val n = 8
        val frames = (0 until n).map { k ->
            val dx = if (k == 0) 0.0 else rnd.nextDouble(-12.0, 12.0)
            val dy = if (k == 0) 0.0 else rnd.nextDouble(-12.0, 12.0)
            val rot = if (k == 0 || rotationDeg == 0.0) 0.0 else rnd.nextDouble(-rotationDeg, rotationDeg)
            var f = warp(gt, dx, dy, rot)
            if (ghost && k in 3..5) {
                // Un objeto claro cruza el encuadre (persona, coche…)
                val ox = gt.w / 4 + k * gt.w / 10
                for (y in gt.h / 3 until gt.h / 3 + gt.h / 6) for (x in ox until min(gt.w, ox + gt.w / 8)) {
                    f.px[y * gt.w + x] = rgb(235, 225, 210)
                }
            }
            f = sensorNoise(f, photons, rnd)
            f
        }
        // Tal como lo hace BurstProcessor
        val ref = Align.luma(frames[0].px)
        val stacker = MeanStacker(gt.w, gt.h)
        stacker.add(frames[0].px)
        var maxErr = 0
        val truth = mutableListOf<Pair<Int, Int>>()
        for (k in 1 until n) {
            val (dx, dy) = Align.estimateShift(ref, Align.luma(frames[k].px), gt.w, gt.h)
            stacker.add(frames[k].px, dx, dy)
            truth += dx to dy
        }
        val lift = 0.3f
        val stacked = Img(gt.w, gt.h, stacker.result(lift))
        val single = applyLift(frames[0], lift)
        val ref8 = applyLift(gt, lift)
        val pS = psnr(single, ref8)
        val pM = psnr(stacked, ref8)
        val tag = photo.substringBefore('.') + "_f$photons" + (if (rotationDeg > 0) "_rot" else "") + (if (ghost) "_fantasma" else "")
        save(single, "noche_${tag}_1toma.png")
        save(stacked, "noche_${tag}_8tomas.png")
        line("NOCHE  %-34s 1 toma: %5.1f dB   8 tomas: %5.1f dB   mejora: %+5.1f dB".format(tag, pS, pM, pM - pS))
        if (ghost) {
            // ¿Quedó rastro del objeto en movimiento? (zona por donde pasó)
            var resid = 0.0
            var cnt = 0
            for (y in gt.h / 3 + 6 until gt.h / 3 + gt.h / 6 - 6) for (x in gt.w / 4 + 3 * gt.w / 10 + 6 until gt.w / 4 + 5 * gt.w / 10) {
                val i = y * gt.w + x
                resid += abs(ch(stacked.px[i], 1) - ch(ref8.px[i], 1)); cnt++
            }
            line("       fantasma: error medio en la trayectoria del objeto = %.1f niveles (de 255)".format(resid / cnt))
        }
        return pS to pM
    }

    @Test fun modoNoche() {
        line("=== MODO NOCHE (8 tomas, pulso ±12 px) ===")
        val results = listOf(
            nightCase("astronaut.png", photons = 300.0, rotationDeg = 0.0, ghost = false, seed = 1),
            nightCase("coffee.png", photons = 150.0, rotationDeg = 0.0, ghost = false, seed = 2),
            nightCase("chelsea.png", photons = 80.0, rotationDeg = 0.0, ghost = false, seed = 3),
            nightCase("rocket.jpg", photons = 150.0, rotationDeg = 0.25, ghost = false, seed = 4),
            nightCase("coffee.png", photons = 150.0, rotationDeg = 0.0, ghost = true, seed = 5),
        )
        flush("reporte_noche.txt")
        results.forEach { (s, m) -> assertTrue("el apilado debe mejorar la imagen ($s → $m)", m > s + 3.0) }
    }

    // ------------------------------------------------------------------ 2. Macro apilado

    /** Desenfoque gaussiano separable. */
    private fun blur(src: Img, sigma: Double): Img {
        if (sigma < 0.3) return src
        val r = (sigma * 3).toInt().coerceAtLeast(1)
        val k = DoubleArray(2 * r + 1) { exp(-((it - r) * (it - r)) / (2 * sigma * sigma)) }
        val ks = k.sum()
        fun pass(p: IntArray, horiz: Boolean): IntArray {
            val o = IntArray(p.size)
            for (y in 0 until src.h) for (x in 0 until src.w) {
                val acc = DoubleArray(3)
                for (t in -r..r) {
                    val xx = if (horiz) (x + t).coerceIn(0, src.w - 1) else x
                    val yy = if (horiz) y else (y + t).coerceIn(0, src.h - 1)
                    val q = p[yy * src.w + xx]
                    for (c in 0..2) acc[c] += ch(q, c) * k[t + r]
                }
                o[y * src.w + x] = rgb((acc[0] / ks + .5).toInt(), (acc[1] / ks + .5).toInt(), (acc[2] / ks + .5).toInt())
            }
            return o
        }
        return Img(src.w, src.h, pass(pass(src.px, true), false))
    }

    /** Laplaciano medio: cuánto detalle fino tiene la imagen. */
    private fun sharpness(img: Img): Double {
        var s = 0.0
        var n = 0
        for (y in 20 until img.h - 20) for (x in 20 until img.w - 20) {
            val i = y * img.w + x
            val l = 4 * ch(img.px[i], 1) - ch(img.px[i - 1], 1) - ch(img.px[i + 1], 1) - ch(img.px[i - img.w], 1) - ch(img.px[i + img.w], 1)
            s += abs(l); n++
        }
        return s / n
    }

    private fun macroCase(photo: String, depth: (Double, Double) -> Double, label: String, seed: Int) {
        val gt = load(photo)
        val rnd = Random(seed)
        val sigmas = doubleArrayOf(0.0, 0.8, 1.6, 2.4, 3.2, 4.0, 5.0, 6.0)
        val levels = sigmas.map { blur(gt, it) }
        val focusPlanes = listOf(0.1, 0.3, 0.5, 0.7, 0.9)
        val frames = focusPlanes.mapIndexed { k, fp ->
            val px = IntArray(gt.px.size)
            for (y in 0 until gt.h) for (x in 0 until gt.w) {
                val s = 9.0 * abs(depth(x / gt.w.toDouble(), y / gt.h.toDouble()) - fp)
                var li = sigmas.indexOfLast { it <= s }.coerceAtLeast(0)
                if (li < sigmas.size - 1 && s - sigmas[li] > (sigmas[li + 1] - s)) li++
                px[y * gt.w + x] = levels[li].px[y * gt.w + x]
            }
            val dx = if (k == 0) 0.0 else rnd.nextDouble(-4.0, 4.0)
            val dy = if (k == 0) 0.0 else rnd.nextDouble(-4.0, 4.0)
            sensorNoise(warp(Img(gt.w, gt.h, px), dx, dy, 0.0), 2000.0, rnd)
        }
        val ref = Align.luma(frames[0].px)
        val fs = FocusStacker(gt.w, gt.h)
        fs.add(frames[0].px)
        for (k in 1 until frames.size) {
            val (dx, dy) = Align.estimateShift(ref, Align.luma(frames[k].px), gt.w, gt.h)
            fs.add(frames[k].px, dx, dy)
        }
        val stacked = Img(gt.w, gt.h, fs.result())
        val best = frames.maxByOrNull { psnr(it, gt) }!!
        val pB = psnr(best, gt)
        val pS = psnr(stacked, gt)
        save(best, "macro_${label}_mejor_toma.png")
        save(stacked, "macro_${label}_apilado.png")
        line(
            "MACRO  %-22s mejor toma sola: %5.1f dB (nitidez %.1f)   apilado: %5.1f dB (nitidez %.1f)   original: nitidez %.1f".format(
                label, pB, sharpness(best), pS, sharpness(stacked), sharpness(gt),
            ),
        )
        assertTrue("el apilado debe superar a la mejor toma sola", pS > pB + 1.0)
    }

    @Test fun macroApilado() {
        line("=== MACRO+ (5 distancias de enfoque, pulso ±4 px) ===")
        // Superficie inclinada (flor/insecto fotografiado en diagonal): cerca arriba, lejos abajo.
        macroCase("chelsea.png", { _, y -> y }, "gato_inclinado", 11)
        // Objeto redondo en el centro (más cerca) sobre un fondo más lejano.
        macroCase("coffee.png", { x, y -> val d = sqrt((x - .5) * (x - .5) + (y - .5) * (y - .5)); (d * 1.6).coerceAtMost(1.0) }, "taza_redonda", 12)
        flush("reporte_macro.txt")
    }

    // ------------------------------------------------------------------ 3. Alineación

    @Test fun precisionDeAlineacion() {
        line("=== ALINEACIÓN con fotos reales y ruido ===")
        var ok = 0
        var total = 0
        for (photo in listOf("astronaut.png", "chelsea.png", "coffee.png", "rocket.jpg", "hubble_deep_field.jpg")) {
            val gt = load(photo)
            val rnd = Random(photo.hashCode())
            val ref = Align.luma(sensorNoise(gt, 200.0, rnd).px)
            var errs = 0.0
            repeat(6) {
                val dx = rnd.nextInt(-40, 41)
                val dy = rnd.nextInt(-40, 41)
                val f = sensorNoise(warp(gt, dx.toDouble(), dy.toDouble(), 0.0), 200.0, rnd)
                val (ex, ey) = Align.estimateShift(ref, Align.luma(f.px), gt.w, gt.h)
                // cur(x+dx', y+dy') = ref(x,y) ⇒ el desplazamiento esperado es (dx, dy)
                val e = abs(ex - dx) + abs(ey - dy)
                errs += e
                if (e <= 1) ok++
                total++
            }
            line("ALINEACIÓN  %-24s error medio %.2f px".format(photo, errs / 6))
        }
        line("ALINEACIÓN  aciertos (error ≤ 1 px): $ok/$total")
        flush("reporte_alineacion.txt")
        assertTrue(ok >= total * 0.9)
    }

    // ------------------------------------------------------------------ 4. Alineación v2

    @Test fun alineacionV2DetectaGiroYEscala() {
        line("=== ALINEACIÓN v2: desplazamiento + giro + escala ===")
        var worst = 0f
        for ((photo, deg) in listOf("astronaut.png" to 0.5, "coffee.png" to -0.35, "rocket.jpg" to 0.2, "chelsea.png" to 0.0)) {
            val gt = load(photo)
            val rnd = Random(photo.hashCode())
            val dx = rnd.nextDouble(-9.0, 9.0); val dy = rnd.nextDouble(-9.0, 9.0)
            val ref = Align.luma(sensorNoise(gt, 400.0, rnd).px)
            val cur = Align.luma(sensorNoise(warp(gt, dx, dy, deg), 400.0, rnd).px)
            val res = TileAlign.estimate(ref, cur, gt.w, gt.h)
            // Error en las esquinas: dónde dice la alineación que está cada esquina vs. dónde está.
            val a = Math.toRadians(deg); val cx = gt.w / 2.0; val cy = gt.h / 2.0
            var err = 0.0
            for ((x, y) in listOf(0.0 to 0.0, gt.w - 1.0 to 0.0, 0.0 to gt.h - 1.0, gt.w - 1.0 to gt.h - 1.0)) {
                // En la toma, el punto de la escena (x,y) aparece girado y desplazado.
                val rx = x - cx; val ry = y - cy
                val tx = cos(a) * rx - sin(a) * ry + cx + dx
                val ty = sin(a) * rx + cos(a) * ry + cy + dy
                val ex = res.transform.mapX(x.toFloat(), y.toFloat()) - tx
                val ey = res.transform.mapY(x.toFloat(), y.toFloat()) - ty
                err = max(err, sqrt(ex * ex + ey * ey))
            }
            worst = max(worst, err.toFloat())
            line("%-16s giro real %+.2f° → medido %+.2f°   error en las esquinas %.2f px   (%d zonas)".format(photo, deg, res.transform.rotationDeg, err, res.tilesUsed))
        }
        flush("reporte_alineacion_v2.txt")
        assertTrue("precisión subpíxel en todo el encuadre ($worst px)", worst < 1.0f)
    }

    private fun nightCompare(photo: String, rotationDeg: Double, ghost: Boolean, seed: Int) {
        val gt = load(photo)
        val rnd = Random(seed)
        val frames = (0 until 8).map { k ->
            val dx = if (k == 0) 0.0 else rnd.nextDouble(-10.0, 10.0)
            val dy = if (k == 0) 0.0 else rnd.nextDouble(-10.0, 10.0)
            val rot = if (k == 0 || rotationDeg == 0.0) 0.0 else rnd.nextDouble(-rotationDeg, rotationDeg)
            var f = warp(gt, dx, dy, rot)
            if (ghost && k in 3..5) {
                val ox = gt.w / 4 + k * gt.w / 10
                for (y in gt.h / 3 until gt.h / 3 + gt.h / 6) for (x in ox until min(gt.w, ox + gt.w / 8)) f.px[y * gt.w + x] = rgb(235, 225, 210)
            }
            sensorNoise(f, 150.0, rnd)
        }
        // v1 (versión actual)
        val ref = Align.luma(frames[0].px)
        val s1 = MeanStacker(gt.w, gt.h).apply { add(frames[0].px) }
        for (k in 1 until 8) { val (sx, sy) = Align.estimateShift(ref, Align.luma(frames[k].px), gt.w, gt.h); s1.add(frames[k].px, sx, sy) }
        val v1 = Img(gt.w, gt.h, s1.result(0f))
        // v2
        val s2 = RobustMerger(gt.w, gt.h, 8).apply { add(frames[0].px) }
        for (k in 1 until 8) {
            val t = TileAlign.estimate(ref, Align.luma(frames[k].px), gt.w, gt.h).transform
            s2.add(TileAlign.warp(frames[k].px, gt.w, gt.h, t))
        }
        val v2 = Img(gt.w, gt.h, s2.result(0f))
        val p0 = psnr(frames[0], gt); val p1 = psnr(v1, gt); val p2 = psnr(v2, gt)
        val tag = photo.substringBefore('.') + (if (rotationDeg > 0) "_giro" else "") + (if (ghost) "_fantasma" else "")
        save(v1, "noche_v1_$tag.png"); save(v2, "noche_v2_$tag.png")
        line("NOCHE  %-26s 1 toma %5.1f dB · v1 %5.1f dB · v2 %5.1f dB   (v2 vs v1 %+.1f dB)".format(tag, p0, p1, p2, p2 - p1))
        assertTrue("$tag: v2 no debe empeorar", p2 >= p1 - 0.2)
    }

    @Test fun modoNocheV2VsV1() {
        line("=== MODO NOCHE v2 vs v1 (8 tomas, pulso ±10 px subpíxel) ===")
        nightCompare("astronaut.png", 0.0, false, 31)
        nightCompare("astronaut.png", 0.5, false, 32)
        nightCompare("rocket.jpg", 0.5, false, 33)
        nightCompare("chelsea.png", 0.4, false, 34)
        nightCompare("coffee.png", 0.3, true, 35)
        flush("reporte_noche_v2.txt")
    }
}
