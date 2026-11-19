package com.iacamara.app.ai

import com.iacamara.app.camera.ExposureMath
import com.iacamara.app.camera.WhiteBalance
import kotlin.math.abs
import kotlin.math.atan

/** Una decisión del cerebro explicada en lenguaje simple: "1/500 s · congela a tu mascota". */
data class Reason(val value: String, val why: String)

/** Todo lo que el cerebro sabe en un instante. */
class BrainInput(
    val scene: Scene,
    val subject: Subject?,
    /** Velocidad del sujeto en "anchos de visor por segundo". */
    val subjectSpeed: Float,
    /** Giro del teléfono (rad/s): mide el pulso. */
    val gyro: Float,
    /** Teléfono totalmente quieto (trípode o apoyado). */
    val steady: Boolean,
    /** Focal equivalente actual en mm (incluye zoom). */
    val equivalentMm: Float,
    val meanLuma: Float,
    val clippedHighlights: Float,
    val liveIso: Int,
    /** Velocidad que eligió el automático de la cámara (cuando el cerebro no la controla). */
    val aeShutterNs: Long,
    val isoMin: Int,
    val isoMax: Int,
    /** ISO que está usando el fotómetro propio cuando el cerebro fija la velocidad. */
    val priorityIso: Int,
    val shutterValues: List<Long>,
)

class BrainDecision(
    val ev: Float,
    val wb: WhiteBalance,
    /** Velocidad fijada por el cerebro (null = la decide la cámara). */
    val shutterNs: Long?,
    /** Lente recomendado para la escena (null = no cambiar). */
    val lensFactor: Float?,
    val reasons: List<Reason>,
)

/**
 * El "cerebro fotógrafo". Aplica, de forma continua y suave, las decisiones que tomaría
 * un profesional: velocidad según el movimiento real, exposición según la escena,
 * protección de altas luces, blancos y exposición larga si detecta trípode.
 */
class PhotoBrain {
    private var smoothEv = 0f
    private var lowIsoSince = 0L

    fun decide(i: BrainInput, engaged: Long?): BrainDecision {
        val reasons = mutableListOf<Reason>()
        val now = System.currentTimeMillis()

        // ------------------------------------------------ Exposición
        var targetEv = i.scene.recipe?.ev ?: 0f
        var evWhy = evReason(i.scene)
        if (i.clippedHighlights > 0.02f) {
            targetEv -= 0.3f + ((i.clippedHighlights - 0.02f) * 15f).coerceAtMost(0.7f)
            evWhy = "protejo las altas luces"
        }
        targetEv = targetEv.coerceIn(-2f, 1f)
        // Cambios suaves (máx. 1/3 de paso por ciclo) para que la imagen no "bombee".
        smoothEv += (targetEv - smoothEv).coerceIn(-0.34f, 0.34f)
        if (abs(smoothEv) >= 0.15f) reasons += Reason(ExposureMath.formatEv(smoothEv) + " EV", evWhy)

        // ------------------------------------------------ Blancos
        val wb = i.scene.recipe?.wb ?: WhiteBalance.AUTO
        if (wb != WhiteBalance.AUTO) {
            reasons += Reason("Blancos ${wb.label}", if (i.scene == Scene.SUNSET) "conserva el tono dorado" else "colores naturales de luz de día")
        }

        // ------------------------------------------------ Velocidad
        var maxT = Double.MAX_VALUE
        var shutterWhy = ""
        fun limit(t: Double, why: String) { if (t < maxT) { maxT = t; shutterWhy = why } }

        val hfov = 2.0 * atan(18.0 / i.equivalentMm.coerceAtLeast(8f)) // radianes
        if (!i.steady) limit(1.0 / (2.0 * i.equivalentMm), "regla de pulso a ${i.equivalentMm.toInt()} mm")
        when (i.scene) {
            Scene.PET -> limit(1.0 / 500, "congela a tu mascota")
            Scene.ANIMAL -> limit(1.0 / 1000, "congela el movimiento")
            Scene.PORTRAIT -> limit(1.0 / 125, "evita retratos movidos")
            Scene.MACRO -> if (!i.steady) limit(1.0 / 250, "de cerca todo tiembla más")
            else -> Unit
        }
        if (i.subjectSpeed > 0.04f) limit(0.003 / i.subjectSpeed, "el sujeto se mueve")
        if (i.gyro > 0.03f) limit(0.002 * hfov / i.gyro, "compenso el movimiento de tu mano")

        var shutter: Long? = null
        val tripodNight = i.steady && i.liveIso >= 400 && i.subject == null &&
            i.scene in setOf(Scene.NIGHT, Scene.LANDSCAPE, Scene.CITY, Scene.GENERAL, Scene.SUNSET)
        if (tripodNight) {
            shutter = snap(250_000_000L, i.shutterValues)
            shutterWhy = "trípode detectado: ISO bajo, imagen limpia"
        } else if (maxT < Double.MAX_VALUE) {
            val wanted = (maxT * 1e9).toLong()
            if (engaged == null) {
                // Solo intervengo si el automático de la cámara eligió algo más lento de lo necesario.
                if (i.aeShutterNs > wanted * 1.15) shutter = snap(wanted, i.shutterValues)
            } else {
                // Si sobra luz (ISO al mínimo un rato), devuelvo el control al automático.
                if (i.priorityIso <= i.isoMin * 1.1f) {
                    if (lowIsoSince == 0L) lowIsoSince = now
                } else lowIsoSince = 0L
                shutter = if (lowIsoSince != 0L && now - lowIsoSince > 2000) {
                    lowIsoSince = 0L
                    null
                } else {
                    var t = wanted
                    if (i.priorityIso >= i.isoMax * 0.95f) {
                        // Falta luz: un fotógrafo cede un poco de velocidad antes que llenar de ruido.
                        t = (wanted * 2).coerceAtMost((1e9 / (2.0 * i.equivalentMm)).toLong().coerceAtLeast(wanted))
                        shutterWhy = "poca luz: equilibrio entre nitidez y ruido"
                    }
                    snap(t, i.shutterValues)
                }
            }
        }
        if (shutter != null) {
            reasons.add(0, Reason(ExposureMath.formatShutter(shutter), shutterWhy))
        }

        // ------------------------------------------------ Lente
        var lens = i.scene.recipe?.lensFactor
        when (i.scene) {
            Scene.GENERAL -> lens = null
            Scene.PORTRAIT -> if ((i.subject?.box?.height() ?: 0f) > 0.3f) lens = null // ya estás cerca
            Scene.ANIMAL -> if ((i.subject?.box?.width() ?: 0f) > 0.35f) lens = null
            else -> Unit
        }
        // Con poca luz el teleobjetivo (f/2.4) rinde peor: el 1× (f/1.8) es más luminoso.
        if (lens != null && lens > 1.5f && i.meanLuma < 0.2f && i.liveIso >= 1600) lens = 1f

        return BrainDecision(smoothEv, wb, shutter, lens, reasons.take(3))
    }

    fun reset() {
        smoothEv = 0f
        lowIsoSince = 0L
    }

    /** Elige el valor estándar más rápido que cumple el límite (nunca más lento). */
    private fun snap(t: Long, values: List<Long>): Long {
        if (values.isEmpty()) return t
        return values.filter { it <= t }.maxOrNull() ?: values.first()
    }

    private fun evReason(s: Scene) = when (s) {
        Scene.SUNSET -> "conserva los colores del cielo"
        Scene.BEACH -> "evita quemar arena y agua"
        Scene.PORTRAIT -> "piel más luminosa"
        Scene.FOOD -> "aspecto más fresco"
        Scene.MACRO -> "más detalle en las zonas claras"
        Scene.LANDSCAPE -> "cielo con detalle"
        Scene.NIGHT -> "mantiene la atmósfera nocturna"
        else -> "exposición equilibrada"
    }
}
