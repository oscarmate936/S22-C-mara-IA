package com.iacamara.app.camera

import androidx.camera.core.ImageProxy

/** Un fotograma guardado en memoria (YUV 4:2:0 compacto, sin relleno de filas). */
class RingFrame(
    val width: Int,
    val height: Int,
    val y: ByteArray,
    val u: ByteArray,
    val v: ByteArray,
    /** Rotación para enderezarlo en el marco de la pantalla. */
    val rotation: Int,
    val timestampNs: Long,
) {
    /** Convierte a píxeles ARGB (BT.601 de rango completo, como el JPEG de la cámara). */
    fun toArgb(): IntArray {
        val out = IntArray(width * height)
        val cw = width / 2
        for (yy in 0 until height) {
            val crow = (yy / 2) * cw
            for (xx in 0 until width) {
                val yv = y[yy * width + xx].toInt() and 0xFF
                val ci = crow + xx / 2
                val uv = (u[ci].toInt() and 0xFF) - 128
                val vv = (v[ci].toInt() and 0xFF) - 128
                val r = (yv + 1.402f * vv).toInt().coerceIn(0, 255)
                val g = (yv - 0.344136f * uv - 0.714136f * vv).toInt().coerceIn(0, 255)
                val b = (yv + 1.772f * uv).toInt().coerceIn(0, 255)
                out[yy * width + xx] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        return out
    }

    /** Solo la luminancia (para alinear y medir nitidez sin convertir a color). */
    fun luma(): ByteArray = y
}

/**
 * Memoria circular de fotogramas: guarda siempre los últimos [capacity] instantes del visor,
 * para poder usar el momento ANTES de tocar el botón (ráfaga inteligente, "foto perfecta").
 * Los búferes se reutilizan para no generar basura en memoria a 30 fotogramas por segundo.
 */
class FrameRing(private val capacity: Int) {
    private val slots = arrayOfNulls<RingFrame>(capacity)
    private var next = 0
    private var size = 0
    private val lock = Any()

    val count: Int get() = synchronized(lock) { size }

    /** Copia el fotograma (se puede cerrar el ImageProxy justo después). */
    fun push(image: ImageProxy, rotation: Int, timestampNs: Long) {
        val w = image.width and 1.inv()
        val h = image.height and 1.inv()
        if (w < 2 || h < 2 || image.planes.size < 3) return
        synchronized(lock) {
            val old = slots[next]
            val y = old?.y?.takeIf { it.size == w * h } ?: ByteArray(w * h)
            val u = old?.u?.takeIf { it.size == w * h / 4 } ?: ByteArray(w * h / 4)
            val v = old?.v?.takeIf { it.size == w * h / 4 } ?: ByteArray(w * h / 4)
            copyPlane(image.planes[0], w, h, y)
            copyPlane(image.planes[1], w / 2, h / 2, u)
            copyPlane(image.planes[2], w / 2, h / 2, v)
            slots[next] = RingFrame(w, h, y, u, v, rotation, timestampNs)
            next = (next + 1) % capacity
            size = minOf(size + 1, capacity)
        }
    }

    /** Los últimos [n] fotogramas, del más antiguo al más reciente (copias independientes). */
    fun recent(n: Int): List<RingFrame> = synchronized(lock) {
        val k = minOf(n, size)
        (0 until k).map { i ->
            val f = slots[((next - k + i) % capacity + capacity) % capacity]!!
            RingFrame(f.width, f.height, f.y.copyOf(), f.u.copyOf(), f.v.copyOf(), f.rotation, f.timestampNs)
        }
    }

    fun clear() = synchronized(lock) {
        for (i in slots.indices) slots[i] = null
        next = 0
        size = 0
    }

    private fun copyPlane(plane: ImageProxy.PlaneProxy, w: Int, h: Int, dst: ByteArray) {
        val buf = plane.buffer
        val rs = plane.rowStride
        val ps = plane.pixelStride
        if (ps == 1) {
            val start = buf.position()
            for (row in 0 until h) {
                buf.position(start + row * rs)
                buf.get(dst, row * w, w)
            }
            buf.position(start)
        } else {
            for (row in 0 until h) for (x in 0 until w) dst[row * w + x] = buf.get(row * rs + x * ps)
        }
    }
}
