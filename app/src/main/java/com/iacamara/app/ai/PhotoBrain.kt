package com.iacamara.app.ai

import com.iacamara.app.camera.ExposureMath
import com.iacamara.app.camera.S22Plus
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
    /** Factor de lente/zoom actual (1 = principal sin zoom). */
    val zoomFactor: Float = 1f,
    /** Cámara frontal. */
    val front: Boolean = false,
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
class PhotoBrain(private val clock: () -> Long = { System.currentTimeMillis() }) {
    private var smoothEv = 0f
    private var lowIsoSince = 0L
    /** Protección del cielo activa (con histéresis: entra al 3 %, sale por debajo del 1.5 %). */
    private var protecting = false
    /** Desde cuándo se pide una velocidad más lenta (se espera antes de hacerla). */
    private var slowerSince = 0L
    /** Desde cuándo ya no hace falta fijar la velocidad (se espera antes de soltarla). */
    private var releaseSince = 0L
    /** Picos recientes de movimiento (se olvidan en ~3 s): la velocidad se elige para lo peor del pulso. */
    private var motionPeak = 0f
    private var gyroPeak = 0f
    private var lastDecide = 0L

    fun decide(i: BrainInput, engaged: Long?): BrainDecision {
        val reasons = mutableListOf<Reason>()
        val now = clock()
        // Memoria de picos: el pulso sube y baja sin parar; un fotógrafo elige la velocidad para
        // los peores momentos de los últimos segundos y la mantiene, en vez de seguir cada temblor.
        val decay = if (lastDecide == 0L) 0f else kotlin.math.exp(-(now - lastDecide) / PEAK_MEMORY_MS.toFloat())
        lastDecide = now
        motionPeak = maxOf(i.imageMotion, motionPeak * decay)
        gyroPeak = maxOf(i.gyro, gyroPeak * decay)

        // ------------------------------------------------ Exposición (medición inteligente)
        // 1) Clave de la escena: lo que un fotómetro "promedio" hace mal.
        var targetEv = when {
            // Nieve: "exponer a la derecha" — subir hasta que lo más claro quede justo sin quemarse.
            i.snowy -> (smoothEv + 2.2f * log2(SNOW_WHITE / i.p98.coerceIn(0.05f, 1f))).coerceIn(0.3f, 2f)
            // Exposición de la escena calibrada con fotógrafos profesionales (FiveK); si no hay datos, la receta.
            else -> com.iacamara.app.processing.Calibration.SCENE_EV[i.scene.name] ?: i.scene.recipe?.ev ?: 0f
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
        // Histéresis: no encender y apagar la protección cuando lo quemado ronda el umbral.
        protecting = if (protecting) i.clippedArea > BIG_CLIP * 0.5f else i.clippedArea > BIG_CLIP
        if (protecting) {
            if (subjectActive && subj!!.isFace) {
                // La cara manda: se queda bien expuesta y la ventana o el cielo se recuperan con HDR.
                hdr = true
            } else {
                val protect = 0.3f + ((i.clippedArea - BIG_CLIP) * 10f).coerceAtMost(0.7f)
                targetEv -= if (subjectActive) protect * 0.5f else protect
                evWhy = "protejo el cielo y las luces grandes"
            }
        }
        // 3b) Altas luces "de profesional": aunque no se queme una zona grande, si lo más claro de la
        //     escena pasa del nivel en que los fotógrafos lo dejan (medido en FiveK), se baja un poco.
        //     Así un cielo luminoso o un atardecer no salen "lavados" como en el automático del teléfono.
        val ceil = com.iacamara.app.processing.Calibration.HIGHLIGHT_CEIL[i.scene.name]
        if (ceil != null && !i.snowy && !protecting && !(subjectActive && subj!!.isFace) && i.p98 > ceil) {
            val down = (2.2f * log2(i.p98 / ceil)).coerceIn(0f, 0.5f)
            if (down >= 0.15f) { targetEv -= down; evWhy = "luces al nivel de un profesional (sin quemar)" }
        }
        // 4) Contraste que el sensor no abarca: cielo quemado Y sombras profundas a la vez.
        if (i.clippedArea > BIG_CLIP && i.shadowArea > 0.08f && i.scene != Scene.NIGHT) hdr = true
        // Amanecer/atardecer: si el resplandor del horizonte se quema, el HDR recupera su color.
        // El HDR de la app solo reemplaza las luces quemadas: la silueta sigue oscura.
        val sunsetGlow = i.scene == Scene.SUNSET && (i.clippedArea > 0.01f || i.p98 > 0.97f)
        if (sunsetGlow) hdr = true
        if (i.subjectSpeed > 0.15f || i.gyro > 0.15f || i.imageMotion > 0.12f) hdr = false // HDR con movimiento deja fantasmas

        targetEv = targetEv.coerceIn(-2f, 2f)
        // Cambios suaves (máx. 1/3 de paso por ciclo) y zona muerta: diferencias menores a
        // 0.15 pasos no se corrigen, para que la imagen no "tiemble".
        if (abs(targetEv - smoothEv) >= 0.15f) smoothEv += (targetEv - smoothEv).coerceIn(-0.34f, 0.34f)
        if (abs(smoothEv) >= 0.15f) reasons += Reason(ExposureMath.formatEv(smoothEv) + " EV", evWhy)
        if (hdr) reasons.add(0, Reason("HDR", if (sunsetGlow) "recupero el color del cielo sin aclarar la silueta" else "cielo y sombras con detalle a la vez"))

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
        // Regla de pulso del lente real del S22+: el principal y el tele tienen estabilizador óptico
        // (≈2 pasos más lento sin que salga movida); el ultra gran angular y la frontal no.
        val spec = S22Plus.specForFactor(i.zoomFactor, i.front)
        val handheld = S22Plus.slowestHandheld(spec, i.equivalentMm)
        if (!i.steady) limit(handheld, if (spec.ois) "pulso a ${i.equivalentMm.toInt()} mm con estabilizador óptico" else "regla de pulso a ${i.equivalentMm.toInt()} mm")
        when (i.scene) {
            Scene.PET -> limit(1.0 / 500, "congela a tu mascota")
            Scene.ANIMAL -> limit(1.0 / 1000, "congela el movimiento")
            Scene.PORTRAIT -> limit(1.0 / 125, "evita retratos movidos")
            Scene.MACRO -> if (!i.steady) limit(1.0 / 250, "de cerca todo tiembla más")
            else -> Unit
        }
        if (i.subjectSpeed > 0.04f) limit(0.003 / i.subjectSpeed, "el sujeto se mueve")
        if (gyroPeak > 0.03f) limit(0.002 * hfov / gyroPeak, "compenso el movimiento de tu mano")
        // Medido en la propia imagen: incluye pulso y paneo tal como afectan a la foto.
        if (motionPeak > 0.03f) limit(0.002 / motionPeak, "compenso el pulso de tu mano")

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
                        t = (wanted * 2).coerceAtMost((1e9 * handheld).toLong().coerceAtLeast(wanted))
                        shutterWhy = "poca luz: equilibrio entre nitidez y ruido"
                    }
                    snap(t, i.shutterValues)
                }
            }
        }
        shutter = stabilize(shutter, engaged, now)
        if (shutter != null) {
            reasons.add(0, Reason(ExposureMath.formatShutter(shutter), shutterWhy.ifEmpty { "mantengo la velocidad estable" }))
        }

        // ------------------------------------------------ Lente
        var lens = i.scene.recipe?.lensFactor
        // Tamaño del sujeto como se vería en 1×: así la decisión no cambia después del zoom
        // (a 2× la cara se ve el doble de grande, pero sigue siendo la misma escena).
        val z = i.zoomFactor.coerceAtLeast(0.3f)
        when (i.scene) {
            Scene.GENERAL -> lens = null
            // Retrato: el lente que deja la cara a un tamaño cómodo (hasta ~45 % del alto).
            Scene.PORTRAIT -> i.subject?.let { s ->
                val h1 = s.box.height() / z
                if (lens != null && h1 * lens > 0.45f) lens = if (h1 > 0.45f) null else 1f
            }
            Scene.ANIMAL -> i.subject?.let { s ->
                val w1 = s.box.width() / z
                if (lens != null && w1 * lens > 0.6f) lens = null
            }
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
        /** Diferencia mínima (en pasos) para cambiar la velocidad. */
        const val MIN_CHANGE_STOPS = 0.6
        /** Tiempo que debe mantenerse la calma antes de ir más lento o soltar el control (acelerar es inmediato). */
        const val HOLD_MS = 3000L
        /** Cuánto se recuerdan los picos de movimiento. */
        const val PEAK_MEMORY_MS = 3000L
        private val SUBJECT_SCENES = setOf(Scene.PORTRAIT, Scene.PET, Scene.ANIMAL, Scene.FOOD, Scene.MACRO, Scene.GENERAL)
    }

    fun reset() {
        smoothEv = 0f
        lowIsoSince = 0L
        protecting = false
        slowerSince = 0L
        releaseSince = 0L
        motionPeak = 0f
        gyroPeak = 0f
        lastDecide = 0L
    }

    /**
     * Estabilidad de la velocidad, como un fotógrafo: no se cambia por diferencias pequeñas,
     * se acelera enseguida si de verdad hace falta (evitar fotos movidas), y se va más lento
     * o se suelta el control solo si la necesidad se mantiene un momento.
     */
    private fun stabilize(wanted: Long?, engaged: Long?, now: Long): Long? {
        if (engaged == null) { slowerSince = 0L; releaseSince = 0L; return wanted }
        if (wanted == null) {
            if (releaseSince == 0L) releaseSince = now
            return if (now - releaseSince >= HOLD_MS) { releaseSince = 0L; null } else engaged
        }
        releaseSince = 0L
        val stops = log2(engaged.toDouble() / wanted)
        return when {
            stops >= MIN_CHANGE_STOPS -> { slowerSince = 0L; wanted }
            stops > -MIN_CHANGE_STOPS -> { slowerSince = 0L; engaged }
            else -> {
                if (slowerSince == 0L) slowerSince = now
                if (now - slowerSince >= HOLD_MS) { slowerSince = 0L; wanted } else engaged
            }
        }
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
