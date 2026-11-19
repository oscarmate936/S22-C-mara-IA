package com.iacamara.app.ai

import kotlin.math.abs
import kotlin.math.max

/** Una cara vista en una toma candidata (coordenadas normalizadas 0..1 del fotograma). */
data class FaceObs(
    val left: Float, val top: Float, val right: Float, val bottom: Float,
    /** Probabilidad de ojos abiertos (el peor de los dos), o null si no se pudo medir. */
    val eyesOpen: Float?,
    /** Probabilidad de sonrisa, o null. */
    val smile: Float?,
) {
    val cx get() = (left + right) / 2
    val cy get() = (top + bottom) / 2
    val width get() = right - left
}

/** Una toma candidata de la ráfaga. */
data class Candidate(
    val index: Int,
    /** Resolución completa (tomada al disparar) o fotograma de la memoria (instante previo). */
    val fullRes: Boolean,
    /** Nitidez (energía del laplaciano en la zona del sujeto). */
    val sharpness: Float,
    val faces: List<FaceObs>,
)

/** Reemplazar la cara [baseFace] de la toma elegida por la cara [donorFace] de la toma [donor]. */
data class FaceSwap(val baseFace: Int, val donor: Int, val donorFace: Int)

/**
 * Elige "la foto perfecta" de una ráfaga, como lo haría un fotógrafo al revisar sus tomas:
 * nítida, con todos los ojos abiertos y, si se puede, sonriendo. En fotos de grupo propone
 * tomar la cara de alguien que parpadeó desde otra toma donde la tenga bien.
 */
object MomentScorer {
    const val EYES_CLOSED = 0.4f
    const val EYES_OPEN = 0.7f

    fun score(c: Candidate, maxSharp: Float): Float {
        val s = if (maxSharp > 0f) (c.sharpness / maxSharp).coerceIn(0f, 1f) else 0f
        if (c.faces.isEmpty()) return s
        // La foto vale lo que vale la peor cara: basta una persona con los ojos cerrados para arruinarla.
        val eyes = c.faces.minOf { it.eyesOpen ?: 0.8f }
        val smile = c.faces.map { it.smile ?: 0.5f }.average().toFloat()
        return 0.45f * s + 0.40f * eyes + 0.15f * smile
    }

    /**
     * Índice de la mejor toma. Se prefieren las de resolución completa: un fotograma de la memoria
     * (menor resolución) solo gana si es claramente mejor (p. ej. el único sin parpadeo o sin movimiento).
     */
    fun choose(cands: List<Candidate>): Int {
        require(cands.isNotEmpty())
        val maxSharp = cands.maxOf { it.sharpness }
        val scored = cands.map { it to score(it, maxSharp) }
        val bestFull = scored.filter { it.first.fullRes }.maxByOrNull { it.second }
        val bestAny = scored.maxByOrNull { it.second }!!
        return if (bestFull != null && (bestAny.first.fullRes || bestFull.second >= bestAny.second * 0.9f)) bestFull.first.index
        else bestAny.first.index
    }

    /**
     * Caras con ojos cerrados en la toma elegida que se pueden corregir con otra toma de
     * resolución completa (la misma persona, en la misma zona, con los ojos abiertos y nítida).
     */
    fun faceSwaps(base: Candidate, others: List<Candidate>): List<FaceSwap> {
        if (!base.fullRes) return emptyList()
        val out = ArrayList<FaceSwap>()
        base.faces.forEachIndexed { fi, f ->
            if ((f.eyesOpen ?: 1f) >= EYES_CLOSED) return@forEachIndexed
            var best: FaceSwap? = null
            var bestEyes = EYES_OPEN
            for (o in others) {
                if (!o.fullRes || o.index == base.index || o.sharpness < base.sharpness * 0.75f) continue
                o.faces.forEachIndexed { oi, g ->
                    val same = abs(g.cx - f.cx) < max(f.width, g.width) * 0.5f && abs(g.cy - f.cy) < max(f.width, g.width) * 0.5f
                    val eyes = g.eyesOpen ?: 0f
                    if (same && eyes > bestEyes) { bestEyes = eyes; best = FaceSwap(fi, o.index, oi) }
                }
            }
            best?.let { out += it }
        }
        return out
    }
}

/** Modo de disparo automático. */
enum class AutoShutterMode(val label: String, val hint: String) {
    OFF("Apagado", ""),
    SMILE("Sonrisa", "SONRÍE PARA DISPARAR"),
    LOOK("Mirada", "MIRA A LA CÁMARA"),
    HAND("Mano", "LEVANTA LA MANO"),
}

/** Lo que la IA ve de una cara para el disparo automático. */
data class FaceCue(val smile: Float?, val eyesOpen: Float?, val yaw: Float, val pitch: Float)

/**
 * Disparo automático: la foto se toma sola cuando todos sonríen (o miran a la cámara, o alguien
 * levanta la mano) durante un instante. Después espera a que la condición deje de cumplirse antes
 * de volver a disparar, para no hacer decenas de fotos seguidas.
 */
class AutoShutter(private val clock: () -> Long = { System.currentTimeMillis() }) {
    enum class State { WAITING, HOLDING, FIRE, COOLDOWN }

    var mode: AutoShutterMode = AutoShutterMode.OFF
        set(v) { field = v; since = 0L; armed = true; lastFire = 0L }
    private var since = 0L
    private var armed = true
    private var lastFire = 0L
    /** Progreso 0..1 del instante que se debe mantener. */
    var progress = 0f
        private set

    fun conditionMet(faces: List<FaceCue>, handRaised: Boolean): Boolean = when (mode) {
        AutoShutterMode.OFF -> false
        AutoShutterMode.SMILE -> faces.isNotEmpty() && faces.all { (it.smile ?: 0f) > 0.7f && (it.eyesOpen ?: 1f) > 0.5f }
        AutoShutterMode.LOOK -> faces.isNotEmpty() && faces.all { abs(it.yaw) < 12f && abs(it.pitch) < 12f && (it.eyesOpen ?: 1f) > 0.6f }
        AutoShutterMode.HAND -> handRaised
    }

    fun update(faces: List<FaceCue>, handRaised: Boolean): State {
        val now = clock()
        if (mode == AutoShutterMode.OFF) { progress = 0f; return State.WAITING }
        val ok = conditionMet(faces, handRaised)
        if (!armed) {
            // Re-armar solo cuando la condición deja de cumplirse (y pasó la pausa).
            if (!ok && now - lastFire > COOLDOWN_MS) armed = true
            progress = 0f
            return State.COOLDOWN
        }
        if (!ok) { since = 0L; progress = 0f; return State.WAITING }
        if (since == 0L) since = now
        progress = ((now - since).toFloat() / HOLD_MS).coerceIn(0f, 1f)
        if (now - since >= HOLD_MS) {
            armed = false
            lastFire = now
            since = 0L
            progress = 0f
            return State.FIRE
        }
        return State.HOLDING
    }

    companion object {
        /** Tiempo que debe mantenerse la sonrisa / mirada / mano. */
        const val HOLD_MS = 600L
        const val COOLDOWN_MS = 2500L
    }
}
