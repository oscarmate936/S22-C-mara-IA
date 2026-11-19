package com.iacamara.app.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.GridOn
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.iacamara.app.R
import com.iacamara.app.ui.theme.Palette
import com.iacamara.app.ui.theme.Type

@Composable
fun PermissionScreen(permanentlyDenied: Boolean, onRequest: () -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Palette.Ink)
            .background(
                Brush.radialGradient(
                    listOf(Palette.Amber.copy(alpha = 0.16f), Color.Transparent),
                    center = Offset(900f, 200f),
                    radius = 1100f,
                ),
            ),
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 28.dp, vertical = 24.dp),
        ) {
            Spacer(Modifier.height(36.dp))
            Box(
                Modifier
                    .size(72.dp)
                    .clip(RoundedCornerShape(22.dp))
                    .background(Palette.Graphite)
                    .border(1.dp, Palette.Hairline, RoundedCornerShape(22.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Image(painterResource(R.drawable.ic_launcher_foreground), null, modifier = Modifier.size(104.dp))
            }
            Spacer(Modifier.height(28.dp))
            Text("IA CÁMARA", style = Type.Label, color = Palette.Amber)
            Spacer(Modifier.height(10.dp))
            Text("Fotos de profesional,\nsin ser fotógrafo.", style = Type.Display, color = Palette.Paper)
            Spacer(Modifier.height(14.dp))
            Text(
                "Tu S22+ tiene tres lentes y un gran sensor. IA Cámara te da el control completo y te guía en cada toma.",
                style = Type.Body, color = Palette.Mist,
            )
            Spacer(Modifier.height(32.dp))
            Feature(Icons.Rounded.Tune, "Control total", "ISO, obturación, blancos y enfoque, con diales precisos.")
            Feature(Icons.Rounded.GridOn, "Composición guiada", "Guías, nivel de horizonte e histograma en vivo.")
            Feature(Icons.Rounded.AutoAwesome, "Inteligencia en camino", "Pronto: la IA reconoce la escena y ajusta todo por ti.")
            Spacer(Modifier.weight(1f))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(58.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(Brush.horizontalGradient(listOf(Palette.Amber, Palette.AmberDeep)))
                    .clickable(onClick = onRequest)
                    .padding(horizontal = 20.dp),
            ) {
                Icon(Icons.Rounded.CameraAlt, null, tint = Palette.Ink)
                Spacer(Modifier.width(12.dp))
                Text(
                    if (permanentlyDenied) "Abrir ajustes del sistema" else "Permitir acceso a la cámara",
                    style = Type.BodyStrong, color = Palette.Ink,
                )
            }
            Spacer(Modifier.height(12.dp))
            Text(
                "Las fotos se guardan solo en tu teléfono, en Imágenes › IA Camara.",
                style = Type.Body, color = Palette.Fog,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )
        }
    }
}

@Composable
private fun Feature(icon: ImageVector, title: String, body: String) {
    Row(Modifier.padding(vertical = 9.dp), verticalAlignment = Alignment.Top) {
        Box(
            Modifier.size(38.dp).clip(RoundedCornerShape(12.dp)).background(Palette.Graphite).border(1.dp, Palette.Hairline, RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, null, tint = Palette.Amber, modifier = Modifier.size(20.dp)) }
        Spacer(Modifier.width(14.dp))
        Column {
            Text(title, style = Type.BodyStrong, color = Palette.Paper)
            Text(body, style = Type.Body, color = Palette.Mist)
        }
    }
}
