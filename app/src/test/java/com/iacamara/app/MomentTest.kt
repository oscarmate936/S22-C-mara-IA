package com.iacamara.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.iacamara.app.ai.AutoShutter
import com.iacamara.app.ai.AutoShutterMode
import com.iacamara.app.ai.Candidate
import com.iacamara.app.ai.FaceCue
import com.iacamara.app.ai.FaceObs
import com.iacamara.app.ai.MomentScorer
import com.iacamara.app.processing.Align
import com.iacamara.app.processing.FaceBlend
import com.iacamara.app.processing.Sharpness
import com.iacamara.app.processing.TileAlign
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.math.abs
import kotlin.math.log10

/** Fase 6: el momento perfecto, corrección de caras en grupo y disparo automático. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class MomentTest {
    private val report = StringBuilder()
    private fun line(s: String) { report.appendLine(s); println("SIM | $s") }
    private fun flush(name: String) = File("build/sim").apply { mkdirs() }.resolve(name).writeText(report.toString())

    private fun face(cx: Float, eyes: Float?, smile: Float? = 0.5f) = FaceObs(cx - 0.05f, 0.3f, cx + 0.05f, 0.42f, eyes, smile)

    // ------------------------------------------------ elegir la mejor toma

    @Test fun eligeNitidaYConOjosAbiertos() {
        val c = listOf(
            Candidate(0, true, 30f, listOf(face(0.3f, 0.95f), face(0.6f, 0.1f))), // alguien parpadea
            Candidate(1, true, 12f, listOf(face(0.3f, 0.95f), face(0.6f, 0.9f))), // movida
            Candidate(2, true, 29f, listOf(face(0.3f, 0.9f), face(0.6f, 0.92f))), // ✓
            Candidate(3, true, 31f, listOf(face(0.3f, 0.2f), face(0.6f, 0.9f))), // parpadea
        )
        assertEquals(2, MomentScorer.choose(c))
        // Sin caras: la más nítida.
        assertEquals(1, MomentScorer.choose(listOf(Candidate(0, true, 10f, emptyList()), Candidate(1, true, 20f, emptyList()))))
        line("MOMENTO  elige la toma nítida con todos los ojos abiertos (descarta parpadeos y tomas movidas) ✓")
        flush("reporte_momento_eleccion.txt")
    }

    @Test fun prefiereResolucionCompletaSalvoQueElInstantePrevioSeaClaramenteMejor() {
        // Instante previo apenas mejor → gana la de resolución completa.
        val a = listOf(Candidate(0, true, 28f, emptyList()), Candidate(1, false, 30f, emptyList()))
        assertEquals(0, MomentScorer.choose(a))
        // Todas las de resolución completa salieron movidas (el perro ya saltó): gana el instante previo.
        val b = listOf(Candidate(0, true, 8f, emptyList()), Candidate(1, true, 9f, emptyList()), Candidate(2, false, 30f, emptyList()))
        assertEquals(2, MomentScorer.choose(b))
    }

    @Test fun proponeCorregirCarasConOjosCerrados() {
        val base = Candidate(0, true, 30f, listOf(face(0.3f, 0.95f), face(0.6f, 0.1f)))
        val other = Candidate(1, true, 28f, listOf(face(0.31f, 0.2f), face(0.61f, 0.93f)))
        val blurry = Candidate(2, true, 10f, listOf(face(0.3f, 0.9f), face(0.6f, 0.99f)))
        val swaps = MomentScorer.faceSwaps(base, listOf(base, other, blurry))
        assertEquals(1, swaps.size)
        assertEquals(1, swaps[0].baseFace)
        assertEquals(1, swaps[0].donor) // no usa la toma movida aunque tenga mejores ojos
    }

    // ------------------------------------------------ nitidez con fotos reales

    @Test fun nitidezDistingueTomasMovidas() {
        val b = BitmapFactory.decodeStream(javaClass.classLoader!!.getResourceAsStream("sim/chelsea.png"))!!
        fun sharp(bmp: Bitmap): Float {
            val px = IntArray(bmp.width * bmp.height).also { bmp.getPixels(it, 0, bmp.width, 0, 0, bmp.width, bmp.height) }
            return Sharpness.of(Align.luma(px), bmp.width, bmp.height)
        }
        val s0 = sharp(b)
        val s1 = sharp(Bitmap.createScaledBitmap(Bitmap.createScaledBitmap(b, b.width / 2, b.height / 2, true), b.width, b.height, true))
        val s2 = sharp(Bitmap.createScaledBitmap(Bitmap.createScaledBitmap(b, b.width / 4, b.height / 4, true), b.width, b.height, true))
        line("NITIDEZ  original %.1f · algo movida %.1f · muy movida %.1f".format(s0, s1, s2))
        assertTrue(s0 > s1 && s1 > s2)
        flush("reporte_momento_nitidez.txt")
    }

    // ------------------------------------------------ corrección de cara en una foto real

    @Test fun corregirCaraSinQueSeNoteLaUnion() {
        val src = BitmapFactory.decodeStream(javaClass.classLoader!!.getResourceAsStream("sim/astronaut.png"))!!
        val w = src.width; val h = src.height
        val truth = IntArray(w * h).also { src.getPixels(it, 0, w, 0, 0, w, h) }
        // Toma elegida: igual pero con los "ojos cerrados" (zona de los ojos borrosa y oscura).
        val face = floatArrayOf(0.36f * w, 0.12f * h, 0.62f * w, 0.42f * h)
        val base = truth.copyOf()
        for (y in (0.22f * h).toInt() until (0.28f * h).toInt()) for (x in (0.40f * w).toInt() until (0.58f * w).toInt()) {
            val p = base[y * w + x]
            fun c(sh: Int) = ((((p shr sh) and 0xFF) * 0.7f).toInt())
            base[y * w + x] = (0xFF shl 24) or (c(16) shl 16) or (c(8) shl 8) or c(0)
        }
        // Otra toma de la ráfaga: ojos abiertos, pero la mano se movió 6 px.
        val donor = IntArray(w * h) { i ->
            val x = (i % w - 6).coerceIn(0, w - 1); val y = (i / w + 4).coerceIn(0, h - 1)
            truth[y * w + x]
        }
        val t = TileAlign.estimate(Align.luma(base), Align.luma(donor), w, h).transform
        val aligned = TileAlign.warp(donor, w, h, t)
        val out = FaceBlend.blend(base, aligned, w, h, face[0], face[1], face[2], face[3])
        fun psnr(a: IntArray, b: IntArray, x0: Int, y0: Int, x1: Int, y1: Int): Double {
            var se = 0.0; var n = 0
            for (y in y0 until y1) for (x in x0 until x1) for (c in 0..2) {
                val d = ((a[y * w + x] shr (16 - 8 * c)) and 0xFF) - ((b[y * w + x] shr (16 - 8 * c)) and 0xFF); se += d * d; n++
            }
            return if (se == 0.0) 99.0 else 10 * log10(255.0 * 255 / (se / n))
        }
        val eyes = psnr(out, truth, (0.40f * w).toInt(), (0.22f * h).toInt(), (0.58f * w).toInt(), (0.28f * h).toInt())
        val before = psnr(base, truth, (0.40f * w).toInt(), (0.22f * h).toInt(), (0.58f * w).toInt(), (0.28f * h).toInt())
        // Fuera de la cara no cambia nada.
        val outside = psnr(out, base, 0, (0.7f * h).toInt(), w, h)
        // Unión invisible: el salto entre píxeles vecinos en el borde de la elipse no supera al de la propia foto.
        var maxJumpOut = 0; var maxJumpTruth = 0
        val cy = ((face[1] + face[3]) / 2).toInt()
        for (x in 1 until w) {
            maxJumpOut = maxOf(maxJumpOut, abs(((out[cy * w + x] shr 8) and 0xFF) - ((out[cy * w + x - 1] shr 8) and 0xFF)))
            maxJumpTruth = maxOf(maxJumpTruth, abs(((truth[cy * w + x] shr 8) and 0xFF) - ((truth[cy * w + x - 1] shr 8) and 0xFF)))
        }
        line("CARA  zona de los ojos: antes %.1f dB → corregida %.1f dB · resto de la foto intacto (%.0f dB) · salto máximo en la unión %d (la foto real tiene %d)".format(before, eyes, outside, maxJumpOut, maxJumpTruth))
        assertTrue(eyes > before + 10)
        assertTrue(outside > 60)
        assertTrue(maxJumpOut <= maxJumpTruth + 4)
        File("build/sim").mkdirs()
        File("build/sim/momento_cara_corregida.png").outputStream().use { Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.PNG, 100, it) }
        File("build/sim/momento_cara_antes.png").outputStream().use { Bitmap.createBitmap(base, w, h, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.PNG, 100, it) }
        flush("reporte_momento_cara.txt")
    }

    // ------------------------------------------------ disparo automático

    @Test fun disparoAutomaticoPorSonrisa() {
        var t = 0L
        val a = AutoShutter(clock = { t })
        a.mode = AutoShutterMode.SMILE
        val serious = listOf(FaceCue(0.1f, 0.9f, 0f, 0f), FaceCue(0.9f, 0.9f, 0f, 0f))
        val smiling = listOf(FaceCue(0.9f, 0.9f, 0f, 0f), FaceCue(0.85f, 0.95f, 0f, 0f))
        val blink = listOf(FaceCue(0.9f, 0.1f, 0f, 0f), FaceCue(0.9f, 0.9f, 0f, 0f))
        var fires = 0
        // 1 s serios, 0.3 s sonriendo con un parpadeo, luego todos sonríen 2 s, luego serios y otra vez sonrisa.
        val script = List(10) { serious } + List(3) { blink } + List(20) { smiling } + List(30) { serious } + List(20) { smiling }
        for (faces in script) {
            if (a.update(faces, false) == AutoShutter.State.FIRE) fires++
            t += 100
        }
        line("DISPARO  sonrisa: ${fires} fotos (una por cada vez que todos sonríen; nada con alguien serio o parpadeando)")
        assertEquals(2, fires)
        flush("reporte_momento_disparo.txt")
    }

    @Test fun disparoPorMiradaYPorMano() {
        var t = 0L
        val a = AutoShutter(clock = { t })
        a.mode = AutoShutterMode.LOOK
        assertTrue(!a.conditionMet(listOf(FaceCue(null, 0.9f, 30f, 0f)), false)) // mira a un lado
        assertTrue(a.conditionMet(listOf(FaceCue(null, 0.9f, 4f, -3f)), false)) // mira a la cámara
        a.mode = AutoShutterMode.HAND
        var fired = false
        repeat(10) { if (a.update(emptyList(), true) == AutoShutter.State.FIRE) fired = true; t += 100 }
        assertTrue(fired)
    }
}
