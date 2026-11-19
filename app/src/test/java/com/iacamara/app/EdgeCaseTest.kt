package com.iacamara.app

import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.RectF
import androidx.camera.core.ImageInfo
import androidx.camera.core.ImageProxy
import com.iacamara.app.ai.BrainInput
import com.iacamara.app.ai.Composer
import com.iacamara.app.ai.PhotoBrain
import com.iacamara.app.ai.Scene
import com.iacamara.app.ai.Subject
import com.iacamara.app.camera.ExposureMath
import com.iacamara.app.camera.FrameAnalyzer
import com.iacamara.app.camera.FrameStats
import com.iacamara.app.camera.HorizonDetector
import com.iacamara.app.camera.NormRect
import com.iacamara.app.processing.Align
import com.iacamara.app.processing.FocusStacker
import com.iacamara.app.processing.HdrMerger
import com.iacamara.app.processing.MeanStacker
import com.iacamara.app.processing.ToneCurve
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.nio.ByteBuffer

/** Casos límite que podrían cerrar la app: imágenes negras, blancas, diminutas, valores vacíos. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EdgeCaseTest {
    private class Plane(private val buf: ByteBuffer, private val row: Int) : ImageProxy.PlaneProxy {
        override fun getRowStride() = row
        override fun getPixelStride() = 1
        override fun getBuffer(): ByteBuffer = buf
    }

    private fun proxy(w: Int, h: Int, value: Int): ImageProxy {
        val planes = arrayOf<ImageProxy.PlaneProxy>(
            Plane(ByteBuffer.wrap(ByteArray(w * h) { value.toByte() }), w),
            Plane(ByteBuffer.wrap(ByteArray(maxOf(1, w * h / 4)) { -128 }), maxOf(1, w / 2)),
            Plane(ByteBuffer.wrap(ByteArray(maxOf(1, w * h / 4)) { -128 }), maxOf(1, w / 2)),
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

    private fun finite(f: Float) = !f.isNaN() && !f.isInfinite()

    @Test fun analizadorConImagenesExtremas() {
        for ((w, h, v) in listOf(Triple(640, 480, 0), Triple(640, 480, 255), Triple(8, 8, 128), Triple(2, 2, 0), Triple(641, 479, 90))) {
            var t = 0L
            var st: FrameStats? = null
            val fa = FrameAnalyzer(onStats = { st = it }, clock = { t }, minIntervalMs = 0)
            fa.region = NormRect(0.4f, 0.4f, 0.6f, 0.6f)
            repeat(3) { fa.analyze(proxy(w, h, v)); t += 125_000_000L }
            val s = st!!
            assertTrue("$w×$h=$v", finite(s.meanLuma) && finite(s.p98) && finite(s.warmth) && finite(s.globalMotion))
            s.subjectLuma?.let { assertTrue(finite(it)) }
            s.subjectMotion?.let { assertTrue(finite(it)) }
        }
    }

    @Test fun cerebroConValoresVacios() {
        val b = PhotoBrain()
        for (luma in listOf(0f, 1f)) {
            val d = b.decide(
                BrainInput(
                    scene = Scene.PORTRAIT, subject = Subject(RectF(0f, 0f, 0f, 0f), "x", true), subjectSpeed = 50f, gyro = 9f,
                    steady = false, equivalentMm = 0f, meanLuma = luma, clippedHighlights = 1f, liveIso = 0, aeShutterNs = 0,
                    isoMin = 0, isoMax = 0, priorityIso = 0, shutterValues = emptyList(), clippedArea = 1f, shadowArea = 1f,
                    subjectLuma = luma, snowy = true, p98 = luma, imageMotion = 99f,
                ),
                engaged = 0L,
            )
            assertTrue(finite(d.ev))
        }
    }

    @Test fun procesamientoConImagenesDiminutasYExtremas() {
        val white = IntArray(16) { -1 }
        val black = IntArray(16) { 0xFF shl 24 }
        Align.estimateShift(Align.luma(white), Align.luma(black), 4, 4)
        MeanStacker(4, 4).apply { add(white); add(black, 3, -3) }.result(0.3f)
        FocusStacker(4, 4).apply { add(white); add(black, 1, 1) }.result()
        val hdr = HdrMerger(4, 4).merge(white, black, 0, 0, 4f)
        assertTrue(hdr.size == 16)
        val big = HdrMerger(64, 48).merge(IntArray(64 * 48) { -1 }, IntArray(64 * 48) { 0xFF shl 24 }, 2, 2, 8f)
        assertTrue(big.all { (it ushr 24) == 0xFF })
        for (wp in listOf(1f, 1.0001f, 2f, 1e6f)) {
            val c = ToneCurve(wp)
            for (y in listOf(0f, 0.5f, 1f, wp, wp * 10)) assertTrue(finite(c(y)) && c(y) in 0f..1f)
        }
        HorizonDetector.detect(ByteArray(4), 2, 2)
        HorizonDetector.detect(ByteArray(160 * 120) { 0 }, 160, 120)
    }

    @Test fun compositorEnLosBordes() {
        for (x in listOf(0f, 1f)) for (y in listOf(0f, 1f)) for (d in listOf(0, 90, 180, 270)) {
            val s = Subject(RectF(x, y, x, y), "x", false)
            Composer.plan(Scene.PET, s, null, heading = android.graphics.PointF(Float.MIN_VALUE, 0f), deviceDelta = d, mirrored = true)
        }
    }

    @Test fun formatosExtremos() {
        for (ns in listOf(1L, 100_000L, 33_333_333L, 999_999_999L, 30_000_000_000L)) ExposureMath.formatShutter(ns)
        assertTrue(ExposureMath.keepExposure(100, 10_000_000L, 0L, 50, 3200) in 50..3200)
        ExposureMath.formatFocus(0f); ExposureMath.formatFocus(100f)
        ExposureMath.isoValues(3200, 50); ExposureMath.shutterValues(1_000_000_000L, 1000L)
    }
}
