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
import com.iacamara.app.ai.CompositionPlan
import com.iacamara.app.ai.SceneResult
import com.iacamara.app.ai.shortTitle
import com.iacamara.app.ai.Subject
import com.iacamara.app.ui.theme.Palette
import com.iacamara.app.ui.theme.Type
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
 * Entrenador de composición con IA: dibuja el plan del compositor.
 *  • Sujeto: línea desde los ojos/centro del sujeto hasta el punto donde debe ir.
 *  • Horizonte: la línea real detectada en la imagen (con su inclinación) y la guía donde debe quedar.
 * Se pone verde y vibra cuando queda bien compuesto.
 */
@Composable
fun CompositionCoach(plan: CompositionPlan, haptics: Boolean) {
    val haptic = LocalHapticFeedback.current
    val hasGuide = plan.target != null || plan.targetLine != null
    LaunchedEffect(plan.aligned && hasGuide) { if (plan.aligned && hasGuide && haptics) haptic.performHapticFeedback(HapticFeedbackType.LongPress) }
    val color by animateColorAsState(if (plan.aligned) Palette.Level else Palette.Cyan, label = "coach")

    plan.targetLine?.let { (t0, t1) ->
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val density = LocalDensity.current
            val wPx = with(density) { maxWidth.toPx() }
            val hPx = with(density) { maxHeight.toPx() }
            Canvas(Modifier.fillMaxSize()) {
                val a = Offset(t0.x * size.width, t0.y * size.height)
                val b = Offset(t1.x * size.width, t1.y * size.height)
                // Guía: dónde debe quedar el horizonte (sigue la orientación del teléfono)
                drawLine(color.copy(alpha = 0.22f), a, b, 6.dp.toPx())
                drawLine(color, a, b, 1.4.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(14f, 10f)))
                // Horizonte real detectado
                plan.horizonLine?.let { (h0, h1) ->
                    val c = Offset(h0.x * size.width, h0.y * size.height)
                    val e = Offset(h1.x * size.width, h1.y * size.height)
                    drawLine(Palette.Ink.copy(alpha = 0.35f), c + Offset(0f, 1.5f), e + Offset(0f, 1.5f), 2.5.dp.toPx())
                    drawLine(if (plan.aligned) Palette.Level else Palette.Amber, c, e, 2.dp.toPx(), cap = StrokeCap.Round)
                }
            }
            val lx = (minOf(t0.x, t1.x) * wPx).coerceIn(0f, wPx - 1f)
            val ly = (minOf(t0.y, t1.y) * hPx).coerceIn(0f, hPx - 1f)
            Text(
                if (plan.aligned && plan.horizonFound) "HORIZONTE PERFECTO" else "HORIZONTE AQUÍ",
                style = Type.Label.copy(fontSize = 9.sp),
                color = color,
                modifier = Modifier.offset { IntOffset((lx + 12.dp.toPx()).roundToInt(), (ly - 18.dp.toPx()).roundToInt().coerceAtLeast(4)) },
            )
        }
    }

    val target = plan.target
    val anchor = plan.anchor
    if (target != null && anchor != null) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val density = LocalDensity.current
            val wPx = with(density) { maxWidth.toPx() }
            val hPx = with(density) { maxHeight.toPx() }
            Canvas(Modifier.fillMaxSize()) {
                val t = Offset(target.x * size.width, target.y * size.height)
                val an = Offset(anchor.x * size.width, anchor.y * size.height)
                val rad = 15.dp.toPx()
                if (!plan.aligned) {
                    drawLine(Palette.Cyan.copy(alpha = 0.8f), an, t, 1.5.dp.toPx(),
                        cap = StrokeCap.Round, pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 9f)))
                    drawCircle(Palette.Cyan, 3.dp.toPx(), an)
                }
                drawCircle(color.copy(alpha = if (plan.aligned) 0.25f else 0.12f), rad, t)
                drawCircle(color, rad, t, style = Stroke(1.8.dp.toPx(),
                    pathEffect = if (plan.aligned) null else PathEffect.dashPathEffect(floatArrayOf(8f, 6f))))
            }
            AnimatedVisibility(
                plan.aligned, enter = fadeIn(), exit = fadeOut(),
                modifier = Modifier.offset {
                    IntOffset((target.x * wPx - 52.dp.toPx()).roundToInt(), (target.y * hPx + 22.dp.toPx()).roundToInt())
                },
            ) {
                InfoPill("BIEN COMPUESTO", Palette.Level, Icons.Rounded.Check)
            }
        }
    }
}

/** Indicación corta de composición ("Mueve el teléfono a la derecha →") con el porqué. */
@Composable
fun CompositionHint(plan: CompositionPlan?) {
    val ins = plan?.instruction
    AnimatedVisibility(ins != null, enter = fadeIn(), exit = fadeOut()) {
        val i = ins ?: return@AnimatedVisibility
        androidx.compose.foundation.layout.Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.glass(RoundedCornerShape(16.dp)).padding(horizontal = 14.dp, vertical = 8.dp),
        ) {
            Text(i.text, style = Type.BodyStrong.copy(fontSize = 14.sp), color = Palette.Cyan)
            i.why?.let { Text(it, style = Type.Body.copy(fontSize = 11.sp, lineHeight = 14.sp), color = Palette.Mist) }
        }
    }
}

/**
 * Etiqueta de escena. Toca el nombre para ver qué haría un fotógrafo;
 * toca "AJUSTAR" para que la app lo aplique en un toque.
 */
@Composable
fun SceneChip(result: SceneResult?, applied: Boolean, showApply: Boolean = true, onOpen: () -> Unit, onApply: () -> Unit) {
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
                    Text(sc.shortTitle().uppercase(), style = Type.Label, color = Palette.Paper)
                }
            }
            if (showApply && r.scene.recipe != null) {
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


/**
 * Lo que está decidiendo el cerebro fotógrafo, explicado en una línea cada cosa.
 * Así aprendes fotografía mientras disparas.
 */
@Composable
fun BrainReasons(reasons: List<com.iacamara.app.ai.Reason>) {
    AnimatedVisibility(reasons.isNotEmpty(), enter = fadeIn(), exit = fadeOut()) {
        androidx.compose.foundation.layout.Column(
            Modifier.width(196.dp).glass(RoundedCornerShape(14.dp)).padding(horizontal = 10.dp, vertical = 8.dp),
        ) {
            reasons.forEachIndexed { idx, r ->
                if (idx > 0) Spacer(Modifier.height(5.dp))
                Row(verticalAlignment = Alignment.Top) {
                    Text(
                        r.value, style = Type.ReadoutSmall, color = Palette.Cyan, maxLines = 1,
                        modifier = Modifier.width(62.dp),
                    )
                    Text(r.why, style = Type.Body.copy(fontSize = 11.sp, lineHeight = 14.sp), color = Palette.Paper.copy(alpha = 0.85f))
                }
            }
        }
    }
}
