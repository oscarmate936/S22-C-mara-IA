package com.iacamara.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.rounded.Camera
import androidx.compose.material.icons.rounded.CenterFocusStrong
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material.icons.rounded.AutoAwesomeMotion
import androidx.compose.material.icons.rounded.Equalizer
import androidx.compose.material.icons.rounded.RawOn
import androidx.compose.material.icons.rounded.FlashOn
import androidx.compose.material.icons.rounded.GridGoldenratio
import androidx.compose.material.icons.rounded.Straighten
import androidx.compose.material.icons.rounded.Vibration
import androidx.compose.material.icons.rounded.WbSunny
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.iacamara.app.BuildConfigInfo
import com.iacamara.app.GridType
import com.iacamara.app.Prefs
import com.iacamara.app.ui.components.ChoiceChip
import com.iacamara.app.ui.theme.Palette
import com.iacamara.app.ui.theme.Type

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSheet(prefs: Prefs, onDismiss: () -> Unit) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = state,
        containerColor = Palette.Graphite,
        contentColor = Palette.Paper,
        scrimColor = Color.Black.copy(alpha = 0.55f),
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
            Text("Ajustes", style = Type.Title, color = Palette.Paper)
            Spacer(Modifier.height(4.dp))
            Text("Ayudas visuales y comportamiento de la cámara.", style = Type.Body, color = Palette.Mist)

            Section("INTELIGENCIA")
            Card {
                ToggleRow(Icons.Rounded.AutoAwesome, "IA de escena", "Reconoce retratos, mascotas, atardeceres, playas, comida, macro y noche. Funciona sin internet.", prefs.aiScene, prefs::updateAiScene)
                Divider()
                ToggleRow(Icons.Rounded.Psychology, "Cerebro fotógrafo", "En modo IA ajusta solo velocidad, exposición y blancos según la escena, el movimiento y tu pulso.", prefs.autoBrain, prefs::updateAutoBrain)
                Divider()
                ToggleRow(Icons.Rounded.Camera, "Lente automático", "Cambia al lente ideal para la escena (siempre puedes deshacerlo).", prefs.autoLens, prefs::updateAutoLens)
                Divider()
                ToggleRow(Icons.Rounded.CenterFocusStrong, "Enfoque en el sujeto", "El foco sigue rostros y sujetos solo, sin tocar la pantalla.", prefs.subjectFocus, prefs::updateSubjectFocus)
                Divider()
                ToggleRow(Icons.Rounded.GridGoldenratio, "Entrenador de composición", "Te muestra a dónde mover el sujeto o el horizonte.", prefs.coach, prefs::updateCoach)
            }

            Section("CALIDAD DE IMAGEN")
            Card {
                ToggleRow(Icons.Rounded.AutoAwesomeMotion, "Tomas múltiples automáticas", "Con poca luz combina hasta 8 fotos (menos ruido) y en macro varias distancias de enfoque (todo nítido). Mantén el teléfono firme unos segundos.", prefs.multiShot, prefs::updateMultiShot)
                Divider()
                ToggleRow(Icons.Rounded.RawOn, "Guardar RAW (DNG)", "Guarda también el archivo en bruto del sensor para editarlo en Lightroom o Snapseed. Con RAW activo, cada foto es de una sola toma.", prefs.raw, prefs::updateRaw)
            }

            Section("COMPOSICIÓN")
            Card {
                Text("Guías", style = Type.BodyStrong, color = Palette.Paper)
                Spacer(Modifier.height(2.dp))
                Text(
                    "Coloca al sujeto sobre una línea o un punto fuerte para una foto más equilibrada.",
                    style = Type.Body, color = Palette.Mist,
                )
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GridType.entries.forEach { g ->
                        ChoiceChip(g.label, null, prefs.grid == g) { prefs.updateGrid(g) }
                    }
                }
                Divider()
                ToggleRow(Icons.Rounded.Straighten, "Nivel de horizonte", "Se pone verde cuando la foto está recta.", prefs.level, prefs::updateLevel)
            }

            Section("EXPOSICIÓN")
            Card {
                ToggleRow(Icons.Rounded.Equalizer, "Histograma", "Muestra cómo se reparte la luz, de sombras a altas luces.", prefs.histogram, prefs::updateHistogram)
                Divider()
                ToggleRow(Icons.Rounded.WbSunny, "Aviso de altas luces", "Te avisa cuando una zona se quema a blanco puro.", prefs.highlightWarning, prefs::updateHighlightWarning)
            }

            Section("SISTEMA")
            Card {
                ToggleRow(Icons.Rounded.FlashOn, "Flash tipo linterna", "Usa la luz continua del flash. Actívalo si el flash normal no dispara en tu teléfono.", prefs.torchFlash, prefs::updateTorchFlash)
                Divider()
                ToggleRow(Icons.Rounded.Vibration, "Respuesta háptica", "Vibración suave en diales, nivel y disparo.", prefs.haptics, prefs::updateHaptics)
            }

            Section("HOJA DE RUTA")
            Card {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(34.dp).clip(CircleShape).background(Palette.Cyan.copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center,
                    ) { Icon(Icons.Rounded.AutoAwesome, null, tint = Palette.Cyan, modifier = Modifier.size(18.dp)) }
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text("IA Cámara ${BuildConfigInfo.VERSION}", style = Type.BodyStrong, color = Palette.Paper)
                        Text("Fase 4 · Calidad de imagen", style = Type.Body, color = Palette.Mist)
                    }
                }
                Spacer(Modifier.height(14.dp))
                Roadmap("1", "Control manual total y lentes", done = true)
                Roadmap("2", "IA que reconoce la escena y al sujeto", done = true)
                Roadmap("3", "Cerebro fotógrafo: ajuste automático experto", done = true)
                Roadmap("4", "RAW, apilado, macro y modo noche", done = true)
            }
        }
    }
}

@Composable
private fun Section(title: String) {
    Spacer(Modifier.height(22.dp))
    Text(title, style = Type.Label, color = Palette.Mist)
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun Card(content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(Palette.Slate.copy(alpha = 0.6f))
            .border(1.dp, Palette.Hairline, RoundedCornerShape(18.dp))
            .padding(16.dp),
    ) { content() }
}

@Composable
private fun Divider() {
    Spacer(Modifier.height(14.dp))
    Box(Modifier.fillMaxWidth().height(1.dp).background(Palette.Hairline))
    Spacer(Modifier.height(14.dp))
}

@Composable
private fun ToggleRow(icon: ImageVector, title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { onChange(!checked) },
    ) {
        Icon(icon, null, tint = if (checked) Palette.Amber else Palette.Mist, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = Type.BodyStrong, color = Palette.Paper)
            Text(subtitle, style = Type.Body, color = Palette.Mist)
        }
        Spacer(Modifier.width(10.dp))
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Palette.Ink,
                checkedTrackColor = Palette.Amber,
                uncheckedThumbColor = Palette.Mist,
                uncheckedTrackColor = Palette.Ink,
                uncheckedBorderColor = Palette.Fog,
            ),
        )
    }
}

@Composable
private fun Roadmap(n: String, text: String, done: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 5.dp)) {
        Box(
            Modifier
                .size(22.dp)
                .clip(CircleShape)
                .background(if (done) Palette.Amber else Color.Transparent)
                .border(1.dp, if (done) Palette.Amber else Palette.Fog, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(n, style = Type.ReadoutSmall, color = if (done) Palette.Ink else Palette.Mist)
        }
        Spacer(Modifier.width(12.dp))
        Text(text, style = Type.Body, color = if (done) Palette.Paper else Palette.Mist)
    }
}
