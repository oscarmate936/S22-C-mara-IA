package com.iacamara.app.ai

import android.graphics.PointF
import com.iacamara.app.camera.Horizon
import kotlin.math.abs
import kotlin.math.hypot

/** Tipo de guía que conviene para la escena. */
enum class GuideType { THIRDS, GOLDEN, CENTER, HORIZON }

/** Indicación para la persona. */
data class Instruction(val text: String, val why: String? = null)

/**
 * Plan de composición. Todos los puntos están en el marco vertical de la PANTALLA (listos para
 * dibujar); las indicaciones se expresan como sostienes el teléfono.
 */
data class CompositionPlan(
    val guide: GuideType,
    /** Dónde está ahora el punto importante (ojos, centro del sujeto). */
    val anchor: PointF? = null,
    /** Dónde debería ir. */
    val target: PointF? = null,
    /** Horizonte detectado (dos extremos) y línea donde debería quedar. */
    val horizonLine: Pair<PointF, PointF>? = null,
    val targetLine: Pair<PointF, PointF>? = null,
    val instruction: Instruction? = null,
    val aligned: Boolean = false,
    /** Hay un horizonte real detectado (no solo la guía). */
    val horizonFound: Boolean = false,
)

/**
 * El "compositor": elige la guía según la escena, decide dónde colocar al sujeto (dejando
 * espacio hacia donde mira o se mueve) o el horizonte, y traduce la diferencia a una
 * indicación simple. Trabaja en el marco de cómo sostienes el teléfono (vertical u horizontal)
 * y devuelve el dibujo en el marco de la pantalla.
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
     * @param subject sujeto en el marco de la pantalla.
     * @param horizon horizonte en el marco del teléfono (como lo sostienes).
     * @param heading dirección de movimiento del sujeto en el marco de la pantalla (anchos/s).
     * @param deviceDelta giro del marco del teléfono respecto a la pantalla (0, 90, 180, 270).
     * @param mirrored vista espejada (cámara frontal): mover el teléfono a la derecha mueve la imagen a la derecha.
     * @param deviceTiltCw inclinación del teléfono medida por el nivel (grados, horario). Es la
     *        referencia para enderezar: las colinas pueden estar inclinadas, la gravedad no.
     * @param aspectHW alto/ancho del visor en vertical (3:4 → 4/3).
     */
    fun plan(
        scene: Scene,
        subject: Subject?,
        horizon: Horizon?,
        heading: PointF? = null,
        deviceDelta: Int = 0,
        mirrored: Boolean = false,
        deviceTiltCw: Float? = null,
        aspectHW: Float = 4f / 3f,
    ): CompositionPlan {
        val d = ((deviceDelta % 360) + 360) % 360
        val devAspect = if (d % 180 == 0) aspectHW else 1f / aspectHW
        val guide = guideFor(scene, subject)
        val dev = if (guide == GuideType.HORIZON) planHorizon(scene, horizon, deviceTiltCw, devAspect, mirrored)
        else planSubject(guide, subject?.let { toDevice(it, d) }, heading?.let { Orient.vectorFromPortrait(it.x, it.y, d) }, devAspect, mirrored)
        return toScreen(dev, d)
    }

    // ------------------------------------------------------------------ marco del teléfono

    private class DevSubject(val anchor: PointF, val width: Float, val box: android.graphics.RectF, val isFace: Boolean, val look: PointF?)

    private fun toDevice(s: Subject, d: Int): DevSubject {
        val (ax, ay) = Orient.fromPortrait(s.anchorX, s.anchorY, d)
        val a = Orient.fromPortrait(s.box.left, s.box.top, d)
        val b = Orient.fromPortrait(s.box.right, s.box.bottom, d)
        val box = android.graphics.RectF(minOf(a.first, b.first), minOf(a.second, b.second), maxOf(a.first, b.first), maxOf(a.second, b.second))
        return DevSubject(PointF(ax, ay), box.width(), box, s.isFace, s.look?.let { Orient.vectorFromPortrait(it.x, it.y, d) })
    }

    private fun planSubject(guide: GuideType, s: DevSubject?, heading: PointF?, aspect: Float, mirrored: Boolean): CompositionPlan {
        if (s == null || s.width > 0.6f) return CompositionPlan(guide) // sin sujeto, o tan cerca que no hay margen
        val anchor = s.anchor
        if (guide == GuideType.CENTER) return finish(guide, anchor, PointF(0.5f, 0.5f), null, aspect, mirrored)
        val a = if (guide == GuideType.GOLDEN) PHI else THIRD
        val cols = floatArrayOf(a, 1f - a)
        // Espacio hacia donde mira (rostro) o hacia donde se mueve (animal, objeto).
        val dir = s.look ?: heading?.takeIf { hypot(it.x, it.y) > 0.08f }?.let { PointF(it.x / 0.2f, it.y / 0.2f) }
        val reason = if (s.look != null) "deja espacio hacia donde mira" else "deja espacio hacia donde va"
        var why: String? = null
        val tx = when {
            dir != null && dir.x >= 0.35f -> { why = reason; cols[0] }
            dir != null && dir.x <= -0.35f -> { why = reason; cols[1] }
            else -> cols.minByOrNull { abs(it - anchor.x) }!!
        }
        val ty = when {
            s.isFace -> a // ojos sobre la línea superior
            dir != null && dir.y >= 0.35f -> a
            dir != null && dir.y <= -0.35f -> 1f - a
            else -> cols.minByOrNull { abs(it - anchor.y) }!!
        }
        return finish(guide, anchor, PointF(tx, ty), why, aspect, mirrored)
    }

    private fun finish(guide: GuideType, anchor: PointF, target: PointF, why: String?, aspect: Float, mirrored: Boolean): CompositionPlan {
        val dx = target.x - anchor.x
        val dy = target.y - anchor.y
        val aligned = hypot(dx, dy * aspect) < ALIGN_TOL
        // Cámara trasera: girar el teléfono a la derecha desplaza la escena a la izquierda del encuadre.
        // Cámara frontal (espejada): la desplaza a la derecha.
        val moveRight = if (mirrored) dx > 0 else dx < 0
        val text = when {
            aligned -> null
            abs(dx) >= abs(dy) -> if (moveRight) "Mueve el teléfono a la derecha →" else "← Mueve el teléfono a la izquierda"
            // Para subir al sujeto en el encuadre hay que apuntar más abajo.
            else -> if (dy < 0) "Apunta un poco más abajo ↓" else "Apunta un poco más arriba ↑"
        }
        return CompositionPlan(guide, anchor, target, instruction = text?.let { Instruction(it, why) }, aligned = aligned)
    }

    private fun planHorizon(scene: Scene, horizon: Horizon?, tiltCw: Float?, aspect: Float, mirrored: Boolean): CompositionPlan {
        // Atardecer y playa: protagonismo al cielo (horizonte abajo). Paisaje: al suelo (arriba).
        val targetY = if (scene == Scene.LANDSCAPE) THIRD else 1f - THIRD
        val targetLine = PointF(0f, targetY) to PointF(1f, targetY)
        // Enderezar: con el nivel del teléfono (la gravedad), no con la forma del terreno.
        // (Girar el teléfono es un movimiento físico: no depende de que la vista esté espejada.)
        val tilt = tiltCw ?: 0f
        val tiltText = when {
            tilt > TILT_TOL -> "Gira el teléfono un poco ↺"
            tilt < -TILT_TOL -> "↻ Gira el teléfono un poco"
            else -> null
        }
        val tiltWhy = "el teléfono está inclinado ${"%.1f".format(abs(tilt))}°"
        val h = horizon
        if (h == null) {
            return CompositionPlan(GuideType.HORIZON, targetLine = targetLine,
                instruction = tiltText?.let { Instruction(it, tiltWhy) }, aligned = false)
        }
        val dy = h.y - targetY
        val heightText = when {
            // Horizonte por debajo de la guía: hay que subirlo en el encuadre → apuntar más abajo.
            dy > HORIZON_TOL -> "Apunta un poco más abajo ↓"
            dy < -HORIZON_TOL -> "Apunta un poco más arriba ↑"
            else -> null
        }
        val text = tiltText ?: heightText
        val why = when {
            tiltText != null -> tiltWhy
            scene == Scene.LANDSCAPE -> "más suelo: el primer plano es el protagonista"
            else -> "más cielo: es el protagonista"
        }
        // Horizonte detectado, con su inclinación real (en coordenadas normalizadas).
        val slope = kotlin.math.tan(Math.toRadians(h.tiltDeg.toDouble())).toFloat() / aspect
        val line = PointF(0f, h.y - slope * 0.5f) to PointF(1f, h.y + slope * 0.5f)
        return CompositionPlan(
            GuideType.HORIZON, horizonLine = line, targetLine = targetLine,
            instruction = text?.let { Instruction(it, why) }, aligned = text == null, horizonFound = true,
        )
    }

    // ------------------------------------------------------------------ a la pantalla

    private fun p(pt: PointF, d: Int): PointF {
        val (x, y) = Orient.toPortrait(pt.x, pt.y, d)
        return PointF(x, y)
    }

    private fun toScreen(plan: CompositionPlan, d: Int): CompositionPlan {
        if (d == 0) return plan
        return plan.copy(
            anchor = plan.anchor?.let { p(it, d) },
            target = plan.target?.let { p(it, d) },
            horizonLine = plan.horizonLine?.let { p(it.first, d) to p(it.second, d) },
            targetLine = plan.targetLine?.let { p(it.first, d) to p(it.second, d) },
        )
    }
}
