package com.iacamara.app.processing

import kotlin.math.atan2
import kotlin.math.cbrt
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.math.tanh

/**
 * Perfil natural: lo que distingue una foto de profesional de una "de celular".
 *
 * Medido en 1 000 fotos editadas por fotógrafos profesionales (MIT-Adobe FiveK): casi nunca
 * saturan — el color (croma CIELAB) del 95 % de los píxeles se queda por debajo de ~30, y solo en
 * paisajes llega a ~45–60 —, y no queman las altas luces. Los teléfonos, en cambio, empujan el
 * color (cielos neón, atardeceres naranja fosforito, verdes de plástico).
 *
 * Aquí el color se limita con una curva suave por tipo de escena: los colores normales no se tocan;
 * solo los que pasan del nivel de los profesionales se comprimen poco a poco (sin aplanar), más
 * en las luces muy brillantes (donde el neón se nota más). La piel se protege.
 */
object NaturalProfile {
    /** Rodilla (desde donde se comprime) y techo del croma CIELAB, por tipo de escena. */
    class Limits(val knee: Float, val ceiling: Float)

    private fun limitsFor(scene: String): Limits = Calibration.CHROMA[scene] ?: Calibration.CHROMA.getValue("ALL")

    // Tablas para convertir rápido.
    private val toLin = FloatArray(256) { (it / 255f).let { v -> if (v <= 0.04045f) v / 12.92f else ((v + 0.055f) / 1.055f).toDouble().pow(2.4).toFloat() } }
    private val enc = IntArray(4097) { (it / 4096.0).let { v -> if (v <= 0.0031308) v * 12.92 else 1.055 * v.pow(1 / 2.4) - 0.055 }.let { (it * 255 + 0.5).toInt().coerceIn(0, 255) } }
    private fun f(t: Float) = if (t > 0.008856f) cbrt(t) else 7.787f * t + 16f / 116f
    private fun fInv(t: Float) = if (t > 0.206893f) t * t * t else (t - 16f / 116f) / 7.787f

    /**
     * @param scene escena de la app (SUNSET, LANDSCAPE…), o "ALL".
     * @param strength 0 = nada, 1 = lo calibrado.
     */
    fun apply(px: IntArray, w: Int, h: Int, scene: String, strength: Float = 1f): IntArray {
        val lim = limitsFor(scene)
        val out = IntArray(px.size)
        val s = strength.coerceIn(0f, 1f)
        parallelRows(h) { ya, yb ->
            for (i in ya * w until yb * w) {
                val p = px[i]
                val r = toLin[(p shr 16) and 0xFF]; val g = toLin[(p shr 8) and 0xFF]; val b = toLin[p and 0xFF]
                val x = (0.4124f * r + 0.3576f * g + 0.1805f * b) / 0.95047f
                val y = 0.2126f * r + 0.7152f * g + 0.0722f * b
                val z = (0.0193f * r + 0.1192f * g + 0.9505f * b) / 1.08883f
                val fx = f(x); val fy = f(y); val fz = f(z)
                val L = 116f * fy - 16f
                val A = 500f * (fx - fy); val B = 200f * (fy - fz)
                val c = sqrt(A * A + B * B)
                // Rodilla más baja en las luces muy brillantes (neón) y más alta en la piel.
                val hue = Math.toDegrees(atan2(B, A).toDouble()).toFloat()
                val skin = A > 0 && B > 0 && hue in 25f..70f && L in 25f..90f
                var knee = lim.knee * (if (L > 80f) 1f - (L - 80f) / 20f * 0.25f else 1f)
                var ceil = lim.ceiling
                if (skin) { knee *= 1.25f; ceil *= 1.15f }
                if (c <= knee || ceil <= knee) { out[i] = p; continue }
                val cNew = knee + (ceil - knee) * tanh((c - knee) / (ceil - knee))
                val k = 1f + (cNew / c - 1f) * s
                val a2 = A * k; val b2 = B * k
                val fx2 = fy + a2 / 500f; val fz2 = fy - b2 / 200f
                val X = fInv(fx2) * 0.95047f; val Z = fInv(fz2) * 1.08883f
                val R = 3.2406f * X - 1.5372f * y - 0.4986f * Z
                val G = -0.9689f * X + 1.8758f * y + 0.0415f * Z
                val Bb = 0.0557f * X - 0.2040f * y + 1.0570f * Z
                out[i] = (0xFF shl 24) or (enc[(max(0f, R) * 4096).toInt().coerceAtMost(4096)] shl 16) or
                    (enc[(max(0f, G) * 4096).toInt().coerceAtMost(4096)] shl 8) or enc[(max(0f, Bb) * 4096).toInt().coerceAtMost(4096)]
            }
        }
        return out
    }

    /** Croma CIELAB del percentil 95 (para medir cuánto color tiene una foto). */
    fun chroma95(px: IntArray, step: Int = 3): Float {
        val hist = IntArray(200)
        var n = 0
        var i = 0
        while (i < px.size) {
            val p = px[i]
            val r = toLin[(p shr 16) and 0xFF]; val g = toLin[(p shr 8) and 0xFF]; val b = toLin[p and 0xFF]
            val fx = f((0.4124f * r + 0.3576f * g + 0.1805f * b) / 0.95047f)
            val fy = f(0.2126f * r + 0.7152f * g + 0.0722f * b)
            val fz = f((0.0193f * r + 0.1192f * g + 0.9505f * b) / 1.08883f)
            val A = 500f * (fx - fy); val B = 200f * (fy - fz)
            hist[sqrt(A * A + B * B).toInt().coerceIn(0, 199)]++; n++
            i += step
        }
        var acc = 0
        for (c in 0 until 200) { acc += hist[c]; if (acc >= n * 0.95) return c.toFloat() }
        return 199f
    }
}
