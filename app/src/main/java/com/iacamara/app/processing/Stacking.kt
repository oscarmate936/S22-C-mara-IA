package com.iacamara.app.processing

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
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

    /** Luminancia de una toma más oscura, llevada al brillo de la normal (para poder alinearlas). */
    fun lumaWithGain(px: IntArray, gain: Float): ByteArray {
        val lut = ByteArray(256) { v -> (((v / 255.0).pow(2.2) * gain).coerceAtMost(1.0).pow(1 / 2.2) * 255 + 0.5).toInt().toByte() }
        val l = luma(px)
        for (i in l.indices) l[i] = lut[l[i].toInt() and 0xFF]
        return l
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

/**
 * HDR natural: combina la toma normal (sombras y sujeto) con una toma más oscura (cielo, luces).
 * Se trabaja en luz lineal (como la física real), con una transición suave y una curva de
 * compresión que solo actúa en las altas luces: los tonos medios quedan intactos, sin "halos"
 * ni aspecto de filtro.
 */
class HdrMerger(private val w: Int, private val h: Int) {
    private val toLin = FloatArray(256) { (it / 255f).toDouble().pow(2.2).toFloat() }
    private val encode = IntArray(4097) { ((it / 4096.0).pow(1 / 2.2) * 255 + 0.5).toInt().coerceIn(0, 255) }

    /** Relación de luz realmente medida entre la toma normal y la oscura (último uso). */
    var measuredRatio = 0f
        private set

    fun merge(normal: IntArray, under: IntArray, dx: Int, dy: Int, nominalRatio: Float = 4f): IntArray {
        val ratio = estimateRatio(normal, under, dx, dy, nominalRatio).also { measuredRatio = it }
        val mask = highlightMask(normal)

        // Mezcla en luz lineal (puede superar 1.0: luz que el sensor normal no pudo guardar).
        val lr = FloatArray(w * h)
        val lg = FloatArray(w * h)
        val lb = FloatArray(w * h)
        parallelRows(h) { ya, yb -> for (y in ya until yb) {
            val sy = y + dy
            for (x in 0 until w) {
                val i = y * w + x
                val p = normal[i]
                var r = toLin[(p shr 16) and 0xFF]
                var g = toLin[(p shr 8) and 0xFF]
                var b = toLin[p and 0xFF]
                val sx = x + dx
                val m = mask[i]
                if (m > 0f && sx in 0 until w && sy in 0 until h) {
                    val q = under[sy * w + sx]
                    r = r * (1 - m) + toLin[(q shr 16) and 0xFF] * ratio * m
                    g = g * (1 - m) + toLin[(q shr 8) and 0xFF] * ratio * m
                    b = b * (1 - m) + toLin[q and 0xFF] * ratio * m
                }
                lr[i] = r; lg[i] = g; lb[i] = b
            }
        } }

        // Punto blanco: el 99.9 % de la luminancia (lo demás puede quemarse, p. ej. el sol).
        val hist = IntArray(4096)
        for (i in 0 until w * h step 3) {
            val yv = 0.2126f * lr[i] + 0.7152f * lg[i] + 0.0722f * lb[i]
            hist[(yv / 16f * 4095).toInt().coerceIn(0, 4095)]++
        }
        var acc = 0
        val total = hist.sum()
        var white = 16f
        for (k in 4095 downTo 0) {
            acc += hist[k]
            if (acc > total / 1000) { white = (k + 1) / 4095f * 16f; break }
        }
        val curve = ToneCurve(white.coerceAtLeast(1f))

        val out = IntArray(w * h)
        parallelRows(h) { ya, yb -> for (y in ya until yb) {
            for (x in 0 until w) {
                val i = y * w + x
                val yv = 0.2126f * lr[i] + 0.7152f * lg[i] + 0.0722f * lb[i]
                val k = if (yv > 1e-6f) curve(yv) / yv else 1f
                fun enc(c: Float) = encode[(c * k * 4096).toInt().coerceIn(0, 4096)]
                out[i] = (0xFF shl 24) or (enc(lr[i]) shl 16) or (enc(lg[i]) shl 8) or enc(lb[i])
            }
        } }
        return out
    }

    /** Mide cuánta más luz tiene la toma normal comparando zonas bien expuestas en ambas. */
    private fun estimateRatio(normal: IntArray, under: IntArray, dx: Int, dy: Int, nominal: Float): Float {
        val ratios = ArrayList<Float>()
        var i = 0
        while (i < w * h && ratios.size < 40000) {
            val x = i % w
            val y = i / w
            val sx = x + dx
            val sy = y + dy
            if (sx in 0 until w && sy in 0 until h) {
                val p = normal[i]
                val mx = max(max((p shr 16) and 0xFF, (p shr 8) and 0xFF), p and 0xFF)
                if (mx in 60..200) {
                    val q = under[sy * w + sx]
                    val ln = 0.2126f * toLin[(p shr 16) and 0xFF] + 0.7152f * toLin[(p shr 8) and 0xFF] + 0.0722f * toLin[p and 0xFF]
                    val lu = 0.2126f * toLin[(q shr 16) and 0xFF] + 0.7152f * toLin[(q shr 8) and 0xFF] + 0.0722f * toLin[q and 0xFF]
                    if (lu > 0.003f) ratios += ln / lu
                }
            }
            i += 7
        }
        if (ratios.size < 200) return nominal
        ratios.sort()
        return ratios[ratios.size / 2].coerceIn(1.5f, 16f)
    }

    /** Dónde usar la toma oscura: zonas casi quemadas, con bordes suaves (sin halos). */
    private fun highlightMask(normal: IntArray): FloatArray {
        val b = 8
        val bw = (w + b - 1) / b
        val bh = (h + b - 1) / b
        var low = FloatArray(bw * bh)
        val n = IntArray(bw * bh)
        for (y in 0 until h) for (x in 0 until w) {
            val p = normal[y * w + x]
            val mx = max(max((p shr 16) and 0xFF, (p shr 8) and 0xFF), p and 0xFF)
            val t = ((mx - 200) / 45f).coerceIn(0f, 1f)
            val bi = (y / b) * bw + x / b
            low[bi] += t * t * (3 - 2 * t)
            n[bi]++
        }
        for (k in low.indices) low[k] /= max(n[k], 1)
        // Dos pasadas de suavizado: transiciones de ~40 px, imperceptibles.
        repeat(2) {
            val o = FloatArray(bw * bh)
            for (by in 0 until bh) for (bx in 0 until bw) {
                var s = 0f
                var c = 0
                for (oy in -1..1) for (ox in -1..1) {
                    val yy = by + oy
                    val xx = bx + ox
                    if (yy in 0 until bh && xx in 0 until bw) { s += low[yy * bw + xx]; c++ }
                }
                o[by * bw + bx] = max(s / c, low[by * bw + bx] * 0.85f)
            }
            low = o
        }
        // Ampliación bilineal a resolución completa.
        val mask = FloatArray(w * h)
        parallelRows(h) { ya, yb -> for (y in ya until yb) {
            val fy = ((y + 0.5f) / b - 0.5f).coerceIn(0f, (bh - 1).toFloat())
            val y0 = fy.toInt()
            val y1 = min(y0 + 1, bh - 1)
            val ty = fy - y0
            for (x in 0 until w) {
                val fx = ((x + 0.5f) / b - 0.5f).coerceIn(0f, (bw - 1).toFloat())
                val x0 = fx.toInt()
                val x1 = min(x0 + 1, bw - 1)
                val tx = fx - x0
                val a = low[y0 * bw + x0] * (1 - tx) + low[y0 * bw + x1] * tx
                val c = low[y1 * bw + x0] * (1 - tx) + low[y1 * bw + x1] * tx
                mask[y * w + x] = (a * (1 - ty) + c * ty).coerceIn(0f, 1f)
            }
        } }
        return mask
    }
}

/**
 * Curva de compresión de altas luces: idéntica por debajo de la rodilla (tonos medios intactos)
 * y suave por encima, llevando el punto blanco [white] a 1.0 sin cortes.
 */
class ToneCurve(private val white: Float, private val knee: Float = 0.5f) {
    private val s: Float = run {
        if (white <= 1f) return@run 0f
        val d = white - knee
        val target = 1f - knee
        var lo = 1e-4f
        var hi = 100f
        repeat(60) {
            val mid = (lo + hi) / 2
            val g = mid * (1 - kotlin.math.exp(-d / mid))
            if (g < target) lo = mid else hi = mid
        }
        (lo + hi) / 2
    }
    private val norm = if (white <= 1f) 1f else 1 - kotlin.math.exp(-(white - knee) / s)

    operator fun invoke(y: Float): Float {
        if (white <= 1f || y <= knee) return y.coerceAtMost(1f)
        return (knee + (1 - knee) * (1 - kotlin.math.exp(-(y - knee) / s)) / norm).coerceAtMost(1f)
    }
}
