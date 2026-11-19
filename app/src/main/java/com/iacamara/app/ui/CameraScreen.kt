package com.iacamara.app.ui

import android.content.Intent
import android.provider.MediaStore
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cameraswitch
import androidx.compose.material.icons.rounded.FlashAuto
import androidx.compose.material.icons.rounded.FlashOff
import androidx.compose.material.icons.rounded.FlashOn
import androidx.compose.material.icons.rounded.GridOff
import androidx.compose.material.icons.rounded.GridOn
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Timer10
import androidx.compose.material.icons.rounded.Timer3
import androidx.compose.material.icons.rounded.TimerOff
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.WbSunny
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.iacamara.app.GridType
import com.iacamara.app.Prefs
import com.iacamara.app.camera.AspectSetting
import com.iacamara.app.camera.CameraEngine
import com.iacamara.app.camera.CaptureMode
import com.iacamara.app.camera.ExposureMath
import com.iacamara.app.camera.FlashSetting
import com.iacamara.app.camera.Param
import com.iacamara.app.camera.WhiteBalance
import com.iacamara.app.sensors.DeviceSensors
import com.iacamara.app.ui.components.BarIconButton
import com.iacamara.app.ui.components.BarTextButton
import com.iacamara.app.ui.components.ChoiceChip
import com.iacamara.app.ui.components.FocusReticle
import com.iacamara.app.ui.components.GalleryThumb
import com.iacamara.app.ui.components.GridOverlay
import com.iacamara.app.ui.components.Histogram
import com.iacamara.app.ui.components.InfoPill
import com.iacamara.app.ui.components.LensSelector
import com.iacamara.app.ui.components.LevelIndicator
import com.iacamara.app.ui.components.ModeSwitch
import com.iacamara.app.ui.components.ProFrame
import com.iacamara.app.ui.components.ReadoutItem
import com.iacamara.app.ui.components.ReadoutStrip
import com.iacamara.app.ui.components.RoundGlassButton
import com.iacamara.app.ui.components.ShutterButton
import com.iacamara.app.ui.components.ValueDial
import com.iacamara.app.ui.components.glass
import com.iacamara.app.ui.theme.Palette
import com.iacamara.app.ui.theme.Type
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

@Composable
fun CameraScreen(
    engine: CameraEngine,
    prefs: Prefs,
    sensors: DeviceSensors,
    registerVolumeShutter: ((() -> Unit)?) -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val previewView = remember {
        PreviewView(context).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }

    var showSettings by remember { mutableStateOf(false) }
    var countdown by remember { mutableIntStateOf(0) }
    var reticle by remember { mutableStateOf<Offset?>(null) }
    var reticleKey by remember { mutableIntStateOf(0) }
    var lastPinch by remember { mutableLongStateOf(0L) }
    val shutterFlash = remember { Animatable(0f) }
    val rotation = sensors.uiRotation

    // Ciclo de vida: cámara y sensores
    DisposableEffect(lifecycleOwner) {
        engine.bind(lifecycleOwner, previewView)
        val obs = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_RESUME -> sensors.start()
                Lifecycle.Event.ON_PAUSE -> sensors.stop()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(obs)
            sensors.stop()
            engine.release()
        }
    }
    LaunchedEffect(sensors.surfaceRotation) { engine.setRotation(sensors.surfaceRotation) }

    fun shoot() {
        if (countdown > 0 || engine.isCapturing) return
        scope.launch {
            if (prefs.timerSeconds > 0) {
                for (s in prefs.timerSeconds downTo 1) {
                    countdown = s
                    if (prefs.haptics) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    delay(1000)
                }
                countdown = 0
            }
            if (prefs.haptics) haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            engine.capture()
            shutterFlash.snapTo(0.9f)
            shutterFlash.animateTo(0f, tween(260))
        }
    }

    DisposableEffect(Unit) {
        registerVolumeShutter { shoot() }
        onDispose { registerVolumeShutter(null) }
    }

    LaunchedEffect(engine.errorMessage) {
        if (engine.errorMessage != null) { delay(2600); engine.errorMessage = null }
    }

    val wide = engine.aspect == AspectSetting.RATIO_16_9
    val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val headerHeight = 52.dp + 58.dp
    val pro = engine.mode == CaptureMode.PRO

    Box(Modifier.fillMaxSize().background(Palette.Ink)) {

        // ------------------------------------------------ Visor
        Box(
            Modifier
                .padding(top = if (wide) statusTop else statusTop + headerHeight)
                .fillMaxWidth()
                .aspectRatio(if (wide) 9f / 16f else 3f / 4f)
                .clip(RoundedCornerShape(if (wide) 0.dp else 4.dp)),
        ) {
            AndroidView(
                factory = { previewView },
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectTransformGestures { _, _, zoom, _ ->
                            if (zoom != 1f) {
                                engine.pinchZoom(zoom)
                                lastPinch = System.currentTimeMillis()
                            }
                        }
                    }
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onTap = { o ->
                                val p = previewView.meteringPointFactory.createPoint(o.x, o.y)
                                engine.tapToFocus(p, lock = false)
                                reticle = o; reticleKey++
                            },
                            onLongPress = { o ->
                                val p = previewView.meteringPointFactory.createPoint(o.x, o.y)
                                engine.tapToFocus(p, lock = true)
                                if (prefs.haptics) haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                reticle = o; reticleKey++
                            },
                        )
                    },
            )
            GridOverlay(prefs.grid)
            if (prefs.level) LevelIndicator(sensors.tilt, sensors.isFlat, prefs.haptics)
            ProFrame(pro)
            reticle?.let { FocusReticle(it, engine.aeAfLocked, reticleKey) }

            // Indicadores superiores del visor
            Column(
                Modifier.align(Alignment.TopStart).padding(top = if (wide) headerHeight + 10.dp else 12.dp, start = 12.dp),
            ) {
                if (prefs.histogram) Histogram(engine.stats)
                val clipped = (engine.stats?.clippedHighlights ?: 0f) > 0.03f
                AnimatedVisibility(prefs.highlightWarning && clipped, enter = fadeIn(), exit = fadeOut()) {
                    InfoPill("ALTAS LUCES", Palette.Signal, Icons.Rounded.WbSunny, Modifier.padding(top = 8.dp))
                }
            }
            Column(
                Modifier.align(Alignment.TopCenter).padding(top = if (wide) headerHeight + 10.dp else 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                AnimatedVisibility(engine.aeAfLocked, enter = fadeIn() + slideInVertically(), exit = fadeOut()) {
                    InfoPill("BLOQUEO AE/AF", Palette.Amber, Icons.Rounded.Lock) {
                        engine.unlockFocus(); reticle = null
                    }
                }
                engine.errorMessage?.let { InfoPill(it.uppercase(), Palette.Signal, modifier = Modifier.padding(top = 6.dp)) }
            }

            // Lentes + panel de parámetro (abajo del visor)
            Column(
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = if (wide) 176.dp else 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                LensSelector(engine.lenses, engine.activeLens, engine.zoomRatio, rotation) { engine.selectLens(it) }
                Spacer(Modifier.height(10.dp))
                AnimatedVisibility(
                    visible = pro && engine.selectedParam != null,
                    enter = expandVertically(expandFrom = Alignment.Bottom) + fadeIn(),
                    exit = shrinkVertically(shrinkTowards = Alignment.Bottom) + fadeOut(),
                ) {
                    engine.selectedParam?.let { ParamPanel(engine, it, prefs.haptics) }
                }
            }

            // Destello de disparo
            Box(Modifier.fillMaxSize().alpha(shutterFlash.value).background(Color.Black))
            // Cuenta atrás grande
            AnimatedVisibility(countdown > 0, Modifier.align(Alignment.Center), enter = fadeIn(), exit = fadeOut()) {
                Text("$countdown", style = Type.Display.copy(fontSize = Type.Display.fontSize * 2.6f), color = Palette.Paper.copy(alpha = 0.9f))
            }
            val pinching = System.currentTimeMillis() - lastPinch < 900
            AnimatedVisibility(pinching, Modifier.align(Alignment.Center), enter = fadeIn(), exit = fadeOut()) {
                engine.activeLens?.let {
                    InfoPill(com.iacamara.app.camera.formatFactor(it.baseFactor * engine.zoomRatio) + "×", Palette.Amber)
                }
            }
        }

        // ------------------------------------------------ Cabecera
        Column(
            Modifier
                .fillMaxWidth()
                .background(
                    if (wide) Brush.verticalGradient(listOf(Palette.Ink.copy(alpha = 0.85f), Color.Transparent))
                    else Brush.verticalGradient(listOf(Palette.Ink, Palette.Ink)),
                )
                .statusBarsPadding(),
        ) {
            TopBar(engine, prefs, rotation, onSettings = { showSettings = true })
            ReadoutStrip(readouts(engine), Modifier.height(58.dp))
        }

        // ------------------------------------------------ Controles inferiores
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(
                    if (wide) Brush.verticalGradient(listOf(Color.Transparent, Palette.Ink.copy(alpha = 0.9f)))
                    else Brush.verticalGradient(listOf(Color.Transparent, Color.Transparent)),
                )
                .navigationBarsPadding()
                .padding(bottom = 18.dp, top = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            ModeSwitch(engine.mode) {
                if (prefs.haptics) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                engine.switchMode(it)
                if (it == CaptureMode.PRO && engine.selectedParam == null) engine.selectedParam = Param.SHUTTER
            }
            Spacer(Modifier.height(18.dp))
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 34.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                GalleryThumb(engine.lastPhoto, rotation) {
                    val intent = engine.lastPhotoUri?.let {
                        Intent(Intent.ACTION_VIEW).setDataAndType(it, "image/jpeg").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    } ?: Intent(Intent.ACTION_VIEW, MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
                    runCatching { context.startActivity(intent) }
                }
                ShutterButton(engine.mode, engine.isCapturing, countdown) { shoot() }
                RoundGlassButton(Icons.Rounded.Cameraswitch, "Cambiar cámara", rotation) {
                    reticle = null
                    engine.flipCamera()
                }
            }
        }

        if (showSettings) SettingsSheet(prefs) { showSettings = false }
    }
}

@Composable
private fun TopBar(engine: CameraEngine, prefs: Prefs, rotation: Int, onSettings: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val flashIcon = when (engine.flash) {
            FlashSetting.OFF -> Icons.Rounded.FlashOff
            FlashSetting.AUTO -> Icons.Rounded.FlashAuto
            FlashSetting.ON -> Icons.Rounded.FlashOn
        }
        BarIconButton(flashIcon, "Flash", active = engine.flash != FlashSetting.OFF, rotation = rotation) { engine.cycleFlash() }
        val timerIcon = when (prefs.timerSeconds) { 3 -> Icons.Rounded.Timer3; 10 -> Icons.Rounded.Timer10; else -> Icons.Rounded.TimerOff }
        BarIconButton(timerIcon, "Temporizador", active = prefs.timerSeconds > 0, rotation = rotation) { prefs.cycleTimer() }
        BarTextButton(engine.aspect.label, rotation) {
            engine.changeAspect(if (engine.aspect == AspectSetting.RATIO_4_3) AspectSetting.RATIO_16_9 else AspectSetting.RATIO_4_3)
        }

        Spacer(Modifier.weight(1f))
        Brand(engine.mode)
        Spacer(Modifier.weight(1f))

        BarIconButton(
            if (prefs.grid == GridType.OFF) Icons.Rounded.GridOff else Icons.Rounded.GridOn,
            "Guías", active = prefs.grid != GridType.OFF, rotation = rotation,
        ) { prefs.cycleGrid() }
        BarIconButton(Icons.Rounded.Tune, "Ajustes", rotation = rotation, onClick = onSettings)
    }
}

@Composable
private fun Brand(mode: CaptureMode) {
    val dot by animateColorAsState(if (mode == CaptureMode.AUTO) Palette.Cyan else Palette.Amber, label = "brandDot")
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(6.dp).background(dot, CircleShape))
        Spacer(Modifier.width(7.dp))
        Text("IA CÁMARA", style = Type.LabelLarge, color = Palette.Paper)
    }
}

private fun readouts(engine: CameraEngine): List<ReadoutItem> {
    val live = engine.live
    val caps = engine.caps
    val pro = engine.mode == CaptureMode.PRO
    val fullManual = pro && engine.manualIso != null && engine.manualShutterNs != null
    val sel = engine.selectedParam.takeIf { pro }
    return listOf(
        ReadoutItem(
            "ISO", (engine.manualIso ?: live.iso).takeIf { it > 0 }?.toString() ?: "—",
            auto = !pro || engine.isAuto(Param.ISO), selected = sel == Param.ISO, enabled = caps.manualSensor,
        ) { engine.openParam(Param.ISO) },
        ReadoutItem(
            "OBTUR.", (engine.manualShutterNs ?: live.shutterNs).takeIf { it > 0 }?.let(ExposureMath::formatShutter) ?: "—",
            auto = !pro || engine.isAuto(Param.SHUTTER), selected = sel == Param.SHUTTER, enabled = caps.manualSensor,
        ) { engine.openParam(Param.SHUTTER) },
        ReadoutItem(
            "BLANCOS", if (pro) engine.whiteBalance.kelvin else "AWB",
            auto = !pro || engine.isAuto(Param.WB), selected = sel == Param.WB,
        ) { engine.openParam(Param.WB) },
        ReadoutItem(
            "ENFOQUE", engine.manualFocus?.takeIf { pro }?.let(ExposureMath::formatFocus) ?: "AF",
            auto = !pro || engine.isAuto(Param.FOCUS), selected = sel == Param.FOCUS, enabled = caps.manualFocus,
        ) { engine.openParam(Param.FOCUS) },
        if (fullManual) {
            ReadoutItem("MEDIDOR", ExposureMath.formatEv(engine.meterStops), auto = abs(engine.meterStops) < 0.4f, selected = false, onClick = null)
        } else {
            ReadoutItem(
                "EXP.", ExposureMath.formatEv(if (pro) engine.evCompensation else 0f),
                auto = !pro || engine.isAuto(Param.EV), selected = sel == Param.EV,
                enabled = caps.evValues.size > 1,
            ) { engine.openParam(Param.EV) }
        },
        ReadoutItem(
            "APERT.", if (caps.aperture > 0f) "ƒ" + ((caps.aperture * 10).toInt() / 10f) else "ƒ—",
            auto = true, selected = false, onClick = null,
        ),
    )
}

private data class ParamCopy(val title: String, val hint: String)

private fun copyFor(p: Param) = when (p) {
    Param.ISO -> ParamCopy("ISO · SENSIBILIDAD", "Bajo = imagen limpia. Alto = más luz, pero más grano.")
    Param.SHUTTER -> ParamCopy("VELOCIDAD DE OBTURACIÓN", "Rápida congela el movimiento. Lenta deja entrar más luz.")
    Param.WB -> ParamCopy("BALANCE DE BLANCOS", "Ajusta el color de la luz para que lo blanco se vea blanco.")
    Param.FOCUS -> ParamCopy("ENFOQUE MANUAL", "Elige a qué distancia queda nítida la imagen.")
    Param.EV -> ParamCopy("COMPENSACIÓN DE EXPOSICIÓN", "Aclara u oscurece toda la foto sin tocar lo demás.")
}

/** Panel de vidrio con el dial del parámetro elegido, una explicación corta y el botón AUTO. */
@Composable
private fun ParamPanel(engine: CameraEngine, param: Param, haptics: Boolean) {
    val caps = engine.caps
    val copy = copyFor(param)
    val auto = engine.isAuto(param)
    Column(
        Modifier
            .padding(horizontal = 10.dp)
            .fillMaxWidth()
            .glass(RoundedCornerShape(20.dp))
            .padding(top = 12.dp, bottom = 8.dp),
    ) {
        Row(Modifier.padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(copy.title, style = Type.Label, color = if (auto) Palette.Cyan else Palette.Amber)
                Spacer(Modifier.height(3.dp))
                Text(copy.hint, style = Type.Body.copy(fontSize = Type.Label.fontSize * 1.2f, lineHeight = Type.Label.fontSize * 1.6f), color = Palette.Mist)
            }
            Spacer(Modifier.width(10.dp))
            InfoPill(if (auto) "AUTO" else "VOLVER A AUTO", if (auto) Palette.Cyan else Palette.Paper) {
                when (param) {
                    Param.ISO -> engine.setIso(null)
                    Param.SHUTTER -> engine.setShutter(null)
                    Param.WB -> engine.changeWhiteBalance(WhiteBalance.AUTO)
                    Param.FOCUS -> engine.setFocus(null)
                    Param.EV -> engine.setEv(0f)
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        when (param) {
            Param.ISO -> {
                val values = caps.isoValues
                if (values.isNotEmpty()) {
                    val current = engine.manualIso ?: engine.live.iso
                    val idx = ExposureMath.nearestIndex(values, current) { a, b -> ExposureMath.logDistance(a, b) }
                    ValueDial(ExposureMath.isoOptions(values), idx, auto, haptics, { engine.setIso(values[it]) })
                }
            }
            Param.SHUTTER -> {
                val values = caps.shutterValues
                if (values.isNotEmpty()) {
                    val current = engine.manualShutterNs ?: engine.live.shutterNs
                    val idx = ExposureMath.nearestIndex(values, current) { a, b -> ExposureMath.logDistance(a, b) }
                    ValueDial(ExposureMath.shutterOptions(values), idx, auto, haptics, { engine.setShutter(values[it]) })
                }
            }
            Param.FOCUS -> {
                val values = caps.focusValues
                val current = engine.manualFocus ?: engine.live.focusDiopters
                val idx = ExposureMath.nearestIndex(values, current) { a, b -> abs((a - b).toDouble()) }
                ValueDial(ExposureMath.focusOptions(values), idx, auto, haptics, { engine.setFocus(values[it]) })
            }
            Param.EV -> {
                val values = caps.evValues
                val idx = ExposureMath.nearestIndex(values, engine.evCompensation) { a, b -> abs((a - b).toDouble()) }
                ValueDial(ExposureMath.evOptions(values), idx, auto, haptics, { engine.setEv(values[it]) })
            }
            Param.WB -> {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    WhiteBalance.entries.forEach { wb ->
                        ChoiceChip(
                            wb.label, wb.kelvin, engine.whiteBalance == wb,
                            accent = if (wb == WhiteBalance.AUTO) Palette.Cyan else Palette.Amber,
                        ) { engine.changeWhiteBalance(wb) }
                    }
                }
                Spacer(Modifier.height(4.dp))
            }
        }
    }
}

