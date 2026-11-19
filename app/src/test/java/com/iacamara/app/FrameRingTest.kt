package com.iacamara.app

import android.graphics.ImageFormat
import android.graphics.Rect
import androidx.camera.core.ImageInfo
import androidx.camera.core.ImageProxy
import com.iacamara.app.camera.FrameRing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.nio.ByteBuffer
import kotlin.math.abs

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FrameRingTest {
    private class Plane(private val buf: ByteBuffer, private val row: Int, private val pix: Int) : ImageProxy.PlaneProxy {
        override fun getRowStride() = row
        override fun getPixelStride() = pix
        override fun getBuffer(): ByteBuffer = buf
    }

    /** Fotograma con relleno de filas (stride) y crominancia intercalada, como en muchos sensores. */
    private fun frame(w: Int, h: Int, yVal: Int, uVal: Int, vVal: Int): ImageProxy {
        val rs = w + 16
        val y = ByteArray(rs * h) { yVal.toByte() }
        val crs = w + 16 // U y V intercalados (pixelStride 2)
        val u = ByteArray(crs * h / 2) { uVal.toByte() }
        val v = ByteArray(crs * h / 2) { vVal.toByte() }
        val planes = arrayOf<ImageProxy.PlaneProxy>(
            Plane(ByteBuffer.wrap(y), rs, 1), Plane(ByteBuffer.wrap(u), crs, 2), Plane(ByteBuffer.wrap(v), crs, 2),
        )
        return object : ImageProxy {
            override fun close() = Unit
            override fun getCropRect() = Rect(0, 0, w, h)
            override fun setCropRect(rect: Rect?) = Unit
            override fun getFormat() = ImageFormat.YUV_420_888
            override fun getHeight() = h
            override fun getWidth() = w
            override fun getPlanes() = planes
            override fun getImageInfo(): ImageInfo = throw UnsupportedOperationException()
            @androidx.camera.core.ExperimentalGetImage
            override fun getImage(): android.media.Image? = null
        }
    }

    @Test fun guardaLosUltimosEnOrden() {
        val ring = FrameRing(4)
        for (k in 0 until 7) ring.push(frame(64, 48, k * 10, 128, 128), 90, k * 1000L)
        val last = ring.recent(10)
        assertEquals(4, last.size)
        assertEquals(listOf(3000L, 4000L, 5000L, 6000L), last.map { it.timestampNs })
        assertEquals(60, last.last().y[0].toInt() and 0xFF)
        assertEquals(90, last.last().rotation)
    }

    @Test fun copiaIndependienteYColorCorrecto() {
        val ring = FrameRing(2)
        ring.push(frame(32, 32, 128, 128, 128), 0, 1L)
        val gray = ring.recent(1).first()
        // Gris neutro → gris
        val p = gray.toArgb()[100]
        assertEquals(128, (p shr 16) and 0xFF); assertEquals(128, (p shr 8) and 0xFF); assertEquals(128, p and 0xFF)
        // Rojo intenso en YUV (Y=76, U=85, V=255) → rojo
        ring.push(frame(32, 32, 76, 85, 255), 0, 2L)
        val red = ring.recent(1).first().toArgb()[50]
        assertTrue(((red shr 16) and 0xFF) > 240 && ((red shr 8) and 0xFF) < 20 && (red and 0xFF) < 20)
        // Las copias no cambian aunque la memoria se siga llenando.
        ring.push(frame(32, 32, 10, 128, 128), 0, 3L)
        assertTrue(abs((gray.y[0].toInt() and 0xFF) - 128) == 0)
    }

    @Test fun memoriaVaciaNoFalla() {
        val ring = FrameRing(3)
        assertEquals(0, ring.recent(5).size)
        ring.clear()
        assertEquals(0, ring.count)
    }
}
