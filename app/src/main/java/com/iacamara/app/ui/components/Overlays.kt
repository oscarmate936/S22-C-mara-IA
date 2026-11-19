package com.iacamara.app.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.iacamara.app.GridType
import com.iacamara.app.camera.FrameStats
import com.iacamara.app.ui.theme.Palette
import com.iacamara.app.ui.theme.Type
import kotlin.math.abs
import kotlin.math.roundToInt

/** Guías de composición. Líneas finas con sombra para que se lean sobre cielo claro o sombra. */
@Composable
fun GridOverlay(type: GridType, modifier: Modifier = Modifier) {
    if (type == GridType.OFF) return
    Canvas(modifier.fillMaxSize()) {
        val stops = when (type) {
            GridType.THIRDS -> listOf(1f / 3f, 2f / 3f)
            GridType.GOLDEN -> listOf(0.382f, 0.618f)
            else -> emptyList()
        }
        fun line(a: Offset, b: Offset) {
            drawLine(Color.Black.copy(alpha = 0.25f), a + Offset(0f, 1f), b + Offset(0f, 1f), strokeWidth = 1.5f)
            drawLine(Color.White.copy(alpha = 0.42f), a, b, strokeWidth = 1.2f)
        }
        stops.forEach { s ->
            line(Offset(size.width * s, 0f), Offset(size.width * s, size.height))
            line(Offset(0f, size.height * s), Offset(size.width, size.height * s))
        }
        if (type == GridType.THIRDS || type == GridType.GOLDEN) {
            // Puntos fuertes: donde la mirada cae de forma natural.
            stops.forEach { sx ->
                stops.forEach { sy ->
                    drawCircle(Color.White.copy(alpha = 0.55f), radius = 2.5.dp.toPx(), center = Offset(size.width * sx, size.height * sy))
                }
            }
        }
        if (type == GridType.CENTER) {
            val c = center
            val l = 14.dp.toPx()
            line(Offset(c.x - l, c.y), Offset(c.x + l, c.y))
            line(Offset(c.x, c.y - l), Offset(c.x, c.y + l))
        }
    }
}

/**
 * Nivel de horizonte. Gira con la inclinación real y se vuelve verde (con una vibración)
 * cuando la foto queda recta.
 */
@Composable
fun LevelIndicator(tilt: Float, flat: Boolean, haptics: Boolean, modifier: Modifier = Modifier) {
    if (flat) return
    val level = abs(tilt) < 0.8f
    val haptic = LocalHapticFeedback.current
    LaunchedEffect(level) { if (level && haptics) haptic.performHapticFeedback(HapticFeedbackType.LongPress) }
    val color by animateColorAsState(if (level) Palette.Level else Palette.Paper.copy(alpha = 0.85f), label = "level")
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(width = 168.dp, height = 2.dp)) {
            // Referencia fija (corta) en el centro
            drawLine(Palette.Paper.copy(alpha = 0.35f), Offset(size.width * 0.44f, 1f), Offset(size.width * 0.56f, 1f), strokeWidth = 2f)
        }
        Canvas(Modifier.size(width = 168.dp, height = 2.dp).rotate(-tilt)) {
            val w = size.width
            val gap = w * 0.16f
            drawLine(color, Offset(0f, 1f), Offset(w / 2 - gap, 1f), strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
            drawLine(color, Offset(w / 2 + gap, 1f), Offset(w, 1f), strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
        }
        if (!level) {
            Text(
                "${if (tilt > 0) "+" else ""}${(tilt * 10).roundToInt() / 10f}°",
                style = Type.ReadoutSmall,
                color = Palette.Paper.copy(alpha = 0.8f),
                modifier = Modifier.offset(y = 16.dp),
            )
        }
    }
}

/** Histograma de luminancia: muestra si la foto está oscura, clara o quemada. */
@Composable
fun Histogram(stats: FrameStats?, modifier: Modifier = Modifier) {
    Column(modifier.glass(RoundedCornerShape(10.dp)).padding(horizontal = 8.dp, vertical = 6.dp)) {
        Canvas(Modifier.size(width = 92.dp, height = 34.dp)) {
            val s = stats ?: return@Canvas
            drawHistogram(s.histogram, Palette.Paper.copy(alpha = 0.75f))
            // Marcas de recorte en los extremos
            if (s.clippedHighlights > 0.02f) {
                drawRect(Palette.Signal, topLeft = Offset(size.width - 2.dp.toPx(), 0f), size = Size(2.dp.toPx(), size.height))
            }
            if (s.clippedShadows > 0.05f) {
                drawRect(Palette.Cyan, topLeft = Offset(0f, 0f), size = Size(2.dp.toPx(), size.height))
            }
        }
    }
}

private fun DrawScope.drawHistogram(h: FloatArray, color: Color) {
    val path = Path()
    val stepX = size.width / (h.size - 1)
    path.moveTo(0f, size.height)
    h.forEachIndexed { i, v -> path.lineTo(i * stepX, size.height - v.coerceIn(0f, 1f) * size.height) }
    path.lineTo(size.width, size.height)
    path.close()
    drawPath(path, color.copy(alpha = 0.35f))
    drawPath(path, color, style = Stroke(width = 1.2f))
}

/** Retícula de enfoque con esquinas, animada al tocar. */
@Composable
fun FocusReticle(position: Offset, locked: Boolean, key: Int) {
    val scale = remember(key) { Animatable(1.35f) }
    val alpha = remember(key) { Animatable(1f) }
    LaunchedEffect(key, locked) {
        scale.animateTo(1f, tween(220))
        if (!locked) {
            kotlinx.coroutines.delay(1400)
            alpha.animateTo(0f, tween(400))
        } else {
            alpha.snapTo(1f)
        }
    }
    val sizeDp = 76.dp
    val px = with(LocalDensity.current) { sizeDp.toPx() }
    Canvas(
        Modifier
            .offset { IntOffset((position.x - px / 2).roundToInt(), (position.y - px / 2).roundToInt()) }
            .size(sizeDp)
            .alpha(alpha.value),
    ) {
        val s = size.width * scale.value
        val o = (size.width - s) / 2
        val c = s * 0.24f
        val color = Palette.Amber
        val sw = 2.dp.toPx()
        val tl = Offset(o, o)
        val br = Offset(o + s, o + s)
        // Cuatro esquinas
        drawLine(color, tl, tl + Offset(c, 0f), sw, StrokeCap.Round)
        drawLine(color, tl, tl + Offset(0f, c), sw, StrokeCap.Round)
        drawLine(color, Offset(br.x, tl.y), Offset(br.x - c, tl.y), sw, StrokeCap.Round)
        drawLine(color, Offset(br.x, tl.y), Offset(br.x, tl.y + c), sw, StrokeCap.Round)
        drawLine(color, Offset(tl.x, br.y), Offset(tl.x + c, br.y), sw, StrokeCap.Round)
        drawLine(color, Offset(tl.x, br.y), Offset(tl.x, br.y - c), sw, StrokeCap.Round)
        drawLine(color, br, br - Offset(c, 0f), sw, StrokeCap.Round)
        drawLine(color, br, br - Offset(0f, c), sw, StrokeCap.Round)
        drawCircle(color, radius = 2.dp.toPx(), center = Offset(size.width / 2, size.height / 2))
    }
}

/** Marco fino alrededor del visor cuando está en PRO (señal clara de "control manual"). */
@Composable
fun ProFrame(visible: Boolean) {
    val a by androidx.compose.animation.core.animateFloatAsState(if (visible) 1f else 0f, label = "frame")
    if (a <= 0f) return
    Canvas(Modifier.fillMaxSize().alpha(a)) {
        val l = 18.dp.toPx()
        val m = 10.dp.toPx()
        val sw = 1.5.dp.toPx()
        val c = Palette.Amber.copy(alpha = 0.8f)
        val w = size.width
        val h = size.height
        drawLine(c, Offset(m, m), Offset(m + l, m), sw); drawLine(c, Offset(m, m), Offset(m, m + l), sw)
        drawLine(c, Offset(w - m, m), Offset(w - m - l, m), sw); drawLine(c, Offset(w - m, m), Offset(w - m, m + l), sw)
        drawLine(c, Offset(m, h - m), Offset(m + l, h - m), sw); drawLine(c, Offset(m, h - m), Offset(m, h - m - l), sw)
        drawLine(c, Offset(w - m, h - m), Offset(w - m - l, h - m), sw); drawLine(c, Offset(w - m, h - m), Offset(w - m, h - m - l), sw)
    }
}

