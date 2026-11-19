package com.iacamara.app.camera

import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.min

/** Horizonte encontrado en la imagen (marco vertical de la pantalla). */
data class Horizon(
    /** Altura del horizonte en el centro de la imagen (0 arriba … 1 abajo). */
    val y: Float,
    /** Inclinación en grados (positivo = baja hacia la derecha). */
    val tiltDeg: Float,
    /** Confianza 0..1 (cuántas franjas coinciden y qué tan marcado es el borde cielo/tierra). */
    val confidence: Float,
)

/**
 * Encuentra la línea donde el cielo (más claro) se encuentra con la tierra o el mar (más oscuros).
 * Divide la imagen en franjas verticales, busca en cada una el borde claro→oscuro más marcado,
 * descarta las franjas que no coinciden (un árbol, un poste) y ajusta una recta: así obtiene
 * altura e inclinación reales del horizonte, no solo la del teléfono.
 */
object HorizonDetector {
    private const val STRIPS = 8
    private const val WIN = 5

    fun detect(luma: ByteArray, w: Int, h: Int): Horizon? {
        if (w < 24 || h < 40) return null
        val xs = ArrayList<Float>()
        val ys = ArrayList<Float>()
        val strengths = ArrayList<Float>()
        val stripW = w / STRIPS
        for (s in 0 until STRIPS) {
            val x0 = s * stripW
            val x1 = min(w, x0 + stripW)
            // Perfil vertical de luz de la franja
            val prof = FloatArray(h)
            for (y in 0 until h) {
                var sum = 0
                for (x in x0 until x1) sum += luma[y * w + x].toInt() and 0xFF
                prof[y] = sum.toFloat() / (x1 - x0)
            }
            var best = 0f
            var by = -1
            for (y in (h * 0.08f).toInt().coerceAtLeast(WIN) until (h * 0.92f).toInt().coerceAtMost(h - WIN)) {
                var above = 0f
                var below = 0f
                for (k in 1..WIN) { above += prof[y - k]; below += prof[y + k - 1] }
                val d = (above - below) / WIN // cielo más claro que la tierra
                if (d > best) { best = d; by = y }
            }
            if (by >= 0 && best >= 12f) {
                xs += (x0 + x1) / 2f
                ys += by.toFloat()
                strengths += best
            }
        }
        if (xs.size < 4) return null
        // Descarta franjas que no coinciden con la mayoría (árboles, postes, edificios).
        val median = ys.sorted()[ys.size / 2]
        val keep = ys.indices.filter { abs(ys[it] - median) <= h * 0.12f }
        if (keep.size < 4) return null
        var fit = fitLine(keep.map { xs[it] }, keep.map { ys[it] })
        // Segunda pasada: solo las franjas cercanas a la recta.
        val keep2 = keep.filter { abs(ys[it] - (fit.first + fit.second * xs[it])) <= h * 0.04f }
        if (keep2.size >= 4) fit = fitLine(keep2.map { xs[it] }, keep2.map { ys[it] })
        val used = if (keep2.size >= 4) keep2 else keep
        val (a, b) = fit
        val yCenter = (a + b * w / 2f) / h
        val tilt = Math.toDegrees(atan(b.toDouble())).toFloat()
        val meanStrength = used.map { strengths[it] }.average().toFloat()
        var confidence = (used.size / STRIPS.toFloat()) * (meanStrength / 30f).coerceAtMost(1f)
        // ¿Lo de arriba es cielo de verdad? El cielo es liso (poca textura) comparado con la tierra;
        // follaje, una cerca, una fruta o una pared con dibujo tienen mucha textura y se descartan.
        val (texAbove, texBelow) = textureAroundLine(luma, w, h, a, b)
        val skyLike = texAbove <= 7f || texAbove <= 0.55f * texBelow
        if (!skyLike) confidence *= 0.3f
        return Horizon(yCenter.coerceIn(0f, 1f), tilt, confidence.coerceIn(0f, 1f))
    }

    /** Textura media (diferencia entre píxeles vecinos) en franjas por encima y por debajo de la línea. */
    private fun textureAroundLine(luma: ByteArray, w: Int, h: Int, a: Float, b: Float): Pair<Float, Float> {
        val band = (h * 0.18f).toInt().coerceAtLeast(3)
        var ta = 0f; var na = 0
        var tb = 0f; var nb = 0
        for (x in 1 until w - 1) {
            val yl = (a + b * x).toInt()
            for (y in maxOf(1, yl - band) until yl - 2) {
                if (y >= h - 1) break
                ta += grad(luma, w, x, y); na++
            }
            for (y in yl + 3 until minOf(h - 1, yl + band)) {
                if (y < 1) continue
                tb += grad(luma, w, x, y); nb++
            }
        }
        return (if (na > 0) ta / na else 99f) to (if (nb > 0) tb / nb else 0f)
    }

    private fun grad(l: ByteArray, w: Int, x: Int, y: Int): Float {
        val c = l[y * w + x].toInt() and 0xFF
        return (abs(c - (l[y * w + x + 1].toInt() and 0xFF)) + abs(c - (l[(y + 1) * w + x].toInt() and 0xFF))) / 2f
    }

    /** Recta y = a + b·x por mínimos cuadrados. */
    private fun fitLine(xs: List<Float>, ys: List<Float>): Pair<Float, Float> {
        val n = xs.size
        val mx = xs.average().toFloat()
        val my = ys.average().toFloat()
        var sxy = 0f
        var sxx = 0f
        for (i in 0 until n) { sxy += (xs[i] - mx) * (ys[i] - my); sxx += (xs[i] - mx) * (xs[i] - mx) }
        val b = if (sxx > 1e-6f) sxy / sxx else 0f
        return (my - b * mx) to b
    }

    /** Gira la imagen reducida del sensor al marco vertical de la pantalla. */
    fun toPortrait(src: ByteArray, sw: Int, sh: Int, rotation: Int): Triple<ByteArray, Int, Int> = when (rotation) {
        90 -> Triple(ByteArray(sw * sh) { i -> val pw = sh; val xp = i % pw; val yp = i / pw; src[(sh - 1 - xp) * sw + yp] }, sh, sw)
        180 -> Triple(ByteArray(sw * sh) { i -> val xp = i % sw; val yp = i / sw; src[(sh - 1 - yp) * sw + (sw - 1 - xp)] }, sw, sh)
        270 -> Triple(ByteArray(sw * sh) { i -> val pw = sh; val xp = i % pw; val yp = i / pw; src[xp * sw + (sw - 1 - yp)] }, sh, sw)
        else -> Triple(src, sw, sh)
    }
}
