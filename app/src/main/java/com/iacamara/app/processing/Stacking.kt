package com.iacamara.app.processing

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import java.util.stream.IntStream

/** Reparte filas [0, h) en bloques que se procesan en paralelo en todos los núcleos. */
internal inline fun parallelRows(h: Int, align: Int = 1, crossinline body: (Int, Int) -> Unit) {
    val parts = (Runtime.getRuntime().availableProcessors()).coerceIn(1, 8)
    var chunk = (h + parts - 1) / parts
    if (align > 1) chunk = ((chunk + align - 1) / align) * align
    IntStream.range(0, parts).parallel().forEach { k ->
        val y0 = k * chunk
        val y1 = min(h, y0 + chunk)
        if (y0 < y1) body(y0, y1)
    }
}

/**
 * Procesamiento multi‑toma propio de IA Cámara.
 * Trabaja sobre arreglos de píxeles ARGB (sin depender de Android) para poder probarse a fondo.
 */
object Align {

    /** Luminancia aproximada (0..255) de cada píxel ARGB. */
    fun luma(px: IntArray): ByteArray {
        val out = ByteArray(px.size)
        parallelRows(px.size) { a, b ->
            for (i in a until b) {
                val p = px[i]
                out[i] = ((((p shr 16) and 0xFF) * 2 + ((p shr 8) and 0xFF) * 5 + (p and 0xFF)) shr 3).toByte()
            }
        }
        return out
    }

    /**
     * Calcula cuánto se movió la toma [cur] respecto a [ref]: devuelve (dx, dy) tal que
     * cur(x + dx, y + dy) ≈ ref(x, y). Búsqueda en tres niveles (grueso → fino).
     */
    fun estimateShift(ref: ByteArray, cur: ByteArray, w: Int, h: Int, maxShift: Int = 48): Pair<Int, Int> {
        val m = min(maxShift, min(w, h) / 4)
        var best = 0 to 0
        best = search(ref, cur, w, h, best, m, 8, 16, m, central = false)
        best = search(ref, cur, w, h, best, 8, 2, 8, m, central = false)
        best = search(ref, cur, w, h, best, 2, 1, 4, m, central = true)
        return best
    }

    private fun search(
        ref: ByteArray, cur: ByteArray, w: Int, h: Int,
        center: Pair<Int, Int>, radius: Int, step: Int, stride: Int, maxShift: Int, central: Boolean,
    ): Pair<Int, Int> {
        var bestCost = Long.MAX_VALUE
        var best = center
        var dy = -radius
        while (dy <= radius) {
            var dx = -radius
            while (dx <= radius) {
                val sx = (center.first + dx).coerceIn(-maxShift, maxShift)
                val sy = (center.second + dy).coerceIn(-maxShift, maxShift)
                val c = sad(ref, cur, w, h, sx, sy, stride, maxShift, central)
                if (c < bestCost) { bestCost = c; best = sx to sy }
                dx += step
            }
            dy += step
        }
        return best
    }

    private fun sad(ref: ByteArray, cur: ByteArray, w: Int, h: Int, dx: Int, dy: Int, stride: Int, margin: Int, central: Boolean): Long {
        val x0 = if (central) w / 4 else margin
        val x1 = if (central) w * 3 / 4 else w - margin
        val y0 = if (central) h / 4 else margin
        val y1 = if (central) h * 3 / 4 else h - margin
        var sum = 0L
        var y = max(y0, margin)
        while (y < min(y1, h - margin)) {
            val rr = y * w
            val cr = (y + dy) * w + dx
            var x = max(x0, margin)
            while (x < min(x1, w - margin)) {
                sum += abs((ref[rr + x].toInt() and 0xFF) - (cur[cr + x].toInt() and 0xFF))
                x += stride
            }
            y += stride
        }
        return sum
    }
}

/**
 * Promedia varias tomas alineadas: el ruido baja como √N (8 tomas ≈ 3 pasos menos de ruido)
 * sin "pintar" la imagen como un filtro de reducción de ruido.
 * Lo que se mueve entre tomas (personas, coches) se descarta para evitar "fantasmas".
 */
class MeanStacker(private val w: Int, private val h: Int) {
    private val r = ShortArray(w * h)
    private val g = ShortArray(w * h)
    private val b = ShortArray(w * h)
    var count = 0
        private set

    fun add(px: IntArray, dx: Int = 0, dy: Int = 0) {
        if (count == 0) {
            for (i in 0 until w * h) {
                val p = px[i]
                r[i] = ((p shr 16) and 0xFF).toShort()
                g[i] = ((p shr 8) and 0xFF).toShort()
                b[i] = (p and 0xFF).toShort()
            }
            count = 1
            return
        }
        val n = count
        // 1) Detección de movimiento por zonas de 4×4 px: el promedio de 16 píxeles casi no tiene
        //    ruido, así que un píxel ruidoso no se confunde con un objeto que se movió.
        val bw = (w + GB - 1) / GB
        val bh = (h + GB - 1) / GB
        val diff = IntArray(bw * bh)
        val cnt = IntArray(bw * bh)
        parallelRows(h, align = GB) { ya, yb -> for (y in ya until yb) {
            val sy = y + dy
            val brow = (y / GB) * bw
            for (x in 0 until w) {
                val i = y * w + x
                val avgL = (r[i] * 2 + g[i] * 5 + b[i]) / (8 * n)
                val sx = x + dx
                val curL = if (sx in 0 until w && sy in 0 until h) {
                    val p = px[sy * w + sx]
                    (((p shr 16) and 0xFF) * 2 + ((p shr 8) and 0xFF) * 5 + (p and 0xFF)) shr 3
                } else avgL
                val bi = brow + x / GB
                diff[bi] += curL - avgL
                cnt[bi]++
            }
        } }
        // 2) Suma de la toma, salvo en las zonas donde algo se movió.
        parallelRows(h) { ya, yb -> for (y in ya until yb) {
            val sy = y + dy
            val row = y * w
            val brow = (y / GB) * bw
            for (x in 0 until w) {
                val i = row + x
                val bi = brow + x / GB
                val moved = abs(diff[bi]) > GHOST_THRESHOLD * cnt[bi]
                var cr = r[i] / n
                var cg = g[i] / n
                var cb = b[i] / n
                val sx = x + dx
                if (!moved && sx in 0 until w && sy in 0 until h) {
                    val p = px[sy * w + sx]
                    cr = (p shr 16) and 0xFF
                    cg = (p shr 8) and 0xFF
                    cb = p and 0xFF
                }
                r[i] = (r[i] + cr).toShort()
                g[i] = (g[i] + cg).toShort()
                b[i] = (b[i] + cb).toShort()
            }
        } }
        count = n + 1
    }

    /**
     * Resultado final. [shadowLift] aclara suavemente las sombras (0 = nada, 0.3 = noche),
     * algo que solo es posible porque el ruido ya bajó.
     */
    fun result(shadowLift: Float = 0f): IntArray {
        val lut = IntArray(256) { v ->
            val f = v / 255f
            val o = f + shadowLift * f * (1f - f) * (1f - f)
            (o * 255f).roundToInt().coerceIn(0, 255)
        }
        val n = max(count, 1)
        val half = n / 2
        return IntArray(w * h) { i ->
            val rr = lut[(r[i] + half) / n]
            val gg = lut[(g[i] + half) / n]
            val bb = lut[(b[i] + half) / n]
            (0xFF shl 24) or (rr shl 16) or (gg shl 8) or bb
        }
    }

    companion object {
        /** Diferencia media de luminancia (por zona de 4×4) a partir de la cual algo "se movió". */
        const val GHOST_THRESHOLD = 22
        /** Tamaño de zona para detectar movimiento. */
        const val GB = 4
    }
}

/**
 * Apilado de enfoque (focus stacking) para macro: de cada toma, enfocada a una distancia
 * distinta, se queda con las zonas más nítidas. Así todo el insecto o la flor sale nítido.
 */
class FocusStacker(private val w: Int, private val h: Int) {
    private val out = IntArray(w * h)
    private val bw = (w + BLOCK - 1) / BLOCK
    private val bh = (h + BLOCK - 1) / BLOCK
    private val best = FloatArray(bw * bh) { -1f }
    var count = 0
        private set

    fun add(px: IntArray, dx: Int = 0, dy: Int = 0) {
        val sharp = sharpness(px, dx, dy)
        val first = count == 0
        parallelRows(h) { ya, yb -> for (y in ya until yb) {
            val sy = y + dy
            val by = y / BLOCK
            for (x in 0 until w) {
                val sx = x + dx
                val i = y * w + x
                val inside = sx in 0 until w && sy in 0 until h
                if (first) {
                    out[i] = if (inside) px[sy * w + sx] else px[i]
                    continue
                }
                val bi = by * bw + x / BLOCK
                if (inside && sharp[bi] > best[bi]) out[i] = px[sy * w + sx]
            }
        } }
        for (i in best.indices) if (sharp[i] > best[i]) best[i] = sharp[i]
        count++
    }

    fun result(): IntArray = out.copyOf()

    /** Nitidez por bloque: energía del laplaciano, suavizada con sus vecinos. */
    private fun sharpness(px: IntArray, dx: Int, dy: Int): FloatArray {
        // Luminancia ya alineada (una sola pasada).
        val lum = IntArray(w * h)
        parallelRows(h) { ya, yb -> for (y in ya until yb) {
            val sy = (y + dy).coerceIn(0, h - 1)
            for (x in 0 until w) {
                val sx = (x + dx).coerceIn(0, w - 1)
                val p = px[sy * w + sx]
                lum[y * w + x] = (((p shr 16) and 0xFF) * 2 + ((p shr 8) and 0xFF) * 5 + (p and 0xFF)) shr 3
            }
        } }
        val raw = FloatArray(bw * bh)
        // Bloques de filas alineados al tamaño de bloque: cada hilo escribe en sus propios bloques.
        parallelRows(h, align = BLOCK) { ya, yb -> for (y in max(ya, 1) until min(yb, h - 1)) {
            val by = y / BLOCK
            val row = y * w
            for (x in 1 until w - 1) {
                val i = row + x
                val lap = abs(4 * lum[i] - lum[i - 1] - lum[i + 1] - lum[i - w] - lum[i + w])
                raw[by * bw + x / BLOCK] += lap.toFloat()
            }
        } }
        // Suavizado 3×3 entre bloques para transiciones más naturales.
        val s = FloatArray(bw * bh)
        for (by in 0 until bh) for (bx in 0 until bw) {
            var sum = 0f
            var n = 0
            for (oy in -1..1) for (ox in -1..1) {
                val yy = by + oy
                val xx = bx + ox
                if (yy in 0 until bh && xx in 0 until bw) { sum += raw[yy * bw + xx]; n++ }
            }
            s[by * bw + bx] = sum / n + raw[by * bw + bx]
        }
        return s
    }

    companion object {
        const val BLOCK = 8
    }
}
