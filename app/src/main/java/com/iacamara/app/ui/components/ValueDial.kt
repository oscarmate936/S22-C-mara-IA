package com.iacamara.app.ui.components

import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import com.iacamara.app.camera.DialOption
import com.iacamara.app.ui.theme.Palette
import com.iacamara.app.ui.theme.Type
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

private val TickWidth = 16.dp

/**
 * Dial horizontal tipo "regla", como el anillo de una cámara de cine.
 * Se arrastra con el pulgar, encaja en cada valor y vibra levemente en cada paso.
 *
 * @param isAuto si es true, el dial sigue el valor que mide la cámara (aguja cian);
 *               al moverlo la persona toma el control manual (aguja ámbar).
 */
@Composable
fun ValueDial(
    options: List<DialOption>,
    selectedIndex: Int,
    isAuto: Boolean,
    haptics: Boolean,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val dragged by listState.interactionSource.collectIsDraggedAsState()
    var userActive by remember { mutableStateOf(false) }
    val currentOnSelect by rememberUpdatedState(onSelect)
    val accent = if (isAuto) Palette.Cyan else Palette.Amber

    val centered by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val center = (info.viewportStartOffset + info.viewportEndOffset) / 2
            info.visibleItemsInfo.minByOrNull { abs(it.offset + it.size / 2 - center) }?.index ?: selectedIndex
        }
    }

    LaunchedEffect(dragged) { if (dragged) userActive = true }
    LaunchedEffect(listState.isScrollInProgress) {
        if (!listState.isScrollInProgress && userActive) {
            delay(250)
            userActive = false
        }
    }
    LaunchedEffect(listState) {
        snapshotFlow { centered }.collect { idx ->
            if (userActive && idx in options.indices) {
                if (haptics) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                currentOnSelect(idx)
            }
        }
    }
    LaunchedEffect(selectedIndex, options.size) {
        if (!userActive && selectedIndex in options.indices && centered != selectedIndex) {
            listState.scrollToItem(selectedIndex)
        }
    }

    BoxWithConstraints(modifier.fillMaxWidth().height(58.dp)) {
        val side = maxWidth / 2 - TickWidth / 2
        LazyRow(
            state = listState,
            flingBehavior = rememberSnapFlingBehavior(listState),
            contentPadding = PaddingValues(horizontal = side),
            modifier = Modifier
                .fillMaxWidth()
                .height(58.dp)
                // Desvanecer los bordes para que la regla "salga" del panel.
                .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
                .drawWithContent {
                    drawContent()
                    drawRect(
                        Brush.horizontalGradient(
                            0f to Color.Transparent, 0.18f to Color.Black,
                            0.82f to Color.Black, 1f to Color.Transparent,
                        ),
                        blendMode = BlendMode.DstIn,
                    )
                },
        ) {
            itemsIndexed(options) { i, opt ->
                val isCenter = i == centered
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .width(TickWidth)
                        .height(58.dp)
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                            userActive = true
                            scope.launch { listState.animateScrollToItem(i) }
                        },
                ) {
                    Box(Modifier.height(8.dp))
                    Box(
                        Modifier
                            .width(if (opt.major) 2.dp else 1.dp)
                            .height(if (opt.major) 16.dp else 9.dp)
                            .offset(y = if (opt.major) 0.dp else 3.5.dp)
                            .background(
                                if (isCenter) accent else if (opt.major) Palette.Paper.copy(alpha = 0.8f) else Palette.Mist.copy(alpha = 0.45f),
                                RoundedCornerShape(1.dp),
                            ),
                    )
                    Box(Modifier.height(8.dp))
                    if (isCenter || (opt.major && abs(i - centered) >= 2)) {
                        Text(
                            opt.label,
                            style = Type.ReadoutSmall,
                            color = if (isCenter) accent else Palette.Mist,
                            softWrap = false,
                            maxLines = 1,
                            modifier = Modifier.wrapContentWidth(unbounded = true),
                        )
                    }
                }
            }
        }
        // Aguja central
        Box(
            Modifier
                .align(Alignment.TopCenter)
                .width(2.dp)
                .height(26.dp)
                .background(accent, RoundedCornerShape(1.dp)),
        )
    }
}
