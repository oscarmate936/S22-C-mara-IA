package com.iacamara.app.processing

/**
 * Valores calibrados con colecciones de fotos reales (ver tools/calibracion y docs/calibracion):
 *  • MIT-Adobe FiveK: cómo editan los fotógrafos profesionales (exposición, altas luces, color).
 *  • KonIQ-10k: notas de calidad dadas por personas (calificación de la foto).
 *  • ADE20K: cielo y vegetación marcados a mano (máscaras de las zonas).
 */
object Calibration {
    /**
     * Máscaras de zonas (ADE20K, 400 fotos de prueba no usadas para ajustar): cielo IoU 0.34 → 0.63,
     * vegetación 0.37 → 0.40. Los cielos cálidos quedan fuera de la máscara de cielo: así el retoque de
     * cielo nunca satura un atardecer (con la regla de cielo cálido el acierto general bajaba a 0.56).
     */
    val MASKS = ZoneMasks.Tuning(
        blueRatio = 1.0718f, blueMinL = 0.0834f, duskMinL = 0.0460f, paleMinL = 0.9074f, paleMaxSat = 0.1977f,
        warmMinL = 1.3796f, skyMaxTexture = 0.02198f, floodStep = 0.1482f, greenRatio = 1.0039f, greenMinSat = 0.1898f,
    )

    /**
     * Límite de color (croma CIELAB) por escena, de 606 fotos editadas por profesionales (FiveK,
     * experto C): la rodilla es el croma del 95 % de los píxeles en una foto típica; el techo, el de las
     * fotos más coloridas (percentil 90) con un pequeño margen.
     */
    val CHROMA: Map<String, NaturalProfile.Limits> = mapOf(
        "CITY" to NaturalProfile.Limits(27.4f, 49.5f), // n=157
        "GENERAL" to NaturalProfile.Limits(24.8f, 48.4f), // n=72
        "INDOOR" to NaturalProfile.Limits(24.8f, 47.5f), // n=135
        "LANDSCAPE" to NaturalProfile.Limits(35.4f, 63.8f), // n=117
        "NIGHT" to NaturalProfile.Limits(32.1f, 53.3f), // n=13
        "PORTRAIT" to NaturalProfile.Limits(29.4f, 48.3f), // n=81
        "SUNSET" to NaturalProfile.Limits(26.6f, 46.2f), // n=31
        "ALL" to NaturalProfile.Limits(27.8f, 51.8f), // n=606
    )

    /**
     * Exposición por escena (pasos EV respecto al automático), mediana de lo que hicieron los
     * profesionales en FiveK. El retrato se deja en 0: la luz se mide sobre la cara.
     */
    val SCENE_EV: Map<String, Float> = mapOf(
        "SUNSET" to -0.4f,
        "LANDSCAPE" to -0.5f,
        "NIGHT" to -0.2f,
        "GENERAL" to -0.3f,
        "PORTRAIT" to 0.0f,
    )

    /**
     * Hasta dónde dejan llegar las altas luces los profesionales (percentil 99 típico, codificado,
     * con margen). Si lo más claro de la escena lo supera, el cerebro baja la exposición (máx. 0.5 EV).
     * Solo en exteriores: en interiores lo más claro suele ser una lámpara, que se deja quemar.
     */
    val HIGHLIGHT_CEIL: Map<String, Float> = mapOf(
        "SUNSET" to 0.86f, // SUNSET: p99 típico del experto 0.57 lineal = 0.78 codificado (+0.08 de margen)
        "LANDSCAPE" to 0.89f, // LANDSCAPE: p99 típico del experto 0.62 lineal = 0.81 codificado (+0.08 de margen)
        "BEACH" to 0.89f, // LANDSCAPE: p99 típico del experto 0.62 lineal = 0.81 codificado (+0.08 de margen)
        "CITY" to 0.95f, // CITY: p99 típico del experto 0.72 lineal = 0.87 codificado (+0.08 de margen)
    )

    /**
     * Curva de tono natural para la cámara (sobre la escala sRGB): igual hasta los medios tonos y,
     * desde 0.55, un hombro suave que llega a 0.96 en el blanco (los profesionales de FiveK dejan el
     * blanco en ~0.92; el automático del teléfono lo lleva a 1.0 y quema el cielo).
     */
    val TONE_IN = floatArrayOf(0.0000f, 0.0312f, 0.0625f, 0.0938f, 0.1250f, 0.1562f, 0.1875f, 0.2188f, 0.2500f, 0.2812f, 0.3125f, 0.3438f, 0.3750f, 0.4062f, 0.4375f, 0.4688f, 0.5000f, 0.5312f, 0.5625f, 0.5938f, 0.6250f, 0.6562f, 0.6875f, 0.7188f, 0.7500f, 0.7812f, 0.8125f, 0.8438f, 0.8750f, 0.9062f, 0.9375f, 0.9688f, 1.0000f)
    val TONE_OUT = floatArrayOf(0.0000f, 0.0312f, 0.0625f, 0.0938f, 0.1250f, 0.1562f, 0.1875f, 0.2188f, 0.2500f, 0.2812f, 0.3125f, 0.3438f, 0.3750f, 0.4062f, 0.4375f, 0.4688f, 0.5000f, 0.5312f, 0.5625f, 0.5934f, 0.6238f, 0.6539f, 0.6836f, 0.7129f, 0.7418f, 0.7704f, 0.7985f, 0.8263f, 0.8538f, 0.8809f, 0.9076f, 0.9340f, 0.9600f)

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

}
