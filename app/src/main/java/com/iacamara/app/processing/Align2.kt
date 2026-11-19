package com.iacamara.app.processing

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Transformación afín: un punto (x, y) de la toma de referencia está en
 * (a·x + b·y + c, d·x + e·y + f) de la otra toma. Representa desplazamiento, giro y un leve
 * cambio de escala (el "zoom" que produce el enfoque en macro).
 */
data class Affine(val a: Float, val b: Float, val c: Float, val d: Float, val e: Float, val f: Float) {
    fun mapX(x: Float, y: Float) = a * x + b * y + c
    fun mapY(x: Float, y: Float) = d * x + e * y + f

    /** Giro aproximado en grados. */
    val rotationDeg: Float get() = Math.toDegrees(kotlin.math.atan2(d.toDouble(), a.toDouble())).toFloat()

    companion object {
        fun translation(dx: Float, dy: Float) = Affine(1f, 0f, dx, 0f, 1f, dy)
    }
}

/** Resultado de la alineación, con datos para saber cuán fiable fue. */
class AlignResult(val transform: Affine, val tilesUsed: Int, val residualPx: Float)

/**
 * Alineación v2 por zonas:
 *  1. desplazamiento global aproximado (como antes),
 *  2. cada zona de 64×64 px se alinea por separado con precisión de subpíxel (Lucas-Kanade),
 *  3. con todos los vectores se ajusta una transformación afín robusta, descartando las zonas que
 *     no encajan (objetos que se movieron).
 * Así se corrigen el giro de la mano y el cambio de escala, no solo el desplazamiento.
 */
object TileAlign {
    private const val TILE = 32 // en media resolución = 64 px reales
    private const val SEARCH = 3
    private const val MAX_TILES_X = 24
    private const val MAX_TILES_Y = 18

    fun estimate(ref: ByteArray, cur: ByteArray, w: Int, h: Int, maxShift: Int = 48): AlignResult {
        val w2 = w / 2
        val h2 = h / 2
        if (w2 < TILE * 3 || h2 < TILE * 3) {
            val (dx, dy) = Align.estimateShift(ref, cur, w, h, maxShift)
            return AlignResult(Affine.translation(dx.toFloat(), dy.toFloat()), 0, 0f)
        }
        val r2 = half(ref, w, h)
        val c2 = half(cur, w, h)
        // Desplazamiento global: en fotos grandes (las del teléfono) basta ¼ de resolución; en
        // fotos pequeñas se usa ½ para no perder precisión en zonas lisas como el cielo.
        val (gx, gy) = if (w2 >= 1000) {
            val (qx, qy) = Align.estimateShift(half(r2, w2, h2), half(c2, w2, h2), w2 / 2, h2 / 2, maxShift / 4)
            qx * 2 to qy * 2
        } else Align.estimateShift(r2, c2, w2, h2, maxShift / 2)

        // Vectores por zona (en media resolución)
        val xs = ArrayList<Float>()
        val ys = ArrayList<Float>()
        val us = ArrayList<Float>()
        val vs = ArrayList<Float>()
        val margin = max(abs(gx), abs(gy)) + SEARCH + 3
        // Rejilla de zonas: como mucho ~24×18 (suficiente para medir giro y escala con precisión).
        val nx = ((w2 - 2 * margin) / TILE).coerceAtLeast(1)
        val ny = ((h2 - 2 * margin) / TILE).coerceAtLeast(1)
        val stepX = maxOf(1, (nx + MAX_TILES_X - 1) / MAX_TILES_X)
        val stepY = maxOf(1, (ny + MAX_TILES_Y - 1) / MAX_TILES_Y)
        val rows = (0 until ny step stepY).toList()
        val found = arrayOfNulls<List<FloatArray>>(rows.size)
        parallelRows(rows.size) { ra, rb ->
            for (ri in ra until rb) {
                val ty = margin + rows[ri] * TILE
                if (ty + TILE + margin >= h2) { found[ri] = emptyList(); continue }
                val list = ArrayList<FloatArray>()
                var ix = 0
                while (ix < nx) {
                    val tx = margin + ix * TILE
                    if (tx + TILE + margin < w2) {
                        tileVector(r2, c2, w2, h2, tx, ty, gx, gy)?.let { (u, v) ->
                            list += floatArrayOf(tx + TILE / 2f, ty + TILE / 2f, u, v)
                        }
                    }
                    ix += stepX
                }
                found[ri] = list
            }
        }
        for (list in found) list?.forEach { xs += it[0]; ys += it[1]; us += it[2]; vs += it[3] }
        if (xs.size < 6) return AlignResult(Affine.translation(gx * 2f, gy * 2f), xs.size, 0f)

        // Ajuste afín robusto (mínimos cuadrados reponderados, descartando lo que no encaja).
        var keep = BooleanArray(xs.size) { true }
        var model = fit(xs, ys, us, vs, keep) ?: return AlignResult(Affine.translation(gx * 2f, gy * 2f), xs.size, 0f)
        var resid = 0f
        repeat(3) {
            val res = FloatArray(xs.size) { i ->
                val px = model.mapX(xs[i], ys[i]) - (xs[i] + us[i])
                val py = model.mapY(xs[i], ys[i]) - (ys[i] + vs[i])
                sqrt(px * px + py * py)
            }
            val med = res.filterIndexed { i, _ -> keep[i] }.sorted().let { it[it.size / 2] }
            val thr = max(0.4f, 2.5f * med)
            val nk = BooleanArray(xs.size) { res[it] <= thr }
            if (nk.count { it } < 6) return@repeat
            keep = nk
            model = fit(xs, ys, us, vs, keep) ?: model
            resid = med
        }
        // De media resolución a resolución completa: x_full = 2·x_half (+0.5 de centro, despreciable).
        val full = Affine(model.a, model.b, model.c * 2f, model.d, model.e, model.f * 2f)
        return AlignResult(full, keep.count { it }, resid * 2f)
    }

    /** Vector de una zona: búsqueda entera alrededor del global + 2 iteraciones de Lucas-Kanade. */
    private fun tileVector(r: ByteArray, c: ByteArray, w: Int, h: Int, x0: Int, y0: Int, gx: Int, gy: Int): Pair<Float, Float>? {
        // Zonas lisas (cielo, pared) no dan información fiable.
        var mean = 0f
        for (y in y0 until y0 + TILE step 2) for (x in x0 until x0 + TILE step 2) mean += r[y * w + x].toInt() and 0xFF
        mean /= (TILE / 2) * (TILE / 2)
        var varSum = 0f
        for (y in y0 until y0 + TILE step 2) for (x in x0 until x0 + TILE step 2) {
            val d = (r[y * w + x].toInt() and 0xFF) - mean; varSum += d * d
        }
        if (varSum / ((TILE / 2) * (TILE / 2)) < 20f) return null

        var best = Long.MAX_VALUE
        var bx = gx
        var by = gy
        for (dy in gy - SEARCH..gy + SEARCH) for (dx in gx - SEARCH..gx + SEARCH) {
            var s = 0L
            for (y in y0 until y0 + TILE step 2) {
                val rr = y * w
                val cr = (y + dy) * w + dx
                for (x in x0 until x0 + TILE step 2) s += abs((r[rr + x].toInt() and 0xFF) - (c[cr + x].toInt() and 0xFF))
            }
            if (s < best) { best = s; bx = dx; by = dy }
        }
        var ux = bx.toFloat()
        var uy = by.toFloat()
        fun rv(x: Int, y: Int) = (r[y * w + x].toInt() and 0xFF).toFloat()
        fun cv(x: Float, y: Float): Float {
            val xx = x.coerceIn(0f, w - 1.001f); val yy = y.coerceIn(0f, h - 1.001f)
            val ix = xx.toInt(); val iy = yy.toInt(); val fx = xx - ix; val fy = yy - iy
            fun v(a: Int, b: Int) = (c[b * w + a].toInt() and 0xFF).toFloat()
            return v(ix, iy) * (1 - fx) * (1 - fy) + v(ix + 1, iy) * fx * (1 - fy) + v(ix, iy + 1) * (1 - fx) * fy + v(ix + 1, iy + 1) * fx * fy
        }
        repeat(2) {
            var sxx = 0.0; var sxy = 0.0; var syy = 0.0; var sxt = 0.0; var syt = 0.0
            for (y in y0 + 1 until y0 + TILE - 1) for (x in x0 + 1 until x0 + TILE - 1) {
                val gxv = (rv(x + 1, y) - rv(x - 1, y)) / 2f
                val gyv = (rv(x, y + 1) - rv(x, y - 1)) / 2f
                val it = cv(x + ux, y + uy) - rv(x, y)
                sxx += gxv * gxv; sxy += gxv * gyv; syy += gyv * gyv; sxt += gxv * it; syt += gyv * it
            }
            val det = sxx * syy - sxy * sxy
            if (det < 1e-3) return null
            ux += ((-(syy * sxt) + sxy * syt) / det).toFloat().coerceIn(-1f, 1f)
            uy += ((sxy * sxt - sxx * syt) / det).toFloat().coerceIn(-1f, 1f)
        }
        return ux to uy
    }

    /** Ajuste afín: (x+u, y+v) = A·(x, y). */
    private fun fit(xs: List<Float>, ys: List<Float>, us: List<Float>, vs: List<Float>, keep: BooleanArray): Affine? {
        // Centrado para estabilidad numérica.
        var mx = 0.0; var my = 0.0; var n = 0
        for (i in xs.indices) if (keep[i]) { mx += xs[i]; my += ys[i]; n++ }
        if (n < 6) return null
        mx /= n; my /= n
        var sxx = 0.0; var sxy = 0.0; var syy = 0.0
        var sxX = 0.0; var syX = 0.0; var sX = 0.0
        var sxY = 0.0; var syY = 0.0; var sY = 0.0
        for (i in xs.indices) {
            if (!keep[i]) continue
            val x = xs[i] - mx; val y = ys[i] - my
            val tx = xs[i] + us[i] - mx; val tyv = ys[i] + vs[i] - my
            sxx += x * x; sxy += x * y; syy += y * y
            sxX += x * tx; syX += y * tx; sX += tx
            sxY += x * tyv; syY += y * tyv; sY += tyv
        }
        val det = sxx * syy - sxy * sxy
        if (abs(det) < 1e-6) return null
        val a = (sxX * syy - syX * sxy) / det
        val b = (syX * sxx - sxX * sxy) / det
        val d = (sxY * syy - syY * sxy) / det
        val e = (syY * sxx - sxY * sxy) / det
        val cC = sX / n
        val fC = sY / n
        // Deshacer el centrado: X' = a(x-mx) + b(y-my) + cC + mx
        val c = cC + mx - a * mx - b * my
        val f = fC + my - d * mx - e * my
        return Affine(a.toFloat(), b.toFloat(), c.toFloat(), d.toFloat(), e.toFloat(), f.toFloat())
    }

    private fun half(l: ByteArray, w: Int, h: Int): ByteArray {
        val w2 = w / 2
        val h2 = h / 2
        val o = ByteArray(w2 * h2)
        parallelRows(h2) { ya, yb ->
            for (y in ya until yb) for (x in 0 until w2) {
                val i = (y * 2) * w + x * 2
                o[y * w2 + x] = (((l[i].toInt() and 0xFF) + (l[i + 1].toInt() and 0xFF) + (l[i + w].toInt() and 0xFF) + (l[i + w + 1].toInt() and 0xFF)) shr 2).toByte()
            }
        }
        return o
    }

    /**
     * Reproyecta la toma [px] sobre la referencia con la transformación (bilineal, subpíxel).
     * Los píxeles sin datos (fuera de la toma) quedan con alfa 0 para que la mezcla los ignore.
     */
    fun warp(px: IntArray, w: Int, h: Int, t: Affine): IntArray {
        val out = IntArray(w * h)
        parallelRows(h) { ya, yb ->
            for (y in ya until yb) for (x in 0 until w) {
                val sx = t.mapX(x.toFloat(), y.toFloat())
                val sy = t.mapY(x.toFloat(), y.toFloat())
                if (sx < 0f || sy < 0f || sx > w - 1.001f || sy > h - 1.001f) { out[y * w + x] = 0; continue }
                val ix = sx.toInt(); val iy = sy.toInt()
                val fx = ((sx - ix) * 256f).toInt(); val fy = ((sy - iy) * 256f).toInt()
                val w00 = (256 - fx) * (256 - fy); val w10 = fx * (256 - fy); val w01 = (256 - fx) * fy; val w11 = fx * fy
                val p00 = px[iy * w + ix]; val p10 = px[iy * w + ix + 1]
                val p01 = px[(iy + 1) * w + ix]; val p11 = px[(iy + 1) * w + ix + 1]
                val rr = (((p00 shr 16) and 0xFF) * w00 + ((p10 shr 16) and 0xFF) * w10 + ((p01 shr 16) and 0xFF) * w01 + ((p11 shr 16) and 0xFF) * w11 + 32768) shr 16
                val gg = (((p00 shr 8) and 0xFF) * w00 + ((p10 shr 8) and 0xFF) * w10 + ((p01 shr 8) and 0xFF) * w01 + ((p11 shr 8) and 0xFF) * w11 + 32768) shr 16
                val bb = ((p00 and 0xFF) * w00 + (p10 and 0xFF) * w10 + (p01 and 0xFF) * w01 + (p11 and 0xFF) * w11 + 32768) shr 16
                val o = (0xFF shl 24) or (min(255, rr) shl 16) or (min(255, gg) shl 8) or min(255, bb)
                out[y * w + x] = o
            }
        }
        return out
    }
}

/**
 * Mezcla robusta: cada zona de 4×4 px de cada toma pesa según cuánto coincide con la referencia
 * (peso suave, calibrado con el ruido medido en la propia ráfaga). Lo que se movió pesa ~0
 * (sin fantasmas), lo que coincide pesa todo (máxima reducción de ruido).
 */
class RobustMerger(private val w: Int, private val h: Int, frames: Int) {
    private val r = ShortArray(w * h)
    private val g = ShortArray(w * h)
    private val b = ShortArray(w * h)
    private val ws = ByteArray(w * h) // suma de pesos (≤ 255)
    private val wMax = (minOf(32767 / (255 * frames.coerceAtLeast(1)), 255 / frames.coerceAtLeast(1))).coerceIn(1, 16)
    private var refLuma: IntArray? = null
    private var sigma = -1f
    var count = 0
        private set

    private val bw = (w + B - 1) / B
    private val bh = (h + B - 1) / B

    /** Luminancia media por zona de 4×4 (ignora píxeles sin datos). */
    private fun blockLuma(px: IntArray): IntArray {
        val sum = IntArray(bw * bh)
        val n = IntArray(bw * bh)
        // Filas alineadas a los bloques: cada hilo escribe en sus propios bloques.
        parallelRows(h, align = B) { ya, yb ->
            for (y in ya until yb) {
                val br = (y / B) * bw
                for (x in 0 until w) {
                    val p = px[y * w + x]
                    if ((p ushr 24) == 0) continue
                    val bi = br + x / B
                    sum[bi] += (((p shr 16) and 0xFF) * 2 + ((p shr 8) and 0xFF) * 5 + (p and 0xFF)) shr 3
                    n[bi]++
                }
            }
        }
        return IntArray(bw * bh) { if (n[it] == 0) -1 else sum[it] / n[it] }
    }

    fun add(px: IntArray) {
        if (count == 0) {
            refLuma = blockLuma(px)
            parallelRows(h) { ya, yb ->
                for (i in ya * w until yb * w) {
                    val p = px[i]
                    r[i] = (((p shr 16) and 0xFF) * wMax).toShort()
                    g[i] = (((p shr 8) and 0xFF) * wMax).toShort()
                    b[i] = ((p and 0xFF) * wMax).toShort()
                    ws[i] = wMax.toByte()
                }
            }
            count = 1
            return
        }
        val ref = refLuma!!
        val cur = blockLuma(px)
        // Ruido: se mide con la primera toma comparada (mediana de diferencias por zona).
        if (sigma < 0f) {
            val diffs = ArrayList<Int>(bw * bh)
            for (i in ref.indices) if (ref[i] >= 0 && cur[i] >= 0) diffs += abs(cur[i] - ref[i])
            diffs.sort()
            val mad = if (diffs.isEmpty()) 2 else diffs[diffs.size / 2]
            sigma = max(3f, 2.5f * 1.4826f * mad)
        }
        val weight = IntArray(bw * bh) { i ->
            if (ref[i] < 0 || cur[i] < 0) 0
            else {
                val d = (cur[i] - ref[i]) / sigma
                if (d * d > 9f) 0 else (wMax * kotlin.math.exp(-(d * d).toDouble()) + 0.5).toInt()
            }
        }
        parallelRows(h) { ya, yb ->
            for (y in ya until yb) {
                val br = (y / B) * bw
                for (x in 0 until w) {
                    val i = y * w + x
                    val p = px[i]
                    if ((p ushr 24) == 0) continue
                    val wt = weight[br + x / B]
                    if (wt == 0) continue
                    r[i] = (r[i] + ((p shr 16) and 0xFF) * wt).toShort()
                    g[i] = (g[i] + ((p shr 8) and 0xFF) * wt).toShort()
                    b[i] = (b[i] + (p and 0xFF) * wt).toShort()
                    ws[i] = ((ws[i].toInt() and 0xFF) + wt).toByte()
                }
            }
        }
        count++
    }

    /** La mezcla sin redondear (sRGB con decimales): para el mapeo de tonos local de noche v2. */
    fun source(): RgbSource = RgbSource { i, o ->
        val s = (ws[i].toInt() and 0xFF).coerceAtLeast(1).toFloat()
        o[0] = (r[i].toInt() and 0xFFFF) / s; o[1] = (g[i].toInt() and 0xFFFF) / s; o[2] = (b[i].toInt() and 0xFFFF) / s
    }

    fun result(shadowLift: Float = 0f): IntArray {
        val lut = IntArray(256) { v ->
            val f = v / 255f
            ((f + shadowLift * f * (1f - f) * (1f - f)) * 255f + 0.5f).toInt().coerceIn(0, 255)
        }
        val out = IntArray(w * h)
        parallelRows(h) { ya, yb ->
            for (i in ya * w until yb * w) {
                val s = (ws[i].toInt() and 0xFF).coerceAtLeast(1)
                val half = s / 2
                fun ch(v: Short) = lut[(((v.toInt() and 0xFFFF) + half) / s).coerceIn(0, 255)]
                out[i] = (0xFF shl 24) or (ch(r[i]) shl 16) or (ch(g[i]) shl 8) or ch(b[i])
            }
        }
        return out
    }

    companion object {
        private const val B = 4
    }
}
