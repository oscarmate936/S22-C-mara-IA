package com.iacamara.app

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

enum class GridType(val label: String) { OFF("Sin guías"), THIRDS("Tercios"), GOLDEN("Áurea"), CENTER("Centro") }

/** Preferencias persistentes de la app, observables desde Compose. */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("iacamara", Context.MODE_PRIVATE)

    var grid by mutableStateOf(GridType.entries.getOrElse(sp.getInt("grid", 1)) { GridType.THIRDS })
        private set
    var level by mutableStateOf(sp.getBoolean("level", true))
        private set
    var histogram by mutableStateOf(sp.getBoolean("histogram", true))
        private set
    var highlightWarning by mutableStateOf(sp.getBoolean("highlights", true))
        private set
    var haptics by mutableStateOf(sp.getBoolean("haptics", true))
        private set
    var aiScene by mutableStateOf(sp.getBoolean("aiScene", true))
        private set
    var coach by mutableStateOf(sp.getBoolean("coach", true))
        private set
    var subjectFocus by mutableStateOf(sp.getBoolean("subjectFocus", true))
        private set
    var autoBrain by mutableStateOf(sp.getBoolean("autoBrain", true))
        private set
    var autoLens by mutableStateOf(sp.getBoolean("autoLens", true))
        private set
    var torchFlash by mutableStateOf(sp.getBoolean("torchFlash", false))
        private set
    var multiShot by mutableStateOf(sp.getBoolean("multiShot", true))
        private set
    var raw by mutableStateOf(sp.getBoolean("raw", false))
        private set
    var timerSeconds by mutableStateOf(sp.getInt("timer", 0))
        private set

    fun updateGrid(v: GridType) { grid = v; sp.edit().putInt("grid", v.ordinal).apply() }
    fun cycleGrid() = updateGrid(GridType.entries[(grid.ordinal + 1) % GridType.entries.size])
    fun updateLevel(v: Boolean) { level = v; sp.edit().putBoolean("level", v).apply() }
    fun updateHistogram(v: Boolean) { histogram = v; sp.edit().putBoolean("histogram", v).apply() }
    fun updateHighlightWarning(v: Boolean) { highlightWarning = v; sp.edit().putBoolean("highlights", v).apply() }
    fun updateHaptics(v: Boolean) { haptics = v; sp.edit().putBoolean("haptics", v).apply() }
    fun updateAiScene(v: Boolean) { aiScene = v; sp.edit().putBoolean("aiScene", v).apply() }
    fun updateCoach(v: Boolean) { coach = v; sp.edit().putBoolean("coach", v).apply() }
    fun updateSubjectFocus(v: Boolean) { subjectFocus = v; sp.edit().putBoolean("subjectFocus", v).apply() }
    fun updateAutoBrain(v: Boolean) { autoBrain = v; sp.edit().putBoolean("autoBrain", v).apply() }
    fun updateAutoLens(v: Boolean) { autoLens = v; sp.edit().putBoolean("autoLens", v).apply() }
    fun updateTorchFlash(v: Boolean) { torchFlash = v; sp.edit().putBoolean("torchFlash", v).apply() }
    fun updateMultiShot(v: Boolean) { multiShot = v; sp.edit().putBoolean("multiShot", v).apply() }
    fun updateRaw(v: Boolean) { raw = v; sp.edit().putBoolean("raw", v).apply() }
    fun cycleTimer() {
        timerSeconds = when (timerSeconds) { 0 -> 3; 3 -> 10; else -> 0 }
        sp.edit().putInt("timer", timerSeconds).apply()
    }
}
