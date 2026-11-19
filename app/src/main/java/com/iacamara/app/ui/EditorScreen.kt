package com.iacamara.app.ui

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.iacamara.app.ai.FaceObs
import com.iacamara.app.ai.TasteStore
import com.iacamara.app.processing.EditParams
import com.iacamara.app.processing.Editor
import com.iacamara.app.processing.Straighten
import com.iacamara.app.processing.ZoneMasks
import com.iacamara.app.processing.ZoneRetouch
import com.iacamara.app.ui.theme.Palette
import com.iacamara.app.ui.theme.Type
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

/** Lo que la IA encontró en la foto (sobre la vista previa). */
class EditAnalysis(
    val preview: Bitmap,
    val px: IntArray,
    val straighten: Straighten.Estimate,
    val masks: ZoneMasks.Masks,
    val faces: List<FaceObs>,
    val hasOriginal: Boolean,
) {
    val hasSky get() = masks.sky.mean() > 0.02f
    val hasFoliage get() = masks.foliage.mean() > 0.02f
}

/**
 * «Mejorar con IA»: enderezar, perspectiva, cielo, piel, vegetación, brillo y calidez, con vista
 * previa al instante. Mantén pulsada la foto para ver la original. Lo que ajustes aquí enseña a
 * la app tu estilo.
 */
@Composable
fun EditorScreen(uri: Uri, onClose: (saved: Boolean) -> Unit, analysisOverride: EditAnalysis? = null) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val taste = remember { TasteStore(context) }
    val scene = remember(uri) { taste.sceneOf(uri.lastPathSegment ?: "") }
    var analysis by remember(uri) { mutableStateOf(analysisOverride) }
    LaunchedEffect(uri) {
        if (analysis != null) return@LaunchedEffect
        analysis = withContext(Dispatchers.Default) {
            runCatching {
                val bytes = Editor.original(context, uri, store = false) ?: return@runCatching null
                val bmp = Editor.decodeUpright(bytes, 1400) ?: return@runCatching null
                val w = bmp.width; val h = bmp.height
                val px = IntArray(w * h).also { bmp.getPixels(it, 0, w, 0, 0, w, h) }
                val faces = Editor.faces(bmp)
                EditAnalysis(
                    bmp, px, Straighten.estimate(com.iacamara.app.processing.Align.luma(px), w, h),
                    ZoneMasks.compute(px, w, h, faces.map { floatArrayOf(it.left, it.top, it.right, it.bottom) }),
                    faces, Editor.hasOriginal(context, uri),
                )
            }.getOrNull()
        }
        if (analysis == null) onClose(false)
    }
    BackHandler { onClose(false) }

    val a = analysis
    // Ajustes (con los valores de tu estilo como punto de partida).
    var straighten by remember(a) { mutableStateOf(a?.straighten?.hasTilt == true) }
    var perspective by remember(a) { mutableStateOf(false) }
    var sky by remember(a) { mutableStateOf(a?.hasSky == true) }
    var skin by remember(a) { mutableStateOf(a?.faces?.isNotEmpty() == true) }
    var foliage by remember(a) { mutableStateOf(a?.hasFoliage == true) }
    var strength by remember(a) { mutableFloatStateOf(taste.core.zoneStrength(0.6f)) }
    var brightness by remember(a) { mutableFloatStateOf(0f) }
    var warmth by remember(a) { mutableFloatStateOf(taste.core.warmthBias(scene)) }
    var showOriginal by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var edited by remember(a) { mutableStateOf<Bitmap?>(null) }

    fun params(): EditParams? {
        val an = a ?: return null
        return EditParams(
            straighten = straighten && an.straighten.hasTilt, perspective = perspective && an.straighten.hasKeystone,
            tiltDeg = an.straighten.tiltDeg, keystonePreview = an.straighten.keystone, previewWidth = an.preview.width,
            zones = ZoneRetouch.Params(sky = sky, skin = skin, foliage = foliage, strength = strength, brightness = brightness, warmth = warmth),
            scene = scene,
        )
    }
    // Vista previa (con una pequeña espera para no recalcular en cada movimiento del control).
    LaunchedEffect(a, straighten, perspective, sky, skin, foliage, strength, brightness, warmth) {
        val an = a ?: return@LaunchedEffect
        delay(120)
        val p = params() ?: return@LaunchedEffect
        val w = an.preview.width; val h = an.preview.height
        edited = withContext(Dispatchers.Default) { Bitmap.createBitmap(Editor.render(an.px, w, h, an.masks, p), w, h, Bitmap.Config.ARGB_8888) }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (a == null) {
            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(color = Palette.Cyan)
                Spacer(Modifier.height(12.dp))
                Text("Analizando la foto…", style = Type.Body, color = Palette.Mist)
            }
            return@Box
        }
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(40.dp).clip(RoundedCornerShape(50)).clickable { onClose(false) }, contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.Close, "Cancelar", tint = Palette.Paper)
                }
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text("Mejorar con IA", style = Type.BodyStrong, color = Palette.Paper)
                    Text(if (showOriginal) "ORIGINAL" else "Mantén pulsada la foto para ver la original", style = Type.ReadoutSmall, color = if (showOriginal) Palette.Amber else Palette.Mist)
                }
            }
            Box(
                Modifier.weight(1f).fillMaxWidth().pointerInput(Unit) {
                    detectTapGestures(onPress = { showOriginal = true; tryAwaitRelease(); showOriginal = false })
                },
                contentAlignment = Alignment.Center,
            ) {
                val shown = if (showOriginal) a.preview else edited ?: a.preview
                Image(shown.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
            }
            Column(Modifier.fillMaxWidth().background(Palette.Ink).navigationBarsPadding().padding(16.dp)) {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val st = a.straighten
                    EditChip(if (st.hasTilt) "Enderezar %.1f°".format(abs(st.tiltDeg)) else "Recta", straighten && st.hasTilt, st.hasTilt) { straighten = !straighten }
                    if (st.hasKeystone) EditChip("Perspectiva", perspective, true) { perspective = !perspective }
                    EditChip("Cielo", sky && a.hasSky, a.hasSky) { sky = !sky }
                    EditChip("Piel", skin && a.faces.isNotEmpty(), a.faces.isNotEmpty()) { skin = !skin }
                    EditChip("Vegetación", foliage && a.hasFoliage, a.hasFoliage) { foliage = !foliage }
                }
                Spacer(Modifier.height(10.dp))
                EditSlider("ZONAS", "%d %%".format((strength * 100).toInt()), strength, 0f..1f) { strength = it }
                EditSlider("BRILLO", "%+.1f".format(brightness), brightness, -1f..1f) { brightness = it }
                EditSlider("CALIDEZ", when { warmth > 0.05f -> "cálido"; warmth < -0.05f -> "frío"; else -> "neutro" }, warmth, -1f..1f) { warmth = it }
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (a.hasOriginal) EditButton("Restaurar original", enabled = !busy) {
                        busy = true
                        scope.launch {
                            val ok = withContext(Dispatchers.IO) { Editor.restore(context, uri) }
                            busy = false; onClose(ok)
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    if (busy) CircularProgressIndicator(Modifier.size(18.dp), color = Palette.Amber, strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    EditButton("Guardar", primary = true, enabled = !busy) {
                        val p = params() ?: return@EditButton
                        busy = true
                        scope.launch {
                            val ok = withContext(Dispatchers.Default) { runCatching { Editor.save(context, uri, p, a.faces) }.getOrDefault(false) }
                            // Tu estilo: brillo y calidez por tipo de escena, intensidad del retoque.
                            if (ok) taste.update {
                                recordExposure(scene, brightness)
                                recordWarmth(scene, warmth)
                                if (sky || skin || foliage) recordZone(strength)
                            }
                            busy = false; onClose(ok)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EditChip(text: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val c = if (selected) Palette.Amber else Palette.Paper
    Text(
        text, style = Type.LabelLarge, color = if (enabled) (if (selected) Palette.Ink else c) else Palette.Fog,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(if (selected) Palette.Amber else Color.Transparent)
            .border(1.dp, if (selected) Color.Transparent else Palette.Hairline, RoundedCornerShape(50))
            .clickable(enabled = enabled, onClick = onClick).padding(horizontal = 14.dp, vertical = 8.dp),
    )
}

@Composable
private fun EditSlider(label: String, value: String, v: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = Type.Label, color = Palette.Mist, modifier = Modifier.width(70.dp))
        Slider(
            v, onChange, Modifier.weight(1f).height(32.dp), valueRange = range,
            colors = SliderDefaults.colors(thumbColor = Palette.Amber, activeTrackColor = Palette.Amber, inactiveTrackColor = Palette.Slate),
        )
        Text(value, style = Type.ReadoutSmall, color = Palette.Paper, modifier = Modifier.width(56.dp).padding(start = 8.dp))
    }
}

@Composable
private fun EditButton(text: String, primary: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    Text(
        text, style = Type.LabelLarge, color = (if (primary) Palette.Ink else Palette.Paper).copy(alpha = if (enabled) 1f else 0.4f),
        modifier = Modifier.clip(RoundedCornerShape(50)).background(if (primary) Palette.Amber else Color.Transparent)
            .border(1.dp, if (primary) Color.Transparent else Palette.Hairline, RoundedCornerShape(50))
            .clickable(enabled = enabled, onClick = onClick).padding(horizontal = 18.dp, vertical = 10.dp),
    )
}
