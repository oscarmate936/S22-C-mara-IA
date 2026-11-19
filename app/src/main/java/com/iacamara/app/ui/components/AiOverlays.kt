package com.iacamara.app.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.iacamara.app.ai.CompositionGoal
import com.iacamara.app.ai.SceneResult
import com.iacamara.app.ai.Subject
import com.iacamara.app.ui.theme.Palette
import com.iacamara.app.ui.theme.Type
import kotlin.math.hypot
import kotlin.math.roundToInt

/** Recuadro cian con esquinas alrededor del sujeto que sigue la IA. */
@Composable
fun SubjectBox(subject: Subject?) {
    if (subject == null) return
    val l by animateFloatAsState(subject.box.left, spring(stiffness = 300f), label = "l")
    val t by animateFloatAsState(subject.box.top, spring(stiffness = 300f), label = "t")
    val r by animateFloatAsState(subject.box.right, spring(stiffness = 300f), label = "r")
    val b by animateFloatAsState(subject.box.bottom, spring(stiffness = 300f), label = "b")
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val wPx = with(density) { maxWidth.toPx() }
        val hPx = with(density) { maxHeight.toPx() }
        Canvas(Modifier.fillMaxSize()) {
            val left = l * size.width
            val top = t * size.height
            val right = r * size.width
            val bottom = b * size.height
            val c = (minOf(right - left, bottom - top) * 0.22f).coerceIn(10f, 60f)
            val sw = 1.6.dp.toPx()
            val col = Palette.Cyan
            listOf(
                Offset(left, top) to Pair(1f, 1f), Offset(right, top) to Pair(-1f, 1f),
                Offset(left, bottom) to Pair(1f, -1f), Offset(right, bottom) to Pair(-1f, -1f),
            ).forEach { (p, d) ->
                drawLine(col, p, p + Offset(c * d.first, 0f), sw, StrokeCap.Round)
                drawLine(col, p, p + Offset(0f, c * d.second), sw, StrokeCap.Round)
            }
        }
        Box(
            Modifier
                .offset { IntOffset((l * wPx).roundToInt(), ((t * hPx) - 22.dp.toPx()).roundToInt().coerceAtLeast(0)) }
                .clip(RoundedCornerShape(4.dp))
                .background(Palette.Cyan.copy(alpha = 0.85f))
                .padding(horizontal = 5.dp, vertical = 1.dp),
        ) {
            Text(subject.label.uppercase(), style = Type.Label.copy(fontSize = 9.sp), color = Palette.Ink)
        }
    }
}

/**
 * Entrenador de composición: muestra a dónde mover el sujeto (punto fuerte de los tercios)
 * o dónde poner el horizonte, y celebra con verde y vibración cuando lo logras.
 */
@Composable
fun CompositionCoach(subject: Subject?, goal: CompositionGoal, haptics: Boolean) {
    val haptic = LocalHapticFeedback.current
    when {
        goal == CompositionGoal.HORIZON_LOW || goal == CompositionGoal.HORIZON_HIGH -> {
            val y = if (goal == CompositionGoal.HORIZON_LOW) 2f / 3f else 1f / 3f
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val hPx = with(LocalDensity.current) { maxHeight.toPx() }
                Canvas(Modifier.fillMaxSize()) {
                    val py = size.height * y
                    drawLine(Palette.Cyan.copy(alpha = 0.25f), Offset(0f, py), Offset(size.width, py), 6.dp.toPx())
                    drawLine(Palette.Cyan, Offset(0f, py), Offset(size.width, py), 1.4.dp.toPx(),
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(14f, 10f)))
                }
                Text(
                    "HORIZONTE AQUÍ",
                    style = Type.Label.copy(fontSize = 9.sp),
                    color = Palette.Cyan,
                    modifier = Modifier.offset { IntOffset(12.dp.roundToPx(), (hPx * y - 18.dp.toPx()).roundToInt()) },
                )
            }
        }
        goal == CompositionGoal.SUBJECT_THIRDS && subject != null -> {
            val ax = subject.anchorX
            val ay = subject.anchorY
            // Punto fuerte más cercano
            val tx = if (ax < 0.5f) 1f / 3f else 2f / 3f
            val ty = if (ay < 0.5f) 1f / 3f else 2f / 3f
            val dist = hypot(ax - tx, (ay - ty) * 4f / 3f)
            val aligned = dist < 0.06f
            val big = subject.box.width() > 0.6f // muy cerca: no tiene sentido moverlo
            if (big) return
            LaunchedEffect(aligned) { if (aligned && haptics) haptic.performHapticFeedback(HapticFeedbackType.LongPress) }
            val ring by animateColorAsState(if (aligned) Palette.Level else Palette.Cyan, label = "ring")
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val density = LocalDensity.current
                val wPx = with(density) { maxWidth.toPx() }
                val hPx = with(density) { maxHeight.toPx() }
                Canvas(Modifier.fillMaxSize()) {
                    val target = Offset(tx * size.width, ty * size.height)
                    val anchor = Offset(ax * size.width, ay * size.height)
                    val rad = 15.dp.toPx()
                    if (!aligned) {
                        drawLine(
                            Palette.Cyan.copy(alpha = 0.8f), anchor, target, 1.5.dp.toPx(),
                            cap = StrokeCap.Round, pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 9f)),
                        )
                        drawCircle(Palette.Cyan, 3.dp.toPx(), anchor)
                    }
                    drawCircle(ring.copy(alpha = if (aligned) 0.25f else 0.12f), rad, target)
                    drawCircle(ring, rad, target, style = Stroke(1.8.dp.toPx(),
                        pathEffect = if (aligned) null else PathEffect.dashPathEffect(floatArrayOf(8f, 6f))))
                }
                AnimatedVisibility(
                    aligned, enter = fadeIn(), exit = fadeOut(),
                    modifier = Modifier.offset {
                        IntOffset((tx * wPx - 52.dp.toPx()).roundToInt(), (ty * hPx + 22.dp.toPx()).roundToInt())
                    },
                ) {
                    InfoPill("BIEN COMPUESTO", Palette.Level, Icons.Rounded.Check)
                }
            }
        }
    }
}

/**
 * Etiqueta de escena. Toca el nombre para ver qué haría un fotógrafo;
 * toca "AJUSTAR" para que la app lo aplique en un toque.
 */
@Composable
fun SceneChip(result: SceneResult?, applied: Boolean, onOpen: () -> Unit, onApply: () -> Unit) {
    AnimatedVisibility(result != null, enter = fadeIn(), exit = fadeOut()) {
        val r = result ?: return@AnimatedVisibility
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.glass(RoundedCornerShape(50)),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.clip(RoundedCornerShape(50)).clickable(onClick = onOpen).padding(start = 10.dp, end = 10.dp, top = 7.dp, bottom = 7.dp),
            ) {
                Icon(Icons.Rounded.AutoAwesome, null, tint = Palette.Cyan, modifier = Modifier.size(13.dp))
                Spacer(Modifier.width(6.dp))
                AnimatedContent(r.scene, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "scene") { sc ->
                    Text(sc.title.substringBefore(" ·").uppercase(), style = Type.Label, color = Palette.Paper)
                }
            }
            if (r.scene.recipe != null) {
                Box(Modifier.width(1.dp).height(18.dp).background(Palette.Hairline))
                val col = if (applied) Palette.Level else Palette.Amber
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.clip(RoundedCornerShape(50)).clickable(enabled = !applied, onClick = onApply)
                        .padding(start = 9.dp, end = 11.dp, top = 7.dp, bottom = 7.dp),
                ) {
                    if (applied) {
                        Icon(Icons.Rounded.Check, null, tint = col, modifier = Modifier.size(12.dp))
                        Spacer(Modifier.width(4.dp))
                    }
                    Text(if (applied) "LISTO" else "AJUSTAR", style = Type.Label, color = col)
                }
            }
        }
    }
}

