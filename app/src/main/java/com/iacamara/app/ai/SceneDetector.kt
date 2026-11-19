package com.iacamara.app.ai

import android.graphics.PointF
import android.graphics.Rect
import android.graphics.RectF
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageProxy
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.face.FaceLandmark
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import com.google.mlkit.vision.objects.ObjectDetection
import com.google.mlkit.vision.objects.defaults.ObjectDetectorOptions
import java.util.concurrent.Executor

/** Un rostro con sus puntos de referencia (normalizados, en el marco vertical de la pantalla). */
class FaceInfo(
    val box: RectF,
    val leftEye: PointF? = null,
    val rightEye: PointF? = null,
    val mouth: PointF? = null,
    val trackId: Int? = null,
    /** Hacia dónde mira la persona (vector en el marco vertical; null = de frente). */
    val look: PointF? = null,
    /** Probabilidad de sonrisa y de ojos abiertos (solo con el disparo automático activo). */
    val smile: Float? = null,
    val eyesOpen: Float? = null,
    /** Giro de la cabeza (grados): izquierda/derecha y arriba/abajo. 0,0 = mira a la cámara. */
    val yaw: Float = 0f,
    val pitch: Float = 0f,
)

/** Lo que la IA vio en un fotograma. Las cajas están normalizadas 0..1 en el marco del visor. */
class Detection(
    val labels: List<Pair<String, Float>>,
    val faces: List<RectF>,
    val prominent: RectF?,
    /** Detalle de cada rostro (mismo orden que [faces]): ojos, boca, identificador de seguimiento. */
    val faceDetails: List<FaceInfo> = faces.map { FaceInfo(it) },
    /** Identificador de seguimiento del objeto principal (el mismo mientras sea el mismo objeto). */
    val prominentTrackId: Int? = null,
    /** Alguien levanta la mano por encima de la cabeza (solo con el disparo por gesto activo). */
    val handRaised: Boolean = false,
)

/**
 * Tres modelos de Google ML Kit, gratuitos y 100 % en el teléfono (sin internet):
 *  • Etiquetado de imagen (~400 conceptos: cielo, playa, perro, flor, comida…)
 *  • Detección de rostros con puntos de referencia (ojos, nariz, boca) y seguimiento
 *  • Detección y seguimiento del objeto principal (animales, insectos, objetos)
 */
class SceneDetector(private val executor: Executor) {
    private val labeler = ImageLabeling.getClient(
        ImageLabelerOptions.Builder().setConfidenceThreshold(0.5f).build(),
    )
    private fun faceClient(classify: Boolean) = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
            .apply { if (classify) setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL) }
            .enableTracking()
            .setMinFaceSize(0.08f)
            .build(),
    )
    private val faces by lazy { faceClient(false) }
    /** Con sonrisa y ojos abiertos: solo se crea si se usa el disparo automático. */
    private val facesClassify by lazy { faceClient(true) }
    /** Postura del cuerpo (para "levanta la mano"): solo si se usa ese disparo. */
    private val pose by lazy {
        com.google.mlkit.vision.pose.PoseDetection.getClient(
            com.google.mlkit.vision.pose.defaults.PoseDetectorOptions.Builder()
                .setDetectorMode(com.google.mlkit.vision.pose.defaults.PoseDetectorOptions.STREAM_MODE)
                .build(),
        )
    }

    /** Medir sonrisa / ojos abiertos (disparo automático por sonrisa o mirada). */
    @Volatile var classify = false
    /** Detectar la mano levantada (disparo por gesto). */
    @Volatile var detectHand = false
    private val objects = ObjectDetection.getClient(
        ObjectDetectorOptions.Builder()
            .setDetectorMode(ObjectDetectorOptions.STREAM_MODE)
            .build(),
    )

    @Volatile var busy = false
        private set

    /**
     * Procesa el fotograma y cierra el ImageProxy al terminar.
     * @param portraitRotation rotación que endereza la imagen en el marco vertical de la pantalla.
     *        Si el fotograma trae otra rotación (teléfono en horizontal), los resultados se
     *        devuelven al marco vertical.
     */
    @androidx.annotation.OptIn(markerClass = [ExperimentalGetImage::class])
    fun process(proxy: ImageProxy, mirror: Boolean, portraitRotation: Int, onResult: (Detection) -> Unit) {
        val media = proxy.image
        if (media == null) { proxy.close(); return }
        busy = true
        val rotation = proxy.imageInfo.rotationDegrees
        val delta = rotation - portraitRotation
        val input = InputImage.fromMediaImage(media, rotation)
        // Tamaño del fotograma tal como lo ve la IA (enderezado según cómo sostienes el teléfono).
        val w = (if (rotation % 180 == 0) proxy.width else proxy.height).toFloat()
        val h = (if (rotation % 180 == 0) proxy.height else proxy.width).toFloat()

        val tLabels = labeler.process(input)
        val tFaces = (if (classify) facesClassify else faces).process(input)
        val tObjects = objects.process(input)
        val tPose = if (detectHand) pose.process(input) else null

        Tasks.whenAllComplete(listOfNotNull(tLabels, tFaces, tObjects, tPose)).addOnCompleteListener(executor) {
            proxy.close()
            busy = false
            fun box(r: Rect) = Orient.rectToPortrait(r.left / w, r.top / h, r.right / w, r.bottom / h, delta, mirror)
            fun point(p: PointF?) = p?.let { Orient.pointToPortrait(it.x / w, it.y / h, delta, mirror) }
            val labels = if (tLabels.isSuccessful) tLabels.result.map { it.text to it.confidence } else emptyList()
            val faceList = if (tFaces.isSuccessful) tFaces.result else emptyList()
            val details = faceList.map { f ->
                // En la imagen espejada (frontal) el ojo izquierdo de la persona queda a la derecha.
                FaceInfo(
                    box = box(f.boundingBox),
                    leftEye = point(f.getLandmark(FaceLandmark.LEFT_EYE)?.position),
                    rightEye = point(f.getLandmark(FaceLandmark.RIGHT_EYE)?.position),
                    mouth = point(f.getLandmark(FaceLandmark.MOUTH_BOTTOM)?.position),
                    trackId = f.trackingId,
                    // Giro de la cabeza: positivo = mira hacia la derecha de la imagen analizada.
                    look = f.headEulerAngleY.let { yaw ->
                        if (kotlin.math.abs(yaw) < 12f) null
                        else Orient.vectorToPortrait((yaw / 40f).coerceIn(-1f, 1f), 0f, delta, mirror)
                    },
                    smile = f.smilingProbability,
                    eyesOpen = listOfNotNull(f.leftEyeOpenProbability, f.rightEyeOpenProbability).minOrNull(),
                    yaw = f.headEulerAngleY,
                    pitch = f.headEulerAngleX,
                )
            }
            val obj = if (tObjects.isSuccessful) tObjects.result.firstOrNull() else null
            // Mano levantada: una muñeca claramente por encima de la nariz (imagen enderezada: y hacia abajo).
            val hand = tPose?.takeIf { it.isSuccessful }?.result?.let { p ->
                val nose = p.getPoseLandmark(com.google.mlkit.vision.pose.PoseLandmark.NOSE)
                val wrists = listOfNotNull(
                    p.getPoseLandmark(com.google.mlkit.vision.pose.PoseLandmark.LEFT_WRIST),
                    p.getPoseLandmark(com.google.mlkit.vision.pose.PoseLandmark.RIGHT_WRIST),
                )
                nose != null && nose.inFrameLikelihood > 0.5f && wrists.any {
                    it.inFrameLikelihood > 0.6f && it.position.y < nose.position.y - h * 0.05f
                }
            } ?: false
            onResult(Detection(labels, details.map { it.box }, obj?.let { box(it.boundingBox) }, details, obj?.trackingId, hand))
        }
    }

    fun close() {
        labeler.close(); faces.close(); objects.close()
    }
}
