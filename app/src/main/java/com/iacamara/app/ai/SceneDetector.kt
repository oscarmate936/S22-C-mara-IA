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
    private val faces = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
            .enableTracking()
            .setMinFaceSize(0.08f)
            .build(),
    )
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
        val tFaces = faces.process(input)
        val tObjects = objects.process(input)

        Tasks.whenAllComplete(tLabels, tFaces, tObjects).addOnCompleteListener(executor) {
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
                )
            }
            val obj = if (tObjects.isSuccessful) tObjects.result.firstOrNull() else null
            onResult(Detection(labels, details.map { it.box }, obj?.let { box(it.boundingBox) }, details, obj?.trackingId))
        }
    }

    fun close() {
        labeler.close(); faces.close(); objects.close()
    }
}
