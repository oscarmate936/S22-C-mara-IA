package com.iacamara.app.calib

import com.iacamara.app.processing.ZoneMasks
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.Random
import android.graphics.BitmapFactory
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Calibra los umbrales de las máscaras de cielo y vegetación con ADE20K (2 000 fotos con las
 * zonas marcadas a mano). Solo corre si existe CALIB_DIR (las fotos no están en el repositorio).
 * Ajuste con las primeras 1 000 fotos; resultado medido con otras 400 que no se usaron para ajustar.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class MaskCalibrationTest {
    private val dir = System.getenv("CALIB_DIR")?.let { File(it, "ade") }

    private class Sample(val px: IntArray, val w: Int, val h: Int, val label: IntArray)

    private fun load(i: Int): Sample? {
        val jf = File(dir, "%04d.jpg".format(i)); val lf = File(dir, "%04d.png".format(i))
        if (!jf.exists() || !lf.exists()) return null
        val im = BitmapFactory.decodeFile(jf.path) ?: return null
        val lb = BitmapFactory.decodeFile(lf.path) ?: return null
        val w = im.width; val h = im.height
        if (lb.width != w || lb.height != h) return null
        val px = IntArray(w * h).also { im.getPixels(it, 0, w, 0, 0, w, h) }
        val lp = IntArray(w * h).also { lb.getPixels(it, 0, w, 0, 0, w, h) }
        val label = IntArray(w * h) { (lp[it] shr 16) and 0xFF }
        return Sample(px, w, h, label)
    }

    /** Intersección y unión acumuladas (a la resolución de la máscara). */
    private class Iou { var inter = 0L; var union = 0L; val value get() = if (union == 0L) 0.0 else inter.toDouble() / union }

    private fun evaluate(samples: List<Sample>, t: ZoneMasks.Tuning): Pair<Double, Double> {
        val sky = Iou(); val veg = Iou()
        for (s in samples) {
            val m = ZoneMasks.compute(s.px, s.w, s.h, emptyList(), t)
            for ((map, iou, classes) in listOf(Triple(m.sky, sky, setOf(3)), Triple(m.foliage, veg, setOf(5, 10, 18)))) {
                for (y in 0 until map.h) for (x in 0 until map.w) {
                    val gx = ((x + 0.5f) / map.w * s.w).toInt().coerceIn(0, s.w - 1); val gy = ((y + 0.5f) / map.h * s.h).toInt().coerceIn(0, s.h - 1)
                    val g = s.label[gy * s.w + gx] in classes
                    val p = map[x, y] >= 0.5f
                    if (g && p) iou.inter++
                    if (g || p) iou.union++
                }
            }
        }
        return sky.value to veg.value
    }

    @Test fun calibrarMascaras() {
        assumeTrue(dir != null && dir.exists())
        val train = (0 until 1000 step 2).mapNotNull { load(it) }
        val test = (1000 until 2000 step 2).take(400).mapNotNull { load(it) }
        val base = ZoneMasks.Tuning()
        val (s0, v0) = evaluate(train, base)
        println("CAL base entrenamiento: cielo IoU %.3f · vegetación IoU %.3f".format(s0, v0))
        val rnd = Random(42)
        var bestSky = base; var bestSkyV = s0
        var bestVeg = base; var bestVegV = v0
        fun j(v: Float, f: Float) = v * (1f + (rnd.nextFloat() * 2 - 1) * f)
        repeat(36) { k ->
            val from = bestSky
            val c = from.copy(
                blueRatio = j(from.blueRatio, 0.03f), blueMinL = j(from.blueMinL, 0.3f), duskMinL = j(from.duskMinL, 0.4f),
                paleMinL = j(from.paleMinL, 0.15f), paleMaxSat = j(from.paleMaxSat, 0.3f), warmMinL = j(from.warmMinL, 0.2f),
                skyMaxTexture = j(from.skyMaxTexture, 0.4f), floodStep = j(from.floodStep, 0.4f),
            )
            val v = evaluate(train.take(250), c).first
            if (v > evaluate(train.take(250), bestSky).first) { bestSky = c; bestSkyV = v; println("CAL cielo $k → %.3f  $c".format(v)) }
        }
        repeat(24) { k ->
            val from = bestVeg
            val c = from.copy(greenRatio = j(from.greenRatio, 0.04f), greenMinSat = j(from.greenMinSat, 0.4f))
            val v = evaluate(train.take(250), c).second
            if (v > evaluate(train.take(250), bestVeg).second) { bestVeg = c; bestVegV = v; println("CAL verde $k → %.3f  $c".format(v)) }
        }
        val best = bestSky.copy(greenRatio = bestVeg.greenRatio, greenMinSat = bestVeg.greenMinSat)
        val (sb, vb) = evaluate(test, base)
        val (sa, va) = evaluate(test, best)
        val report = "MÁSCARAS (ADE20K, 400 fotos de prueba no usadas para ajustar)\n" +
            "cielo IoU %.3f → %.3f · vegetación IoU %.3f → %.3f\nmejor: %s\n".format(sb, sa, vb, va, best)
        println("CAL $report")
        File(System.getenv("CALIB_DIR"), "mascaras.txt").writeText(report)
    }

    @Test fun compararVariantes() {
        assumeTrue(dir != null && dir.exists() && System.getenv("CALIB_VARIANTS") != null)
        val test = (1000 until 2000 step 2).take(400).mapNotNull { load(it) }
        val best = ZoneMasks.Tuning(1.0718f, 0.0834f, 0.0460f, 0.9074f, 0.1977f, 1.3796f, 0.02198f, 0.1482f, 1.0039f, 0.1898f)
        for ((name, t) in listOf("ajuste" to best, "ajuste + cielos dorados (0.70)" to best.copy(warmMinL = 0.70f), "ajuste + dorados 0.60 + nublado 0.80" to best.copy(warmMinL = 0.60f, paleMinL = 0.80f))) {
            val (s, v) = evaluate(test, t)
            println("CAL variante $name: cielo %.3f vegetación %.3f".format(s, v))
        }
    }
}
