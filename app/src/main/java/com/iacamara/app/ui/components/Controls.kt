package com.iacamara.app.ui.components

import android.graphics.Bitmap
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.iacamara.app.camera.CaptureMode
import com.iacamara.app.camera.Lens
import com.iacamara.app.camera.formatFactor
import com.iacamara.app.ui.theme.Palette
import com.iacamara.app.ui.theme.Type

/** Superficie de "vidrio ahumado" usada por todos los paneles sobre la imagen. */
fun Modifier.glass(shape: androidx.compose.ui.graphics.Shape = RoundedCornerShape(16.dp)) =
    this.clip(shape).background(Palette.Glass).border(1.dp, Palette.Hairline, shape)

/** Iconos rotan con el teléfono, con animación elástica. */
@Composable
fun Modifier.followRotation(degrees: Int): Modifier {
    // Toma el camino más corto (270 → 0 gira -90, no +270).
    val target = remember { mutableListOf(0f) }
    val last = target[0]
    var t = degrees.toFloat()
    while (t - last > 180f) t -= 360f
    while (t - last < -180f) t += 360f
    target[0] = t
    val anim by animateFloatAsState(t, spring(dampingRatio = 0.7f, stiffness = Spring.StiffnessMediumLow), label = "rot")
    return this.rotate(anim)
}

@Composable
fun BarIconButton(
    icon: ImageVector,
    contentDescription: String,
    active: Boolean = false,
    rotation: Int = 0,
    tint: Color = if (active) Palette.Amber else Palette.Paper,
    onClick: () -> Unit,
) {
    val color by animateColorAsState(tint, label = "tint")
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(44.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick),
    ) {
        Icon(icon, contentDescription, tint = color, modifier = Modifier.size(22.dp).followRotation(rotation))
    }
}

/** Pastilla de texto en la barra superior (p. ej. "4:3"). */
@Composable
fun BarTextButton(text: String, rotation: Int, onClick: () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.size(44.dp).clip(CircleShape).clickable(onClick = onClick),
    ) {
        Box(
            Modifier
                .followRotation(rotation)
                .border(1.5.dp, Palette.Paper.copy(alpha = 0.85f), RoundedCornerShape(6.dp))
                .padding(horizontal = 5.dp, vertical = 2.dp),
        ) {
            Text(text, style = Type.ReadoutSmall.copy(fontSize = 10.sp), color = Palette.Paper)
        }
    }
}

// ------------------------------------------------------------------ Disparador

@Composable
fun ShutterButton(
    mode: CaptureMode,
    busy: Boolean,
    countdown: Int,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val innerScale by animateFloatAsState(
        if (pressed || busy) 0.82f else 1f,
        spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessMedium), label = "shutter",
    )
    val ringColor by animateColorAsState(if (mode == CaptureMode.PRO) Palette.Amber else Palette.Paper, label = "ring")
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(82.dp)
            .clip(CircleShape)
            .border(3.5.dp, ringColor, CircleShape)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
    ) {
        Box(
            Modifier
                .size(66.dp)
                .scale(innerScale)
                .background(if (countdown > 0) Palette.Signal else Palette.Paper, CircleShape),
        )
        if (countdown > 0) {
            AnimatedContent(countdown, transitionSpec = { scaleIn() + fadeIn() togetherWith fadeOut() }, label = "cd") {
                Text("$it", style = Type.Title, color = Palette.Paper)
            }
        }
    }
}

@Composable
fun GalleryThumb(bitmap: Bitmap?, rotation: Int, onClick: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(54.dp)
            .followRotation(rotation)
            .clip(shape)
            .background(Palette.Slate)
            .border(1.5.dp, Palette.Paper.copy(alpha = 0.25f), shape)
            .clickable(onClick = onClick),
    ) {
        AnimatedContent(bitmap, transitionSpec = { scaleIn(initialScale = 0.6f) + fadeIn() togetherWith fadeOut() }, label = "thumb") { bmp ->
            if (bmp != null) {
                Image(bmp.asImageBitmap(), "Última foto", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            } else {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.PhotoLibrary, "Galería", tint = Palette.Mist, modifier = Modifier.size(22.dp))
                }
            }
        }
    }
}

@Composable
fun RoundGlassButton(icon: ImageVector, contentDescription: String, rotation: Int, onClick: () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(54.dp)
            .glass(CircleShape)
            .clickable(onClick = onClick),
    ) {
        Icon(icon, contentDescription, tint = Palette.Paper, modifier = Modifier.size(24.dp).followRotation(rotation))
    }
}

// ------------------------------------------------------------------ Modo

@Composable
fun ModeSwitch(mode: CaptureMode, onChange: (CaptureMode) -> Unit) {
    val items = listOf(CaptureMode.AUTO to "AUTO", CaptureMode.PRO to "PRO")
    val segment = 76.dp
    val offset by animateDpAsState(
        if (mode == CaptureMode.AUTO) 0.dp else segment,
        spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow), label = "seg",
    )
    val pillColor by animateColorAsState(if (mode == CaptureMode.AUTO) Palette.Cyan else Palette.Amber, label = "pill")
    Box(
        Modifier
            .glass(RoundedCornerShape(50))
            .padding(3.dp),
    ) {
        Box(
            Modifier
                .offset(x = offset)
                .width(segment)
                .height(32.dp)
                .background(pillColor.copy(alpha = 0.16f), RoundedCornerShape(50))
                .border(1.dp, pillColor.copy(alpha = 0.5f), RoundedCornerShape(50)),
        )
        Row {
            items.forEach { (m, label) ->
                val selected = m == mode
                val color by animateColorAsState(
                    if (selected) (if (m == CaptureMode.AUTO) Palette.Cyan else Palette.Amber) else Palette.Mist,
                    label = "segText",
                )
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .width(segment)
                        .height(32.dp)
                        .clip(RoundedCornerShape(50))
                        .clickable { onChange(m) },
                ) {
                    Text(label, style = Type.LabelLarge, color = color)
                }
            }
        }
    }
}

// ------------------------------------------------------------------ Lentes

@Composable
fun LensSelector(
    lenses: List<Lens>,
    active: Lens?,
    zoomRatio: Float,
    rotation: Int,
    onSelect: (Lens) -> Unit,
) {
    if (lenses.isEmpty() || active == null) return
    val currentFactor = active.baseFactor * zoomRatio
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.glass(RoundedCornerShape(50)).padding(4.dp),
        ) {
            lenses.forEach { lens ->
                val selected = lens == active
                val size by animateDpAsState(if (selected) 42.dp else 34.dp, label = "lensSize")
                val bg by animateColorAsState(if (selected) Palette.Ink.copy(alpha = 0.85f) else Color.Transparent, label = "lensBg")
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(size)
                        .clip(CircleShape)
                        .background(bg)
                        .clickable { onSelect(lens) },
                ) {
                    val text = if (selected) formatFactor(currentFactor) + "×" else lens.label
                    Text(
                        text,
                        style = Type.ReadoutSmall.copy(
                            fontSize = if (selected) 12.sp else 11.sp,
                            fontWeight = FontWeight.SemiBold,
                        ),
                        color = if (selected) Palette.Amber else Palette.Paper,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.followRotation(rotation),
                    )
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        val mm = (active.equivalentMm * zoomRatio / active.zoom).toInt()
        Text("$mm MM", style = Type.Label, color = Palette.Paper.copy(alpha = 0.7f))
    }
}

// ------------------------------------------------------------------ Lecturas

data class ReadoutItem(
    val label: String,
    val value: String,
    val auto: Boolean,
    val selected: Boolean,
    val enabled: Boolean = true,
    val onClick: (() -> Unit)?,
)

/**
 * Tira de instrumentos (estilo cámara de cine): siempre muestra lo que la cámara está usando.
 * Cian + "A" = lo decide la cámara. Ámbar = lo fijaste tú.
 */
@Composable
fun ReadoutStrip(items: List<ReadoutItem>, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        items.forEach { item -> ReadoutCell(item, Modifier.weight(1f)) }
    }
}

@Composable
private fun ReadoutCell(item: ReadoutItem, modifier: Modifier) {
    val valueColor by animateColorAsState(
        when {
            !item.enabled -> Palette.Fog
            item.auto -> Palette.Paper
            else -> Palette.Amber
        }, label = "readout",
    )
    val underline by animateFloatAsState(if (item.selected) 1f else 0f, tween(220), label = "ul")
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .then(if (item.onClick != null && item.enabled) Modifier.clickable { item.onClick.invoke() } else Modifier)
            .padding(vertical = 6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AnimatedVisibility(item.auto && item.enabled && item.onClick != null) {
                Box(Modifier.padding(end = 4.dp).size(4.dp).background(Palette.Cyan, CircleShape))
            }
            Text(item.label, style = Type.Label.copy(fontSize = 9.sp), color = if (item.selected) Palette.Amber else Palette.Mist, maxLines = 1, softWrap = false)
        }
        Spacer(Modifier.height(3.dp))
        Text(item.value, style = Type.Readout, color = valueColor, maxLines = 1, softWrap = false)
        Spacer(Modifier.height(5.dp))
        Box(
            Modifier
                .width(18.dp * underline)
                .height(2.dp)
                .background(Palette.Amber, RoundedCornerShape(1.dp)),
        )
    }
}

/** Pequeña pastilla informativa (bloqueo AE/AF, altas luces, zoom…). */
@Composable
fun InfoPill(text: String, color: Color, icon: ImageVector? = null, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.18f))
            .border(1.dp, color.copy(alpha = 0.55f), RoundedCornerShape(50))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 10.dp, vertical = 5.dp),
    ) {
        if (icon != null) {
            Icon(icon, null, tint = color, modifier = Modifier.size(12.dp))
            Spacer(Modifier.width(5.dp))
        }
        Text(text, style = Type.Label, color = color)
    }
}

/** Chip seleccionable (balance de blancos, opciones de ajustes). */
@Composable
fun ChoiceChip(label: String, sub: String?, selected: Boolean, accent: Color = Palette.Amber, width: Dp? = null, onClick: () -> Unit) {
    val bg by animateColorAsState(if (selected) accent.copy(alpha = 0.16f) else Palette.Ink.copy(alpha = 0.35f), label = "chipBg")
    val border by animateColorAsState(if (selected) accent.copy(alpha = 0.7f) else Palette.Hairline, label = "chipBorder")
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .then(if (width != null) Modifier.width(width) else Modifier)
            .clip(RoundedCornerShape(12.dp))
            .background(bg)
            .border(1.dp, border, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 7.dp),
    ) {
        Text(label, style = Type.LabelLarge.copy(fontSize = 11.sp, letterSpacing = 0.4.sp), color = if (selected) accent else Palette.Paper)
        if (sub != null) Text(sub, style = Type.ReadoutSmall.copy(fontSize = 9.sp), color = Palette.Mist)
    }
}
