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
    /**
     * Calidez de las zonas más luminosas (el 25 % más claro, normalmente el cielo).
     * Un atardecer tiene el cielo naranja; una piel o una mesa cálidas no cuentan.
     */
    val highlightWarmth: Float = 0f,
    /**
     * Fracción del encuadre ocupada por zonas GRANDES quemadas (cielo, ventana).
     * Una lámpara o el sol ocupan muy poco y no cuentan: un fotógrafo los deja quemarse.
     */
    val clippedArea: Float = 0f,
    /** Fracción del encuadre en sombras profundas (casi negro). */
    val shadowArea: Float = 0f,
    /** Luz media (0..1) sobre el sujeto (rostro, mascota…), si hay uno. */
    val subjectLuma: Float? = null,
    /** Percentil 98 de la luz (0..1): cuánto margen queda antes de quemar. */
    val p98: Float = 1f,
)

/** Rectángulo normalizado 0..1 en el marco del visor (vertical, como se ve en pantalla). */
data class NormRect(val left: Float, val top: Float, val right: Float, val bottom: Float)

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

    /** Zona del sujeto a medir (la fija el motor con lo que detecta la IA). */
    @Volatile var region: NormRect? = null
    /** La zona viene espejada (cámara frontal). */
    @Volatile var regionMirrored: Boolean = false

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
        // Rejilla de zonas: distingue una zona grande quemada (cielo) de un punto de luz (lámpara).
        val cellHigh = IntArray(GX * GY)
        val cellDark = IntArray(GX * GY)
        val cellN = IntArray(GX * GY)
        var y = 0
        while (y < h) {
            val row = y * rowStride
            val cyIdx = (y * GY / h) * GX
            var x = 0
            while (x < w) {
                val v = buffer.get(row + x * pixelStride).toInt() and 0xFF
                bins[v shr 2]++
                sum += v
                val cell = cyIdx + x * GX / w
                cellN[cell]++
                if (v >= 250) { high++; cellHigh[cell]++ }
                if (v <= 5) low++
                if (v <= 16) cellDark[cell]++
                count++
                x += step
            }
            y += step
        }
        if (count == 0) return null
        var blown = 0
        var dark = 0
        for (c in 0 until GX * GY) {
            if (cellN[c] == 0) continue
            if (cellHigh[c] * 2 > cellN[c]) blown++
            if (cellDark[c] * 2 > cellN[c]) dark++
        }
        val subjectLuma = region?.let { measureRegion(image, it) }

        // Umbral del 25 % más luminoso, a partir del histograma.
        var acc = 0
        var p75 = 255
        for (bin in 63 downTo 0) {
            acc += bins[bin]
            if (acc >= count / 4) { p75 = bin shl 2; break }
        }
        val brightCut = maxOf(p75, 90)
        var acc98 = 0
        var p98 = 255
        for (bin in 63 downTo 0) {
            acc98 += bins[bin]
            if (acc98 >= count / 50) { p98 = (bin shl 2) + 2; break }
        }

        // Crominancia (U = azul, V = rojo) submuestreada a la mitad.
        var warmth = 0f
        var highlightWarmth = 0f
        if (image.planes.size >= 3) {
            val u = image.planes[1]
            val v = image.planes[2]
            var su = 0L
            var sv = 0L
            var n = 0
            var hd = 0L
            var hn = 0
            var cy = 0
            while (cy < h / 2) {
                var cx = 0
                while (cx < w / 2) {
                    val uu = u.buffer.get(cy * u.rowStride + cx * u.pixelStride).toInt() and 0xFF
                    val vv = v.buffer.get(cy * v.rowStride + cx * v.pixelStride).toInt() and 0xFF
                    su += uu
                    sv += vv
                    n++
                    val yy = buffer.get((cy * 2) * rowStride + (cx * 2) * pixelStride).toInt() and 0xFF
                    if (yy >= brightCut) { hd += vv - uu; hn++ }
                    cx += 4
                }
                cy += 4
            }
            if (n > 0) warmth = ((sv - su).toFloat() / n) / 255f
            if (hn > 0) highlightWarmth = (hd.toFloat() / hn) / 255f
        }

        val peak = bins.max().coerceAtLeast(1).toFloat()
        return FrameStats(
            histogram = FloatArray(64) { bins[it] / peak },
            meanLuma = sum.toFloat() / count / 255f,
            clippedHighlights = high.toFloat() / count,
            clippedShadows = low.toFloat() / count,
            warmth = warmth,
            highlightWarmth = highlightWarmth,
            clippedArea = blown.toFloat() / (GX * GY),
            shadowArea = dark.toFloat() / (GX * GY),
            subjectLuma = subjectLuma,
            p98 = p98 / 255f,
        )
    }

    /**
     * Luz media dentro del sujeto. La zona viene en el marco vertical de la pantalla y el
     * fotograma está en la orientación del sensor: se gira según rotationDegrees.
     */
    private fun measureRegion(image: ImageProxy, r: NormRect): Float? {
        val rotation = runCatching { image.imageInfo.rotationDegrees }.getOrDefault(0)
        val w = image.width
        val h = image.height
        val l = if (regionMirrored) 1f - r.right else r.left
        val rr = if (regionMirrored) 1f - r.left else r.right
        // (u, v) vertical → (x, y) del sensor
        fun map(u: Float, v: Float): Pair<Float, Float> = when (rotation) {
            90 -> v * w to (1f - u) * h
            180 -> (1f - u) * w to (1f - v) * h
            270 -> (1f - v) * w to u * h
            else -> u * w to v * h
        }
        val a = map(l, r.top)
        val b = map(rr, r.bottom)
        val x0 = minOf(a.first, b.first).toInt().coerceIn(0, w - 1)
        val x1 = maxOf(a.first, b.first).toInt().coerceIn(0, w - 1)
        val y0 = minOf(a.second, b.second).toInt().coerceIn(0, h - 1)
        val y1 = maxOf(a.second, b.second).toInt().coerceIn(0, h - 1)
        if (x1 - x0 < 4 || y1 - y0 < 4) return null
        val plane = image.planes[0]
        val buf = plane.buffer
        var s = 0L
        var n = 0
        var yy = y0
        while (yy <= y1) {
            var xx = x0
            while (xx <= x1) {
                s += buf.get(yy * plane.rowStride + xx * plane.pixelStride).toInt() and 0xFF
                n++
                xx += 2
            }
            yy += 2
        }
        return if (n == 0) null else s.toFloat() / n / 255f
    }

    companion object {
        private const val GX = 16
        private const val GY = 12
    }
}
