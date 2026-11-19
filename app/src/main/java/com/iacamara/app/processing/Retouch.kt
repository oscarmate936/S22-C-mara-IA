package com.iacamara.app.processing

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tanh

/**
 * Enderezado y perspectiva automáticos: busca las líneas rectas de la foto (bordes de
 * edificios, postes, el horizonte, una mesa) con el tensor de estructura por zonas, mide cuánto
 * están inclinadas y, en las verticales, si convergen hacia arriba (teléfono inclinado hacia
 * arriba, edificios "cayéndose hacia atrás").
 */
object Straighten {
    /**
     * @param tiltDeg inclinación medida (grados, en coordenadas de imagen con y hacia abajo).
     * @param keystone convergencia de las verticales (por píxel); 0 = paralelas.
     * @param confidence 0..1: cuántas líneas claras la respaldan.
     */
    class Estimate(val tiltDeg: Float, val keystone: Float, val confidence: Float, val verticalLines: Int, val horizontalLines: Int) {
        val hasTilt get() = confidence >= 0.35f && abs(tiltDeg) >= 0.3f && abs(tiltDeg) <= MAX_TILT
        val hasKeystone get() = verticalLines >= 12 && abs(keystone) > 1e-5f
    }

    const val MAX_TILT = 10f
    private const val BLOCK = 8

    /** Líneas claras de una zona: ángulo (grados), peso y posición. */
    private class Seg(val angle: Float, val weight: Float, val x: Float, val y: Float)

    fun estimate(luma: ByteArray, w: Int, h: Int): Estimate {
        // Trabaja a ~800 px de lado mayor.
        val f = max(1, max(w, h) / 800)
        val gw = w / f; val gh = h / f
        val g = FloatArray(gw * gh)
        for (y in 0 until gh) for (x in 0 until gw) {
            var s = 0; for (oy in 0 until f) for (ox in 0 until f) s += luma[(y * f + oy) * w + x * f + ox].toInt() and 0xFF
            g[y * gw + x] = s.toFloat() / (f * f)
        }
        // Suavizado previo (σ ≈ 1.5 px): sin él, el escalonado de los píxeles en un borde casi
        // vertical sesga el ángulo medido hasta un 20 %.
        val gs = MaskRefine.box(MaskRefine.box(MaskRefine.box(g, gw, gh, 1), gw, gh, 1), gw, gh, 1)
        System.arraycopy(gs, 0, g, 0, g.size)
        val segs = ArrayList<Seg>()
        val bw = gw / BLOCK; val bh = gh / BLOCK
        for (by in 0 until bh) for (bx in 0 until bw) {
            var jxx = 0f; var jyy = 0f; var jxy = 0f
            for (y in by * BLOCK until (by + 1) * BLOCK) for (x in bx * BLOCK until (bx + 1) * BLOCK) {
                if (x < 1 || y < 1 || x >= gw - 1 || y >= gh - 1) continue
                val gx = (g[y * gw + x + 1] - g[y * gw + x - 1]) * 0.5f
                val gy = (g[(y + 1) * gw + x] - g[(y - 1) * gw + x]) * 0.5f
                jxx += gx * gx; jyy += gy * gy; jxy += gx * gy
            }
            val tr = jxx + jyy
            if (tr < 20f * BLOCK * BLOCK) continue // zona lisa
            val coh = sqrt((jxx - jyy) * (jxx - jyy) + 4 * jxy * jxy) / tr
            if (coh < 0.8f) continue // textura, no una línea
            // Dirección del gradiente; la línea es perpendicular.
            val phi = 0.5f * atan2(2 * jxy, jxx - jyy)
            var line = Math.toDegrees(phi.toDouble()).toFloat() + 90f
            while (line > 90f) line -= 180f
            while (line <= -90f) line += 180f
            segs += Seg(line, sqrt(tr) * coh * coh, ((bx + 0.5f) * BLOCK) / gw, ((by + 0.5f) * BLOCK) / gh)
        }
        // Verticales (±(90−15)°) y horizontales (±15°).
        val vert = segs.filter { abs(it.angle) > 75f }.map { Seg(if (it.angle > 0) it.angle - 90f else it.angle + 90f, it.weight, it.x, it.y) }
        val hor = segs.filter { abs(it.angle) < MAX_TILT + 2f }
        // Verticales: desviación = a + k·(x − ½) (giro + convergencia), ajuste robusto ponderado.
        // Verticales: todas apuntan a un mismo punto de fuga (arriba o abajo si el teléfono estaba
        // inclinado; en el infinito si estaba derecho). Se busca ese punto (mínimos cuadrados
        // homogéneos, reponderados para ignorar líneas que no encajan): su dirección desde el
        // centro da el giro y su distancia, la convergencia.
        var a = 0f; var keyB = 0f; var vOk = 0
        if (vert.size >= 8) {
            val S = max(gw, gh).toDouble()
            val medW = vert.map { it.weight }.sorted()[vert.size / 2]
            val base = DoubleArray(vert.size) { min(vert[it].weight, 3f * medW).toDouble() }
            val wts = base.copyOf()
            // Rectas en coordenadas centradas y normalizadas: n·p = c.
            val lx = DoubleArray(vert.size); val ly = DoubleArray(vert.size); val lc = DoubleArray(vert.size)
            for ((i, q) in vert.withIndex()) {
                val ang = Math.toRadians((90f + q.angle).toDouble()) // dirección de la línea
                val dx = cos(ang); val dy = sin(ang)
                val px0 = (q.x * gw - gw / 2.0) / S; val py0 = (q.y * gh - gh / 2.0) / S
                lx[i] = -dy; ly[i] = dx; lc[i] = -(lx[i] * px0 + ly[i] * py0)
            }
            var v = doubleArrayOf(0.0, -1.0, 0.0)
            repeat(6) {
                val m = Array(3) { DoubleArray(3) }
                for (i in vert.indices) {
                    val l = doubleArrayOf(lx[i], ly[i], lc[i])
                    for (r in 0..2) for (c in 0..2) m[r][c] += wts[i] * l[r] * l[c]
                }
                v = smallestEigen(m)
                // Residuo: ángulo entre cada línea y la dirección hacia el punto de fuga.
                for ((i, q) in vert.withIndex()) {
                    val px0 = (q.x * gw - gw / 2.0) / S; val py0 = (q.y * gh - gh / 2.0) / S
                    var ux = v[0] - px0 * v[2]; var uy = v[1] - py0 * v[2]
                    if (uy > 0) { ux = -ux; uy = -uy }
                    val ang = Math.toDegrees(atan2(ux, -uy)).toFloat()
                    val r = abs(ang - q.angle)
                    wts[i] = base[i] * if (r <= 1f) 1.0 else 1.0 / r
                }
            }
            var ux = v[0]; var uy = v[1]
            val above = (v[2] != 0.0 && v[1] / v[2] < 0) // punto de fuga arriba del centro
            if (uy > 0) { ux = -ux; uy = -uy }
            a = Math.toDegrees(atan2(ux, -uy)).toFloat()
            val dist = if (abs(v[2]) < 1e-9) Double.MAX_VALUE else kotlin.math.hypot(v[0] / v[2], v[1] / v[2]) * max(w, h)
            keyB = if (dist > 50.0 * max(w, h)) 0f else ((if (above) 1.0 else -1.0) / dist).toFloat()
            vOk = vert.indices.count { wts[it] >= base[it] * 0.5 }
        }
        // Horizontales: mediana ponderada (el horizonte, mesas, ventanas de frente).
        val hAngle = weightedMedian(hor)
        val hCount = hor.size
        val tilt: Float
        val conf: Float
        when {
            vOk >= 10 && (hCount < 10 || vOk >= 3 * hCount || abs(a - (hAngle ?: a)) < 1.5f) -> { tilt = a; conf = min(1f, vOk / 40f) }
            hAngle != null && hCount >= 10 -> {
                // Sin verticales claras: solo horizontales (paisaje). Que estén de acuerdo entre sí.
                val spread = hor.map { abs(it.angle - hAngle) }.sorted()[hCount / 2]
                tilt = hAngle; conf = (min(1f, hCount / 40f) * (1f - spread / 4f)).coerceIn(0f, 1f)
            }
            vOk >= 6 -> { tilt = a; conf = vOk / 40f }
            else -> { tilt = 0f; conf = 0f }
        }
        return Estimate(tilt, if (vOk >= 12) keyB else 0f, conf, vOk, hCount)
    }

    /** Vector propio del menor valor propio de una matriz simétrica 3×3 (Jacobi). */
    private fun smallestEigen(m0: Array<DoubleArray>): DoubleArray {
        val a = Array(3) { m0[it].copyOf() }
        val v = Array(3) { r -> DoubleArray(3) { c -> if (r == c) 1.0 else 0.0 } }
        repeat(30) {
            var p = 0; var q = 1
            if (abs(a[0][2]) > abs(a[p][q])) { p = 0; q = 2 }
            if (abs(a[1][2]) > abs(a[p][q])) { p = 1; q = 2 }
            if (abs(a[p][q]) < 1e-15) return@repeat
            val th = 0.5 * atan2(2 * a[p][q], a[q][q] - a[p][p])
            val c = cos(th); val sn = sin(th)
            for (k in 0..2) { val akp = a[k][p]; val akq = a[k][q]; a[k][p] = c * akp - sn * akq; a[k][q] = sn * akp + c * akq }
            for (k in 0..2) { val apk = a[p][k]; val aqk = a[q][k]; a[p][k] = c * apk - sn * aqk; a[q][k] = sn * apk + c * aqk }
            for (k in 0..2) { val vkp = v[k][p]; val vkq = v[k][q]; v[k][p] = c * vkp - sn * vkq; v[k][q] = sn * vkp + c * vkq }
        }
        var best = 0
        for (i in 1..2) if (a[i][i] < a[best][best]) best = i
        return doubleArrayOf(v[0][best], v[1][best], v[2][best])
    }

    private fun weightedMedian(s: List<Seg>): Float? {
        if (s.isEmpty()) return null
        val sorted = s.sortedBy { it.angle }
        val total = sorted.sumOf { it.weight.toDouble() }
        var acc = 0.0
        for (q in sorted) { acc += q.weight; if (acc >= total / 2) return q.angle }
        return sorted.last().angle
    }

    /**
     * Endereza (gira −tilt) y corrige la perspectiva vertical, y recorta lo justo para que no
     * queden bordes vacíos (mismo tamaño y proporción que el original).
     * @param keystoneAmount cuánto se corrige la convergencia (1 = verticales paralelas).
     */
    fun apply(px: IntArray, w: Int, h: Int, tiltDeg: Float, keystone: Float, keystoneAmount: Float = 0.8f): IntArray {
        val a = Math.toRadians(tiltDeg.toDouble())
        val ca = cos(a).toFloat(); val sa = sin(a).toFloat()
        val b = keystone * keystoneAmount
        val cx = w / 2f; val cy = h / 2f
        fun map(px0: Float, py0: Float, z: Float, out: FloatArray) {
            val qx = cx + (px0 - cx) / z; val qy = cy + (py0 - cy) / z
            val kx = cx + (qx - cx) * (1 + b * (qy - cy))
            val dx = kx - cx; val dy = qy - cy
            out[0] = cx + ca * dx - sa * dy; out[1] = cy + sa * dx + ca * dy
        }
        // Zoom mínimo para que todo el borde de la salida caiga dentro de la foto.
        val tmp = FloatArray(2)
        fun fits(z: Float): Boolean {
            for (i in 0..40) {
                val t = i / 40f
                for ((x, y) in listOf(t * (w - 1) to 0f, t * (w - 1) to (h - 1f), 0f to t * (h - 1), (w - 1f) to t * (h - 1))) {
                    map(x, y, z, tmp)
                    if (tmp[0] < 0f || tmp[1] < 0f || tmp[0] > w - 1.001f || tmp[1] > h - 1.001f) return false
                }
            }
            return true
        }
        var lo = 1f; var hi = 2f
        if (!fits(hi)) hi = 4f
        if (fits(lo)) hi = lo else repeat(20) { val m = (lo + hi) / 2; if (fits(m)) hi = m else lo = m }
        val z = hi
        val out = IntArray(w * h)
        parallelRows(h) { ya, yb ->
            val s = FloatArray(2)
            for (y in ya until yb) for (x in 0 until w) {
                map(x.toFloat(), y.toFloat(), z, s)
                out[y * w + x] = bilinear(px, w, h, s[0], s[1])
            }
        }
        return out
    }

    /** Zoom (recorte) que necesitaría la corrección, para avisar si es mucho. */
    fun cropFactor(w: Int, h: Int, tiltDeg: Float): Float {
        val a = Math.toRadians(abs(tiltDeg).toDouble())
        val r = max(w, h).toDouble() / min(w, h)
        return (cos(a) + sin(a) * r).toFloat()
    }

    internal fun bilinear(px: IntArray, w: Int, h: Int, x: Float, y: Float): Int {
        val sx = x.coerceIn(0f, w - 1.001f); val sy = y.coerceIn(0f, h - 1.001f)
        val ix = sx.toInt(); val iy = sy.toInt()
        val fx = ((sx - ix) * 256f).toInt(); val fy = ((sy - iy) * 256f).toInt()
        val w00 = (256 - fx) * (256 - fy); val w10 = fx * (256 - fy); val w01 = (256 - fx) * fy; val w11 = fx * fy
        val p00 = px[iy * w + ix]; val p10 = px[iy * w + ix + 1]; val p01 = px[(iy + 1) * w + ix]; val p11 = px[(iy + 1) * w + ix + 1]
        fun c(sh: Int) = (((p00 shr sh) and 0xFF) * w00 + ((p10 shr sh) and 0xFF) * w10 + ((p01 shr sh) and 0xFF) * w01 + ((p11 shr sh) and 0xFF) * w11 + 32768) shr 16
        return (0xFF shl 24) or (c(16) shl 16) or (c(8) shl 8) or c(0)
    }
}

/**
 * Zonas de la foto para retocar cada una a su manera (como haría un editor a mano):
 *  • cielo: zona alta, lisa y luminosa conectada con el borde superior;
 *  • piel: dentro de los rostros, por color de piel (cualquier tono);
 *  • vegetación: verdes saturados.
 * Las máscaras se calculan a baja resolución, se ajustan a los bordes de la foto (filtro
 * guiado) y se aplican con transición suave.
 */
object ZoneMasks {
    class Masks(val sky: FloatMap, val skin: FloatMap, val foliage: FloatMap)

    /** Umbrales de cada zona (calibrados con ADE20K: 2 000 fotos con el cielo y la vegetación marcados a mano). */
    data class Tuning(
        val blueRatio: Float = 1.02f,
        val blueMinL: Float = 0.22f,
        val duskMinL: Float = 0.05f,
        val paleMinL: Float = 0.62f,
        val paleMaxSat: Float = 0.22f,
        val warmMinL: Float = 0.45f,
        val skyMaxTexture: Float = 0.045f,
        val floodStep: Float = 0.08f,
        val greenRatio: Float = 1.06f,
        val greenMinSat: Float = 0.15f,
    )

    /** @param faces rectángulos de rostros normalizados (izq, arriba, der, abajo), en la orientación de [px]. */
    fun compute(px: IntArray, w: Int, h: Int, faces: List<FloatArray> = emptyList(), t: Tuning = Calibration.MASKS): Masks {
        val f = max(1, max(w, h) / 384)
        val gw = max(1, w / f); val gh = max(1, h / f)
        val r = FloatArray(gw * gh); val g = FloatArray(gw * gh); val b = FloatArray(gw * gh); val l = FloatArray(gw * gh)
        for (y in 0 until gh) for (x in 0 until gw) {
            var sr = 0; var sg = 0; var sb = 0; var n = 0
            for (oy in 0 until f step max(1, f / 3)) for (ox in 0 until f step max(1, f / 3)) {
                val p = px[min(h - 1, y * f + oy) * w + min(w - 1, x * f + ox)]
                sr += (p shr 16) and 0xFF; sg += (p shr 8) and 0xFF; sb += p and 0xFF; n++
            }
            val i = y * gw + x
            r[i] = sr / (255f * n); g[i] = sg / (255f * n); b[i] = sb / (255f * n)
            l[i] = 0.299f * r[i] + 0.587f * g[i] + 0.114f * b[i]
        }
        // Textura local (variación de la luz en 3×3).
        val tex = FloatArray(gw * gh)
        for (y in 1 until gh - 1) for (x in 1 until gw - 1) {
            val i = y * gw + x
            tex[i] = (abs(l[i + 1] - l[i - 1]) + abs(l[i + gw] - l[i - gw])) * 0.5f
        }
        // --- cielo: candidato liso y luminoso (azul, gris claro o cálido de atardecer)…
        val cand = BooleanArray(gw * gh) { i ->
            val mx = max(r[i], max(g[i], b[i])); val mn = min(r[i], min(g[i], b[i]))
            val sat = if (mx > 0f) (mx - mn) / mx else 0f
            val blue = (b[i] > r[i] * t.blueRatio && b[i] >= g[i] * 0.92f && l[i] > t.blueMinL) || (b[i] > r[i] * 1.25f && b[i] > g[i] * 1.05f && l[i] > t.duskMinL) // azul de día o del crepúsculo
            val pale = l[i] > t.paleMinL && sat < t.paleMaxSat
            val warm = l[i] > t.warmMinL && r[i] > b[i]
            tex[i] < t.skyMaxTexture && (blue || pale || warm)
        }
        // …y conectado con el borde superior (relleno por inundación).
        val sky = FloatArray(gw * gh)
        val stack = ArrayDeque<Int>()
        for (x in 0 until gw) if (cand[x]) { sky[x] = 1f; stack.addLast(x) }
        while (stack.isNotEmpty()) {
            val i = stack.removeLast(); val x = i % gw; val y = i / gw
            for (j in intArrayOf(if (x > 0) i - 1 else -1, if (x < gw - 1) i + 1 else -1, if (y > 0) i - gw else -1, if (y < gh - 1) i + gw else -1)) {
                if (j >= 0 && sky[j] == 0f && cand[j] && abs(l[j] - l[i]) < t.floodStep) { sky[j] = 1f; stack.addLast(j) }
            }
        }
        // El cielo de verdad ocupa una parte apreciable; unas manchas sueltas no cuentan.
        if (sky.sum() < 0.02f * gw * gh) sky.fill(0f)
        // --- piel: color de piel (YCbCr) dentro de los rostros (ampliados para frente y cuello).
        val skin = FloatArray(gw * gh)
        for (fc in faces) {
            val fw = fc[2] - fc[0]; val fh = fc[3] - fc[1]
            val x0 = ((fc[0] - fw * 0.15f) * gw).toInt().coerceIn(0, gw - 1); val x1 = ((fc[2] + fw * 0.15f) * gw).toInt().coerceIn(0, gw - 1)
            val y0 = ((fc[1] - fh * 0.15f) * gh).toInt().coerceIn(0, gh - 1); val y1 = ((fc[3] + fh * 0.35f) * gh).toInt().coerceIn(0, gh - 1)
            for (y in y0..y1) for (x in x0..x1) {
                val i = y * gw + x
                val cb = 128 + (-0.1687f * r[i] - 0.3313f * g[i] + 0.5f * b[i]) * 255
                val cr = 128 + (0.5f * r[i] - 0.4187f * g[i] - 0.0813f * b[i]) * 255
                if (cb in 77f..130f && cr in 132f..178f && l[i] > 0.08f) skin[i] = 1f
            }
        }
        // --- vegetación: verdes con algo de color.
        val fol = FloatArray(gw * gh) { i ->
            val mx = max(r[i], max(g[i], b[i])); val mn = min(r[i], min(g[i], b[i]))
            val sat = if (mx > 0f) (mx - mn) / mx else 0f
            if (g[i] > r[i] * t.greenRatio && g[i] > b[i] * t.greenRatio && sat > t.greenMinSat && l[i] > 0.06f && sky[i] == 0f) 1f else 0f
        }
        fun refine(m: FloatArray): FloatMap {
            if (m.all { it == 0f }) return FloatMap(gw, gh, m)
            return MaskRefine.refine(FloatMap(gw, gh, m), l, max(2, max(gw, gh) / 96))
        }
        return Masks(refine(sky), refine(skin), refine(fol))
    }
}

/**
 * Retoque natural por zonas + brillo y calidez generales. Todo en luz lineal y con valores
 * moderados: el objetivo es que parezca una buena foto, no un filtro.
 */
object ZoneRetouch {
    class Params(
        val sky: Boolean = true,
        val skin: Boolean = true,
        val foliage: Boolean = true,
        /** Intensidad de los retoques por zona (0..1). */
        val strength: Float = 0.6f,
        /** Brillo general en pasos (−1..1). */
        val brightness: Float = 0f,
        /** Calidez (−1 = más frío, 1 = más cálido). */
        val warmth: Float = 0f,
    ) {
        val isNeutral get() = (!sky && !skin && !foliage || strength <= 0f) && abs(brightness) < 0.01f && abs(warmth) < 0.01f
    }

    private val toLin = FloatArray(256) { (it / 255f).toDouble().pow(2.2).toFloat() }
    private val enc = IntArray(8193) { ((it / 8192.0).pow(1 / 2.2) * 255 + 0.5).toInt().coerceIn(0, 255) }

    fun apply(px: IntArray, w: Int, h: Int, masks: ZoneMasks.Masks?, p: Params): IntArray {
        if (p.isNeutral) return px.copyOf()
        val s = p.strength.coerceIn(0f, 1f)
        val gain = 2f.pow(p.brightness.coerceIn(-1.5f, 1.5f))
        val wr = 1f + 0.07f * p.warmth; val wb = 1f - 0.07f * p.warmth
        // Brillo medio de la piel (para aclarar caras oscuras solo si hace falta).
        var skinLift = 1f
        if (p.skin && masks != null) {
            var sum = 0.0; var sw = 0.0
            for (y in 0 until h step 4) for (x in 0 until w step 4) {
                val m = masks.skin.sample((x + 0.5f) / w, (y + 0.5f) / h)
                if (m < 0.5f) continue
                val q = px[y * w + x]
                sum += 0.2126 * toLin[(q shr 16) and 0xFF] + 0.7152 * toLin[(q shr 8) and 0xFF] + 0.0722 * toLin[q and 0xFF]; sw++
            }
            if (sw > 20) {
                val mean = (sum / sw).toFloat()
                // Piel bien expuesta ≈ 0.25–0.4 lineal; si está por debajo, hasta +0.7 pasos.
                if (mean < 0.22f) skinLift = 2f.pow(min(0.7f, (log2(0.22f / max(mean, 1e-3f))) * 0.7f) * s)
            }
        }
        val out = IntArray(w * h)
        parallelRows(h) { ya, yb ->
            for (y in ya until yb) for (x in 0 until w) {
                val i = y * w + x
                val q = px[i]
                var r = toLin[(q shr 16) and 0xFF] * gain * wr
                var g = toLin[(q shr 8) and 0xFF] * gain
                var b = toLin[q and 0xFF] * gain * wb
                if (masks != null) {
                    val u = (x + 0.5f) / w; val t = (y + 0.5f) / h
                    if (p.sky) {
                        val m = masks.sky.sample(u, t) * s
                        if (m > 0.01f) {
                            // Cielo: algo más profundo y con un poco más de color, sin oscurecer nubes oscuras.
                            val yl = 0.2126f * r + 0.7152f * g + 0.0722f * b
                            val deepen = 1f - 0.18f * m * smooth(yl, 0.15f, 0.6f)
                            r *= deepen; g *= deepen; b *= deepen
                            val sat = 1f + 0.22f * m
                            val y2 = 0.2126f * r + 0.7152f * g + 0.0722f * b
                            r = y2 + (r - y2) * sat; g = y2 + (g - y2) * sat; b = y2 + (b - y2) * sat
                        }
                    }
                    if (p.skin) {
                        val m = masks.skin.sample(u, t)
                        if (m > 0.01f) {
                            // Piel: aclarar si está oscura y bajar el exceso de rojo (natural, sin suavizar).
                            val lift = 1f + (skinLift - 1f) * m
                            r *= lift; g *= lift; b *= lift
                            val y2 = 0.2126f * r + 0.7152f * g + 0.0722f * b
                            val desat = 1f - 0.12f * m * s * smooth((r - g) / max(y2, 1e-3f), 0.6f, 1.4f)
                            r = y2 + (r - y2) * desat; g = y2 + (g - y2) * desat; b = y2 + (b - y2) * desat
                        }
                    }
                    if (p.foliage) {
                        val m = masks.foliage.sample(u, t) * s
                        if (m > 0.01f) {
                            val y2 = 0.2126f * r + 0.7152f * g + 0.0722f * b
                            val sat = 1f + 0.18f * m
                            r = y2 + (r - y2) * sat; g = y2 + (g - y2) * sat; b = y2 + (b - y2) * sat
                            // Un poco de luz en las sombras del follaje.
                            val lift = 1f + 0.15f * m * (1f - smooth(y2, 0.02f, 0.2f))
                            r *= lift; g *= lift; b *= lift
                        }
                    }
                }
                out[i] = (0xFF shl 24) or (enc[(soft(r) * 8192).toInt().coerceIn(0, 8192)] shl 16) or
                    (enc[(soft(g) * 8192).toInt().coerceIn(0, 8192)] shl 8) or enc[(soft(b) * 8192).toInt().coerceIn(0, 8192)]
            }
        }
        return out
    }

    private fun soft(v: Float) = if (v <= 0.8f) max(0f, v) else 0.8f + 0.2f * tanh((v - 0.8f) / 0.2f)
    private fun smooth(v: Float, a: Float, b: Float): Float { val t = ((v - a) / (b - a)).coerceIn(0f, 1f); return t * t * (3 - 2 * t) }
    private fun log2(v: Float) = (kotlin.math.ln(v.toDouble()) / kotlin.math.ln(2.0)).toFloat()
}
