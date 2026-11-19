package com.iacamara.app

import com.iacamara.app.ai.BrainInput
import com.iacamara.app.ai.PhotoBrain
import com.iacamara.app.ai.Scene
import com.iacamara.app.camera.ExposureMath
import com.iacamara.app.camera.WhiteBalance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Situaciones reales: ¿el cerebro decide lo que decidiría un fotógrafo? */
class PhotoBrainTest {
    private val shutters = ExposureMath.shutterValues(100_000L, 500_000_000L)

    private fun input(
        scene: Scene,
        aeShutterNs: Long = 1_000_000_000L / 60,
        gyro: Float = 0.01f,
        steady: Boolean = false,
        mm: Float = 24f,
        luma: Float = 0.45f,
        clipped: Float = 0f,
        iso: Int = 200,
        priorityIso: Int = 400,
        speed: Float = 0f,
    ) = BrainInput(
        scene = scene, subject = null, subjectSpeed = speed, gyro = gyro, steady = steady,
        equivalentMm = mm, meanLuma = luma, clippedHighlights = clipped, liveIso = iso,
        aeShutterNs = aeShutterNs, isoMin = 50, isoMax = 3200, priorityIso = priorityIso,
        shutterValues = shutters,
    )

    @Test fun mascotaCongelaMovimiento() {
        val d = PhotoBrain().decide(input(Scene.PET), engaged = null)
        assertEquals(1_000_000_000L / 500, d.shutterNs)
        assertTrue(d.reasons.first().why.contains("mascota"))
    }

    @Test fun siLaCamaraYaEligioRapidoNoInterviene() {
        val d = PhotoBrain().decide(input(Scene.PET, aeShutterNs = 1_000_000_000L / 1000), engaged = null)
        assertNull(d.shutterNs)
    }

    @Test fun teleobjetivoAPulsoExigeMasVelocidad() {
        // 69 mm a pulso con la cámara eligiendo 1/30: regla de pulso → al menos 1/138 → 1/160.
        val d = PhotoBrain().decide(input(Scene.GENERAL, aeShutterNs = 1_000_000_000L / 30, mm = 69f), engaged = null)
        assertNotNull(d.shutterNs)
        assertTrue(d.shutterNs!! <= 1_000_000_000L / 138)
    }

    @Test fun atardecerBajaExposicionYMantieneDorado() {
        val b = PhotoBrain()
        var d = b.decide(input(Scene.SUNSET), null)
        repeat(4) { d = b.decide(input(Scene.SUNSET), null) }
        assertEquals(-0.7f, d.ev, 0.05f)
        assertEquals(WhiteBalance.DAYLIGHT, d.wb)
    }

    @Test fun protegeAltasLuces() {
        val b = PhotoBrain()
        var d = b.decide(input(Scene.GENERAL, clipped = 0.08f), null)
        repeat(5) { d = b.decide(input(Scene.GENERAL, clipped = 0.08f), null) }
        assertTrue(d.ev < -0.5f)
        assertTrue(d.reasons.any { it.why.contains("altas luces") })
    }

    @Test fun cambiosDeExposicionSonSuaves() {
        val d = PhotoBrain().decide(input(Scene.SUNSET), null)
        assertTrue("no debe saltar más de 1/3 de paso", d.ev >= -0.35f)
    }

    @Test fun tripodeDeNocheUsaExposicionLarga() {
        val d = PhotoBrain().decide(input(Scene.NIGHT, steady = true, gyro = 0.002f, luma = 0.12f, iso = 1600), null)
        assertEquals(250_000_000L, d.shutterNs)
        assertTrue(d.reasons.first().why.contains("trípode"))
    }

    @Test fun sujetoRapidoSubeLaVelocidad() {
        // El sujeto cruza medio visor por segundo → ~1/166 s o más rápido.
        val d = PhotoBrain().decide(input(Scene.GENERAL, speed = 0.5f), null)
        assertNotNull(d.shutterNs)
        assertTrue(d.shutterNs!! <= 6_000_000L)
    }

    @Test fun conPocaLuzNoUsaTeleobjetivo() {
        val d = PhotoBrain().decide(input(Scene.ANIMAL, luma = 0.12f, iso = 3200), null)
        assertEquals(1f, d.lensFactor)
    }

    @Test fun tripodeNoOscilaAlBajarElIso() {
        // Ya en exposición larga, el ISO bajó a 200: debe mantenerla, no soltarla y volver a tomarla.
        val d = PhotoBrain().decide(input(Scene.NIGHT, steady = true, gyro = 0.002f, luma = 0.3f, iso = 200), engaged = 250_000_000L)
        assertEquals(250_000_000L, d.shutterNs)
    }
}
