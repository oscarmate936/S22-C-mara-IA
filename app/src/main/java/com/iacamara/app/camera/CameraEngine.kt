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
import com.iacamara.app.ai.BrainInput
import com.iacamara.app.ai.PhotoBrain
import com.iacamara.app.ai.Reason
import com.iacamara.app.ai.Scene
import com.iacamara.app.processing.BurstKind
import com.iacamara.app.processing.BurstProcessor
import com.iacamara.app.ai.SceneBrain
import com.iacamara.app.ai.SceneDetector
import com.iacamara.app.ai.SceneResult
import com.iacamara.app.ai.Subject
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

/** Cómo se toma la foto. AUTO = lo decide la IA. */
enum class ShotSetting(val label: String) { AUTO("Auto"), SINGLE("1 toma"), NIGHT("Noche"), HDR("HDR"), MACRO("Macro+") }
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
    /** true si es zoom digital (recorte) y no un lente físico. */
    val digital: Boolean = false,
) {
    val factor: Float get() = baseFactor * zoom
    val label: String get() = if (factor >= 1.5f) "${factor.roundToInt()}" else formatFactor(factor)
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
    /** Pantalla en blanco como flash para la cámara frontal. */
    var screenFlash by mutableStateOf(false)
        private set
    /** Usar la luz del flash en modo linterna (compatibilidad). */
    var torchFlash: Boolean = false

    // ---------------- Calidad de imagen (fase 4) ----------------
    var shotSetting by mutableStateOf(ShotSetting.AUTO)
        private set
    /** La IA puede elegir tomas múltiples (noche / macro) sola. */
    var multiShotAuto: Boolean = true
    /** Progreso de una toma múltiple ("Tomando 3/8"…), null si no hay. */
    var burstStatus by mutableStateOf<String?>(null)
        private set
    /** La persona pidió RAW (DNG). */
    var rawRequested: Boolean = false
        set(v) { if (field != v) { field = v; rebind() } }
    /** RAW realmente activo en el lente actual. */
    var rawActive by mutableStateOf(false)
        private set

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

    // ---------------- IA (fase 2) ----------------
    /** Escena reconocida por la IA (null si la IA está apagada). */
    var scene by mutableStateOf<SceneResult?>(null)
        private set
    /** Sujeto principal en el visor (rostro u objeto), coordenadas 0..1. */
    var subject by mutableStateOf<Subject?>(null)
        private set
    /** Escena cuya receta profesional está aplicada ahora. */
    var appliedScene by mutableStateOf<Scene?>(null)
        private set
    var aiEnabled: Boolean = true
        set(v) {
            field = v
            if (!v) { scene = null; subject = null; clearBrain(); applyControls() }
        }
    /** Enfocar solo al sujeto que detecta la IA. */
    var subjectFocus: Boolean = true

    // ---------------- Cerebro fotógrafo (fase 3) ----------------
    /** El cerebro controla el modo automático. */
    var autoBrain: Boolean = true
        set(v) { field = v; if (!v) clearBrain(); applyControls() }
    /** El cerebro puede cambiar de lente solo. */
    var autoLens: Boolean = true
    var brainEv by mutableFloatStateOf(0f)
        private set
    var brainWb by mutableStateOf(WhiteBalance.AUTO)
        private set
    var brainShutterNs by mutableStateOf<Long?>(null)
        private set
    /** Decisiones explicadas que se muestran en pantalla. */
    var brainReasons by mutableStateOf<List<Reason>>(emptyList())
        private set
    /** Aviso de cambio automático de lente (con opción de deshacer). */
    var lensNotice by mutableStateOf<String?>(null)
    val brainActive: Boolean get() = mode == CaptureMode.AUTO && aiEnabled && autoBrain

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

    private val detector by lazy { SceneDetector(analysisExecutor) }
    private val brain = SceneBrain()
    private var lastAi = 0L
    private var lastUserTap = 0L
    private var lastAutoFocus = 0L
    private var lastFocusX = -1f
    private var lastFocusY = -1f

    private val photoBrain = PhotoBrain()
    private val processingExecutor = Executors.newSingleThreadExecutor()
    /** Enfoque temporal durante un apilado de enfoque. */
    private var burstFocus: Float? = null
    /** Exposición y blancos bloqueados durante una ráfaga (todas las tomas iguales). */
    private var burstLock = false
    /** Un cambio de lente/formato pedido durante una captura se aplica al terminar. */
    private var pendingRebind = false
    /** Exposición manual temporal (ISO, velocidad) para la toma oscura del HDR. */
    private var burstExposure: Pair<Int, Long>? = null
    private var frameAnalyzer: FrameAnalyzer? = null
    private var analysisUseCase: ImageAnalysis? = null
    /** Rotación que endereza los fotogramas en el marco vertical de la pantalla. */
    @Volatile private var portraitRotation = 90
    private var lastTrackId: Int? = null
    private var imageMotion = 0f
    /** La IA ve nieve en la escena. */
    private var snowy = false
    /** El cerebro recomienda HDR ahora. */
    private var brainWantsHdr = false
    private var lastBrainRun = 0L
    private var gyro = 0f
    private var steady = false
    private var subjectSpeed = 0f
    private var lastSubjectX = -1f
    private var lastSubjectY = -1f
    private var lastSubjectTime = 0L
    private var lastLensDecision: Float? = null
    private var stableScene: Scene? = null
    private var stableSince = 0L
    private var autoLensScene: Scene? = null
    private var lastUserLens = 0L
    private var lensBeforeAuto: Lens? = null

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

    /** Rango de zoom de una cámara, leído del sensor (disponible antes de abrirla). */
    private fun zoomRange(info: CameraInfo): Pair<Float, Float> {
        info.zoomState.value?.let { return it.minZoomRatio to it.maxZoomRatio }
        val c2 = Camera2CameraInfo.from(info)
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            c2.getCameraCharacteristic(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)?.let { return it.lower to it.upper }
        }
        val digital = c2.getCameraCharacteristic(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1f
        return 1f to digital
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
            val (minZoom, maxZoom) = zoomRange(mainInfo)
            if (result.none { it.factor < 0.9f } && minZoom < 0.9f) {
                result += Lens(mainId, minZoom, 1f, (mainEquivalentMm * minZoom).roundToInt())
            }
            if (result.none { it.factor > 1.9f } && maxZoom >= 3f) {
                result += Lens(mainId, 3f, 1f, (mainEquivalentMm * 3f).roundToInt())
            }
            // Paradas de zoom como en la cámara de Samsung:
            // 2× = recorte central del sensor de 50 MP (mantiene muy buena calidad).
            if (result.none { abs(it.factor - 2f) < 0.4f } && maxZoom >= 2f) {
                result += Lens(mainId, 2f, 1f, (mainEquivalentMm * 2f).roundToInt(), digital = true)
            }
            // 10× = teleobjetivo + recorte (o la principal si no hay tele).
            val tele = result.filter { it.zoom == 1f && it.baseFactor > 1.5f }.maxByOrNull { it.baseFactor }
            val teleMax = tele?.let { t ->
                infos.firstOrNull { Camera2CameraInfo.from(it).cameraId == t.cameraId }?.let { zoomRange(it).second }
            } ?: 0f
            if (tele != null && tele.baseFactor * teleMax >= 10f) {
                val z = 10f / tele.baseFactor
                result += Lens(tele.cameraId, z, tele.baseFactor, (tele.equivalentMm * z).roundToInt(), digital = true)
            } else if (maxZoom >= 10f) {
                result += Lens(mainId, 10f, 1f, (mainEquivalentMm * 10f).roundToInt(), digital = true)
            }
        }
        lenses = result.sortedBy { it.factor }
        activeLens = lenses.firstOrNull { it.cameraId == mainId && it.zoom == 1f } ?: lenses.first()
    }

    fun selectLens(lens: Lens, byUser: Boolean = true) {
        if (byUser) { lastUserLens = System.currentTimeMillis(); lensNotice = null }
        if (lens == activeLens) {
            camera?.cameraControl?.setZoomRatio(lens.zoom)
            return
        }
        val needsRebind = lens.cameraId != boundCameraId
        activeLens = lens
        lastSubjectTime = 0L // el encuadre cambia: no confundirlo con movimiento del sujeto
        if (needsRebind) rebind() else camera?.cameraControl?.setZoomRatio(lens.zoom)
    }

    fun flipCamera() {
        useFront = !useFront
        clearBrain()
        resetManual()
        discoverLenses()
        rebind()
    }

    /** Pellizcar para hacer zoom: multiplica el zoom actual. */
    fun pinchZoom(scale: Float) {
        val cam = camera ?: return
        lastUserLens = System.currentTimeMillis()
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
        // La IA analiza la imagen como la sostienes (reconoce caras con el teléfono en horizontal).
        analysisUseCase?.targetRotation = rotation
    }

    private fun rebind() {
        if (isCapturing) { pendingRebind = true; return }
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

        val targetInfo = p.availableCameraInfos.firstOrNull { Camera2CameraInfo.from(it).cameraId == lens.cameraId }
        val rawSupported = rawRequested && targetInfo != null && runCatching {
            ImageCapture.OUTPUT_FORMAT_RAW_JPEG in ImageCapture.getImageCaptureCapabilities(targetInfo).supportedOutputFormats
        }.getOrDefault(false)
        val captureBuilder = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setAspectRatioStrategy(ratio)
                    .setResolutionStrategy(ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY)
                    .build(),
            )
            .setTargetRotation(targetRotation)
            .setJpegQuality(95)
        if (rawSupported) captureBuilder.setOutputFormat(ImageCapture.OUTPUT_FORMAT_RAW_JPEG)
        val capture = captureBuilder.build()
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
        val fa = FrameAnalyzer(
            onStats = { s -> mainHandler.post { onFrameStats(s) } },
            handOff = { img -> handToAi(img) },
        )
        frameAnalyzer = fa
        analysisUseCase = analysis
        analysis.targetRotation = targetRotation
        analysis.setAnalyzer(analysisExecutor, fa)

        val cameraSelector = CameraSelector.Builder()
            .addCameraFilter { infos -> infos.filter { Camera2CameraInfo.from(it).cameraId == lens.cameraId } }
            .build()

        try {
            camera?.cameraInfo?.zoomState?.removeObservers(owner)
            p.unbindAll()
            val cam = try {
                p.bindToLifecycle(owner, cameraSelector, preview, capture, analysis).also { rawActive = rawSupported }
            } catch (e: Exception) {
                if (!rawSupported) throw e
                // Este lente no admite RAW junto a la vista previa: seguimos solo en JPEG.
                Log.w(TAG, "RAW no disponible con esta combinación", e)
                p.unbindAll()
                val jpegOnly = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                    .setResolutionSelector(
                        ResolutionSelector.Builder().setAspectRatioStrategy(ratio)
                            .setResolutionStrategy(ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY).build(),
                    )
                    .setTargetRotation(targetRotation).setJpegQuality(95).build()
                imageCapture = jpegOnly
                rawActive = false
                errorMessage = "RAW no disponible en este lente"
                p.bindToLifecycle(owner, cameraSelector, preview, jpegOnly, analysis)
            }
            camera = cam
            boundCameraId = lens.cameraId
            portraitRotation = cam.cameraInfo.getSensorRotationDegrees(Surface.ROTATION_0)
            fa.portraitRotation = portraitRotation
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
        if (m == CaptureMode.AUTO) { selectedParam = null; appliedScene = null }
        clearBrain()
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
        appliedScene = null
        applyControls()
    }

    fun isAuto(p: Param): Boolean = when (p) {
        Param.ISO -> manualIso == null
        Param.SHUTTER -> manualShutterNs == null
        Param.WB -> whiteBalance == WhiteBalance.AUTO
        Param.FOCUS -> manualFocus == null
        Param.EV -> abs(evCompensation) < 0.01f
    }

    /** ISO fijado (solo por la persona en PRO). */
    private val fixedIso: Int? get() = if (mode == CaptureMode.PRO) manualIso else null
    /** Velocidad fijada: por la persona en PRO o por el cerebro en AUTO. */
    private val fixedShutter: Long?
        get() = when {
            mode == CaptureMode.PRO -> manualShutterNs
            brainActive -> brainShutterNs
            else -> null
        }
    private val sensorManual: Boolean
        get() = caps.manualSensor && (fixedIso != null || fixedShutter != null)
    /** Compensación que usa el fotómetro propio. */
    private val meterEv: Float get() = if (mode == CaptureMode.PRO) evCompensation else if (brainActive) brainEv else 0f

    private fun clearBrain() {
        brainEv = 0f
        brainWb = WhiteBalance.AUTO
        brainShutterNs = null
        brainWantsHdr = false
        brainReasons = emptyList()
        photoBrain.reset()
    }

    private fun applyControls() {
        val cam = camera ?: return
        val c2 = Camera2CameraControl.from(cam.cameraControl)
        burstExposure?.let { (iso, shutter) ->
            c2.setCaptureRequestOptions(
                CaptureRequestOptions.Builder()
                    .setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
                    .setCaptureRequestOption(CaptureRequest.SENSOR_SENSITIVITY, iso)
                    .setCaptureRequestOption(CaptureRequest.SENSOR_EXPOSURE_TIME, shutter)
                    .setCaptureRequestOption(CaptureRequest.SENSOR_FRAME_DURATION, maxOf(shutter, 33_333_333L))
                    .setCaptureRequestOption(CaptureRequest.CONTROL_AWB_LOCK, true)
                    .build(),
            )
            return
        }
        val evIndex = (evCompensation / caps.evStep).roundToInt()

        if (mode == CaptureMode.AUTO) {
            val bf = burstFocus
            if (!brainActive && (bf != null || burstLock)) {
                cam.cameraControl.setExposureCompensationIndex(0)
                val o = CaptureRequestOptions.Builder()
                bf?.let {
                    o.setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
                    o.setCaptureRequestOption(CaptureRequest.LENS_FOCUS_DISTANCE, it)
                }
                o.addBurstLock()
                c2.setCaptureRequestOptions(o.build())
                return
            }
            if (!brainActive) {
                c2.clearCaptureRequestOptions()
                cam.cameraControl.setExposureCompensationIndex(0)
                return
            }
            // El cerebro fotógrafo al mando.
            val b = CaptureRequestOptions.Builder()
            val shutter = brainShutterNs
            if (shutter != null && sensorManual) {
                b.setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
                b.setCaptureRequestOption(CaptureRequest.SENSOR_SENSITIVITY, priorityIso)
                b.setCaptureRequestOption(CaptureRequest.SENSOR_EXPOSURE_TIME, shutter)
                b.setCaptureRequestOption(CaptureRequest.SENSOR_FRAME_DURATION, maxOf(shutter, 33_333_333L))
            } else {
                val lo = ((caps.evValues.firstOrNull() ?: 0f) / caps.evStep).roundToInt()
                val hi = ((caps.evValues.lastOrNull() ?: 0f) / caps.evStep).roundToInt()
                cam.cameraControl.setExposureCompensationIndex((brainEv / caps.evStep).roundToInt().coerceIn(lo, hi))
            }
            b.setCaptureRequestOption(CaptureRequest.CONTROL_AWB_MODE, brainWb.awbMode)
            bf?.let {
                b.setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
                b.setCaptureRequestOption(CaptureRequest.LENS_FOCUS_DISTANCE, it)
            }
            b.addBurstLock()
            c2.setCaptureRequestOptions(b.build())
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
        } else {
            cam.cameraControl.setExposureCompensationIndex(evIndex)
        }
        b.setCaptureRequestOption(CaptureRequest.CONTROL_AWB_MODE, whiteBalance.awbMode)
        (burstFocus ?: manualFocus)?.takeIf { caps.manualFocus }?.let {
            b.setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
            b.setCaptureRequestOption(CaptureRequest.LENS_FOCUS_DISTANCE, it)
        }
        b.addBurstLock()
        c2.setCaptureRequestOptions(b.build())
    }

    private fun CaptureRequestOptions.Builder.addBurstLock() {
        if (!burstLock) return
        setCaptureRequestOption(CaptureRequest.CONTROL_AE_LOCK, true)
        setCaptureRequestOption(CaptureRequest.CONTROL_AWB_LOCK, true)
    }

    /**
     * Fotómetro propio. Con un solo valor manual (prioridad ISO o prioridad obturación),
     * ajusta el otro para llegar a la exposición correcta, como haría una cámara réflex.
     */
    private fun onFrameStats(s: FrameStats) {
        stats = s
        // Flujo óptico (8 veces por segundo): movimiento real del sujeto y de la imagen.
        imageMotion += (s.globalMotion - imageMotion) * 0.5f
        if (subject != null && s.subjectMotion != null) subjectSpeed += (s.subjectMotion - subjectSpeed) * 0.5f
        // Gris medio en espacio gamma ≈ 0.46; cada paso equivale a ×2^(1/2.2) en luminancia codificada.
        val target = 0.46f * 2f.pow(meterEv / 2.2f)
        val error = 2.2f * log2(target / s.meanLuma.coerceIn(0.01f, 0.99f))
        meterStops = -error

        if (brainActive) runBrain()
        if (!sensorManual) return
        val isoFree = fixedIso == null
        val shutterFree = fixedShutter == null
        if (isoFree == shutterFree) return // ambos manuales: solo se muestra el fotómetro
        val now = System.currentTimeMillis()
        // Esperar al menos ~2 fotogramas para ver el efecto del ajuste anterior (evita oscilar).
        val frameMs = ((fixedShutter ?: 0L) / 1_000_000L).coerceAtLeast(33L)
        if (now - lastPriorityApply < maxOf(200L, frameMs * 3) || abs(error) < 0.15f) return
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

    // ---------------- Cerebro fotógrafo ----------------

    /** Datos de movimiento del teléfono (giroscopio). */
    fun updateMotion(shake: Float, isSteady: Boolean) {
        gyro = shake
        steady = isSteady
    }

    private fun currentEquivalentMm(): Float {
        val l = activeLens ?: return mainEquivalentMm
        return l.equivalentMm * zoomRatio / l.zoom
    }

    /** Dos veces por segundo: el cerebro decide y la cámara se ajusta suavemente. */
    private fun runBrain() {
        val now = System.currentTimeMillis()
        if (now - lastBrainRun < 500) return
        lastBrainRun = now
        val st = stats ?: return
        val sc = scene?.scene ?: Scene.GENERAL
        val engagedBefore = brainShutterNs
        val d = photoBrain.decide(
            BrainInput(
                scene = sc, subject = subject, subjectSpeed = subjectSpeed, gyro = gyro, steady = steady,
                equivalentMm = currentEquivalentMm(), meanLuma = st.meanLuma, clippedHighlights = st.clippedHighlights,
                liveIso = live.iso, aeShutterNs = live.shutterNs,
                isoMin = caps.isoValues.firstOrNull() ?: 50, isoMax = caps.isoValues.lastOrNull() ?: 3200,
                priorityIso = priorityIso, shutterValues = caps.shutterValues,
                clippedArea = st.clippedArea, shadowArea = st.shadowArea,
                subjectLuma = if (subject != null) st.subjectLuma else null,
                snowy = snowy, p98 = st.p98, imageMotion = imageMotion,
            ),
            engagedBefore,
        )
        brainWantsHdr = d.hdr && caps.manualSensor
        val flashReady = flash != FlashSetting.OFF && caps.hasFlash && !useFront
        val shutter = d.shutterNs.takeIf { caps.manualSensor && !flashReady }
        if (shutter != null && engagedBefore == null && live.shutterNs > 0 && live.iso > 0) {
            // Mantener el mismo brillo al pasar del automático al control del cerebro.
            val iso = (live.iso.toDouble() * live.shutterNs / shutter).toInt()
            priorityIso = iso.coerceIn(caps.isoValues.firstOrNull() ?: 50, caps.isoValues.lastOrNull() ?: 3200)
        }
        val changed = abs(d.ev - brainEv) > 0.05f || d.wb != brainWb || shutter != brainShutterNs
        brainEv = d.ev
        brainWb = d.wb
        brainShutterNs = shutter
        brainReasons = if (flashReady && d.shutterNs != null) {
            listOf(Reason("Flash", "la cámara mide la luz junto con el flash")) + d.reasons.filterNot { it.value == ExposureMath.formatShutter(d.shutterNs) }
        } else d.reasons
        lastLensDecision = d.lensFactor
        if (changed) applyControls()
    }

    /** Cambia de lente solo cuando la escena es estable y la persona no eligió uno hace poco. */
    private fun maybeAutoLens(sc: Scene) {
        val now = System.currentTimeMillis()
        if (sc != stableScene) { stableScene = sc; stableSince = now; return }
        if (!brainActive || !autoLens || useFront || isCapturing) return
        if (now - stableSince < 2000 || now - lastUserLens < 15000 || autoLensScene == sc) return
        val factor = lastLensDecision ?: return
        val current = activeLens ?: return
        val target = lenses.filter { it.factor <= 3.5f }
            .minByOrNull { abs(kotlin.math.ln(it.factor / factor)) } ?: return
        autoLensScene = sc
        if (abs(target.factor - current.baseFactor * zoomRatio) < 0.3f) return
        lensBeforeAuto = current
        selectLens(target, byUser = false)
        lensNotice = "LENTE ${target.label}× · ${sc.title.substringBefore(" ·").uppercase()}"
    }

    fun undoAutoLens() {
        lensBeforeAuto?.let { selectLens(it, byUser = true) }
        lensNotice = null
    }

    // ---------------- IA de escena y sujeto ----------------

    /** Corre en el hilo de análisis: decide si este fotograma va a la IA (~3 por segundo). */
    private fun handToAi(img: androidx.camera.core.ImageProxy): Boolean {
        if (!aiEnabled || detector.busy) return false
        val now = System.currentTimeMillis()
        if (now - lastAi < 330) return false
        lastAi = now
        val mirror = useFront
        detector.process(img, mirror, portraitRotation) { d -> mainHandler.post { onDetection(d) } }
        return true
    }

    private fun onDetection(d: com.iacamara.app.ai.Detection) {
        if (!aiEnabled) return
        val (result, subj) = brain.update(d, stats, live, useFront)
        scene = result
        // Velocidad del sujeto (anchos de visor por segundo), suavizada.
        val now = System.currentTimeMillis()
        // Otro sujeto distinto (cambió el identificador de seguimiento): no es "movimiento".
        if (subj?.trackId != null && subj.trackId != lastTrackId) lastSubjectTime = 0L
        lastTrackId = subj?.trackId
        val opticalFlow = stats?.subjectMotion != null
        if (!opticalFlow && subj != null && lastSubjectTime > 0 && lastSubjectX >= 0f) {
            val dt = (now - lastSubjectTime) / 1000f
            if (dt in 0.05f..1.5f) {
                val v = kotlin.math.hypot(subj.box.centerX() - lastSubjectX, subj.box.centerY() - lastSubjectY) / dt
                subjectSpeed += (v - subjectSpeed) * 0.4f
            }
        } else if (subj == null) subjectSpeed *= 0.5f
        lastSubjectX = subj?.box?.centerX() ?: -1f
        lastSubjectY = subj?.box?.centerY() ?: -1f
        lastSubjectTime = now
        subject = subj
        frameAnalyzer?.let { fa ->
            fa.regionMirrored = useFront
            // Se mide la luz en la piel (mejillas/nariz) o el centro del objeto, no en el recuadro entero.
            fa.region = subj?.meterBox?.let { NormRect(it.left, it.top, it.right, it.bottom) }
        }
        snowy = d.labels.any { (label, conf) -> conf >= 0.6f && (label == "Snow" || label == "Skiing" || label == "Glacier") }
        maybeAutoLens(result.scene)
        if (appliedScene != null && appliedScene != result.scene && result.scene != Scene.GENERAL) appliedScene = null
        subj?.let { focusOnSubject(it) }
    }

    /** Como un fotógrafo: el foco sigue al sujeto (rostro primero) sin que toques la pantalla. */
    private fun focusOnSubject(s: Subject) {
        val cam = camera ?: return
        val view = previewView ?: return
        if (!subjectFocus || aeAfLocked || manualFocus != null || isCapturing) return
        val now = System.currentTimeMillis()
        if (now - lastUserTap < 5000 || now - lastAutoFocus < 2500) return
        val moved = abs(s.anchorX - lastFocusX) + abs(s.anchorY - lastFocusY)
        if (moved < 0.08f && now - lastAutoFocus < 8000) return
        if (view.width == 0 || view.height == 0) return
        lastAutoFocus = now
        lastFocusX = s.anchorX
        lastFocusY = s.anchorY
        val point = view.meteringPointFactory.createPoint(s.box.centerX() * view.width, s.anchorY * view.height, 0.12f)
        val flags = if (sensorManual) FocusMeteringAction.FLAG_AF else FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE
        cam.cameraControl.startFocusAndMetering(
            FocusMeteringAction.Builder(point, flags).setAutoCancelDuration(3, TimeUnit.SECONDS).build(),
        )
    }

    /** Aplica la receta profesional de la escena: lente, exposición, blancos y velocidad. */
    fun applyRecipe(s: Scene) {
        val r = s.recipe ?: return
        mode = CaptureMode.PRO
        selectedParam = null
        manualIso = null
        manualFocus = null
        whiteBalance = r.wb
        evCompensation = caps.evValues.minByOrNull { abs(it - r.ev) } ?: 0f
        manualShutterNs = if (r.shutterNs != null && caps.manualSensor) {
            priorityIso = live.iso.takeIf { it > 0 } ?: priorityIso
            caps.shutterValues.minByOrNull { ExposureMath.logDistance(it, r.shutterNs) }
        } else null
        appliedScene = s
        val lens = r.lensFactor?.let { f -> lenses.minByOrNull { abs(kotlin.math.ln(it.factor / f)) } }
        if (lens != null && lens != activeLens) selectLens(lens)
        applyControls()
    }

    // ---------------- Enfoque ----------------

    fun tapToFocus(point: MeteringPoint, lock: Boolean) {
        val cam = camera ?: return
        val flags = if (sensorManual) FocusMeteringAction.FLAG_AF else FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE
        val builder = FocusMeteringAction.Builder(point, flags)
        if (lock) builder.disableAutoCancel() else builder.setAutoCancelDuration(4, TimeUnit.SECONDS)
        if (manualFocus != null) setFocus(null)
        lastUserTap = System.currentTimeMillis()
        cam.cameraControl.startFocusAndMetering(builder.build())
        aeAfLocked = lock
    }

    fun unlockFocus() {
        camera?.cameraControl?.cancelFocusAndMetering()
        aeAfLocked = false
    }

    // ---------------- Captura ----------------

    /** Guarda el RAW (DNG) y el JPEG a la vez. */
    private fun takeRawPicture(ic: ImageCapture, onDone: (Boolean) -> Unit) {
        val base = "IACAM_" + SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(System.currentTimeMillis())
        fun opts(mime: String) = ImageCapture.OutputFileOptions.Builder(
            context.contentResolver, MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, base)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/IA Camara")
            },
        ).build()
        var pending = 2
        var success = false
        var reported = false
        fun report(ok: Boolean) {
            if (reported) return
            reported = true
            endCapture()
            if (ok) captureCount++
            onDone(ok)
        }
        ic.takePicture(opts("image/x-adobe-dng"), opts("image/jpeg"), ContextCompat.getMainExecutor(context), object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(result: ImageCapture.OutputFileResults) {
                val uri = result.savedUri
                if (uri != null && context.contentResolver.getType(uri)?.contains("jpeg") == true) {
                    lastPhotoUri = uri
                    analysisExecutor.execute {
                        val thumb = runCatching { context.contentResolver.loadThumbnail(uri, Size(240, 240), null) }.getOrNull()
                        mainHandler.post { if (thumb != null) lastPhoto = thumb }
                    }
                }
                success = true
                if (--pending <= 0 || uri == null) report(true)
            }

            override fun onError(e: ImageCaptureException) {
                Log.e(TAG, "Error al capturar RAW", e)
                if (!success) errorMessage = "No se pudo guardar la foto RAW"
                report(success)
            }
        })
    }

    private fun endCapture() {
        isCapturing = false
        if (pendingRebind) {
            pendingRebind = false
            rebind()
        }
    }

    /** ¿Hace falta flash ahora? En AUTO solo con poca luz. */
    private fun wantsFlash(): Boolean = when (flash) {
        FlashSetting.OFF -> false
        FlashSetting.ON -> true
        FlashSetting.AUTO -> (stats?.meanLuma ?: 1f) < 0.2f || live.iso >= 1600
    }

    /**
     * Dispara. El flash se resuelve aquí según la situación:
     *  • Exposición automática → flash real del teléfono (con preflash de medición).
     *  • Exposición manual (PRO o cerebro con velocidad fija) → luz continua medio segundo,
     *    porque el flash real necesita que la cámara mida la luz por sí misma.
     *  • Cámara frontal → la pantalla se ilumina en blanco.
     */
    fun capture(onDone: (Boolean) -> Unit = {}) {
        val ic = imageCapture ?: return
        val cam = camera ?: return
        if (isCapturing) return
        isCapturing = true
        val flashNow = wantsFlash()
        ic.flashMode = ImageCapture.FLASH_MODE_OFF
        val burst = if (flashNow) null else burstPlan()
        when {
            burst != null -> captureBurst(ic, burst.first, burst.second, onDone)
            !flashNow -> takePicture(ic, onDone)
            useFront -> {
                screenFlash = true
                mainHandler.postDelayed({ takePicture(ic) { ok -> screenFlash = false; onDone(ok) } }, 600)
            }
            !caps.hasFlash -> {
                errorMessage = "Este lente no tiene flash · usa 1×"
                takePicture(ic, onDone)
            }
            sensorManual || torchFlash -> {
                cam.cameraControl.enableTorch(true)
                mainHandler.postDelayed({
                    takePicture(ic) { ok -> cam.cameraControl.enableTorch(false); onDone(ok) }
                }, 500)
            }
            else -> {
                ic.flashMode = ImageCapture.FLASH_MODE_ON
                takePicture(ic, onDone)
            }
        }
    }

    fun cycleShot() {
        shotSetting = ShotSetting.entries[(shotSetting.ordinal + 1) % ShotSetting.entries.size]
    }

    /** Toma múltiple que corresponde ahora (tipo y número de tomas), o null para una sola. */
    fun burstPlan(): Pair<BurstKind, Int>? {
        if (rawActive) return null
        val kind = when (shotSetting) {
            ShotSetting.SINGLE -> null
            ShotSetting.NIGHT -> BurstKind.NIGHT
            ShotSetting.HDR -> if (caps.manualSensor) BurstKind.HDR else null
            ShotSetting.MACRO -> if (caps.manualFocus) BurstKind.MACRO else null
            ShotSetting.AUTO -> {
                if (!multiShotAuto || !brainActive) null
                else {
                    val st = stats
                    val sc = scene?.scene
                    val dark = live.iso >= 1250 || (st != null && st.meanLuma < 0.22f && live.iso >= 640)
                    when {
                        sc == Scene.MACRO && caps.manualFocus && live.focusDiopters > 5f -> BurstKind.MACRO
                        sc == Scene.NIGHT || dark -> BurstKind.NIGHT
                        brainWantsHdr -> BurstKind.HDR
                        else -> null
                    }
                }
            }
        } ?: return null
        val n = when (kind) {
            BurstKind.NIGHT -> if (steady) 6 else 8
            BurstKind.MACRO -> 5
            BurstKind.HDR -> 3
        }
        return kind to n
    }

    /** Explicación corta de la toma múltiple para la interfaz. */
    fun burstLabel(): String? = burstPlan()?.let { (k, n) ->
        when (k) {
            BurstKind.NIGHT -> "NOCHE · $n TOMAS"
            BurstKind.MACRO -> "MACRO+ · $n ENFOQUES"
            BurstKind.HDR -> "HDR · $n TOMAS"
        }
    }

    /**
     * Ráfaga: N tomas seguidas (en macro, cada una enfocada un poco más cerca o más lejos),
     * luego se alinean y combinan en segundo plano.
     */
    private fun captureBurst(ic: ImageCapture, kind: BurstKind, n: Int, onDone: (Boolean) -> Unit) {
        val frames = ArrayList<ByteArray>(n)
        var rotation = 0
        burstLock = true
        applyControls()
        val baseFocus = (manualFocus ?: live.focusDiopters).coerceAtLeast(0.5f)
        val maxFocus = caps.focusValues.lastOrNull() ?: baseFocus
        val focusSteps = listOf(0.8f, 0.9f, 1f, 1.12f, 1.25f).map { (baseFocus * it).coerceIn(0f, maxFocus) }

        // HDR: la última toma con 4× menos luz (velocidad más rápida; si no se puede, menos ISO).
        val baseIso = live.iso.takeIf { it > 0 } ?: priorityIso
        val baseShutter = live.shutterNs.takeIf { it > 0 } ?: 10_000_000L
        val minShutter = caps.shutterValues.firstOrNull() ?: 100_000L
        val minIso = caps.isoValues.firstOrNull() ?: 50
        val hdrRatio = BurstProcessor.hdrRatioFor(stats?.clippedArea ?: 0f)
        val underShutter = (baseShutter / hdrRatio).toLong()
        val underExposure = if (underShutter >= minShutter) baseIso to underShutter
        else {
            // Velocidad mínima alcanzada: el resto de la reducción, con ISO.
            val rest = hdrRatio * minShutter / baseShutter.toFloat()
            (baseIso / rest).toInt().coerceAtLeast(minIso) to minShutter
        }

        fun finish(ok: Boolean) {
            burstFocus = null
            burstExposure = null
            burstLock = false
            applyControls()
            endCapture()
            burstStatus = null
            onDone(ok)
        }

        fun process() {
            burstFocus = null
            burstExposure = null
            burstLock = false
            applyControls()
            burstStatus = "Combinando $n tomas…"
            processingExecutor.execute {
                val uri = runCatching {
                    BurstProcessor(context).process(
                        frames, kind, rotation,
                        shadowLift = if (kind == BurstKind.NIGHT) 0.3f else 0f,
                        hdrRatio = hdrRatio,
                    ) { done, total -> mainHandler.post { burstStatus = "Combinando $done/$total…" } }
                }.onFailure { Log.e(TAG, "Fallo al combinar", it) }.getOrNull()
                val thumb = uri?.let { runCatching { context.contentResolver.loadThumbnail(it, Size(240, 240), null) }.getOrNull() }
                mainHandler.post {
                    if (uri != null) {
                        lastPhotoUri = uri
                        thumb?.let { lastPhoto = it }
                        captureCount++
                    } else errorMessage = "No se pudo combinar la foto"
                    finish(uri != null)
                }
            }
        }

        fun shoot(i: Int) {
            burstStatus = if (kind == BurstKind.MACRO) "Enfoque ${i + 1}/$n · no te muevas" else "Tomando ${i + 1}/$n · no te muevas"
            val go = {
                ic.takePicture(ContextCompat.getMainExecutor(context), object : ImageCapture.OnImageCapturedCallback() {
                    override fun onCaptureSuccess(image: androidx.camera.core.ImageProxy) {
                        if (i == 0) rotation = image.imageInfo.rotationDegrees
                        val buffer = image.planes[0].buffer
                        val bytes = ByteArray(buffer.remaining()).also { buffer.get(it) }
                        image.close()
                        frames += bytes
                        if (i + 1 < n) shoot(i + 1) else process()
                    }

                    override fun onError(e: ImageCaptureException) {
                        Log.e(TAG, "Error en ráfaga", e)
                        if (frames.size >= 2) process() else { errorMessage = "No se pudo tomar la ráfaga"; finish(false) }
                    }
                })
            }
            if (kind == BurstKind.MACRO) {
                burstFocus = focusSteps[i % focusSteps.size]
                applyControls()
                mainHandler.postDelayed({ go() }, 280) // tiempo para que el lente se mueva
            } else if (kind == BurstKind.HDR && i == n - 1) {
                burstExposure = underExposure
                applyControls()
                mainHandler.postDelayed({ go() }, 350) // unos fotogramas para que el sensor cambie
            } else go()
        }
        shoot(0)
    }

    private fun takePicture(ic: ImageCapture, onDone: (Boolean) -> Unit) {
        if (rawActive) { takeRawPicture(ic, onDone); return }
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
                endCapture()
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
                endCapture()
                Log.e(TAG, "Error al capturar", e)
                errorMessage = "No se pudo guardar la foto"
                onDone(false)
            }
        })
    }

    fun cycleFlash() {
        flash = FlashSetting.entries[(flash.ordinal + 1) % FlashSetting.entries.size]
        if (flash != FlashSetting.OFF && !caps.hasFlash && !useFront) errorMessage = "Este lente no tiene flash · usa 1×"
        // Con flash, el cerebro deja que la cámara mida la luz (necesario para el flash real).
        if (brainActive) { lastBrainRun = 0L; brainShutterNs = null }
        applyControls()
    }

    /** Estado de ejemplo para capturas de pantalla de la interfaz (sin cámara real). */
    @androidx.annotation.VisibleForTesting
    fun loadDemoState(pro: Boolean) {
        lenses = listOf(
            Lens("2", 1f, 0.55f, 13), Lens("0", 1f, 1f, 24), Lens("0", 2f, 1f, 48, digital = true),
            Lens("3", 1f, 2.9f, 69), Lens("3", 3.45f, 2.9f, 238, digital = true),
        )
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
        scene = SceneResult(Scene.PORTRAIT, 0.92f, listOf("rostro"))
        subject = Subject(android.graphics.RectF(0.42f, 0.30f, 0.70f, 0.52f), "Persona", true)
        brainEv = 0.3f
        brainShutterNs = 8_000_000L
        brainReasons = listOf(
            Reason("1/125", "evita retratos movidos"),
            Reason("+0.3 EV", "piel más luminosa"),
        )
        lensNotice = "LENTE 2× · RETRATO"
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
