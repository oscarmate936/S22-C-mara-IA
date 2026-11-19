package com.iacamara.app

import com.iacamara.app.processing.Align
import com.iacamara.app.processing.FocusStacker
import com.iacamara.app.processing.MeanStacker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

class StackingTest {
    private val w = 320
    private val h = 240

    /** Escena sintética con textura (como una foto real, no uniforme). */
    private fun scene(x: Int, y: Int): Int {
        val v = 128 + 50 * sin(x / 7.0) * sin(y / 11.0) + 40 * sin((x + 2 * y) / 23.0)
        return v.toInt().coerceIn(0, 255)
    }

    private fun gray(v: Int) = (0xFF shl 24) or (v shl 16) or (v shl 8) or v

    private fun frame(shiftX: Int, shiftY: Int, noise: Double, rnd: Random) = IntArray(w * h) { i ->
        val x = i % w
        val y = i / w
        val v = scene(x - shiftX, y - shiftY) + rnd.nextDouble(-noise, noise)
        gray(v.toInt().coerceIn(0, 255))
    }

    @Test fun detectaElMovimientoDeLaMano() {
        val rnd = Random(1)
        val ref = Align.luma(frame(0, 0, 4.0, rnd))
        val cur = Align.luma(frame(7, -5, 4.0, rnd))
        assertEquals(7 to -5, Align.estimateShift(ref, cur, w, h))
    }

    @Test fun detectaMovimientosGrandes() {
        val rnd = Random(2)
        val ref = Align.luma(frame(0, 0, 3.0, rnd))
        val cur = Align.luma(frame(-23, 31, 3.0, rnd))
        assertEquals(-23 to 31, Align.estimateShift(ref, cur, w, h, maxShift = 48))
    }

    private fun noiseStd(px: IntArray): Double {
        var sum = 0.0
        var n = 0
        for (y in 60 until 180) for (x in 60 until 260) {
            val d = (px[y * w + x] and 0xFF) - scene(x, y)
            sum += d * d; n++
        }
        return sqrt(sum / n)
    }

    @Test fun ochoTomasReducenElRuido() {
        val rnd = Random(3)
        val stacker = MeanStacker(w, h)
        val single = frame(0, 0, 30.0, rnd)
        stacker.add(single)
        repeat(7) {
            val sx = rnd.nextInt(-6, 7)
            val sy = rnd.nextInt(-6, 7)
            val f = frame(sx, sy, 30.0, rnd)
            val (dx, dy) = Align.estimateShift(Align.luma(single), Align.luma(f), w, h)
            stacker.add(f, dx, dy)
        }
        val before = noiseStd(single)
        val after = noiseStd(stacker.result())
        // √8 ≈ 2.8× menos ruido en teoría; exigimos al menos 2×.
        assertTrue("ruido antes=$before después=$after", after < before / 2)
    }

    @Test fun descartaObjetosEnMovimiento() {
        val stacker = MeanStacker(w, h)
        val base = IntArray(w * h) { gray(100) }
        stacker.add(base)
        stacker.add(base)
        // Un "coche" blanco pasa por una sola toma.
        val withCar = base.copyOf().also { for (y in 100 until 140) for (x in 100 until 160) it[y * w + x] = gray(250) }
        stacker.add(withCar)
        stacker.add(base)
        val out = stacker.result()
        assertEquals(100, out[120 * w + 130] and 0xFF)
    }

    @Test fun apiladoDeEnfoqueEligeLoNitido() {
        // Toma A: mitad izquierda nítida (textura), derecha borrosa (plano). Toma B: al revés.
        val a = IntArray(w * h) { i -> val x = i % w; val y = i / w; gray(if (x < w / 2) scene(x * 3, y * 3) else 128) }
        val b = IntArray(w * h) { i -> val x = i % w; val y = i / w; gray(if (x >= w / 2) scene(x * 3, y * 3) else 128) }
        val fs = FocusStacker(w, h)
        fs.add(a)
        fs.add(b)
        val out = fs.result()
        var errLeft = 0
        var errRight = 0
        for (y in 20 until h - 20) {
            for (x in 20 until w / 2 - 20) errLeft += abs((out[y * w + x] and 0xFF) - (a[y * w + x] and 0xFF))
            for (x in w / 2 + 20 until w - 20) errRight += abs((out[y * w + x] and 0xFF) - (b[y * w + x] and 0xFF))
        }
        assertEquals(0, errLeft)
        assertEquals(0, errRight)
    }

    @Test fun levantarSombrasNoTocaBlancosNiNegros() {
        val st = MeanStacker(2, 1)
        st.add(intArrayOf(gray(0), gray(255)))
        val out = st.result(shadowLift = 0.3f)
        assertEquals(0, out[0] and 0xFF)
        assertEquals(255, out[1] and 0xFF)
    }
}
