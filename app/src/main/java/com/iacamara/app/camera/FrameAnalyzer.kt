package com.iacamara.app.camera

import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy

/** Resultado del análisis de un fotograma de la vista previa. */
class FrameStats(
    /** 64 cubetas normalizadas 0..1 (la más alta = 1). */
    val histogram: FloatArray,
    /** Luminancia media 0..1 (codificada en gamma, como la ve el ojo). */
    val meanLuma: Float,
    /** Fracción de píxeles casi blancos (altas luces quemadas). */
    val clippedHighlights: Float,
    /** Fracción de píxeles casi negros (sombras empastadas). */
    val clippedShadows: Float,
    /** Calidez del color: >0 tonos rojizos/dorados, <0 tonos azulados. */
    val warmth: Float = 0f,
)

/**
 * Lee cada fotograma: histograma, luz media, recortes y calidez del color.
 * Es la base del fotómetro propio de la app. Si [handOff] acepta el fotograma
 * (la IA lo procesa), él se encarga de cerrarlo.
 */
class FrameAnalyzer(
    private val onStats: (FrameStats) -> Unit,
    private val handOff: (ImageProxy) -> Boolean = { false },
) : ImageAnalysis.Analyzer {
    private var lastEmit = 0L

    override fun analyze(image: ImageProxy) {
        val now = System.currentTimeMillis()
        if (now - lastEmit >= 120) {
            lastEmit = now
            computeStats(image)?.let(onStats)
        }
        if (!handOff(image)) image.close()
    }

    private fun computeStats(image: ImageProxy): FrameStats? {
        val plane = image.planes[0]
        val buffer = plane.buffer
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        val w = image.width
        val h = image.height
        val bins = IntArray(64)
        var sum = 0L
        var count = 0
        var high = 0
        var low = 0
        val step = 4
        var y = 0
        while (y < h) {
            val row = y * rowStride
            var x = 0
            while (x < w) {
                val v = buffer.get(row + x * pixelStride).toInt() and 0xFF
                bins[v shr 2]++
                sum += v
                if (v >= 250) high++
                if (v <= 5) low++
                count++
                x += step
            }
            y += step
        }
        if (count == 0) return null

        // Crominancia (U = azul, V = rojo) submuestreada a la mitad.
        var warmth = 0f
        if (image.planes.size >= 3) {
            val u = image.planes[1]
            val v = image.planes[2]
            var su = 0L
            var sv = 0L
            var n = 0
            var cy = 0
            while (cy < h / 2) {
                var cx = 0
                while (cx < w / 2) {
                    su += u.buffer.get(cy * u.rowStride + cx * u.pixelStride).toInt() and 0xFF
                    sv += v.buffer.get(cy * v.rowStride + cx * v.pixelStride).toInt() and 0xFF
                    n++
                    cx += 8
                }
                cy += 8
            }
            if (n > 0) warmth = ((sv - su).toFloat() / n) / 255f
        }

        val peak = bins.max().coerceAtLeast(1).toFloat()
        return FrameStats(
            histogram = FloatArray(64) { bins[it] / peak },
            meanLuma = sum.toFloat() / count / 255f,
            clippedHighlights = high.toFloat() / count,
            clippedShadows = low.toFloat() / count,
            warmth = warmth,
        )
    }
}
