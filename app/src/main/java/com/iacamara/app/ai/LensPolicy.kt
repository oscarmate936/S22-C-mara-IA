package com.iacamara.app.ai

import kotlin.math.abs
import kotlin.math.ln

/**
 * Cuándo cambiar de lente solo. Un fotógrafo elige el lente UNA vez para la toma y lo mantiene:
 *  • solo con una escena clara y estable,
 *  • nunca otra vez durante un rato después de un cambio,
 *  • una mirada rápida a otra cosa no cuenta: después de un cambio, la nueva escena tiene que
 *    mantenerse más tiempo para volver a cambiar,
 *  • y la elección de la persona siempre manda.
 */
class LensPolicy(private val clock: () -> Long = { System.currentTimeMillis() }) {
    private var stableScene: Scene? = null
    private var stableSince = 0L
    private var handledScene: Scene? = null
    private var lastAutoSwitch = 0L
    private var userAt = 0L

    /** La persona eligió un lente o hizo zoom: se respeta. */
    fun onUserLens() {
        userAt = clock()
        handledScene = stableScene
    }

    /**
     * @param wanted lente que recomienda el cerebro para la escena (null = ninguno en particular).
     * @param current factor actual (incluye zoom).
     * @param available factores disponibles (0.6, 1, 2, 3…).
     * @return factor al que cambiar ahora, o null para no cambiar.
     */
    fun decide(scene: Scene, confidence: Float, wanted: Float?, current: Float, available: List<Float>): Float? {
        val now = clock()
        if (scene != stableScene) { stableScene = scene; stableSince = now; return null }
        // Ya hubo un cambio hace poco: hace falta más certeza (una mirada rápida no basta).
        val needStable = if (lastAutoSwitch != 0L && now - lastAutoSwitch < COMMIT_WINDOW_MS) COMMIT_STABLE_MS else STABLE_MS
        if (now - stableSince < needStable || confidence < MIN_CONFIDENCE) return null
        if (userAt != 0L && now - userAt < USER_HOLD_MS) return null
        if (lastAutoSwitch != 0L && now - lastAutoSwitch < COOLDOWN_MS) return null
        if (handledScene == scene) return null
        val w = wanted ?: return null
        val target = available.minByOrNull { abs(ln(it / w)) } ?: return null
        handledScene = scene
        if (abs(target - current) < 0.3f) return null
        lastAutoSwitch = now
        return target
    }

    companion object {
        const val STABLE_MS = 2500L
        const val MIN_CONFIDENCE = 0.6f
        const val USER_HOLD_MS = 15_000L
        const val COOLDOWN_MS = 20_000L
        /** Tras un cambio, durante este tiempo la nueva escena debe durar [COMMIT_STABLE_MS]. */
        const val COMMIT_WINDOW_MS = 60_000L
        const val COMMIT_STABLE_MS = 5_000L
    }
}
