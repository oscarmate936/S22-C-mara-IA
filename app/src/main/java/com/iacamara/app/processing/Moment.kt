package com.iacamara.app.processing

import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Nitidez de una imagen: energía media del laplaciano (más alto = más detalle, menos movida). */
object Sharpness {
    /**
     * @param region zona a medir en píxeles (x0, y0, x1, y1); null = centro de la imagen.
     */
    fun of(luma: ByteArray, w: Int, h: Int, region: IntArray? = null): Float {
        val x0 = (region?.get(0) ?: w / 4).coerceIn(1, w - 2)
        val y0 = (region?.get(1) ?: h / 4).coerceIn(1, h - 2)
        val x1 = (region?.get(2) ?: w * 3 / 4).coerceIn(x0 + 1, w - 1)
        val y1 = (region?.get(3) ?: h * 3 / 4).coerceIn(y0 + 1, h - 1)
        var sum = 0.0
        var n = 0
        for (y in y0 until y1) {
            val r = y * w
            for (x in x0 until x1) {
                val c = luma[r + x].toInt() and 0xFF
                val lap = 4 * c - (luma[r + x - 1].toInt() and 0xFF) - (luma[r + x + 1].toInt() and 0xFF) -
                    (luma[r + x - w].toInt() and 0xFF) - (luma[r + x + w].toInt() and 0xFF)
                sum += lap * lap
                n++
            }
        }
        return if (n == 0) 0f else sqrt(sum / n).toFloat()
    }
}

/**
 * Funde la cara de otra toma (ya alineada) sobre la foto elegida, con un borde elíptico muy
 * suave para que la unión no se note.
 */
object FaceBlend {
    /** [l],[t],[r],[b] = recuadro de la cara en píxeles. Devuelve una copia con la cara reemplazada. */
    fun blend(base: IntArray, donor: IntArray, w: Int, h: Int, l: Float, t: Float, r: Float, b: Float): IntArray {
        val out = base.copyOf()
        val cx = (l + r) / 2
        val cy = (t + b) / 2
        // Elipse algo mayor que la cara (incluye frente y mentón), con transición suave.
        val rx = (r - l) * 0.62f
        val ry = (b - t) * 0.72f
        val x0 = max(0, (cx - rx).toInt())
        val x1 = min(w - 1, (cx + rx).toInt() + 1)
        val y0 = max(0, (cy - ry).toInt())
        val y1 = min(h - 1, (cy + ry).toInt() + 1)
        for (y in y0..y1) for (x in x0..x1) {
            val i = y * w + x
            val d = donor[i]
            if ((d ushr 24) == 0) continue
            val nx = (x - cx) / rx
            val ny = (y - cy) / ry
            val rr = sqrt(nx * nx + ny * ny)
            if (rr >= 1f) continue
            // 1 en el centro de la cara, 0 en el borde de la elipse (transición en el último 30 %).
            val tt = ((1f - rr) / 0.3f).coerceIn(0f, 1f)
            val a = tt * tt * (3 - 2 * tt)
            val p = base[i]
            fun mix(sh: Int) = (((p shr sh) and 0xFF) * (1 - a) + ((d shr sh) and 0xFF) * a + 0.5f).toInt()
            out[i] = (0xFF shl 24) or (mix(16) shl 16) or (mix(8) shl 8) or mix(0)
        }
        return out
    }
}
