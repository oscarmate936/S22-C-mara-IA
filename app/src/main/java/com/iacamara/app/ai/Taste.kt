package com.iacamara.app.ai

import android.content.Context
import kotlin.math.abs
import kotlin.math.min

/**
 * "Aprender tu gusto": la app observa tus correcciones y las adopta poco a poco.
 *  • Exposición por tipo de escena: cuando en PRO compensas la exposición, o aclaras/oscureces en
 *    «Mejorar», la IA aprende que te gustan esas escenas más claras u oscuras.
 *  • Calidez por escena (desde «Mejorar»).
 *  • Intensidad favorita del desenfoque de retrato y del retoque por zonas.
 * Necesita varias muestras antes de influir, se limita a ±0.7 pasos y se puede olvidar.
 */
class TasteCore {
    class Stat(var sum: Float = 0f, var n: Int = 0) {
        fun add(v: Float) {
            // Media móvil: las correcciones recientes pesan más (tu gusto puede cambiar).
            if (n >= MAX_SAMPLES) sum = sum / n * (MAX_SAMPLES - 1)
            sum += v; n = min(n + 1, MAX_SAMPLES)
        }
        val mean get() = if (n == 0) 0f else sum / n
    }

    val exposure = HashMap<String, Stat>()
    val warmth = HashMap<String, Stat>()
    val portrait = Stat()
    val zone = Stat()

    fun recordExposure(scene: String, ev: Float) { if (abs(ev) >= 0.1f) { exposure.getOrPut(scene) { Stat() }.add(ev.coerceIn(-2f, 2f)); exposure.getOrPut(ALL) { Stat() }.add(ev.coerceIn(-2f, 2f)) } }
    fun recordWarmth(scene: String, w: Float) { if (abs(w) >= 0.05f) { warmth.getOrPut(scene) { Stat() }.add(w); warmth.getOrPut(ALL) { Stat() }.add(w) } }
    fun recordPortrait(strength: Float) = portrait.add(strength)
    fun recordZone(strength: Float) = zone.add(strength)

    /**
     * Ajuste de exposición aprendido para una escena: la media de la escena, mezclada con la
     * general cuando hay pocas muestras, y aplicada solo en parte (0.7) y con tope.
     */
    fun evBias(scene: String): Float = bias(exposure, scene, 0.7f, 0.7f)
    fun warmthBias(scene: String): Float = bias(warmth, scene, 0.8f, 0.6f)

    private fun bias(map: Map<String, Stat>, scene: String, k: Float, cap: Float): Float {
        val all = map[ALL]
        val s = map[scene]
        val total = (s?.n ?: 0) + (all?.n ?: 0)
        if (total < MIN_SAMPLES) return 0f
        val sceneW = ((s?.n ?: 0) / 4f).coerceIn(0f, 1f)
        val m = (s?.mean ?: 0f) * sceneW + (all?.mean ?: 0f) * (1 - sceneW)
        val confidence = (total / 6f).coerceIn(0f, 1f)
        return (m * k * confidence).coerceIn(-cap, cap)
    }

    fun portraitStrength(default: Float) = if (portrait.n >= 2) portrait.mean.coerceIn(0.1f, 1f) else default
    fun zoneStrength(default: Float) = if (zone.n >= 2) zone.mean.coerceIn(0f, 1f) else default

    val isEmpty get() = exposure.isEmpty() && warmth.isEmpty() && portrait.n == 0 && zone.n == 0

    fun serialize(): String = buildString {
        for ((k, v) in exposure) append("e|$k|${v.sum}|${v.n}\n")
        for ((k, v) in warmth) append("w|$k|${v.sum}|${v.n}\n")
        append("p||${portrait.sum}|${portrait.n}\n")
        append("z||${zone.sum}|${zone.n}\n")
    }

    companion object {
        const val ALL = "*"
        const val MIN_SAMPLES = 2
        const val MAX_SAMPLES = 12

        fun parse(s: String?): TasteCore {
            val t = TasteCore()
            s?.lineSequence()?.forEach { line ->
                val p = line.split('|')
                if (p.size != 4) return@forEach
                val st = Stat(p[2].toFloatOrNull() ?: return@forEach, p[3].toIntOrNull() ?: return@forEach)
                when (p[0]) {
                    "e" -> t.exposure[p[1]] = st
                    "w" -> t.warmth[p[1]] = st
                    "p" -> { t.portrait.sum = st.sum; t.portrait.n = st.n }
                    "z" -> { t.zone.sum = st.sum; t.zone.n = st.n }
                }
            }
            return t
        }
    }
}

/** Guarda tu gusto en el teléfono (nunca sale de él) y recuerda la escena de cada foto. */
class TasteStore(context: Context) {
    private val sp = context.applicationContext.getSharedPreferences("gusto", Context.MODE_PRIVATE)
    @Volatile var core: TasteCore = TasteCore.parse(sp.getString("core", null))
        private set

    /** Aprender de tus correcciones (se puede desactivar en Ajustes). */
    var enabled: Boolean
        get() = sp.getBoolean("enabled", true)
        set(v) { sp.edit().putBoolean("enabled", v).apply() }

    fun update(block: TasteCore.() -> Unit) {
        if (!enabled) return
        synchronized(this) {
            core.block()
            sp.edit().putString("core", core.serialize()).apply()
        }
    }

    fun forget() { synchronized(this) { core = TasteCore(); sp.edit().remove("core").apply() } }

    /** Escena con la que se tomó cada foto (para aprender por tipo de escena desde «Mejorar»). */
    fun rememberScene(photoId: String, scene: String) {
        synchronized(this) {
            val list = sp.getString("scenes", "")!!.lineSequence().filter { it.isNotBlank() && !it.startsWith("$photoId=") }.toMutableList()
            list += "$photoId=$scene"
            sp.edit().putString("scenes", list.takeLast(400).joinToString("\n")).apply()
        }
    }

    fun sceneOf(photoId: String): String =
        sp.getString("scenes", "")!!.lineSequence().firstOrNull { it.startsWith("$photoId=") }?.substringAfter('=') ?: TasteCore.ALL

    /** Resumen legible para Ajustes › Tu estilo. */
    fun summary(): List<String> {
        val c = core
        val out = ArrayList<String>()
        for ((k, v) in c.exposure) if (k != TasteCore.ALL && v.n >= 2) {
            val b = c.evBias(k)
            if (abs(b) >= 0.1f) out += "${sceneName(k)}: %s%.1f EV (%d correcciones)".format(if (b > 0) "+" else "", b, v.n)
        }
        c.evBias(TasteCore.ALL).takeIf { abs(it) >= 0.1f }?.let { out += "En general: fotos %s (%+.1f EV)".format(if (it > 0) "más claras" else "más oscuras", it) }
        c.warmthBias(TasteCore.ALL).takeIf { abs(it) >= 0.1f }?.let { out += "Colores %s".format(if (it > 0) "más cálidos" else "más fríos") }
        if (c.portrait.n >= 2) out += "Desenfoque de retrato favorito: ${com.iacamara.app.ui.fNumber(c.portraitStrength(0.6f))}"
        if (c.zone.n >= 2) out += "Retoque por zonas: %d %%".format((c.zoneStrength(0.6f) * 100).toInt())
        return out
    }

    private fun sceneName(k: String) = runCatching { Scene.valueOf(k).title }.getOrDefault(k)
}
