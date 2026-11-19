package com.iacamara.app.camera

import android.os.Build
import kotlin.math.abs
import kotlin.math.ln

/**
 * Perfil del Samsung Galaxy S22+ (SM-S906*): la app está hecha solo para este teléfono.
 * Sus cuatro cámaras, con lo que cada una hace bien y mal, deciden qué lente usar, la velocidad
 * mínima a pulso (estabilizador óptico o no), el ISO a partir del cual conviene otra estrategia y
 * cuán cerca se puede enfocar.
 */
object S22Plus {
    /** Modelos del S22+ (Exynos 2200 y Snapdragon 8 Gen 1). */
    private val MODEL_PREFIX = "SM-S906"
    private val DEVICES = setOf("g0s", "g0q")

    val isThisPhone: Boolean
        get() = Build.MODEL.uppercase().startsWith(MODEL_PREFIX) || Build.DEVICE.lowercase() in DEVICES

    enum class Role { ULTRA_WIDE, WIDE, TELE, FRONT }

    class LensSpec(
        val role: Role,
        val name: String,
        /** Focal equivalente (mm, formato 35 mm). */
        val equivalentMm: Float,
        val aperture: Float,
        /** Diagonal del sensor (pulgadas ópticas, p. ej. 1/1.56"). */
        val sensorInch: String,
        val megapixels: Int,
        /** Estabilizador óptico de imagen. */
        val ois: Boolean,
        /** Enfoque automático (el ultra gran angular del S22+ es de foco fijo). */
        val autofocus: Boolean,
        /** Distancia mínima de enfoque aproximada (cm). */
        val minFocusCm: Int,
        /**
         * ISO a partir del cual el ruido de este sensor se nota en la foto: el pequeño sensor del tele
         * pierde calidad mucho antes que el principal.
         */
        val cleanIsoMax: Int,
        /** Cuántos pasos más lenta se puede disparar a pulso gracias al OIS (regla 1/focal × 2^pasos). */
        val stabilizationStops: Float,
    ) {
        /** Factor respecto a la cámara principal (1×). */
        val factor get() = equivalentMm / WIDE_MM
    }

    private const val WIDE_MM = 23f

    val ULTRA_WIDE = LensSpec(Role.ULTRA_WIDE, "Ultra gran angular", 13f, 2.2f, "1/2.55\"", 12, ois = false, autofocus = false, minFocusCm = 30, cleanIsoMax = 800, stabilizationStops = 0f)
    val WIDE = LensSpec(Role.WIDE, "Gran angular (principal)", WIDE_MM, 1.8f, "1/1.56\"", 50, ois = true, autofocus = true, minFocusCm = 10, cleanIsoMax = 1600, stabilizationStops = 2f)
    val TELE = LensSpec(Role.TELE, "Teleobjetivo 3×", 70f, 2.4f, "1/3.94\"", 10, ois = true, autofocus = true, minFocusCm = 40, cleanIsoMax = 640, stabilizationStops = 2f)
    val FRONT = LensSpec(Role.FRONT, "Frontal", 26f, 2.2f, "1/3.24\"", 10, ois = false, autofocus = true, minFocusCm = 25, cleanIsoMax = 800, stabilizationStops = 0f)

    val REAR = listOf(ULTRA_WIDE, WIDE, TELE)

    /** Qué cámara física corresponde a una focal equivalente medida por el sensor. */
    fun specFor(equivalentMm: Float, front: Boolean): LensSpec =
        if (front) FRONT else REAR.minBy { abs(ln(it.equivalentMm / equivalentMm.coerceAtLeast(1f))) }

    /** Lente de la persona (factor total con zoom) → cámara física. */
    fun specForFactor(factor: Float, front: Boolean): LensSpec = when {
        front -> FRONT
        factor < 0.9f -> ULTRA_WIDE
        factor >= 2.9f -> TELE
        else -> WIDE
    }

    /**
     * Velocidad más lenta segura a pulso (s) para una focal equivalente: regla 1/(2·focal),
     * relajada por el estabilizador óptico del lente.
     */
    fun slowestHandheld(spec: LensSpec, equivalentMm: Float): Double =
        (1.0 / (2.0 * equivalentMm.coerceAtLeast(8f))) * Math.pow(2.0, spec.stabilizationStops.toDouble())

    /**
     * Con poca luz, el teleobjetivo (f/2.4 y sensor diminuto) da peor foto que un recorte 3× del
     * principal (f/1.8 y sensor 6 veces más grande): la cámara de Samsung hace lo mismo.
     */
    fun preferMainCropOverTele(isoOnMain: Int): Boolean = isoOnMain >= 400
}
