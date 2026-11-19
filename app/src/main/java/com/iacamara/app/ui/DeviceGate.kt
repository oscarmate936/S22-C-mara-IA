package com.iacamara.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.iacamara.app.camera.S22Plus
import com.iacamara.app.ui.theme.Palette
import com.iacamara.app.ui.theme.Type

/** IA Cámara está calibrada solo para el Galaxy S22+: en otro teléfono se avisa antes de abrir. */
@Composable
fun DeviceGate(model: String, onContinue: () -> Unit) {
    Column(
        Modifier.fillMaxSize().background(Palette.Ink).statusBarsPadding().navigationBarsPadding().padding(28.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text("Hecha para el Galaxy S22+", style = Type.Display, color = Palette.Paper)
        Spacer(Modifier.height(14.dp))
        Text(
            "IA Cámara está calibrada solo para las cuatro cámaras del Samsung Galaxy S22+. Este teléfono ($model) es otro: " +
                "los lentes, la estabilización y los ajustes de la IA no coincidirán y las fotos pueden salir peor.",
            style = Type.Body, color = Palette.Mist,
        )
        Spacer(Modifier.height(20.dp))
        for (s in S22Plus.REAR + S22Plus.FRONT) Row(Modifier.padding(vertical = 4.dp)) {
            Text("${s.name}", style = Type.BodyStrong, color = Palette.Paper, modifier = Modifier.width(170.dp))
            Text("${s.megapixels} MP · f/${s.aperture} · ${s.equivalentMm.toInt()} mm" + if (s.ois) " · OIS" else "", style = Type.ReadoutSmall, color = Palette.Mist)
        }
        Spacer(Modifier.height(28.dp))
        Text(
            "Abrir de todos modos", style = Type.LabelLarge, color = Palette.Paper,
            modifier = Modifier.clip(RoundedCornerShape(50)).border(1.dp, Palette.Hairline, RoundedCornerShape(50))
                .clickable(onClick = onContinue).padding(horizontal = 18.dp, vertical = 10.dp),
        )
    }
}
