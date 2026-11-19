package com.iacamara.app.camera

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import android.util.Size
import android.view.Surface
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.MeteringPoint
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.log2
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

enum class CaptureMode { AUTO, PRO }
enum class Param { ISO, SHUTTER, WB, FOCUS, EV }
enum class FlashSetting { OFF, AUTO, ON }
enum class AspectSetting(val label: String) { RATIO_4_3("4:3"), RATIO_16_9("16:9") }

/**
 * Un "lente" tal como lo ve la persona: 0.6×, 1×, 3×.
 * Puede ser una cámara física distinta (cameraId) o la principal con zoom.
 *
 * @param baseFactor cuánto "acerca" esta cámara respecto a la principal con zoom 1.
 */
data class Lens(
    val cameraId: String,
    val zoom: Float,
    val baseFactor: Float,
    val equivalentMm: Int,
) {
    val factor: Float get() = baseFactor * zoom
    val label: String get() = formatFactor(factor)
}

fun formatFactor(f: Float): String {
    val r = (f * 10).roundToInt() / 10f
    return if (abs(r - r.roundToInt()) < 0.05f) "${r.roundToInt()}" else "$r"
}

/** Lo que el sensor está usando realmente en cada fotograma (para enseñar y para el modo PRO). */
data class LiveExposure(val iso: Int = 0, val shutterNs: Long = 0L, val focusDiopters: Float = 0f)

/** Rangos del sensor activo. */
data class SensorCaps(
    val isoValues: List<Int> = emptyList(),
    val shutterValues: List<Long> = emptyList(),
    val focusValues: List<Float> = emptyList(),
    val evValues: List<Float> = emptyList(),
    val evStep: Float = 1f / 3f,
    val manualSensor: Boolean = false,
    val manualFocus: Boolean = false,
    val hasFlash: Boolean = false,
    val aperture: Float = 0f,
)

@SuppressLint("UnsafeOptInUsageError")
@androidx.annotation.OptIn(markerClass = [ExperimentalCamera2Interop::class])
class CameraEngine(private val context: Context) {

    // ---------------- Estado observable por la interfaz ----------------
    var mode by mutableStateOf(CaptureMode.AUTO)
        private set
    var selectedParam by mutableStateOf<Param?>(null)
    var lenses by mutableStateOf<List<Lens>>(emptyList())
        private set
    var activeLens by mutableStateOf<Lens?>(null)
        private set
    var zoomRatio by mutableFloatStateOf(1f)
        private set
    var useFront by mutableStateOf(false)
        private set
    var caps by mutableStateOf(SensorCaps())
        private set
    var live by mutableStateOf(LiveExposure())
        private set
    var stats by mutableStateOf<FrameStats?>(null)
        private set
    var flash by mutableStateOf(FlashSetting.OFF)
    var aspect by mutableStateOf(AspectSetting.RATIO_4_3)
        private set
    var lastPhoto by mutableStateOf<Bitmap?>(null)
        private set
    var lastPhotoUri by mutableStateOf<Uri?>(null)
        private set
    var isCapturing by mutableStateOf(false)
        private set
    var captureCount by mutableIntStateOf(0)
        private set
    var aeAfLocked by mutableStateOf(false)
        private set
    var errorMessage by mutableStateOf<String?>(null)

    // Ajustes manuales (null = automático)
    var manualIso by mutableStateOf<Int?>(null)
        private set
    var manualShutterNs by mutableStateOf<Long?>(null)
        private set
    var whiteBalance by mutableStateOf(WhiteBalance.AUTO)
        private set
    var manualFocus by mutableStateOf<Float?>(null)
        private set
    var evCompensation by mutableFloatStateOf(0f)
        private set

    /** Lectura del fotómetro en pasos (exposición medida vs. ideal) cuando todo es manual. */
    var meterStops by mutableFloatStateOf(0f)
        private set

    // ---------------- Interno ----------------
    private val mainHandler = Handler(Looper.getMainLooper())
    private val analysisExecutor = Executors.newSingleThreadExecutor()
    private var provider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var imageCapture: ImageCapture? = null
    private var lifecycleOwner: LifecycleOwner? = null
    private var previewView: PreviewView? = null
    private var boundCameraId: String? = null
    private var mainEquivalentMm = 24f
    private var lastLiveUpdate = 0L
    private var targetRotation = Surface.ROTATION_0

    // Valores que el bucle de prioridad ajusta solo (cuando uno de ISO/obturación es manual).
    private var priorityIso = 100
    private var priorityShutterNs = 10_000_000L
    private var lastPriorityApply = 0L

    fun bind(owner: LifecycleOwner, view: PreviewView) {
        lifecycleOwner = owner
        previewView = view
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            try {
                provider = future.get()
                discoverLenses()
                rebind()
            } catch (e: Exception) {
                Log.e(TAG, "Cámara no disponible", e)
                errorMessage = "Cámara no disponible"
            }
        }, ContextCompat.getMainExecutor(context))
    }

    fun release() {
        provider?.unbindAll()
        camera = null
    }

    // ---------------- Lentes ----------------

    private fun equivalentMm(info: CameraInfo): Float? {
        val c2 = Camera2CameraInfo.from(info)
        val focal = c2.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull()
            ?: return null
        val size = c2.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE) ?: return null
        val diag = sqrt(size.width * size.width + size.height * size.height)
        if (diag <= 0f) return null
        return focal * 43.27f / diag
    }

    /**
     * Descubre los lentes traseros. En el S22+ Samsung expone gran angular, ultra gran angular
     * y teleobjetivo como cámaras separadas; si un teléfono no lo hace, usamos el zoom de la principal.
     */
    private fun discoverLenses() {
        val p = provider ?: return
        val facing = if (useFront) CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK
        val infos = p.availableCameraInfos.filter { it.lensFacing == facing }
        if (infos.isEmpty()) { lenses = emptyList(); return }

        val defaultSelector = if (useFront) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
        val mainInfo = runCatching { defaultSelector.filter(infos).first() }.getOrDefault(infos.first())
        val mainId = Camera2CameraInfo.from(mainInfo).cameraId
        mainEquivalentMm = equivalentMm(mainInfo) ?: 24f

        val result = mutableListOf<Lens>()
        result += Lens(mainId, 1f, 1f, mainEquivalentMm.roundToInt())

        if (!useFront) {
            // Cámaras físicas adicionales, sin duplicar distancias focales.
            infos.filter { Camera2CameraInfo.from(it).cameraId != mainId }
                .mapNotNull { info -> equivalentMm(info)?.let { info to it } }
                .sortedBy { Camera2CameraInfo.from(it.first).cameraId.toIntOrNull() ?: 99 }
                .forEach { (info, mm) ->
                    val factor = mm / mainEquivalentMm
                    if (result.none { abs(it.baseFactor - factor) < 0.25f }) {
                        result += Lens(Camera2CameraInfo.from(info).cameraId, 1f, factor, mm.roundToInt())
                    }
                }
            // Respaldo: lentes "virtuales" con el zoom de la cámara principal (cámara lógica).
            val zoom = mainInfo.zoomState.value
            val minZoom = zoom?.minZoomRatio ?: 1f
            val maxZoom = zoom?.maxZoomRatio ?: 1f
            if (result.none { it.factor < 0.9f } && minZoom < 0.9f) {
                result += Lens(mainId, minZoom, 1f, (mainEquivalentMm * minZoom).roundToInt())
            }
            if (result.none { it.factor > 1.9f } && maxZoom >= 3f) {
                result += Lens(mainId, 3f, 1f, (mainEquivalentMm * 3f).roundToInt())
            }
        }
        lenses = result.sortedBy { it.factor }
        activeLens = lenses.firstOrNull { it.cameraId == mainId && it.zoom == 1f } ?: lenses.first()
    }

    fun selectLens(lens: Lens) {
        if (lens == activeLens) return
        val needsRebind = lens.cameraId != boundCameraId
        activeLens = lens
        if (needsRebind) rebind() else camera?.cameraControl?.setZoomRatio(lens.zoom)
    }

    fun flipCamera() {
        useFront = !useFront
        resetManual()
        discoverLenses()
        rebind()
    }

    /** Pellizcar para hacer zoom: multiplica el zoom actual. */
    fun pinchZoom(scale: Float) {
        val cam = camera ?: return
        val z = cam.cameraInfo.zoomState.value ?: return
        val next = (z.zoomRatio * scale).coerceIn(z.minZoomRatio, z.maxZoomRatio)
        cam.cameraControl.setZoomRatio(next)
    }

    // ---------------- Enlace de casos de uso ----------------

    fun changeAspect(a: AspectSetting) {
        if (a == aspect) return
        aspect = a
        rebind()
    }

    fun setRotation(rotation: Int) {
        targetRotation = rotation
        imageCapture?.targetRotation = rotation
    }

    private fun rebind() {
        val p = provider ?: return
        val owner = lifecycleOwner ?: return
        val view = previewView ?: return
        val lens = activeLens ?: return

        val ratio = when (aspect) {
            AspectSetting.RATIO_4_3 -> AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY
            AspectSetting.RATIO_16_9 -> AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY
        }
        val selector = ResolutionSelector.Builder().setAspectRatioStrategy(ratio).build()

        val previewBuilder = Preview.Builder().setResolutionSelector(selector)
        Camera2Interop.Extender(previewBuilder).setSessionCaptureCallback(captureCallback)
        val preview = previewBuilder.build().also { it.surfaceProvider = view.surfaceProvider }

        val capture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setAspectRatioStrategy(ratio)
                    .setResolutionStrategy(ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY)
                    .build(),
            )
            .setTargetRotation(targetRotation)
            .setJpegQuality(95)
            .build()
        imageCapture = capture

        val analysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setAspectRatioStrategy(ratio)
                    .setResolutionStrategy(
                        ResolutionStrategy(Size(640, 480), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER),
                    )
                    .build(),
            )
            .build()
        analysis.setAnalyzer(analysisExecutor, FrameAnalyzer { s -> mainHandler.post { onFrameStats(s) } })

        val cameraSelector = CameraSelector.Builder()
            .addCameraFilter { infos -> infos.filter { Camera2CameraInfo.from(it).cameraId == lens.cameraId } }
            .build()

        try {
            p.unbindAll()
            val cam = p.bindToLifecycle(owner, cameraSelector, preview, capture, analysis)
            camera = cam
            boundCameraId = lens.cameraId
            aeAfLocked = false
            readCaps(cam)
            cam.cameraInfo.zoomState.observe(owner) { zoomRatio = it.zoomRatio }
            if (lens.zoom != 1f) cam.cameraControl.setZoomRatio(lens.zoom)
            applyControls()
        } catch (e: Exception) {
            Log.e(TAG, "No se pudo abrir la cámara", e)
            errorMessage = "No se pudo abrir este lente"
        }
    }

    private fun readCaps(cam: Camera) {
        val c2 = Camera2CameraInfo.from(cam.cameraInfo)
        val isoRange = c2.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
        val expRange = c2.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
        val minFocus = c2.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
        val capabilities = c2.getCameraCharacteristic(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: IntArray(0)
        val manualSensor = CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR in capabilities &&
            isoRange != null && expRange != null
        val exposure = cam.cameraInfo.exposureState
        val step = exposure.exposureCompensationStep.toFloat().takeIf { it > 0f } ?: (1f / 3f)
        val evRange = exposure.exposureCompensationRange
        val aperture = c2.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES)?.firstOrNull() ?: 0f

        caps = SensorCaps(
            isoValues = isoRange?.let { ExposureMath.isoValues(it.lower, it.upper) } ?: emptyList(),
            shutterValues = expRange?.let { ExposureMath.shutterValues(it.lower, it.upper) } ?: emptyList(),
            focusValues = ExposureMath.focusValues(minFocus),
            evValues = if (exposure.isExposureCompensationSupported) {
                ExposureMath.evValues(evRange.lower, evRange.upper, step)
            } else listOf(0f),
            evStep = step,
            manualSensor = manualSensor,
            manualFocus = minFocus > 0f,
            hasFlash = cam.cameraInfo.hasFlashUnit(),
            aperture = aperture,
        )
    }

    private val captureCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
            val now = System.currentTimeMillis()
            if (now - lastLiveUpdate < 150) return
            lastLiveUpdate = now
            val iso = result.get(CaptureResult.SENSOR_SENSITIVITY) ?: return
            val shutter = result.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: return
            val focus = result.get(CaptureResult.LENS_FOCUS_DISTANCE) ?: 0f
            mainHandler.post { live = LiveExposure(iso, shutter, focus) }
        }
    }

    // ---------------- Modo y controles manuales ----------------

    fun switchMode(m: CaptureMode) {
        if (m == mode) return
        mode = m
        if (m == CaptureMode.AUTO) selectedParam = null
        applyControls()
    }

    /** Toca una lectura: en AUTO pasa a PRO con ese parámetro abierto. */
    fun openParam(p: Param) {
        if (mode == CaptureMode.AUTO) mode = CaptureMode.PRO
        selectedParam = if (selectedParam == p) null else p
        applyControls()
    }

    fun setIso(v: Int?) {
        if (v != null && manualShutterNs == null) priorityShutterNs = live.shutterNs.takeIf { it > 0 } ?: priorityShutterNs
        manualIso = v
        applyControls()
    }

    fun setShutter(v: Long?) {
        if (v != null && manualIso == null) priorityIso = live.iso.takeIf { it > 0 } ?: priorityIso
        manualShutterNs = v
        applyControls()
    }

    fun changeWhiteBalance(wb: WhiteBalance) {
        whiteBalance = wb
        applyControls()
    }

    fun setFocus(d: Float?) {
        manualFocus = d
        applyControls()
    }

    fun setEv(ev: Float) {
        evCompensation = ev
        applyControls()
    }

    fun resetManual() {
        manualIso = null
        manualShutterNs = null
        manualFocus = null
        whiteBalance = WhiteBalance.AUTO
        evCompensation = 0f
        applyControls()
    }

    fun isAuto(p: Param): Boolean = when (p) {
        Param.ISO -> manualIso == null
        Param.SHUTTER -> manualShutterNs == null
        Param.WB -> whiteBalance == WhiteBalance.AUTO
        Param.FOCUS -> manualFocus == null
        Param.EV -> abs(evCompensation) < 0.01f
    }

    private val sensorManual: Boolean
        get() = mode == CaptureMode.PRO && caps.manualSensor && (manualIso != null || manualShutterNs != null)

    private fun applyControls() {
        val cam = camera ?: return
        val c2 = Camera2CameraControl.from(cam.cameraControl)
        val evIndex = (evCompensation / caps.evStep).roundToInt()

        if (mode == CaptureMode.AUTO) {
            c2.clearCaptureRequestOptions()
            cam.cameraControl.setExposureCompensationIndex(0)
            imageCapture?.flashMode = flash.toCameraX()
            return
        }

        val b = CaptureRequestOptions.Builder()
        if (sensorManual) {
            val iso = manualIso ?: priorityIso
            val shutter = manualShutterNs ?: priorityShutterNs
            b.setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
            b.setCaptureRequestOption(CaptureRequest.SENSOR_SENSITIVITY, iso)
            b.setCaptureRequestOption(CaptureRequest.SENSOR_EXPOSURE_TIME, shutter)
            b.setCaptureRequestOption(CaptureRequest.SENSOR_FRAME_DURATION, maxOf(shutter, 33_333_333L))
            imageCapture?.flashMode = ImageCapture.FLASH_MODE_OFF
        } else {
            cam.cameraControl.setExposureCompensationIndex(evIndex)
            imageCapture?.flashMode = flash.toCameraX()
        }
        b.setCaptureRequestOption(CaptureRequest.CONTROL_AWB_MODE, whiteBalance.awbMode)
        manualFocus?.takeIf { caps.manualFocus }?.let {
            b.setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
            b.setCaptureRequestOption(CaptureRequest.LENS_FOCUS_DISTANCE, it)
        }
        c2.setCaptureRequestOptions(b.build())
    }

    /**
     * Fotómetro propio. Con un solo valor manual (prioridad ISO o prioridad obturación),
     * ajusta el otro para llegar a la exposición correcta, como haría una cámara réflex.
     */
    private fun onFrameStats(s: FrameStats) {
        stats = s
        // Gris medio en espacio gamma ≈ 0.46; cada paso equivale a ×2^(1/2.2) en luminancia codificada.
        val target = 0.46f * 2f.pow(evCompensation / 2.2f)
        val error = 2.2f * log2(target / s.meanLuma.coerceIn(0.01f, 0.99f))
        meterStops = -error

        if (!sensorManual) return
        val isoFree = manualIso == null
        val shutterFree = manualShutterNs == null
        if (isoFree == shutterFree) return // ambos manuales: solo se muestra el fotómetro
        val now = System.currentTimeMillis()
        if (now - lastPriorityApply < 200 || abs(error) < 0.15f) return
        lastPriorityApply = now
        val adjust = 2f.pow((error * 0.6f).coerceIn(-1.5f, 1.5f))
        if (isoFree) {
            val vals = caps.isoValues
            if (vals.isEmpty()) return
            priorityIso = (priorityIso * adjust).roundToInt().coerceIn(vals.first(), vals.last())
        } else {
            val vals = caps.shutterValues
            if (vals.isEmpty()) return
            // A pulso no conviene bajar de 1/15 s aunque falte luz.
            val maxHandheld = minOf(vals.last(), 66_666_666L)
            priorityShutterNs = (priorityShutterNs * adjust).toLong().coerceIn(vals.first(), maxHandheld)
        }
        applyControls()
    }

    // ---------------- Enfoque ----------------

    fun tapToFocus(point: MeteringPoint, lock: Boolean) {
        val cam = camera ?: return
        val flags = if (sensorManual) FocusMeteringAction.FLAG_AF else FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE
        val builder = FocusMeteringAction.Builder(point, flags)
        if (lock) builder.disableAutoCancel() else builder.setAutoCancelDuration(4, TimeUnit.SECONDS)
        if (manualFocus != null) setFocus(null)
        cam.cameraControl.startFocusAndMetering(builder.build())
        aeAfLocked = lock
    }

    fun unlockFocus() {
        camera?.cameraControl?.cancelFocusAndMetering()
        aeAfLocked = false
    }

    // ---------------- Captura ----------------

    fun capture(onDone: (Boolean) -> Unit = {}) {
        val ic = imageCapture ?: return
        if (isCapturing) return
        isCapturing = true
        val name = "IACAM_" + SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(System.currentTimeMillis())
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/IA Camara")
        }
        val output = ImageCapture.OutputFileOptions.Builder(
            context.contentResolver, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values,
        ).build()
        ic.takePicture(output, ContextCompat.getMainExecutor(context), object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(result: ImageCapture.OutputFileResults) {
                isCapturing = false
                captureCount++
                result.savedUri?.let { uri ->
                    lastPhotoUri = uri
                    analysisExecutor.execute {
                        val thumb = runCatching { context.contentResolver.loadThumbnail(uri, Size(240, 240), null) }.getOrNull()
                        mainHandler.post { if (thumb != null) lastPhoto = thumb }
                    }
                }
                onDone(true)
            }

            override fun onError(e: ImageCaptureException) {
                isCapturing = false
                Log.e(TAG, "Error al capturar", e)
                errorMessage = "No se pudo guardar la foto"
                onDone(false)
            }
        })
    }

    private fun FlashSetting.toCameraX() = when (this) {
        FlashSetting.OFF -> ImageCapture.FLASH_MODE_OFF
        FlashSetting.AUTO -> ImageCapture.FLASH_MODE_AUTO
        FlashSetting.ON -> ImageCapture.FLASH_MODE_ON
    }

    fun cycleFlash() {
        flash = FlashSetting.entries[(flash.ordinal + 1) % FlashSetting.entries.size]
        applyControls()
    }

    /** Estado de ejemplo para capturas de pantalla de la interfaz (sin cámara real). */
    @androidx.annotation.VisibleForTesting
    fun loadDemoState(pro: Boolean) {
        lenses = listOf(Lens("2", 1f, 0.55f, 13), Lens("0", 1f, 1f, 24), Lens("3", 1f, 2.9f, 69))
        activeLens = lenses[1]
        caps = SensorCaps(
            isoValues = ExposureMath.isoValues(50, 3200),
            shutterValues = ExposureMath.shutterValues(100_000L, 500_000_000L),
            focusValues = ExposureMath.focusValues(10f),
            evValues = ExposureMath.evValues(-6, 6, 1f / 3f),
            manualSensor = true, manualFocus = true, hasFlash = true, aperture = 1.8f,
        )
        live = LiveExposure(125, 4_000_000L, 0.4f)
        val hist = FloatArray(64) { i -> val x = (i - 28) / 12f; (kotlin.math.exp(-x * x) * 0.9f + 0.08f).toFloat() }
        stats = FrameStats(hist, 0.45f, 0.01f, 0.0f)
        if (pro) {
            mode = CaptureMode.PRO
            manualShutterNs = 4_000_000L
            whiteBalance = WhiteBalance.DAYLIGHT
            selectedParam = Param.SHUTTER
        }
    }

    companion object {
        private const val TAG = "IACamara"
    }
}
