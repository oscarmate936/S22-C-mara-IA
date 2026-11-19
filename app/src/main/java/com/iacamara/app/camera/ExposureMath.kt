package com.iacamara.app.camera

import android.hardware.camera2.CameraMetadata
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.roundToInt

/** Una posición seleccionable en un dial. */
data class DialOption(val label: String, val major: Boolean)

/** Preajustes de balance de blancos (los que Camera2 garantiza en todos los dispositivos). */
enum class WhiteBalance(val label: String, val kelvin: String, val awbMode: Int) {
    AUTO("Auto", "AWB", CameraMetadata.CONTROL_AWB_MODE_AUTO),
    INCANDESCENT("Cálida", "3200K", CameraMetadata.CONTROL_AWB_MODE_INCANDESCENT),
    FLUORESCENT("Fluor.", "4000K", CameraMetadata.CONTROL_AWB_MODE_FLUORESCENT),
    DAYLIGHT("Sol", "5500K", CameraMetadata.CONTROL_AWB_MODE_DAYLIGHT),
    CLOUDY("Nublado", "6500K", CameraMetadata.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT),
    SHADE("Sombra", "7500K", CameraMetadata.CONTROL_AWB_MODE_SHADE),
}

object ExposureMath {
    private const val SECOND = 1_000_000_000L

    /** Escala estándar de ISO en tercios de paso. Los pasos completos se marcan como mayores. */
    private val ISO_STOPS = listOf(
        50, 64, 80, 100, 125, 160, 200, 250, 320, 400, 500, 640, 800, 1000, 1250,
        1600, 2000, 2500, 3200, 4000, 5000, 6400, 8000, 10000, 12800,
    )
    private val ISO_MAJOR = setOf(50, 100, 200, 400, 800, 1600, 3200, 6400, 12800)

    /** Escala estándar de velocidades en tercios de paso (en segundos). */
    private val SHUTTER_STOPS: List<Double> = listOf(
        8000, 6400, 5000, 4000, 3200, 2500, 2000, 1600, 1250, 1000, 800, 640, 500, 400, 320,
        250, 200, 160, 125, 100, 80, 60, 50, 40, 30, 25, 20, 15, 13, 10, 8, 6, 5, 4,
    ).map { 1.0 / it } + listOf(0.3, 0.4, 0.5, 0.6, 0.8, 1.0)
    private val SHUTTER_MAJOR = setOf(8000, 4000, 2000, 1000, 500, 250, 125, 60, 30, 15, 8, 4)

    /** Tope de exposición manual en esta fase (las exposiciones largas llegan en la fase 4). */
    const val MAX_SHUTTER_NS = SECOND

    fun isoValues(min: Int, max: Int): List<Int> =
        ISO_STOPS.filter { it in min..max }.ifEmpty { listOf(min, max).distinct() }

    fun isoOptions(values: List<Int>) = values.map { DialOption(it.toString(), it in ISO_MAJOR) }

    fun shutterValues(minNs: Long, maxNs: Long): List<Long> {
        val cap = minOf(maxNs, MAX_SHUTTER_NS)
        return SHUTTER_STOPS.map { (it * SECOND).toLong() }
            .filter { it in minNs..cap }
            .ifEmpty { listOf(minNs, cap).distinct() }
    }

    fun shutterOptions(values: List<Long>) = values.map {
        val s = it.toDouble() / SECOND
        val major = s >= 1.0 || (s < 1.0 && (1.0 / s).roundToInt() in SHUTTER_MAJOR)
        DialOption(formatShutter(it), major)
    }

    fun formatShutter(ns: Long): String {
        val s = ns.toDouble() / SECOND
        if (s >= 0.3) {
            val r = (s * 10).roundToInt() / 10.0
            return if (r == r.toInt().toDouble()) "${r.toInt()}\"" else "$r\""
        }
        val denom = 1.0 / s
        // Redondea al valor "bonito" más cercano de la escala estándar.
        val nice = SHUTTER_STOPS.filter { it < 0.3 }.map { (1.0 / it).roundToInt() }
            .minByOrNull { abs(ln(it / denom)) } ?: denom.roundToInt()
        return if (abs(ln(nice / denom)) < 0.12) "1/$nice" else "1/${denom.roundToInt()}"
    }

    fun formatFocus(diopters: Float): String {
        if (diopters <= 0.05f) return "∞"
        val m = 1f / diopters
        return when {
            m >= 10f -> "${m.roundToInt()}m"
            m >= 1f -> "${(m * 10).roundToInt() / 10f}m"
            else -> "${(m * 100).roundToInt()}cm"
        }
    }

    /** Pasos de enfoque, lineales en dioptrías (como el anillo de enfoque de un objetivo). */
    fun focusValues(minFocusDiopters: Float): List<Float> {
        if (minFocusDiopters <= 0f) return listOf(0f)
        val steps = 40
        return (0..steps).map { minFocusDiopters * it / steps }
    }

    fun focusOptions(values: List<Float>) = values.mapIndexed { i, d -> DialOption(formatFocus(d), i % 5 == 0) }

    fun evValues(minIndex: Int, maxIndex: Int, step: Float): List<Float> =
        (minIndex..maxIndex).map { it * step }

    fun evOptions(values: List<Float>) = values.map {
        val major = abs(it - it.roundToInt()) < 0.01f
        DialOption(formatEv(it), major)
    }

    fun formatEv(ev: Float): String {
        val v = (ev * 10).roundToInt() / 10f
        return when {
            abs(v) < 0.05f -> "±0.0"
            v > 0 -> "+$v"
            else -> "$v"
        }
    }

    fun <T : Comparable<T>> nearestIndex(values: List<T>, target: T, distance: (T, T) -> Double): Int =
        values.indices.minByOrNull { distance(values[it], target) } ?: 0

    fun logDistance(a: Number, b: Number): Double =
        abs(ln(a.toDouble().coerceAtLeast(1e-9) / b.toDouble().coerceAtLeast(1e-9)))
}
