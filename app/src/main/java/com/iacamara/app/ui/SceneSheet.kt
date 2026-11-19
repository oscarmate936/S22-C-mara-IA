package com.iacamara.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.iacamara.app.ai.SceneResult
import com.iacamara.app.camera.ExposureMath
import com.iacamara.app.camera.WhiteBalance
import com.iacamara.app.camera.formatFactor
import com.iacamara.app.ui.theme.Palette
import com.iacamara.app.ui.theme.Type

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SceneSheet(result: SceneResult, applied: Boolean, autoMode: Boolean = false, onApply: () -> Unit, onDismiss: () -> Unit) {
    val scene = result.scene
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Palette.Graphite,
        contentColor = Palette.Paper,
        scrimColor = Color.Black.copy(alpha = 0.45f),
        dragHandle = {
            Box(Modifier.padding(top = 10.dp, bottom = 6.dp).size(width = 36.dp, height = 4.dp).background(Palette.Fog, RoundedCornerShape(2.dp)))
        },
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .navigationBarsPadding()
                .padding(bottom = 16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.AutoAwesome, null, tint = Palette.Cyan, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(6.dp))
                Text("LA IA DETECTÓ", style = Type.Label, color = Palette.Cyan)
                Spacer(Modifier.weight(1f))
                Text("${(result.confidence.coerceIn(0f, 1f) * 100).toInt()}% seguro", style = Type.ReadoutSmall, color = Palette.Mist)
            }
            Spacer(Modifier.height(6.dp))
            Text(scene.title, style = Type.Title, color = Palette.Paper)
            Spacer(Modifier.height(4.dp))
            Text(scene.why, style = Type.Body, color = Palette.Mist)
            if (result.evidence.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    result.evidence.forEach {
                        Text(
                            it.lowercase(),
                            style = Type.ReadoutSmall,
                            color = Palette.Cyan,
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(Palette.Cyan.copy(alpha = 0.12f))
                                .padding(horizontal = 8.dp, vertical = 3.dp),
                        )
                    }
                }
            }

            Spacer(Modifier.height(22.dp))
            Text("CÓMO LO HARÍA UN FOTÓGRAFO", style = Type.Label, color = Palette.Mist)
            Spacer(Modifier.height(10.dp))
            scene.tips.forEachIndexed { i, tip ->
                Row(Modifier.padding(vertical = 5.dp), verticalAlignment = Alignment.Top) {
                    Box(
                        Modifier.size(20.dp).clip(CircleShape).border(1.dp, Palette.Fog, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) { Text("${i + 1}", style = Type.ReadoutSmall, color = Palette.Mist) }
                    Spacer(Modifier.width(12.dp))
                    Text(tip, style = Type.Body, color = Palette.Paper, modifier = Modifier.weight(1f))
                }
            }

            scene.recipe?.let { r ->
                Spacer(Modifier.height(20.dp))
                Text("RECETA", style = Type.Label, color = Palette.Mist)
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    RecipeCell("LENTE", r.lensFactor?.let { formatFactor(it) + "×" } ?: "—", Modifier.weight(1f))
                    RecipeCell("OBTUR.", r.shutterNs?.let { ExposureMath.formatShutter(it) } ?: "Auto", Modifier.weight(1f))
                    RecipeCell("EXP.", ExposureMath.formatEv(r.ev), Modifier.weight(1f))
                    RecipeCell("BLANCOS", if (r.wb == WhiteBalance.AUTO) "Auto" else r.wb.label, Modifier.weight(1f))
                }
                Spacer(Modifier.height(18.dp))
                if (autoMode) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
                            .background(Palette.Cyan.copy(alpha = 0.12f)).padding(14.dp),
                    ) {
                        Icon(Icons.Rounded.AutoAwesome, null, tint = Palette.Cyan, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("El modo IA ya lo está aplicando por ti", style = Type.BodyStrong, color = Palette.Cyan)
                    }
                    return@let
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(54.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(
                            if (applied) Brush.horizontalGradient(listOf(Palette.Slate, Palette.Slate))
                            else Brush.horizontalGradient(listOf(Palette.Amber, Palette.AmberDeep)),
                        )
                        .clickable(enabled = !applied) { onApply(); onDismiss() },
                ) {
                    if (applied) {
                        Icon(Icons.Rounded.Check, null, tint = Palette.Level, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Receta aplicada", style = Type.BodyStrong, color = Palette.Level)
                    } else {
                        Text("Ajustar como profesional", style = Type.BodyStrong, color = Palette.Ink)
                    }
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    "En modo IA esto se aplica solo; en PRO te sirve como punto de partida.",
                    style = Type.Body, color = Palette.Fog,
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                )
            }
        }
    }
}

@Composable
private fun RecipeCell(label: String, value: String, modifier: Modifier) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Palette.Slate.copy(alpha = 0.6f))
            .border(1.dp, Palette.Hairline, RoundedCornerShape(12.dp))
            .padding(vertical = 10.dp),
    ) {
        Text(label, style = Type.Label.copy(fontSize = Type.Label.fontSize * 0.9f), color = Palette.Mist)
        Spacer(Modifier.height(4.dp))
        Text(value, style = Type.Readout, color = Palette.Amber, maxLines = 1)
    }
}
