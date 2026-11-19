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
    /** Movimiento de toda la imagen en el encuadre (anchos de visor por segundo): pulso o paneo. */
    val globalMotion: Float = 0f,
    /** Movimiento del sujeto en el encuadre (anchos por segundo), medido píxel a píxel. */
    val subjectMotion: Float? = null,
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
    /** Reloj en nanosegundos (se usa si el fotograma no trae marca de tiempo). */
    private val clock: () -> Long = { System.nanoTime() },
    /** Mínimo entre análisis (ms). */
    private val minIntervalMs: Long = 120,
) : ImageAnalysis.Analyzer {
    private var lastEmit = 0L

    /**
     * Rotación que endereza el fotograma en el marco vertical de la pantalla. null = usar la del
     * fotograma (que cambia cuando el teléfono se gira).
     */
    @Volatile var portraitRotation: Int? = null

    // Flujo óptico: fotograma anterior reducido.
    private var prevSmall: ByteArray? = null
    private var prevW = 0
    private var prevH = 0
    private var prevTime = 0L

    /** Zona del sujeto a medir (la fija el motor con lo que detecta la IA). */
    @Volatile var region: NormRect? = null
    /** La zona viene espejada (cámara frontal). */
    @Volatile var regionMirrored: Boolean = false

    override fun analyze(image: ImageProxy) {
        val now = System.currentTimeMillis()
        if (minIntervalMs <= 0 || now - lastEmit >= minIntervalMs) {
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
        val (globalMotion, subjectMotion) = measureMotion(image)

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
            globalMotion = globalMotion,
            subjectMotion = subjectMotion,
        )
    }

    /**
     * Luz media dentro del sujeto. La zona viene en el marco vertical de la pantalla y el
     * fotograma está en la orientación del sensor: se gira según rotationDegrees.
     */
    private fun rotationOf(image: ImageProxy) =
        portraitRotation ?: runCatching { image.imageInfo.rotationDegrees }.getOrDefault(0)

    /** Zona vertical normalizada → rectángulo en píxeles del sensor (x0, y0, x1, y1). */
    private fun regionToBuffer(r: NormRect, rotation: Int, w: Int, h: Int): IntArray {
        val l = if (regionMirrored) 1f - r.right else r.left
        val rr = if (regionMirrored) 1f - r.left else r.right
        fun map(u: Float, v: Float): Pair<Float, Float> = when (rotation) {
            90 -> v * w to (1f - u) * h
            180 -> (1f - u) * w to (1f - v) * h
            270 -> (1f - v) * w to u * h
            else -> u * w to v * h
        }
        val a = map(l, r.top)
        val b = map(rr, r.bottom)
        return intArrayOf(
            minOf(a.first, b.first).toInt().coerceIn(0, w - 1), minOf(a.second, b.second).toInt().coerceIn(0, h - 1),
            maxOf(a.first, b.first).toInt().coerceIn(0, w - 1), maxOf(a.second, b.second).toInt().coerceIn(0, h - 1),
        )
    }

    private fun measureRegion(image: ImageProxy, r: NormRect): Float? {
        val w = image.width
        val h = image.height
        val (x0, y0, x1, y1) = regionToBuffer(r, rotationOf(image), w, h).let { arrayOf(it[0], it[1], it[2], it[3]) }
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

    /**
     * Flujo óptico entre este fotograma y el anterior, sobre una versión reducida (¼):
     *  • movimiento global (pulso, paneo) y
     *  • movimiento del sujeto dentro de su zona,
     * con precisión de subpíxel (ajuste parabólico). Devuelve anchos de visor por segundo.
     */
    private fun measureMotion(image: ImageProxy): Pair<Float, Float?> {
        val plane = image.planes[0]
        val buf = plane.buffer
        val w = image.width
        val h = image.height
        val sw = w / DS
        val sh = h / DS
        if (sw < 32 || sh < 32) return 0f to null
        val small = ByteArray(sw * sh)
        for (y in 0 until sh) for (x in 0 until sw) {
            var s = 0
            for (oy in 0 until DS step 2) for (ox in 0 until DS step 2) {
                s += buf.get((y * DS + oy) * plane.rowStride + (x * DS + ox) * plane.pixelStride).toInt() and 0xFF
            }
            small[y * sw + x] = (s / ((DS / 2) * (DS / 2))).toByte()
        }
        val t = runCatching { image.imageInfo.timestamp }.getOrNull()?.takeIf { it > 0 } ?: clock()
        val prev = prevSmall
        val dtS = (t - prevTime) / 1e9f
        prevSmall = small; prevW = sw; prevH = sh; prevTime = t
        if (prev == null || prev.size != small.size || dtS !in 0.02f..1.0f) return 0f to null

        val short = minOf(sw, sh).toFloat()
        val m = SEARCH + 2
        // Zona del sujeto en la imagen reducida (se excluye del cálculo global).
        val subj = region?.let { r ->
            val rb = regionToBuffer(r, rotationOf(image), w, h)
            val x0 = (rb[0] / DS).coerceIn(m, sw - m - 1)
            val y0 = (rb[1] / DS).coerceIn(m, sh - m - 1)
            intArrayOf(x0, y0, (rb[2] / DS).coerceIn(x0 + 1, sw - m), (rb[3] / DS).coerceIn(y0 + 1, sh - m))
        }
        // Global: toda la imagen salvo el margen y el sujeto (el sujeto no debe "arrastrar" la medida).
        val g = flow(prev, small, sw, m, m, sw - m, sh - m, subj)
        val global = kotlin.math.hypot(g.first, g.second) / short / dtS

        // Sujeto: su propia zona (incluye el pulso: lo que importa para que no salga movido es
        // cuánto se desplaza el sujeto sobre el sensor).
        val subject = subj?.let { b ->
            if ((b[2] - b[0]) * (b[3] - b[1]) < 36) null
            else {
                val f = flow(prev, small, sw, b[0], b[1], b[2], b[3], null)
                kotlin.math.hypot(f.first, f.second) / short / dtS
            }
        }
        return global to subject
    }

    /**
     * Desplazamiento de la zona [x0,x1)×[y0,y1) entre prev y cur:
     *  1) búsqueda entera por diferencia absoluta (robusta a movimientos grandes),
     *  2) refinamiento subpíxel de Lucas-Kanade (resuelve el desplazamiento exacto a partir
     *     de los gradientes de la imagen), dos iteraciones.
     * [exclude] = rectángulo a ignorar (x0, y0, x1, y1), p. ej. el sujeto al medir el fondo.
     */
    private fun flow(prev: ByteArray, cur: ByteArray, sw: Int, x0: Int, y0: Int, x1: Int, y1: Int, exclude: IntArray?): Pair<Float, Float> {
        fun skip(x: Int, y: Int) = exclude != null && x >= exclude[0] && x < exclude[2] && y >= exclude[1] && y < exclude[3]
        fun sad(dx: Int, dy: Int): Long {
            var s = 0L
            for (y in y0 until y1) {
                val pr = y * sw
                val cr = (y + dy) * sw + dx
                for (x in x0 until x1) {
                    if (skip(x, y)) continue
                    s += kotlin.math.abs((prev[pr + x].toInt() and 0xFF) - (cur[cr + x].toInt() and 0xFF))
                }
            }
            return s
        }
        var best = Long.MAX_VALUE
        var bx = 0
        var by = 0
        for (dy in -SEARCH..SEARCH) for (dx in -SEARCH..SEARCH) {
            val c = sad(dx, dy)
            if (c < best) { best = c; bx = dx; by = dy }
        }
        // Lucas-Kanade alrededor del desplazamiento entero.
        val sh = cur.size / sw
        fun p(x: Int, y: Int) = (prev[y * sw + x].toInt() and 0xFF).toFloat()
        fun c(x: Float, y: Float): Float {
            val xx = x.coerceIn(0f, sw - 1.001f); val yy = y.coerceIn(0f, sh - 1.001f)
            val ix = xx.toInt(); val iy = yy.toInt(); val fx = xx - ix; val fy = yy - iy
            fun v(a: Int, b: Int) = (cur[b * sw + a].toInt() and 0xFF).toFloat()
            return v(ix, iy) * (1 - fx) * (1 - fy) + v(ix + 1, iy) * fx * (1 - fy) + v(ix, iy + 1) * (1 - fx) * fy + v(ix + 1, iy + 1) * fx * fy
        }
        var ux = bx.toFloat()
        var uy = by.toFloat()
        repeat(2) {
            var sxx = 0.0; var sxy = 0.0; var syy = 0.0; var sxt = 0.0; var syt = 0.0
            for (y in maxOf(y0, 1) until minOf(y1, sh - 1)) for (x in maxOf(x0, 1) until minOf(x1, sw - 1)) {
                if (skip(x, y)) continue
                val gx = (p(x + 1, y) - p(x - 1, y)) / 2f
                val gy = (p(x, y + 1) - p(x, y - 1)) / 2f
                val it = c(x + ux, y + uy) - p(x, y)
                sxx += gx * gx; sxy += gx * gy; syy += gy * gy; sxt += gx * it; syt += gy * it
            }
            val det = sxx * syy - sxy * sxy
            if (det < 1e-6) return@repeat
            val ddx = (-(syy * sxt) + sxy * syt) / det
            val ddy = (sxy * sxt - sxx * syt) / det
            ux += ddx.toFloat().coerceIn(-1f, 1f)
            uy += ddy.toFloat().coerceIn(-1f, 1f)
        }
        return ux to uy
    }

    companion object {
        private const val GX = 16
        private const val GY = 12
        /** Factor de reducción para el flujo óptico. */
        private const val DS = 4
        /** Búsqueda ±px (en la imagen reducida) entre fotogramas. */
        private const val SEARCH = 10
    }
}
