package com.iacamara.app

import android.graphics.BitmapFactory
import android.graphics.PointF
import android.graphics.RectF
import com.iacamara.app.ai.Composer
import com.iacamara.app.ai.FaceInfo
import com.iacamara.app.ai.GuideType
import com.iacamara.app.ai.Scene
import com.iacamara.app.ai.Subject
import com.iacamara.app.camera.Horizon
import com.iacamara.app.camera.HorizonDetector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.math.abs
import kotlin.math.tan
import kotlin.random.Random

/** Composición con IA: guía según la escena, espacio hacia la mirada, indicaciones y horizonte real. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class CompositionTest {
    private val report = StringBuilder()
    private fun line(s: String) { report.appendLine(s); println("SIM | $s") }
    private fun flush(name: String) = File("build/sim").apply { mkdirs() }.resolve(name).writeText(report.toString())

    private fun face(cx: Float, eyeY: Float, look: PointF? = null): Subject {
        val box = RectF(cx - 0.1f, eyeY - 0.12f, cx + 0.1f, eyeY + 0.16f)
        return Subject.fromFace(FaceInfo(box, PointF(cx - 0.04f, eyeY), PointF(cx + 0.04f, eyeY), PointF(cx, eyeY + 0.11f), 1, look))
    }

    // ------------------------------------------------ 1. Reglas de un fotógrafo

    @Test fun guiaSegunLaEscena() {
        assertEquals(GuideType.HORIZON, Composer.guideFor(Scene.SUNSET, null))
        assertEquals(GuideType.HORIZON, Composer.guideFor(Scene.LANDSCAPE, null))
        assertEquals(GuideType.GOLDEN, Composer.guideFor(Scene.MACRO, null))
        assertEquals(GuideType.THIRDS, Composer.guideFor(Scene.PORTRAIT, null))
        // Plato centrado y grande (desde arriba): guía al centro.
        val plate = Subject(RectF(0.2f, 0.25f, 0.8f, 0.75f), "Comida", false)
        assertEquals(GuideType.CENTER, Composer.guideFor(Scene.FOOD, plate))
        line("GUÍA  atardecer/paisaje → horizonte · macro → áurea · retrato → tercios · plato desde arriba → centro")
        flush("reporte_composicion_guias.txt")
    }

    @Test fun espacioHaciaDondeMira() {
        // Mira a la derecha → la persona va en el tercio izquierdo (espacio delante de su mirada).
        val right = Composer.plan(Scene.PORTRAIT, face(0.5f, 0.33f, PointF(0.8f, 0f)), null)
        assertEquals(1f / 3f, right.target!!.x, 1e-4f)
        assertEquals("deja espacio hacia donde mira", right.instruction!!.why)
        // Mira a la izquierda → tercio derecho.
        val left = Composer.plan(Scene.PORTRAIT, face(0.5f, 0.33f, PointF(-0.8f, 0f)), null)
        assertEquals(2f / 3f, left.target!!.x, 1e-4f)
        // De frente → el punto fuerte más cercano; los ojos en la línea superior.
        val front = Composer.plan(Scene.PORTRAIT, face(0.62f, 0.45f), null)
        assertEquals(2f / 3f, front.target!!.x, 1e-4f)
        assertEquals(1f / 3f, front.target!!.y, 1e-4f)
        // Perro corriendo hacia la derecha → espacio por delante.
        val dog = Subject(RectF(0.4f, 0.5f, 0.6f, 0.7f), "Perro", false)
        val run = Composer.plan(Scene.PET, dog, null, heading = PointF(0.3f, 0f))
        assertEquals(1f / 3f, run.target!!.x, 1e-4f)
        assertEquals("deja espacio hacia donde va", run.instruction!!.why)
        line("MIRADA  mira a la derecha → tercio izquierdo · a la izquierda → tercio derecho · perro corriendo → espacio delante ✓")
        flush("reporte_composicion_mirada.txt")
    }

    /**
     * Sigue las indicaciones como lo haría una persona (girar el teléfono mueve la escena al revés
     * en el encuadre) y comprueba que siempre se llega a una buena composición.
     */
    @Test fun seguirLasIndicacionesLlevaAlObjetivo() {
        val rnd = Random(5)
        var ok = 0
        var steps = 0
        val trials = 200
        repeat(trials) {
            var x = rnd.nextFloat() * 0.6f + 0.2f
            var y = rnd.nextFloat() * 0.6f + 0.2f
            val look = if (rnd.nextBoolean()) PointF(if (rnd.nextBoolean()) 0.7f else -0.7f, 0f) else null
            var done = false
            for (k in 0 until 60) {
                val plan = Composer.plan(Scene.PORTRAIT, face(x, y, look), null)
                if (plan.aligned) { done = true; steps += k; break }
                val text = plan.instruction!!.text
                val step = 0.02f
                when {
                    // Girar el teléfono a la derecha: la escena se desplaza a la izquierda en el encuadre.
                    "derecha" in text -> x -= step
                    "izquierda" in text -> x += step
                    // Apuntar más abajo: la escena sube en el encuadre.
                    "abajo" in text -> y -= step
                    "arriba" in text -> y += step
                }
            }
            if (done) ok++
        }
        line("INDICACIONES  siguiendo las indicaciones se logra «BIEN COMPUESTO» en $ok/$trials casos (≈ ${steps / maxOf(ok, 1)} correcciones)")
        assertEquals(trials, ok)

        // Horizonte: seguir "apunta más abajo/arriba" y "gira" hasta alinearlo.
        var hOk = 0
        repeat(100) {
            var hy = rnd.nextFloat() * 0.7f + 0.15f
            var tilt = (rnd.nextFloat() - 0.5f) * 10f
            for (k in 0 until 80) {
                // La inclinación la mide el nivel del teléfono (horario positivo).
                val plan = Composer.plan(Scene.SUNSET, null, Horizon(hy, 0f, 0.9f), deviceTiltCw = tilt)
                if (plan.aligned) { hOk++; break }
                val t = plan.instruction!!.text
                when {
                    "↺" in t -> tilt -= 0.5f // girar en sentido antihorario corrige un teléfono girado en horario
                    "↻" in t -> tilt += 0.5f
                    "abajo" in t -> hy -= 0.02f
                    "arriba" in t -> hy += 0.02f
                }
            }
        }
        line("INDICACIONES  horizonte: alineado y derecho en $hOk/100 casos")
        assertEquals(100, hOk)
        flush("reporte_composicion_indicaciones.txt")
    }

    @Test fun bienCompuestoNoDaIndicaciones() {
        val plan = Composer.plan(Scene.PORTRAIT, face(2f / 3f, 1f / 3f), null)
        assertTrue(plan.aligned)
        assertNull(plan.instruction)
    }

    // ------------------------------------------------ 2. Horizonte real

    @Test fun detectaElHorizonteReal() {
        line("=== HORIZONTE REAL (cielo con nubes, tierra con textura, árbol que lo tapa, ruido) ===")
        val sky = BitmapFactory.decodeStream(javaClass.classLoader!!.getResourceAsStream("sim/rocket.jpg"))!!
        val ground = BitmapFactory.decodeStream(javaClass.classLoader!!.getResourceAsStream("sim/chelsea.png"))!!
        val w = 120
        val h = 160 // tamaño del análisis reducido (¼ de 480×640)
        val rnd = Random(8)
        var maxErrY = 0f
        var maxErrT = 0f
        for (hy in listOf(0.3f, 0.5f, 0.66f, 0.75f)) for (tilt in listOf(0f, 2.5f, -4f)) {
            val slope = tan(Math.toRadians(tilt.toDouble())).toFloat()
            val luma = ByteArray(w * h) { i ->
                val x = i % w; val y = i / w
                val horizonAt = hy * h + slope * (x - w / 2f)
                val tree = x in 20..27 && y > horizonAt - 22 // un árbol oscuro que sobresale del horizonte
                val v = when {
                    tree -> 25
                    y < horizonAt -> {
                        val p = sky.getPixel(10 + x * 3, 20 + y) // cielo con algo de textura
                        150 + (((p shr 8) and 0xFF) - 128) / 6
                    }
                    else -> {
                        val p = ground.getPixel(x * 3 % ground.width, (y * 2) % ground.height)
                        40 + (((p shr 8) and 0xFF)) / 5
                    }
                }
                (v + rnd.nextInt(-6, 7)).coerceIn(0, 255).toByte()
            }
            val found = HorizonDetector.detect(luma, w, h)
            assertNotNull("horizonte en $hy / $tilt°", found)
            val ey = abs(found!!.y - hy)
            val et = abs(found.tiltDeg - tilt)
            maxErrY = maxOf(maxErrY, ey); maxErrT = maxOf(maxErrT, et)
            line("altura %.2f · inclinación %+4.1f° → detectado %.2f · %+4.1f°   (confianza %.2f)".format(hy, tilt, found.y, found.tiltDeg, found.confidence))
        }
        line("error máximo: altura %.3f (≈ %.1f%% del encuadre) · inclinación %.2f°".format(maxErrY, maxErrY * 100, maxErrT))
        assertTrue(maxErrY < 0.03f)
        assertTrue(maxErrT < 1.0f)

        // Sin horizonte (pared lisa con textura): no debe inventar uno.
        val wall = ByteArray(w * h) { (120 + rnd.nextInt(-10, 11)).toByte() }
        assertNull(HorizonDetector.detect(wall, w, h))
        line("pared sin horizonte → no inventa ninguno ✓")
        flush("reporte_composicion_horizonte.txt")
    }

    @Test fun giroAlMarcoVertical() {
        // Imagen del sensor (horizontal) con el cielo a la "izquierda" (rotación 90): al enderezarla,
        // el cielo debe quedar arriba.
        val sw = 16; val sh = 12
        val src = ByteArray(sw * sh) { i -> if (i % sw < 6) 200.toByte() else 30 }
        val (p, pw, ph) = HorizonDetector.toPortrait(src, sw, sh, 90)
        assertEquals(sh, pw); assertEquals(sw, ph)
        assertEquals(200, p[0].toInt() and 0xFF) // arriba: cielo
        assertEquals(30, p[(ph - 1) * pw].toInt() and 0xFF) // abajo: tierra
    }

    /**
     * Teléfono en horizontal (90° y 270°) y cámara frontal (espejada): se simula a una persona que
     * sigue las indicaciones con el teléfono como lo sostiene, y debe llegar a «BIEN COMPUESTO».
     */
    @Test fun indicacionesConElTelefonoDeLadoYFrontal() {
        val rnd = Random(17)
        for ((delta, mirrored) in listOf(90 to false, 270 to false, 0 to true, 90 to true)) {
            var ok = 0
            repeat(100) {
                // Posición de los ojos y mirada en el marco del teléfono (como lo ve la persona).
                var xd = rnd.nextFloat() * 0.6f + 0.2f
                var yd = rnd.nextFloat() * 0.6f + 0.2f
                val lookDev = if (rnd.nextBoolean()) PointF(if (rnd.nextBoolean()) 0.7f else -0.7f, 0f) else null
                for (k in 0 until 80) {
                    // Lo que recibe el compositor: todo en el marco de la pantalla.
                    val (px, py) = com.iacamara.app.ai.Orient.toPortrait(xd, yd, delta)
                    val look = lookDev?.let { com.iacamara.app.ai.Orient.vectorToPortrait(it.x, it.y, delta, false) }
                    val box = RectF(px - 0.08f, py - 0.08f, px + 0.08f, py + 0.08f)
                    val subj = Subject(box, "Persona", true, PointF(px, py), box, 1, look)
                    val plan = Composer.plan(Scene.PORTRAIT, subj, null, deviceDelta = delta, mirrored = mirrored)
                    if (plan.aligned) { ok++; break }
                    val t = plan.instruction!!.text
                    val step = 0.02f
                    when {
                        // Trasera: girar a la derecha mueve la escena a la izquierda. Frontal (espejo): a la derecha.
                        "derecha" in t -> xd += if (mirrored) step else -step
                        "izquierda" in t -> xd += if (mirrored) -step else step
                        "abajo" in t -> yd -= step
                        "arriba" in t -> yd += step
                    }
                }
            }
            line("ORIENTACIÓN  teléfono a $delta°${if (mirrored) " · cámara frontal" else ""}: bien compuesto en $ok/100")
            assertEquals(100, ok)
        }
        // En horizontal, la guía del horizonte se dibuja vertical en la pantalla (paralela al paisaje real).
        val plan = Composer.plan(Scene.SUNSET, null, Horizon(0.5f, 0f, 0.9f), deviceDelta = 90)
        val (a, b) = plan.targetLine!!
        assertEquals(a.x, b.x, 1e-4f)
        line("ORIENTACIÓN  con el teléfono de lado, la guía del horizonte queda paralela al paisaje ✓")
        flush("reporte_composicion_orientacion.txt")
    }

    @Test fun colinasInclinadasNoPidenGirar() {
        // Horizonte de colinas que sube 6° pero el teléfono está nivelado: no hay que girar nada.
        val plan = Composer.plan(Scene.LANDSCAPE, null, Horizon(1f / 3f, -6f, 0.9f), deviceTiltCw = 0.3f)
        assertTrue(plan.aligned)
        assertNull(plan.instruction)
    }
}
