package com.iacamara.app.ai

import android.graphics.PointF
import com.iacamara.app.camera.Horizon
import kotlin.math.abs
import kotlin.math.hypot

/** Tipo de guía que conviene para la escena. */
enum class GuideType { THIRDS, GOLDEN, CENTER, HORIZON }

/** Indicación para la persona. */
data class Instruction(val text: String, val why: String? = null)

/** Plan de composición: qué guía mostrar, dónde va el sujeto y qué hacer para lograrlo. */
data class CompositionPlan(
    val guide: GuideType,
    /** Dónde está ahora el punto importante (ojos, centro del sujeto). */
    val anchor: PointF? = null,
    /** Dónde debería ir. */
    val target: PointF? = null,
    /** Horizonte detectado y altura a la que debería estar. */
    val horizon: com.iacamara.app.camera.Horizon? = null,
    val horizonTargetY: Float? = null,
    val instruction: Instruction? = null,
    val aligned: Boolean = false,
)

/**
 * El "compositor": elige la guía según la escena, decide dónde colocar al sujeto (dejando
 * espacio hacia donde mira o se mueve) o el horizonte, y traduce la diferencia a una
 * indicación simple: "Mueve el teléfono a la derecha", "Apunta un poco más abajo"…
 */
object Composer {
    private const val THIRD = 1f / 3f
    private const val PHI = 0.382f
    /** Tolerancia para considerar que ya está bien compuesto (fracción del encuadre). */
    const val ALIGN_TOL = 0.06f
    const val HORIZON_TOL = 0.04f
    const val TILT_TOL = 1.5f

    fun guideFor(scene: Scene, subject: Subject?): GuideType = when (scene) {
        Scene.LANDSCAPE, Scene.SUNSET, Scene.BEACH -> GuideType.HORIZON
        Scene.MACRO -> GuideType.GOLDEN
        // Plato visto desde arriba: centrado y simétrico.
        Scene.FOOD -> if (subject == null || (subject.box.width() * subject.box.height() > 0.2f &&
                abs(subject.box.centerX() - 0.5f) < 0.15f && abs(subject.box.centerY() - 0.5f) < 0.15f)) GuideType.CENTER else GuideType.THIRDS
        else -> GuideType.THIRDS
    }

    /**
     * @param heading dirección de movimiento del sujeto (si no es un rostro), en anchos/s.
     */
    fun plan(scene: Scene, subject: Subject?, horizon: Horizon?, heading: PointF? = null): CompositionPlan {
        val guide = guideFor(scene, subject)
        if (guide == GuideType.HORIZON) return planHorizon(scene, horizon)
        val s = subject ?: return CompositionPlan(guide)
        if (s.box.width() > 0.6f) return CompositionPlan(guide) // muy cerca: no hay margen para mover

        val anchor = PointF(s.anchorX, s.anchorY)
        if (guide == GuideType.CENTER) return finish(guide, anchor, PointF(0.5f, 0.5f), null)

        val a = if (guide == GuideType.GOLDEN) PHI else THIRD
        val cols = floatArrayOf(a, 1f - a)
        // Espacio hacia donde mira (rostro) o hacia donde se mueve (animal, objeto).
        val dir = s.look ?: heading?.takeIf { hypot(it.x, it.y) > 0.08f }?.let { PointF(it.x / 0.2f, it.y / 0.2f) }
        var why: String? = null
        val tx = when {
            dir != null && dir.x >= 0.35f -> { why = if (s.look != null) "deja espacio hacia donde mira" else "deja espacio hacia donde va"; cols[0] }
            dir != null && dir.x <= -0.35f -> { why = if (s.look != null) "deja espacio hacia donde mira" else "deja espacio hacia donde va"; cols[1] }
            else -> cols.minByOrNull { abs(it - anchor.x) }!!
        }
        val ty = when {
            s.isFace -> a // ojos sobre la línea superior
            dir != null && dir.y >= 0.35f -> a
            dir != null && dir.y <= -0.35f -> 1f - a
            else -> cols.minByOrNull { abs(it - anchor.y) }!!
        }
        return finish(guide, anchor, PointF(tx, ty), why)
    }

    private fun finish(guide: GuideType, anchor: PointF, target: PointF, why: String?): CompositionPlan {
        val dx = target.x - anchor.x
        val dy = target.y - anchor.y
        val aligned = hypot(dx, dy * 4f / 3f) < ALIGN_TOL
        val text = when {
            aligned -> null
            // Para mover al sujeto hacia la izquierda del encuadre hay que girar el teléfono a la derecha.
            abs(dx) >= abs(dy) -> if (dx < 0) "Mueve el teléfono a la derecha →" else "← Mueve el teléfono a la izquierda"
            // Para subir al sujeto en el encuadre hay que apuntar más abajo.
            else -> if (dy < 0) "Apunta un poco más abajo ↓" else "Apunta un poco más arriba ↑"
        }
        return CompositionPlan(guide, anchor, target, instruction = text?.let { Instruction(it, why) }, aligned = aligned)
    }

    private fun planHorizon(scene: Scene, horizon: Horizon?): CompositionPlan {
        // Atardecer y playa: protagonismo al cielo (horizonte abajo). Paisaje: al suelo (arriba).
        val targetY = if (scene == Scene.LANDSCAPE) THIRD else 1f - THIRD
        val h = horizon ?: return CompositionPlan(GuideType.HORIZON, horizonTargetY = targetY)
        val dy = h.y - targetY
        val text = when {
            // Horizonte que baja a la derecha → girar el teléfono en sentido horario lo endereza.
            h.tiltDeg > TILT_TOL -> "Gira el teléfono un poco ↻"
            h.tiltDeg < -TILT_TOL -> "↺ Gira el teléfono un poco"
            // Horizonte por debajo de la guía: hay que subirlo en el encuadre → apuntar más abajo.
            dy > HORIZON_TOL -> "Apunta un poco más abajo ↓"
            dy < -HORIZON_TOL -> "Apunta un poco más arriba ↑"
            else -> null
        }
        val why = when {
            abs(h.tiltDeg) > TILT_TOL -> "el horizonte está torcido ${"%.1f".format(abs(h.tiltDeg))}°"
            scene == Scene.LANDSCAPE -> "más suelo: el primer plano es el protagonista"
            else -> "más cielo: es el protagonista"
        }
        return CompositionPlan(
            GuideType.HORIZON, horizon = h, horizonTargetY = targetY,
            instruction = text?.let { Instruction(it, why) },
            aligned = text == null,
        )
    }
}
