package com.iacamara.app.processing

import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** Mapa de valores 0..1 (máscara del sujeto, desenfoque…) con su tamaño. */
class FloatMap(val w: Int, val h: Int, val v: FloatArray = FloatArray(w * h)) {
    operator fun get(x: Int, y: Int) = v[y * w + x]

    /** Muestra bilineal en coordenadas normalizadas 0..1. */
    fun sample(u: Float, t: Float): Float {
        val x = (u * w - 0.5f).coerceIn(0f, w - 1.001f)
        val y = (t * h - 0.5f).coerceIn(0f, h - 1.001f)
        val ix = x.toInt(); val iy = y.toInt(); val fx = x - ix; val fy = y - iy
        val x1 = min(ix + 1, w - 1); val y1 = min(iy + 1, h - 1)
        return v[iy * w + ix] * (1 - fx) * (1 - fy) + v[iy * w + x1] * fx * (1 - fy) + v[y1 * w + ix] * (1 - fx) * fy + v[y1 * w + x1] * fx * fy
    }

    fun resized(nw: Int, nh: Int) = FloatMap(nw, nh, FloatArray(nw * nh) { i -> sample((i % nw + 0.5f) / nw, (i / nw + 0.5f) / nh) })

    /** Girado en sentido horario (0, 90, 180 o 270 grados). */
    fun rotatedCw(deg: Int): FloatMap = when (((deg % 360) + 360) % 360) {
        90 -> FloatMap(h, w, FloatArray(w * h) { i -> val x = i % h; val y = i / h; v[(h - 1 - x) * w + y] })
        180 -> FloatMap(w, h, FloatArray(w * h) { i -> v[w * h - 1 - i] })
        270 -> FloatMap(h, w, FloatArray(w * h) { i -> val x = i % h; val y = i / h; v[x * w + (w - 1 - y)] })
        else -> this
    }

    /** Proporción de la imagen ocupada (media de la máscara). */
    fun mean(): Float { var s = 0.0; for (x in v) s += x; return (s / v.size.coerceAtLeast(1)).toFloat() }
}

/**
 * Refinado de bordes con un "filtro guiado" (He et al.): la máscara del sujeto, que la IA da en
 * baja resolución y con bordes toscos, se ajusta a los bordes reales de la foto (pelo, orejas,
 * hombros), usando la propia imagen como guía.
 */
object MaskRefine {
    fun guided(mask: FloatMap, guide: FloatArray, radius: Int, eps: Float = 1e-3f): FloatMap {
        val w = mask.w; val h = mask.h
        val n = w * h
        val ip = FloatArray(n) { guide[it] * mask.v[it] }
        val ii = FloatArray(n) { guide[it] * guide[it] }
        val mI = box(guide, w, h, radius)
        val mP = box(mask.v, w, h, radius)
        val mIp = box(ip, w, h, radius)
        val mII = box(ii, w, h, radius)
        val a = FloatArray(n)
        val b = FloatArray(n)
        for (i in 0 until n) {
            val cov = mIp[i] - mI[i] * mP[i]
            val varI = mII[i] - mI[i] * mI[i]
            a[i] = cov / (varI + eps)
            b[i] = mP[i] - a[i] * mI[i]
        }
        val mA = box(a, w, h, radius)
        val mB = box(b, w, h, radius)
        return FloatMap(w, h, FloatArray(n) { (mA[it] * guide[it] + mB[it]).coerceIn(0f, 1f) })
    }

    /**
     * Refinado completo: filtro guiado (los bordes siguen a la foto) y una curva de contraste
     * suave (el filtro deja el borde demasiado difuso; la curva lo afina sin volverlo duro, así
     * el pelo conserva su transición). Medido: el error en el contorno baja hasta 5×.
     */
    fun refine(mask: FloatMap, guide: FloatArray, radius: Int, eps: Float = 1e-4f): FloatMap {
        val q = guided(mask, guide, radius, eps)
        return FloatMap(q.w, q.h, FloatArray(q.v.size) {
            val t = ((q.v[it] - 0.3f) / 0.4f).coerceIn(0f, 1f)
            t * t * (3 - 2 * t)
        })
    }

    /** Media en una ventana cuadrada de lado 2r+1 (imagen integral). */
    fun box(src: FloatArray, w: Int, h: Int, r: Int): FloatArray {
        val integ = DoubleArray((w + 1) * (h + 1))
        for (y in 0 until h) {
            var row = 0.0
            for (x in 0 until w) {
                row += src[y * w + x]
                integ[(y + 1) * (w + 1) + x + 1] = integ[y * (w + 1) + x + 1] + row
            }
        }
        val out = FloatArray(w * h)
        for (y in 0 until h) {
            val y0 = max(0, y - r); val y1 = min(h, y + r + 1)
            for (x in 0 until w) {
                val x0 = max(0, x - r); val x1 = min(w, x + r + 1)
                val s = integ[y1 * (w + 1) + x1] - integ[y0 * (w + 1) + x1] - integ[y1 * (w + 1) + x0] + integ[y0 * (w + 1) + x0]
                out[y * w + x] = (s / ((x1 - x0) * (y1 - y0))).toFloat()
            }
        }
        return out
    }
}

/**
 * Profundidad aproximada a partir del recorte del sujeto: lo que está junto al sujeto se
 * desenfoca poco y lo lejano mucho (transición gradual, sin aspecto de "recorte pegado");
 * el suelo justo delante de los pies queda más nítido, como con un lente real.
 * Devuelve el desenfoque relativo 0..1 de cada zona (0 = nítido).
 */
object DepthHint {
    fun blurMap(mask: FloatMap): FloatMap {
        val w = mask.w; val h = mask.h
        // Distancia (en píxeles del mapa) al sujeto: transformada de distancia en dos pasadas.
        val inf = 1e9f
        val d = FloatArray(w * h) { if (mask.v[it] >= 0.5f) 0f else inf }
        val diag = 1.4142f
        for (y in 0 until h) for (x in 0 until w) {
            val i = y * w + x
            if (x > 0) d[i] = min(d[i], d[i - 1] + 1)
            if (y > 0) { d[i] = min(d[i], d[i - w] + 1); if (x > 0) d[i] = min(d[i], d[i - w - 1] + diag); if (x < w - 1) d[i] = min(d[i], d[i - w + 1] + diag) }
        }
        for (y in h - 1 downTo 0) for (x in w - 1 downTo 0) {
            val i = y * w + x
            if (x < w - 1) d[i] = min(d[i], d[i + 1] + 1)
            if (y < h - 1) { d[i] = min(d[i], d[i + w] + 1); if (x < w - 1) d[i] = min(d[i], d[i + w + 1] + diag); if (x > 0) d[i] = min(d[i], d[i + w - 1] + diag) }
        }
        // Línea de los "pies" del sujeto (su punto más bajo).
        var feet = -1
        for (y in h - 1 downTo 0) { var any = false; for (x in 0 until w) if (mask.v[y * w + x] >= 0.5f) { any = true; break }; if (any) { feet = y; break } }
        val ref = 0.15f * max(w, h)
        val out = FloatArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            val i = y * w + x
            if (mask.v[i] >= 0.5f || d[i] >= inf) { out[i] = if (d[i] >= inf) 1f else 0f; continue }
            var b = (0.4f + 0.6f * (d[i] / ref)).coerceIn(0f, 1f)
            // Suelo delante del sujeto: más cerca del plano de enfoque, menos desenfoque.
            if (feet >= 0 && y > feet) b *= (0.35f + (y - feet).toFloat() / (0.35f * h)).coerceIn(0.35f, 1f)
            out[i] = b
        }
        return FloatMap(w, h, out)
    }
}

/**
 * Desenfoque óptico de fondo ("bokeh"):
 *  • forma de disco (como el diafragma de un lente), no una mancha gaussiana,
 *  • en luz lineal, con realce de las luces: los puntos brillantes se vuelven círculos,
 *  • sin que el color del sujeto "sangre" hacia el fondo (sin halos),
 *  • calculado a media resolución y combinado con el sujeto nítido a resolución completa.
 */
object Bokeh {
    private val toLin = FloatArray(256) { (it / 255f).toDouble().pow(2.2).toFloat() }
    private val encode = IntArray(4097) { ((it / 4096.0).pow(1 / 2.2) * 255 + 0.5).toInt().coerceIn(0, 255) }

    /** Puntos del disco (espiral de ángulo áureo: reparto uniforme). */
    private fun disc(n: Int): FloatArray {
        val out = FloatArray(n * 2)
        val golden = 2.39996323f
        for (i in 0 until n) {
            val r = sqrt((i + 0.5f) / n)
            out[2 * i] = r * cos(i * golden)
            out[2 * i + 1] = r * sin(i * golden)
        }
        return out
    }

    /**
     * @param alpha máscara del sujeto a resolución completa (1 = sujeto).
     * @param blur desenfoque relativo 0..1 por zona (cualquier tamaño).
     * @param maxRadius radio máximo del desenfoque en píxeles de la imagen completa.
     * @param scale reducción para el cálculo del fondo (2 = media resolución).
     */
    fun render(px: IntArray, w: Int, h: Int, alpha: FloatMap, blur: FloatMap, maxRadius: Float, scale: Int = 2, samples: Int = 56): IntArray {
        if (maxRadius < 0.5f) return px.copyOf()
        val s = scale.coerceAtLeast(1)
        val w2 = max(1, w / s); val h2 = max(1, h / s)
        // Imagen reducida en luz lineal + máscara reducida
        val lr = FloatArray(w2 * h2); val lg = FloatArray(w2 * h2); val lb = FloatArray(w2 * h2)
        val a2 = FloatArray(w2 * h2)
        parallelRows(h2) { ya, yb ->
            for (y in ya until yb) for (x in 0 until w2) {
                var r = 0f; var g = 0f; var b = 0f; var n = 0
                for (oy in 0 until s) for (ox in 0 until s) {
                    val xx = min(w - 1, x * s + ox); val yy = min(h - 1, y * s + oy)
                    val p = px[yy * w + xx]
                    r += toLin[(p shr 16) and 0xFF]; g += toLin[(p shr 8) and 0xFF]; b += toLin[p and 0xFF]; n++
                }
                val i = y * w2 + x
                lr[i] = r / n; lg[i] = g / n; lb[i] = b / n
                a2[i] = alpha.sample((x + 0.5f) / w2, (y + 0.5f) / h2)
            }
        }
        val rMax = maxRadius / s
        // Cada píxel del fondo aporta con un peso: 0 si es sujeto (sin halos) y más si es una luz
        // fuerte (así aparecen los círculos de bokeh). Color y peso se suavizan un poco antes de
        // muestrear: equivale a muestrear el disco de forma continua, y las luces pequeñas salen
        // como discos lisos en lugar de un patrón de puntos.
        val n2 = w2 * h2
        val pr = FloatArray(n2); val pg = FloatArray(n2); val pb = FloatArray(n2); val pw = FloatArray(n2)
        for (q in 0 until n2) {
            val bgw = 1f - a2[q]
            if (bgw <= 0.02f) continue
            val l = 0.2126f * lr[q] + 0.7152f * lg[q] + 0.0722f * lb[q]
            val wt = bgw * (1f + 6f * ((l - 0.55f) / 0.45f).coerceIn(0f, 1f))
            pr[q] = lr[q] * wt; pg[q] = lg[q] * wt; pb[q] = lb[q] * wt; pw[q] = wt
        }
        val spacing = rMax * sqrt(Math.PI.toFloat() / samples)
        val pf = (spacing / 2f + 0.5f).toInt().coerceIn(0, 4)
        val fr = if (pf > 0) MaskRefine.box(pr, w2, h2, pf) else pr
        val fg = if (pf > 0) MaskRefine.box(pg, w2, h2, pf) else pg
        val fb = if (pf > 0) MaskRefine.box(pb, w2, h2, pf) else pb
        val fw = if (pf > 0) MaskRefine.box(pw, w2, h2, pf) else pw
        // Varias versiones giradas del disco, alternadas por píxel: sin patrones repetidos.
        val base = disc(samples)
        val variants = Array(8) { k ->
            val ang = k * 0.39f
            val c = cos(ang); val sn = sin(ang)
            FloatArray(base.size).also { o ->
                for (i in 0 until samples) { val x = base[2 * i]; val y = base[2 * i + 1]; o[2 * i] = x * c - y * sn; o[2 * i + 1] = x * sn + y * c }
            }
        }
        val br = FloatArray(n2); val bg = FloatArray(n2); val bb = FloatArray(n2)
        parallelRows(h2) { ya, yb ->
            for (y in ya until yb) for (x in 0 until w2) {
                val i = y * w2 + x
                val rad = blur.sample((x + 0.5f) / w2, (y + 0.5f) / h2) * rMax
                if (rad < 0.6f) { br[i] = lr[i]; bg[i] = lg[i]; bb[i] = lb[i]; continue }
                val pts = variants[(x * 7 + y * 13) and 7]
                var sr = 0f; var sg = 0f; var sb = 0f; var sw = 0f
                var k = 0
                while (k < pts.size) {
                    val qx = (x + pts[k] * rad + 0.5f).toInt()
                    val qy = (y + pts[k + 1] * rad + 0.5f).toInt()
                    k += 2
                    if (qx < 0 || qy < 0 || qx >= w2 || qy >= h2) continue
                    val q = qy * w2 + qx
                    sr += fr[q]; sg += fg[q]; sb += fb[q]; sw += fw[q]
                }
                if (sw < 0.5f) { br[i] = lr[i]; bg[i] = lg[i]; bb[i] = lb[i] } else { br[i] = sr / sw; bg[i] = sg / sw; bb[i] = sb / sw }
            }
        }
        // Volver a resolución completa y combinar con el sujeto nítido.
        val out = IntArray(w * h)
        val bmR = FloatMap(w2, h2, br); val bmG = FloatMap(w2, h2, bg); val bmB = FloatMap(w2, h2, bb)
        parallelRows(h) { ya, yb ->
            for (y in ya until yb) for (x in 0 until w) {
                val i = y * w + x
                val u = (x + 0.5f) / w; val t = (y + 0.5f) / h
                val a = alpha.sample(u, t)
                val p = px[i]
                if (a >= 0.999f) { out[i] = p; continue }
                fun mix(lin: Float, sh: Int): Int {
                    val sharp = toLin[(p shr sh) and 0xFF]
                    return encode[((sharp * a + lin * (1 - a)) * 4096).toInt().coerceIn(0, 4096)]
                }
                out[i] = (0xFF shl 24) or (mix(bmR.sample(u, t), 16) shl 16) or (mix(bmG.sample(u, t), 8) shl 8) or mix(bmB.sample(u, t), 0)
            }
        }
        return out
    }
}
