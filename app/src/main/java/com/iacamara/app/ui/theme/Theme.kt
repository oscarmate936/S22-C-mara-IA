package com.iacamara.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.iacamara.app.R

/**
 * Sistema de diseño de IA Cámara.
 *
 * Inspiración (sin copiar): la sobriedad de Leica LUX, las lecturas técnicas en
 * monoespaciada de Blackmagic Camera, el acento cálido y los diales de Halide y
 * la jerarquía de controles de Moment. Resultado propio: negro tinta, paneles de
 * vidrio, ámbar "hora dorada" como acento de acción y cian para todo lo que hace la IA.
 */
object Palette {
    val Ink = Color(0xFF0A0A0C)          // fondo
    val Graphite = Color(0xFF151519)     // superficies
    val Slate = Color(0xFF22222A)        // superficies elevadas
    val Hairline = Color(0x26FFFFFF)     // bordes finos
    val Glass = Color(0x8C0A0A0C)        // paneles sobre la imagen
    val Paper = Color(0xFFF4F1EA)        // texto principal (blanco cálido)
    val Mist = Color(0xFFA3A1A8)         // texto secundario
    val Fog = Color(0xFF6B6A72)          // texto terciario / deshabilitado
    val Amber = Color(0xFFFFB547)        // acento: acción, valores manuales
    val AmberDeep = Color(0xFFE08A1E)
    val Cyan = Color(0xFF5CE1E6)         // IA / automático
    val Signal = Color(0xFFFF5A4E)       // alertas (sobreexposición, temporizador)
    val Level = Color(0xFF7CE38B)        // horizonte nivelado
}

val Manrope = FontFamily(
    Font(R.font.manrope_medium, FontWeight.Medium),
    Font(R.font.manrope_semibold, FontWeight.SemiBold),
    Font(R.font.manrope_bold, FontWeight.Bold),
    Font(R.font.manrope_extrabold, FontWeight.ExtraBold),
)

val JetMono = FontFamily(
    Font(R.font.jbmono_medium, FontWeight.Medium),
    Font(R.font.jbmono_semibold, FontWeight.SemiBold),
)

object Type {
    /** Etiquetas pequeñas en mayúsculas con espaciado (estilo instrumento). */
    val Label = TextStyle(fontFamily = Manrope, fontWeight = FontWeight.Bold, fontSize = 10.sp, letterSpacing = 1.4.sp)
    val LabelLarge = TextStyle(fontFamily = Manrope, fontWeight = FontWeight.Bold, fontSize = 12.sp, letterSpacing = 1.2.sp)
    val Body = TextStyle(fontFamily = Manrope, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp)
    val BodyStrong = TextStyle(fontFamily = Manrope, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, lineHeight = 21.sp)
    val Title = TextStyle(fontFamily = Manrope, fontWeight = FontWeight.ExtraBold, fontSize = 22.sp, letterSpacing = (-0.3).sp)
    val Display = TextStyle(fontFamily = Manrope, fontWeight = FontWeight.ExtraBold, fontSize = 34.sp, letterSpacing = (-0.8).sp, lineHeight = 38.sp)
    /** Lecturas numéricas: siempre monoespaciadas para que no "bailen". */
    val Readout = TextStyle(fontFamily = JetMono, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
    val ReadoutSmall = TextStyle(fontFamily = JetMono, fontWeight = FontWeight.Medium, fontSize = 11.sp)
    val ReadoutLarge = TextStyle(fontFamily = JetMono, fontWeight = FontWeight.SemiBold, fontSize = 20.sp)
}

@Composable
fun IACamaraTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Palette.Amber,
            onPrimary = Palette.Ink,
            secondary = Palette.Cyan,
            background = Palette.Ink,
            surface = Palette.Graphite,
            onSurface = Palette.Paper,
            onBackground = Palette.Paper,
            surfaceVariant = Palette.Slate,
            outline = Palette.Hairline,
        ),
        content = content,
    )
}
