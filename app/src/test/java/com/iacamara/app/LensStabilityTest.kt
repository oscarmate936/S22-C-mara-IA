package com.iacamara.app

import com.iacamara.app.ai.BrainInput
import com.iacamara.app.ai.PhotoBrain
import com.iacamara.app.ai.Scene
import com.iacamara.app.ai.Subject
import com.iacamara.app.camera.ExposureMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Estabilidad del cambio automático de lente. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LensStabilityTest {
    private val shutters = ExposureMath.shutterValues(100_000L, 500_000_000L)

    /**
     * Sesión de 60 s fotografiando a una persona: a ratos gira la cara (la IA la pierde) y una
     * vez apuntas al paisaje. Se cuenta cuántas veces cambia el lente solo, antes y ahora.
     */
    @Test fun lenteEstableDuranteLaToma() = session("persona con miradas al paisaje", maxNew = 1, minOld = 3, expectEnd = 2f) { t ->
        when {
            t < 8 -> Scene.PORTRAIT
            t < 11 -> Scene.GENERAL
            t < 20 -> Scene.PORTRAIT
            t < 24 -> Scene.LANDSCAPE
            t < 34 -> Scene.PORTRAIT
            t < 37 -> Scene.GENERAL
            else -> Scene.PORTRAIT
        }
    }

    @Test fun cambioRealDeTemaSiCambiaElLente() = session("paisaje un buen rato y vuelta a la persona", maxNew = 3, minOld = 0, expectEnd = 2f) { t ->
        when {
            t < 12 -> Scene.PORTRAIT
            t < 30 -> Scene.LANDSCAPE
            else -> Scene.PORTRAIT
        }
    }

    private fun session(name: String, maxNew: Int, minOld: Int, expectEnd: Float, sceneAt: (Double) -> Scene) {
        val lenses = listOf(0.55f, 1f, 2f, 2.9f)
        val face1x = 0.18f // alto de la cara visto en 1×
        fun nearest(f: Float) = lenses.minByOrNull { kotlin.math.abs(kotlin.math.ln(it / f)) }!!

        // --- Antes: decisión según el tamaño en el lente actual y sin pausas.
        var current = 1f
        var oldSwitches = 0
        var stable: Scene? = null; var since = 0.0; var handled: Scene? = null
        // --- Ahora
        var t = 0L
        val policy = com.iacamara.app.ai.LensPolicy(clock = { t })
        val brain = PhotoBrain(clock = { t })
        var now = 1f
        var newSwitches = 0
        var k = 0
        while (k < 180) {
            val time = k / 3.0
            val sc = sceneAt(time)
            // Antes
            val faceNow = face1x * current
            val oldWanted = when (sc) { Scene.PORTRAIT -> if (faceNow > 0.3f) null else 2f; Scene.LANDSCAPE -> 0.6f; else -> null }
            if (sc != stable) { stable = sc; since = time } else if (time - since >= 2 && handled != sc && oldWanted != null) {
                handled = sc
                val target = nearest(oldWanted)
                if (kotlin.math.abs(target - current) >= 0.3f) { current = target; oldSwitches++ }
            }
            // Ahora: cerebro real (tamaño normalizado a 1×) + política real.
            val subject = if (sc == Scene.PORTRAIT) Subject(android.graphics.RectF(0.4f, 0.2f, 0.6f, 0.2f + face1x * now), "Persona", true) else null
            val d = brain.decide(
                BrainInput(
                    scene = sc, subject = subject, subjectSpeed = 0f, gyro = 0.01f, steady = false, equivalentMm = 24f * now,
                    meanLuma = 0.45f, clippedHighlights = 0f, liveIso = 100, aeShutterNs = 1_000_000_000L / 500, isoMin = 50,
                    isoMax = 3200, priorityIso = 100, shutterValues = shutters, zoomFactor = now,
                ),
                null,
            )
            policy.decide(sc, 0.85f, d.lensFactor, now, lenses)?.let { now = nearest(it); newSwitches++ }
            t += 333
            k++
        }
        println("SIM | LENTE 60 s ($name) — antes: cambió solo $oldSwitches veces, terminó en ${current}× · ahora: $newSwitches, termina en ${now}×")
        assertTrue("antes ($oldSwitches)", oldSwitches >= minOld)
        assertTrue("ahora estable ($newSwitches)", newSwitches <= maxNew)
        assertEquals(expectEnd, now)
    }

    @Test fun retratoNoCambiaDeOpinionTrasElZoom() {
        // Cara de 0.18 en 1× → el cerebro pide 2×. Ya en 2× (la cara mide 0.36) debe seguir pidiendo 2×.
        val b = PhotoBrain()
        fun ask(zoom: Float) = b.decide(
            BrainInput(
                scene = Scene.PORTRAIT, subject = Subject(android.graphics.RectF(0.4f, 0.2f, 0.6f, 0.2f + 0.18f * zoom), "Persona", true),
                subjectSpeed = 0f, gyro = 0.01f, steady = false, equivalentMm = 24f * zoom, meanLuma = 0.45f, clippedHighlights = 0f,
                liveIso = 100, aeShutterNs = 1_000_000_000L / 500, isoMin = 50, isoMax = 3200, priorityIso = 100,
                shutterValues = shutters, zoomFactor = zoom,
            ),
            null,
        ).lensFactor
        assertEquals(2f, ask(1f))
        assertEquals(2f, ask(2f))
    }
}
