package com.iacamara.app.processing

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.math.tanh

/** Píxel en sRGB 0..255 con decimales (p. ej. la media de varias tomas, sin redondear). */
fun interface RgbSource {
    fun get(i: Int, out: FloatArray)

    companion object {
        fun of(px: IntArray) = RgbSource { i, o ->
            val p = px[i]
            o[0] = ((p shr 16) and 0xFF).toFloat(); o[1] = ((p shr 8) and 0xFF).toFloat(); o[2] = (p and 0xFF).toFloat()
        }
    }
}

/** Conversión sRGB ↔ luz lineal con tablas (rápido). */
internal object Lin {
    /** sRGB 0..255 (en pasos de 1/16) → lineal 0..1. */
    private val dec = FloatArray(255 * 16 + 2) { (it / (255f * 16f)).toDouble().coerceAtMost(1.0).pow(2.2).toFloat() }
    private val enc = IntArray(8193) { ((it / 8192.0).pow(1 / 2.2) * 255 + 0.5).toInt().coerceIn(0, 255) }
    fun toLin(v: Float): Float = dec[(v * 16f).toInt().coerceIn(0, dec.size - 1)]
    fun encode(lin: Float): Int = enc[(lin * 8192f).toInt().coerceIn(0, 8192)]
}

/**
 * Mapeo de tonos LOCAL (noche v2, astro): aclara las zonas oscuras y contiene las luces fuertes
 * según la luz de cada ZONA, no de cada píxel, así el contraste fino (texturas, detalles) se
 * conserva y la foto no queda "plana" como con una curva global. La ganancia se calcula a baja
 * resolución con un filtro guiado (respeta los bordes: sin halos alrededor de farolas o
 * ventanas) y se aplica a resolución completa.
 */
object LocalTone {
    class Params(
        /** Cuánto se acercan las zonas oscuras al gris medio (0 = nada, 1 = todo). */
        val shadowStrength: Float = 0.45f,
        /** Máximo aclarado en pasos (stops). Limitado por el ruido. */
        val maxLiftStops: Float = 2f,
        /** Compresión de las zonas muy brillantes (0 = nada). */
        val highlightCompress: Float = 0.35f,
        /** Gris medio de referencia (luz lineal). */
        val mid: Float = 0.18f,
    )

    private const val LOG_FLOOR = 1e-4f

    fun apply(px: IntArray, w: Int, h: Int, p: Params = Params()) = apply(RgbSource.of(px), w, h, p)

    fun apply(src: RgbSource, w: Int, h: Int, p: Params = Params()): IntArray {
        // 1) Luz media por zona (en pasos, log2) a baja resolución.
        val f = max(1, (max(w, h) + 383) / 384)
        val gw = (w + f - 1) / f
        val gh = (h + f - 1) / f
        val sum = FloatArray(gw * gh)
        val cnt = IntArray(gw * gh)
        val rgb = FloatArray(3)
        for (y in 0 until h step 2) for (x in 0 until w step 2) {
            src.get(y * w + x, rgb)
            val c = (y / f) * gw + x / f
            sum[c] += luma(rgb); cnt[c]++
        }
        val logL = FloatArray(gw * gh) { log2(max(LOG_FLOOR, sum[it] / max(1, cnt[it]))) }
        val r = max(2, max(gw, gh) / 24)
        // 2) Base suave que respeta bordes (luz de la zona).
        val base = guided(logL, logL, gw, gh, r, 0.25f).let { (a, b) -> FloatArray(gw * gh) { a[it] * logL[it] + b[it] } }
        // 3) Ganancia deseada por zona (en pasos).
        val midLog = log2(p.mid)
        val hiLog = log2(0.6f)
        val gain = FloatArray(gw * gh) { i ->
            val bb = base[i]
            when {
                bb < midLog -> min(p.maxLiftStops, (midLog - bb) * p.shadowStrength)
                bb > hiLog -> -(bb - hiLog) * p.highlightCompress
                else -> 0f
            }
        }
        // 4) Coeficientes locales (ganancia ≈ A·log(luz) + B) que siguen los bordes de la foto.
        val (ca, cb) = guided(gain, logL, gw, gh, r, 0.25f)
        val mA = FloatMap(gw, gh, ca)
        val mB = FloatMap(gw, gh, cb)
        // 5) Aplicar a resolución completa.
        val out = IntArray(w * h)
        parallelRows(h) { ya, yb ->
            val c = FloatArray(3)
            for (y in ya until yb) for (x in 0 until w) {
                val i = y * w + x
                src.get(i, c)
                val lr = Lin.toLin(c[0]); val lg = Lin.toLin(c[1]); val lb = Lin.toLin(c[2])
                val yy = 0.2126f * lr + 0.7152f * lg + 0.0722f * lb
                val u = (x + 0.5f) / w; val t = (y + 0.5f) / h
                val stops = (mA.sample(u, t) * log2(max(LOG_FLOOR, yy)) + mB.sample(u, t)).coerceIn(-1.5f, p.maxLiftStops)
                val g = 2f.pow(stops)
                out[i] = (0xFF shl 24) or (Lin.encode(soft(lr * g)) shl 16) or (Lin.encode(soft(lg * g)) shl 8) or Lin.encode(soft(lb * g))
            }
        }
        return out
    }

    /** Hombro suave: las luces no se recortan de golpe. */
    private fun soft(v: Float) = if (v <= 0.8f) v else 0.8f + 0.2f * tanh((v - 0.8f) / 0.2f)

    private fun luma(c: FloatArray) = 0.2126f * Lin.toLin(c[0]) + 0.7152f * Lin.toLin(c[1]) + 0.0722f * Lin.toLin(c[2])

    private fun log2(v: Float) = (ln(v.toDouble()) / ln(2.0)).toFloat()

    /** Filtro guiado sin recortar: devuelve los coeficientes medios (A, B) de q = A·I + B. */
    internal fun guided(p: FloatArray, guide: FloatArray, w: Int, h: Int, r: Int, eps: Float): Pair<FloatArray, FloatArray> {
        val n = w * h
        val mI = MaskRefine.box(guide, w, h, r)
        val mP = MaskRefine.box(p, w, h, r)
        val mIp = MaskRefine.box(FloatArray(n) { guide[it] * p[it] }, w, h, r)
        val mII = MaskRefine.box(FloatArray(n) { guide[it] * guide[it] }, w, h, r)
        val a = FloatArray(n) { (mIp[it] - mI[it] * mP[it]) / (mII[it] - mI[it] * mI[it] + eps) }
        val b = FloatArray(n) { mP[it] - a[it] * mI[it] }
        return MaskRefine.box(a, w, h, r) to MaskRefine.box(b, w, h, r)
    }
}

/**
 * Enfoque final (máscara de desenfoque sobre la luminancia, sin tocar el color). En súper
 * resolución, la mezcla de varias tomas baja tanto el ruido que se puede recuperar más detalle
 * sin que aparezca grano.
 */
object Sharpen {
    fun apply(px: IntArray, w: Int, h: Int, amount: Float, threshold: Int = 2): IntArray {
        if (amount <= 0f) return px.copyOf()
        val l = FloatArray(w * h) { val p = px[it]; ((((p shr 16) and 0xFF) * 2 + ((p shr 8) and 0xFF) * 5 + (p and 0xFF)) shr 3).toFloat() }
        // Aproximación gaussiana (σ ≈ 1 px) con dos cajas de 3×3.
        val blur = MaskRefine.box(MaskRefine.box(l, w, h, 1), w, h, 1)
        val out = IntArray(w * h)
        parallelRows(h) { ya, yb ->
            for (i in ya * w until yb * w) {
                var d = l[i] - blur[i]
                // Umbral suave: las diferencias mínimas (ruido) casi no se realzan.
                val ad = abs(d)
                d *= if (ad <= threshold) ad / threshold * 0.3f else 1f
                val k = (d * amount).toInt()
                val p = px[i]
                fun ch(v: Int) = (v + k).coerceIn(0, 255)
                out[i] = (0xFF shl 24) or (ch((p shr 16) and 0xFF) shl 16) or (ch((p shr 8) and 0xFF) shl 8) or ch(p and 0xFF)
            }
        }
        return out
    }
}

/**
 * Exposición larga simulada: varias tomas alineadas (corrige el pulso) se promedian en luz
 * lineal (el agua se vuelve seda, la gente que pasa desaparece) y las luces que se mueven
 * (faros, estrellas) se conservan como estelas continuas.
 */
class LongExposureMerger(private val w: Int, private val h: Int) {
    private val sr = IntArray(w * h)
    private val sg = IntArray(w * h)
    private val sb = IntArray(w * h)
    private val n = ShortArray(w * h)
    private val maxPx = IntArray(w * h)
    private val maxY = ShortArray(w * h)
    var count = 0
        private set

    fun add(px: IntArray) {
        parallelRows(h) { ya, yb ->
            for (i in ya * w until yb * w) {
                val p = px[i]
                if ((p ushr 24) == 0) continue // fuera de la toma (tras alinear)
                val r = LIN12[(p shr 16) and 0xFF]; val g = LIN12[(p shr 8) and 0xFF]; val b = LIN12[p and 0xFF]
                sr[i] += r; sg[i] += g; sb[i] += b; n[i]++
                val y = (r * 54 + g * 183 + b * 19) shr 8
                if (y > maxY[i]) { maxY[i] = y.toShort(); maxPx[i] = p }
            }
        }
        count++
    }

    /** @param trails cuánto se conservan las estelas de luz (0 = promedio puro). */
    fun result(trails: Float = 0.85f): IntArray {
        // 1) Dónde hay estela: algo brillante pasó por aquí en pocas tomas (el máximo supera mucho a la media).
        val tw = FloatArray(w * h)
        parallelRows(h) { ya, yb ->
            for (i in ya * w until yb * w) {
                val c = n[i].toInt().coerceAtLeast(1)
                val my = (sr[i] * 0.2126f + sg[i] * 0.7152f + sb[i] * 0.0722f) / c / 4095f
                val xy = maxY[i] / 4095f
                val rel = if (xy > 1e-4f) (xy - my) / xy else 0f
                tw[i] = smooth(rel, 0.35f, 0.75f) * smooth(xy, 0.25f, 0.6f)
            }
        }
        // 2) Entre una toma y la siguiente la cámara no ve (tiempo de lectura): la estela queda
        //    cortada. Se rellenan los huecos cortos que tienen estela a ambos lados.
        val gap = max(8, w / 150)
        val fillT = FloatArray(w * h)
        val fillPx = IntArray(w * h)
        val dirs = arrayOf(intArrayOf(1, 0), intArrayOf(0, 1), intArrayOf(1, 1), intArrayOf(1, -1))
        // Solo cerca de una estela (la gran mayoría de la foto se salta: rápido).
        val near = MaskRefine.box(FloatArray(w * h) { if (tw[it] >= 0.5f) 1f else 0f }, w, h, gap)
        parallelRows(h) { ya, yb ->
            for (y in ya until yb) for (x in 0 until w) {
                val i = y * w + x
                if (tw[i] >= 0.5f || near[i] < 1e-4f) continue
                for (d in dirs) {
                    var kf = 0; var kb = 0
                    for (k in 1..gap) { val xx = x + d[0] * k; val yy = y + d[1] * k; if (xx !in 0 until w || yy !in 0 until h) break; if (tw[yy * w + xx] >= 0.5f) { kf = k; break } }
                    if (kf == 0) continue
                    for (k in 1..gap - kf) { val xx = x - d[0] * k; val yy = y - d[1] * k; if (xx !in 0 until w || yy !in 0 until h) break; if (tw[yy * w + xx] >= 0.5f) { kb = k; break } }
                    if (kb == 0) continue
                    val a = (y + d[1] * kf) * w + x + d[0] * kf; val b = (y - d[1] * kb) * w + x - d[0] * kb
                    val t = min(tw[a], tw[b])
                    if (t > fillT[i]) {
                        fillT[i] = t
                        val pa = maxPx[a]; val pb = maxPx[b]
                        fillPx[i] = (0xFF shl 24) or (((((pa shr 16) and 0xFF) + ((pb shr 16) and 0xFF)) / 2) shl 16) or
                            (((((pa shr 8) and 0xFF) + ((pb shr 8) and 0xFF)) / 2) shl 8) or (((pa and 0xFF) + (pb and 0xFF)) / 2)
                    }
                }
            }
        }
        val out = IntArray(w * h)
        parallelRows(h) { ya, yb ->
            for (i in ya * w until yb * w) {
                val c = n[i].toInt().coerceAtLeast(1)
                val mr = sr[i].toFloat() / c / 4095f; val mg = sg[i].toFloat() / c / 4095f; val mb = sb[i].toFloat() / c / 4095f
                val useFill = fillT[i] > tw[i]
                val p = if (useFill) fillPx[i] else maxPx[i]
                val xr = LIN12[(p shr 16) and 0xFF] / 4095f; val xg = LIN12[(p shr 8) and 0xFF] / 4095f; val xb = LIN12[p and 0xFF] / 4095f
                val t = trails * (if (useFill) fillT[i] else tw[i])
                fun mix(m: Float, x: Float) = Lin.encode(m + t * (x - m))
                out[i] = (0xFF shl 24) or (mix(mr, xr) shl 16) or (mix(mg, xg) shl 8) or mix(mb, xb)
            }
        }
        return out
    }

    private fun smooth(v: Float, a: Float, b: Float): Float { val t = ((v - a) / (b - a)).coerceIn(0f, 1f); return t * t * (3 - 2 * t) }

    companion object {
        private val LIN12 = IntArray(256) { ((it / 255.0).pow(2.2) * 4095 + 0.5).toInt() }
    }
}

/**
 * Astrofotografía: las estrellas se mueven (la Tierra gira) mientras el paisaje queda quieto.
 *  • Se detectan las estrellas de cada toma y se alinea el cielo con ellas (giro + desplazamiento).
 *  • El paisaje se combina sin mover (está en trípode).
 *  • Una línea de horizonte separa ambos, con una transición suave.
 *  • Los píxeles "calientes" del sensor (puntos fijos brillantes) se eliminan.
 */
object Astro {
    class Star(val x: Float, val y: Float, val flux: Float)

    /** Estrellas: máximos locales claramente por encima del fondo del cielo (en [luma], por debajo de [maxY] no). */
    fun detectStars(luma: ByteArray, w: Int, h: Int, maxY: Int = h, limit: Int = 250): List<Star> {
        // Fondo por bloques de 32×32 y ruido global (desviación absoluta mediana).
        val B = 32
        val bw = (w + B - 1) / B; val bh = (h + B - 1) / B
        val bg = FloatArray(bw * bh)
        val bn = IntArray(bw * bh)
        for (y in 0 until h step 2) for (x in 0 until w step 2) { val c = (y / B) * bw + x / B; bg[c] += (luma[y * w + x].toInt() and 0xFF); bn[c]++ }
        for (i in bg.indices) bg[i] /= max(1, bn[i])
        val dev = ArrayList<Int>()
        for (y in 0 until h step 7) for (x in 0 until w step 7) dev += abs((luma[y * w + x].toInt() and 0xFF) - bg[(y / B) * bw + x / B].toInt())
        dev.sort()
        val sigma = max(1f, 1.4826f * (dev.getOrNull(dev.size / 2) ?: 1))
        val thr = max(6f, 5f * sigma)
        val stars = ArrayList<Star>()
        fun v(x: Int, y: Int) = luma[y * w + x].toInt() and 0xFF
        for (y in 2 until min(h - 2, maxY)) for (x in 2 until w - 2) {
            val c = v(x, y)
            val b0 = bg[(y / B) * bw + x / B]
            if (c - b0 < thr) continue
            var isMax = true
            loop@ for (dy in -1..1) for (dx in -1..1) {
                if (dx == 0 && dy == 0) continue
                val q = v(x + dx, y + dy)
                if (q > c || (q == c && (dy < 0 || (dy == 0 && dx < 0)))) { isMax = false; break@loop }
            }
            if (!isMax) continue
            // Una estrella real ocupa varios píxeles (el lente la difumina); un píxel caliente, uno solo.
            var neigh = 0
            for (dy in -1..1) for (dx in -1..1) if ((dx != 0 || dy != 0) && v(x + dx, y + dy) - b0 > thr * 0.35f) neigh++
            if (neigh < 2) continue
            // Centro con precisión de subpíxel (centroide 3×3 sobre el fondo).
            var sw = 0f; var sx = 0f; var sy = 0f
            for (dy in -1..1) for (dx in -1..1) { val q = max(0f, v(x + dx, y + dy) - b0); sw += q; sx += q * dx; sy += q * dy }
            stars += Star(x + sx / sw, y + sy / sw, sw)
        }
        return stars.sortedByDescending { it.flux }.take(limit)
    }

    /**
     * Transformación (coordenadas de la referencia → de la toma) que alinea las estrellas de
     * [cur] con las de [ref]: votación de desplazamientos + ajuste de giro por mínimos cuadrados.
     * null si no hay suficientes estrellas en común.
     */
    fun matchStars(ref: List<Star>, cur: List<Star>, maxShift: Int = 80): Affine? {
        if (ref.size < 4 || cur.size < 4) return null
        val a = ref.take(80); val b = cur.take(80)
        val size = 2 * maxShift + 1
        val votes = IntArray(size * size)
        for (s in a) for (q in b) {
            val dx = (q.x - s.x).toInt() + maxShift; val dy = (q.y - s.y).toInt() + maxShift
            if (dx in 0 until size && dy in 0 until size) votes[dy * size + dx]++
        }
        // Pico con su vecindad 3×3 (el desplazamiento real cae entre dos casillas).
        var best = -1; var bestV = 0
        for (y in 1 until size - 1) for (x in 1 until size - 1) {
            var v = 0
            for (dy in -1..1) for (dx in -1..1) v += votes[(y + dy) * size + x + dx]
            if (v > bestV) { bestV = v; best = y * size + x }
        }
        if (best < 0 || bestV < 3) return null
        val tx = (best % size - maxShift).toFloat() + 0.5f
        val ty = (best / size - maxShift).toFloat() + 0.5f
        // Parejas: la estrella más cercana a donde la pone el desplazamiento.
        val px = ArrayList<FloatArray>()
        for (s in ref) {
            var bd = 3.5f * 3.5f; var m: Star? = null
            for (q in cur) { val ex = q.x - s.x - tx; val ey = q.y - s.y - ty; val d = ex * ex + ey * ey; if (d < bd) { bd = d; m = q } }
            if (m != null) px += floatArrayOf(s.x, s.y, m.x, m.y)
        }
        if (px.size < 3) return null
        var keep = BooleanArray(px.size) { true }
        var t: Affine = Affine.translation(tx, ty)
        repeat(3) {
            t = fitSimilarity(px, keep) ?: return@repeat
            val res = px.map { e -> val ex = t.mapX(e[0], e[1]) - e[2]; val ey = t.mapY(e[0], e[1]) - e[3]; sqrt(ex * ex + ey * ey) }
            keep = BooleanArray(px.size) { res[it] < 1.2f }
            if (keep.count { it } < 3) return null
        }
        return t
    }

    /** Ajuste de giro + desplazamiento (+ escala mínima) por mínimos cuadrados: x' = a·x − b·y + c, y' = b·x + a·y + d. */
    private fun fitSimilarity(p: List<FloatArray>, keep: BooleanArray): Affine? {
        var n = 0; var mx = 0.0; var my = 0.0; var mu = 0.0; var mv = 0.0
        for (i in p.indices) if (keep[i]) { n++; mx += p[i][0]; my += p[i][1]; mu += p[i][2]; mv += p[i][3] }
        if (n < 2) return null
        mx /= n; my /= n; mu /= n; mv /= n
        var sxx = 0.0; var sa = 0.0; var sb = 0.0
        for (i in p.indices) if (keep[i]) {
            val x = p[i][0] - mx; val y = p[i][1] - my; val u = p[i][2] - mu; val v = p[i][3] - mv
            sxx += x * x + y * y; sa += x * u + y * v; sb += x * v - y * u
        }
        if (sxx < 1e-6) return Affine.translation((mu - mx).toFloat(), (mv - my).toFloat())
        val a = sa / sxx; val b = sb / sxx
        val c = mu - (a * mx - b * my); val d = mv - (b * mx + a * my)
        return Affine(a.toFloat(), (-b).toFloat(), c.toFloat(), b.toFloat(), a.toFloat(), d.toFloat())
    }

    /**
     * Horizonte (fila por columna): por encima, cielo oscuro y liso (solo estrellas); por debajo,
     * el paisaje (con textura o luz). Si no hay paisaje, todo es cielo (devuelve h).
     */
    fun skyline(luma: ByteArray, w: Int, h: Int): IntArray {
        val f = max(2, max(w, h) / 128)
        val gw = w / f; val gh = h / f
        if (gw < 4 || gh < 4) return IntArray(w) { h }
        // Reducción por percentil 30 de cada celda: las estrellas (pocos píxeles) desaparecen.
        val g = FloatArray(gw * gh)
        val hist = IntArray(256)
        val step = max(1, f / 6)
        for (y in 0 until gh) for (x in 0 until gw) {
            java.util.Arrays.fill(hist, 0)
            var n = 0
            for (oy in 0 until f step step) for (ox in 0 until f step step) { hist[luma[(y * f + oy) * w + x * f + ox].toInt() and 0xFF]++; n++ }
            var acc = 0; var v = 0
            while (v < 255) { acc += hist[v]; if (acc * 10 >= n * 3) break; v++ }
            g[y * gw + x] = v.toFloat()
        }
        // Brillo del cielo: la parte superior.
        val top = ArrayList<Float>()
        for (y in 0 until max(1, gh / 8)) for (x in 0 until gw) top += g[y * gw + x]
        top.sort()
        val skyLevel = top[top.size / 2]
        val skySpread = max(3f, top[(top.size * 0.9).toInt().coerceAtMost(top.size - 1)] - skyLevel)
        val rows = IntArray(gw) { gh }
        for (x in 0 until gw) {
            var run = 0
            for (y in 1 until gh) {
                val d = abs(g[y * gw + x] - skyLevel)
                val grad = abs(g[y * gw + x] - g[(y - 1) * gw + x])
                if (d > 2.5f * skySpread || grad > 2f * skySpread) { run++; if (run >= 3) { rows[x] = y - 2; break } } else run = 0
            }
        }
        // Suavizado: mediana de 9 columnas (un árbol aislado no rompe el horizonte).
        val sm = IntArray(gw) { x -> (max(0, x - 4)..min(gw - 1, x + 4)).map { rows[it] }.sorted().let { it[it.size / 2] } }
        return IntArray(w) { x -> if (sm[min(gw - 1, x / f)] >= gh) h else sm[min(gw - 1, x / f)] * f }
    }

    /**
     * Reproyección bicúbica (Catmull-Rom): las estrellas son puntos de 2–3 px y la bilineal les
     * quita brillo al moverlas fracciones de píxel; la bicúbica las conserva nítidas.
     */
    fun warpCubic(px: IntArray, w: Int, h: Int, t: Affine): IntArray {
        val out = IntArray(w * h)
        parallelRows(h) { ya, yb ->
            val wx = FloatArray(4); val wy = FloatArray(4)
            for (y in ya until yb) for (x in 0 until w) {
                val sx = t.mapX(x.toFloat(), y.toFloat()); val sy = t.mapY(x.toFloat(), y.toFloat())
                if (sx < 1f || sy < 1f || sx > w - 3f || sy > h - 3f) { out[y * w + x] = 0; continue }
                val ix = sx.toInt(); val iy = sy.toInt()
                cr(sx - ix, wx); cr(sy - iy, wy)
                var r = 0f; var g = 0f; var b = 0f
                for (j in 0..3) {
                    val row = (iy - 1 + j) * w + ix - 1
                    for (k in 0..3) {
                        val q = px[row + k]; val wt = wx[k] * wy[j]
                        r += ((q shr 16) and 0xFF) * wt; g += ((q shr 8) and 0xFF) * wt; b += (q and 0xFF) * wt
                    }
                }
                out[y * w + x] = (0xFF shl 24) or ((r + 0.5f).toInt().coerceIn(0, 255) shl 16) or ((g + 0.5f).toInt().coerceIn(0, 255) shl 8) or (b + 0.5f).toInt().coerceIn(0, 255)
            }
        }
        return out
    }

    private fun cr(f: Float, o: FloatArray) {
        val f2 = f * f; val f3 = f2 * f
        o[0] = -0.5f * f3 + f2 - 0.5f * f
        o[1] = 1.5f * f3 - 2.5f * f2 + 1f
        o[2] = -1.5f * f3 + 2f * f2 + 0.5f * f
        o[3] = 0.5f * f3 - 0.5f * f2
    }

    /** Elimina píxeles calientes: un solo píxel mucho más brillante que todos sus vecinos. */
    fun removeHotPixels(px: IntArray, w: Int, h: Int, margin: Int = 40): Int {
        var fixed = 0
        for (y in 1 until h - 1) for (x in 1 until w - 1) {
            val i = y * w + x
            val c = lum(px[i])
            var mx = 0
            for (dy in -1..1) for (dx in -1..1) if (dx != 0 || dy != 0) mx = max(mx, lum(px[i + dy * w + dx]))
            if (c > mx + margin) {
                // Media de los 4 vecinos directos.
                var r = 0; var g = 0; var b = 0
                for (q in intArrayOf(i - 1, i + 1, i - w, i + w)) { val p = px[q]; r += (p shr 16) and 0xFF; g += (p shr 8) and 0xFF; b += p and 0xFF }
                px[i] = (0xFF shl 24) or ((r / 4) shl 16) or ((g / 4) shl 8) or (b / 4)
                fixed++
            }
        }
        return fixed
    }

    private fun lum(p: Int) = (((p shr 16) and 0xFF) * 2 + ((p shr 8) and 0xFF) * 5 + (p and 0xFF)) shr 3

    /** Une cielo (alineado a las estrellas) y paisaje (sin mover) con una transición suave. */
    fun composite(sky: IntArray, ground: IntArray, w: Int, h: Int, line: IntArray, feather: Int = max(4, h / 100)): IntArray {
        val out = IntArray(w * h)
        parallelRows(h) { ya, yb ->
            for (y in ya until yb) for (x in 0 until w) {
                val i = y * w + x
                val t = ((y - line[x] + feather / 2f) / feather).coerceIn(0f, 1f) // 0 = cielo, 1 = paisaje
                val s = sky[i]
                val g = ground[i]
                if ((s ushr 24) == 0 || t >= 1f) { out[i] = g or (0xFF shl 24); continue }
                if (t <= 0f) { out[i] = s; continue }
                fun ch(sh: Int) = (((s shr sh) and 0xFF) * (1 - t) + ((g shr sh) and 0xFF) * t + 0.5f).toInt()
                out[i] = (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
            }
        }
        return out
    }

    /** Oscurece un poco el fondo del cielo (contaminación lumínica) para que resalten las estrellas. */
    fun darkenSkyBackground(px: IntArray, w: Int, h: Int, line: IntArray, amount: Float = 0.5f) {
        val vals = ArrayList<Int>()
        for (y in 0 until h step 9) for (x in 0 until w step 9) if (y < line[x]) vals += lum(px[y * w + x])
        if (vals.size < 50) return
        vals.sort()
        val bgLevel = vals[vals.size / 2] * amount
        val lut = IntArray(256) { v -> if (v <= bgLevel) (v * 0.5f).toInt() else (bgLevel * 0.5f + (v - bgLevel) * (255 - bgLevel * 0.5f) / (255 - bgLevel)).toInt().coerceIn(0, 255) }
        for (y in 0 until h) for (x in 0 until w) {
            if (y >= line[x]) continue
            val i = y * w + x; val p = px[i]
            px[i] = (0xFF shl 24) or (lut[(p shr 16) and 0xFF] shl 16) or (lut[(p shr 8) and 0xFF] shl 8) or lut[p and 0xFF]
        }
    }

    /** Duración de la toma (ns), ISO y número de tomas para astro según lo que permite el sensor. */
    fun plan(maxShutterNs: Long, isoMax: Int): Triple<Long, Int, Int> {
        val shutter = min(maxShutterNs, 4_000_000_000L).coerceAtLeast(250_000_000L)
        val iso = min(isoMax, if (shutter >= 2_000_000_000L) 1600 else 3200)
        // ~30 s en total, entre 8 y 15 tomas.
        val n = (30_000_000_000L / shutter).toInt().coerceIn(8, 15)
        return Triple(shutter, iso, n)
    }
}

/**
 * Súper resolución por múltiples tomas: con zoom digital cada toma es un recorte ampliado;
 * el pulso de la mano desplaza el sensor fracciones de píxel entre tomas, así cada una ve
 * detalles que las otras no. Se alinean con precisión de subpíxel, se mezclan (también baja el
 * ruido) y, como el ruido es menor, se puede enfocar más que una sola toma.
 */
object SuperRes {
    /** Enfoque según cuántas tomas se mezclaron y cuánto zoom digital hay. */
    fun sharpenAmount(frames: Int, digitalZoom: Float): Float {
        // Medido en la simulación: con 8 tomas y zoom 2× el óptimo está cerca de 1.9 (una toma: ~1).
        val z = (digitalZoom - 1f).coerceIn(0f, 1f)
        val k = (frames.coerceAtMost(8) - 1) / 7f
        return 0.6f + 1.3f * z * (0.4f + 0.6f * k)
    }
}
