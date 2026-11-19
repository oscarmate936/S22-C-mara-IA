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
        clippedArea: Float = clipped,
        shadowArea: Float = 0f,
        snowy: Boolean = false,
        p98: Float = 0.62f,
        zoom: Float = 1f,
    ) = BrainInput(
        scene = scene, subject = null, subjectSpeed = speed, gyro = gyro, steady = steady,
        equivalentMm = mm, meanLuma = luma, clippedHighlights = clipped, liveIso = iso,
        aeShutterNs = aeShutterNs, isoMin = 50, isoMax = 3200, priorityIso = priorityIso,
        shutterValues = shutters,
        clippedArea = clippedArea, shadowArea = shadowArea, snowy = snowy, p98 = p98, zoomFactor = zoom,
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
        // Tele del S22+ (69 mm, con estabilizador óptico ≈ 2 pasos) a pulso con la cámara eligiendo 1/15:
        // regla 1/(2·69) relajada por el OIS → al menos ~1/35.
        val d = PhotoBrain().decide(input(Scene.GENERAL, aeShutterNs = 1_000_000_000L / 15, mm = 69f, zoom = 2.9f), engaged = null)
        assertNotNull(d.shutterNs)
        assertTrue(d.shutterNs!! <= 1_000_000_000L / 34)
        // Ultra gran angular (sin OIS) a 13 mm: 1/26 basta; con la cámara en 1/15 también interviene.
        val u = PhotoBrain().decide(input(Scene.GENERAL, aeShutterNs = 1_000_000_000L / 15, mm = 13f, zoom = 0.55f), engaged = null)
        assertNotNull(u.shutterNs)
        assertTrue(u.shutterNs!! <= 1_000_000_000L / 25)
    }

    @Test fun atardecerBajaExposicionYMantieneDorado() {
        val b = PhotoBrain()
        var d = b.decide(input(Scene.SUNSET), null)
        repeat(4) { d = b.decide(input(Scene.SUNSET), null) }
        // Calibrado con 31 atardeceres editados por profesionales (FiveK): −0.4 EV de base…
        assertEquals(-0.4f, d.ev, 0.1f) // (zona muerta de 0.15 EV para no hacer temblar la imagen)
        // …y más si el resplandor pasa del nivel en que ellos dejan las luces.
        var bright = b.decide(input(Scene.SUNSET, p98 = 0.97f), null)
        repeat(6) { bright = b.decide(input(Scene.SUNSET, p98 = 0.97f), null) }
        assertTrue("ev ${bright.ev}", bright.ev < -0.6f)
        assertEquals(WhiteBalance.DAYLIGHT, d.wb)
    }

    @Test fun protegeAltasLuces() {
        val b = PhotoBrain()
        var d = b.decide(input(Scene.GENERAL, clipped = 0.08f), null)
        repeat(5) { d = b.decide(input(Scene.GENERAL, clipped = 0.08f), null) }
        assertTrue(d.ev < -0.5f)
        assertTrue(d.reasons.any { it.why.contains("cielo") })
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

    @Test fun unaLamparaNoOscureceLaFoto() {
        // 4 % de píxeles quemados pero concentrados (lámpara/sol): ocupan solo el 1 % del encuadre.
        val b = PhotoBrain()
        var d = b.decide(input(Scene.GENERAL, clipped = 0.04f, clippedArea = 0.01f), null)
        repeat(5) { d = b.decide(input(Scene.GENERAL, clipped = 0.04f, clippedArea = 0.01f), null) }
        // No baja más allá de la exposición calibrada de la escena.
        assertEquals(com.iacamara.app.processing.Calibration.SCENE_EV["GENERAL"] ?: 0f, d.ev, 0.05f)
    }

    @Test fun laNieveSaleBlanca() {
        // Lo más claro de la nieve está en 0.62: hay ~1.3 pasos de margen antes de quemar.
        val b = PhotoBrain()
        var d = b.decide(input(Scene.LANDSCAPE, snowy = true), null)
        repeat(3) { d = b.decide(input(Scene.LANDSCAPE, snowy = true), null) }
        assertTrue("debe subir la exposición (${d.ev})", d.ev > 0.9f)
        assertTrue(d.reasons.any { it.why.contains("nieve") })
    }

    @Test fun altoContrastePideHdr() {
        val d = PhotoBrain().decide(input(Scene.GENERAL, clippedArea = 0.12f, shadowArea = 0.15f), null)
        assertTrue(d.hdr)
        assertEquals("HDR", d.reasons.first().value)
    }

    @Test fun sinHdrSiHayMovimiento() {
        val d = PhotoBrain().decide(input(Scene.GENERAL, clippedArea = 0.12f, shadowArea = 0.15f, gyro = 0.3f), null)
        assertTrue(!d.hdr)
    }

    @Test fun atardecerConResplandorQuemadoUsaHdrSinAclararLaSilueta() {
        val b = PhotoBrain()
        var d = b.decide(input(Scene.SUNSET, clippedArea = 0.06f, shadowArea = 0.3f, p98 = 1f), null)
        repeat(4) { d = b.decide(input(Scene.SUNSET, clippedArea = 0.06f, shadowArea = 0.3f, p98 = 1f), null) }
        assertTrue(d.hdr)
        assertTrue("sigue exponiendo para el cielo (silueta oscura)", d.ev <= -0.7f)
    }

    @Test fun atardecerSinQuemarNoNecesitaHdr() {
        val d = PhotoBrain().decide(input(Scene.SUNSET, clippedArea = 0f, shadowArea = 0.3f, p98 = 0.85f), null)
        assertTrue(!d.hdr)
    }

    /**
     * Reproduce el video del amanecer: teléfono a pulso (temblor real, ruidoso) y el cielo
     * quemado rondando el 3 %. 30 s a 2 decisiones por segundo, con el motor compensando el ISO.
     */
    @Test fun estabilidadAPulsoSinBombeo() {
        val rnd = kotlin.random.Random(21)
        var t = 0L
        val b = PhotoBrain(clock = { t })
        var engaged: Long? = null
        var iso = 200
        var shutterChanges = 0
        var evChanges = 0
        var brightnessJumps = 0
        var lastEv = 0f
        var lastExposure = 0.0
        // Modelo del comportamiento anterior (sin márgenes ni compensación de ISO), para comparar.
        var oldShutter: Long? = null
        var oldChanges = 0
        var oldJumps = 0
        var oldIso = 200
        var oldEvChanges = 0
        var oldProtect = false
        repeat(60) { k ->
            val tremor = 0.05f + rnd.nextFloat() * 0.25f // anchos/s: el pulso nunca es constante
            val clipped = 0.022f + rnd.nextFloat() * 0.016f // 2.2 %–3.8 %: ronda el umbral
            val d = b.decide(
                input(Scene.SUNSET, aeShutterNs = 1_000_000_000L / 100, clippedArea = clipped, p98 = 0.96f, iso = 200, priorityIso = iso).let { base ->
                    BrainInput(
                        scene = base.scene, subject = null, subjectSpeed = 0f, gyro = 0.01f, steady = false,
                        equivalentMm = 23f, meanLuma = 0.4f, clippedHighlights = clipped, liveIso = 200,
                        aeShutterNs = base.aeShutterNs, isoMin = 50, isoMax = 3200, priorityIso = iso,
                        shutterValues = shutters, clippedArea = clipped, p98 = 0.96f, imageMotion = tremor,
                    )
                },
                engaged,
            )
            // Motor: si cambia la velocidad, el ISO compensa al instante.
            val newShutter = d.shutterNs
            if (newShutter != null && engaged == null) iso = com.iacamara.app.camera.ExposureMath.keepExposure(200, 1_000_000_000L / 100, newShutter, 50, 3200)
            else if (newShutter != null && engaged != null && newShutter != engaged) iso = com.iacamara.app.camera.ExposureMath.keepExposure(iso, engaged, newShutter, 50, 3200)
            val exposure = iso.toDouble() * (newShutter ?: (1_000_000_000L / 100))
            if (k > 0 && newShutter != engaged) shutterChanges++
            if (k > 0 && kotlin.math.abs(kotlin.math.ln(exposure / lastExposure)) > 0.23) brightnessJumps++
            if (k > 0 && kotlin.math.abs(d.ev - lastEv) > 0.01f) evChanges++
            engaged = newShutter; lastExposure = exposure; lastEv = d.ev

            // Comportamiento anterior: sigue cada variación del pulso y no compensa el ISO al cambiar.
            val oldWanted = shutters.filter { it <= (0.002 / tremor * 1e9).toLong() }.maxOrNull()
            if (k > 0 && oldWanted != oldShutter) { oldChanges++; if (oldShutter != null && oldWanted != null &&
                kotlin.math.abs(kotlin.math.ln(oldShutter.toDouble() / oldWanted)) > 0.23) oldJumps++ }
            oldShutter = oldWanted
            val p = clipped > 0.03f
            if (k > 0 && p != oldProtect) oldEvChanges++
            oldProtect = p
            t += 500
        }
        println("SIM | ESTABILIDAD 30 s a pulso — antes: velocidad cambió $oldChanges veces, $oldJumps saltos de brillo, protección del cielo encendida/apagada $oldEvChanges veces")
        println("SIM | ESTABILIDAD 30 s a pulso — ahora: velocidad cambió $shutterChanges veces, $brightnessJumps saltos de brillo, ajustes de exposición $evChanges")
        assertTrue("la velocidad no debe cambiar a cada rato ($shutterChanges)", shutterChanges <= 4)
        assertEquals("el brillo no debe saltar", 0, brightnessJumps)
        assertTrue("la exposición se asienta ($evChanges)", evChanges <= 6)
    }
}
