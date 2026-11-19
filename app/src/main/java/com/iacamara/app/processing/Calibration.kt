package com.iacamara.app.processing

/**
 * Valores calibrados con colecciones de fotos reales (ver tools/calibracion y docs/calibracion):
 *  • MIT-Adobe FiveK: cómo editan los fotógrafos profesionales (exposición, altas luces, color).
 *  • KonIQ-10k: notas de calidad dadas por personas (calificación de la foto).
 *  • ADE20K: cielo y vegetación marcados a mano (máscaras de las zonas).
 */
object Calibration {
    val MASKS = ZoneMasks.Tuning()

    /** Exposición por escena (pasos EV), provisional hasta la calibración completa. */
    val SCENE_EV: Map<String, Float> = emptyMap()

    /** Hasta dónde llegan las altas luces (percentil 98, codificado 0..1) por escena, provisional. */
    val HIGHLIGHT_CEIL: Map<String, Float> = emptyMap()

    /**
     * Curva de tono natural (entrada lineal del sensor → salida codificada), provisional.
     * Pares (entrada, salida) para Camera2.
     */
    val TONE_IN = floatArrayOf(0f, 1f)
    val TONE_OUT = floatArrayOf(0f, 1f)

    /** Curva de 64 puntos intercalados (entrada, salida) para TONEMAP_CURVE. */
    fun toneCurvePoints(): FloatArray {
        val n = 64
        val out = FloatArray(n * 2)
        for (k in 0 until n) {
            val x = k / (n - 1f)
            out[2 * k] = x
            out[2 * k + 1] = tone(x)
        }
        return out
    }

    /** Curva lineal → codificada (interpolada entre los puntos calibrados, sobre la escala sRGB). */
    fun tone(lin: Float): Float {
        val e = if (lin <= 0.0031308f) lin * 12.92f else (1.055f * Math.pow(lin.toDouble(), 1 / 2.4) - 0.055).toFloat()
        val i = TONE_IN.indexOfFirst { it >= e }.let { if (it <= 0) 1 else it }
        val t = ((e - TONE_IN[i - 1]) / (TONE_IN[i] - TONE_IN[i - 1]).coerceAtLeast(1e-6f)).coerceIn(0f, 1f)
        return (TONE_OUT[i - 1] + (TONE_OUT[i] - TONE_OUT[i - 1]) * t).coerceIn(0f, 1f)
    }

    /** Límites de color (croma CIELAB) por escena: provisionales hasta la calibración completa. */
    val CHROMA: Map<String, NaturalProfile.Limits> = mapOf(
        "ALL" to NaturalProfile.Limits(30f, 50f),
    )
}
