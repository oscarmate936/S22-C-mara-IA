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
)

/**
 * Lee el plano Y (luminancia) de cada fotograma: histograma, media y recortes.
 * Es la base del fotómetro propio de la app y, en la fase 2, de la IA de escena.
 */
class FrameAnalyzer(private val onStats: (FrameStats) -> Unit) : ImageAnalysis.Analyzer {
    private var lastEmit = 0L

    override fun analyze(image: ImageProxy) {
        val now = System.currentTimeMillis()
        if (now - lastEmit < 120) { image.close(); return }
        lastEmit = now

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
        image.close()
        if (count == 0) return
        val peak = bins.max().coerceAtLeast(1).toFloat()
        val hist = FloatArray(64) { bins[it] / peak }
        onStats(
            FrameStats(
                histogram = hist,
                meanLuma = sum.toFloat() / count / 255f,
                clippedHighlights = high.toFloat() / count,
                clippedShadows = low.toFloat() / count,
            ),
        )
    }
}
