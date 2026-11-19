package com.iacamara.app.ai

import com.iacamara.app.camera.ExposureMath
import com.iacamara.app.camera.WhiteBalance
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.log2

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
    /** Zonas grandes quemadas (cielo, ventana) — no cuenta lámparas ni el sol. */
    val clippedArea: Float = 0f,
    /** Zonas en sombra profunda. */
    val shadowArea: Float = 0f,
    /** Luz medida sobre el sujeto (0..1), o null. */
    val subjectLuma: Float? = null,
    /** La IA ve nieve: hay que exponer "a la derecha" para que salga blanca. */
    val snowy: Boolean = false,
    /** Percentil 98 de la luz (0..1). */
    val p98: Float = 0.9f,
    /** Movimiento de toda la imagen en el encuadre (anchos/s), medido por flujo óptico. */
    val imageMotion: Float = 0f,
)

class BrainDecision(
    val ev: Float,
    val wb: WhiteBalance,
    /** Velocidad fijada por el cerebro (null = la decide la cámara). */
    val shutterNs: Long?,
    /** Lente recomendado para la escena (null = no cambiar). */
    val lensFactor: Float?,
    val reasons: List<Reason>,
    /** La escena tiene más contraste del que capta el sensor: conviene HDR (varias exposiciones). */
    val hdr: Boolean = false,
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

        // ------------------------------------------------ Exposición (medición inteligente)
        // 1) Clave de la escena: lo que un fotómetro "promedio" hace mal.
        var targetEv = when {
            // Nieve: "exponer a la derecha" — subir hasta que lo más claro quede justo sin quemarse.
            i.snowy -> (smoothEv + 2.2f * log2(SNOW_WHITE / i.p98.coerceIn(0.05f, 1f))).coerceIn(0.3f, 2f)
            else -> i.scene.recipe?.ev ?: 0f
        }
        var evWhy = if (i.snowy) "la nieve debe verse blanca, no gris" else evReason(i.scene)

        // 2) Prioridad al sujeto: se mide la luz SOBRE la cara (o la mascota) y se expone para él,
        //    aunque el fondo sea muy brillante (contraluz) o muy oscuro (escenario, noche).
        var backlit = false
        var subjectActive = false
        val subj = i.subject
        val sl = i.subjectLuma
        if (subj != null && sl != null && i.scene in SUBJECT_SCENES) {
            val goal = if (subj.isFace) FACE_TARGET else SUBJECT_TARGET
            // Pasos que faltan (+) o sobran (−) en el sujeto con el ajuste actual.
            val corr = (2.2f * log2(goal / sl.coerceIn(0.02f, 0.98f))).coerceIn(-2f, 2f)
            val weight = if (subj.isFace) 0.85f else 0.5f
            // El sujeto "sostiene" la exposición: aunque ya esté bien (corr ≈ 0) se mantiene
            // el ajuste actual, en vez de volver a la regla general (eso hacía oscilar).
            targetEv = targetEv * (1 - weight) + (smoothEv + corr) * weight
            subjectActive = true
            backlit = subj.isFace && (corr > 0.6f || (i.clippedArea > BIG_CLIP && smoothEv > 0.3f))
            evWhy = when {
                backlit -> "cara a contraluz: la ilumino"
                corr > 0.2f || smoothEv > 0.3f -> "expongo para ${subj.label.lowercase()}"
                corr < -0.2f || smoothEv < -0.3f -> "evito quemar ${subj.label.lowercase()}"
                else -> evWhy
            }
        }

        // 3) Protección de altas luces SOLO si una zona grande se quema (cielo, ventana).
        //    Una lámpara o el sol se dejan quemar, como haría un fotógrafo.
        var hdr = false
        if (i.clippedArea > BIG_CLIP) {
            if (subjectActive && subj!!.isFace) {
                // La cara manda: se queda bien expuesta y la ventana o el cielo se recuperan con HDR.
                hdr = true
            } else {
                val protect = 0.3f + ((i.clippedArea - BIG_CLIP) * 10f).coerceAtMost(0.7f)
                targetEv -= if (subjectActive) protect * 0.5f else protect
                evWhy = "protejo el cielo y las luces grandes"
            }
        }
        // 4) Contraste que el sensor no abarca: cielo quemado Y sombras profundas a la vez.
        if (i.clippedArea > BIG_CLIP && i.shadowArea > 0.08f && i.scene != Scene.SUNSET && i.scene != Scene.NIGHT) hdr = true
        if (i.subjectSpeed > 0.15f || i.gyro > 0.15f || i.imageMotion > 0.12f) hdr = false // HDR con movimiento deja fantasmas

        targetEv = targetEv.coerceIn(-2f, 2f)
        // Cambios suaves (máx. 1/3 de paso por ciclo) para que la imagen no "bombee".
        smoothEv += (targetEv - smoothEv).coerceIn(-0.34f, 0.34f)
        if (abs(smoothEv) >= 0.15f) reasons += Reason(ExposureMath.formatEv(smoothEv) + " EV", evWhy)
        if (hdr) reasons.add(0, Reason("HDR", "cielo y sombras con detalle a la vez"))

        // ------------------------------------------------ Blancos
        val wb = i.scene.recipe?.wb ?: WhiteBalance.AUTO
        if (wb != WhiteBalance.AUTO) {
            reasons += Reason(wb.label, if (i.scene == Scene.SUNSET) "blancos: conservo el tono dorado" else "blancos: colores naturales de día")
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
        // Medido en la propia imagen: incluye pulso y paneo tal como afectan a la foto.
        if (i.imageMotion > 0.03f) limit(0.002 / i.imageMotion, "la imagen se desplaza: compenso")

        var shutter: Long? = null
        // Si ya estamos en exposición larga, el ISO bajó justamente por eso: no soltarla (evita oscilar).
        val alreadyLong = engaged != null && engaged >= 200_000_000L
        val tripodNight = i.steady && (i.liveIso >= 400 || alreadyLong) && i.subject == null &&
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

        return BrainDecision(smoothEv, wb, shutter, lens, reasons.take(3), hdr)
    }

    companion object {
        /** Luz objetivo para un rostro (piel bien expuesta, un poco más clara que el gris medio). */
        const val FACE_TARGET = 0.50f
        const val SUBJECT_TARGET = 0.45f
        /** Fracción del encuadre quemada a partir de la cual es "zona grande" y no un punto de luz. */
        const val BIG_CLIP = 0.03f
        /** Brillo al que debe llegar lo más claro de la nieve (blanco con detalle, sin quemar). */
        const val SNOW_WHITE = 0.95f
        private val SUBJECT_SCENES = setOf(Scene.PORTRAIT, Scene.PET, Scene.ANIMAL, Scene.FOOD, Scene.MACRO, Scene.GENERAL)
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
        Scene.BEACH -> "la arena debe verse luminosa"
        Scene.PORTRAIT -> "piel más luminosa"
        Scene.FOOD -> "aspecto más fresco"
        Scene.MACRO -> "más detalle en las zonas claras"
        Scene.LANDSCAPE -> "cielo con detalle"
        Scene.NIGHT -> "mantiene la atmósfera nocturna"
        else -> "exposición equilibrada"
    }
}
