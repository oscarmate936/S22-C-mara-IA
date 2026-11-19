package com.iacamara.app.ai

import android.graphics.Rect
import android.graphics.RectF
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageProxy
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import com.google.mlkit.vision.objects.ObjectDetection
import com.google.mlkit.vision.objects.defaults.ObjectDetectorOptions
import java.util.concurrent.Executor

/** Lo que la IA vio en un fotograma. Las cajas están normalizadas 0..1 en el marco del visor. */
class Detection(
    val labels: List<Pair<String, Float>>,
    val faces: List<RectF>,
    val prominent: RectF?,
)

/**
 * Tres modelos de Google ML Kit, gratuitos y 100 % en el teléfono (sin internet):
 *  • Etiquetado de imagen (~400 conceptos: cielo, playa, perro, flor, comida…)
 *  • Detección de rostros
 *  • Detección del objeto principal (para animales, insectos, objetos)
 */
class SceneDetector(private val executor: Executor) {
    private val labeler = ImageLabeling.getClient(
        ImageLabelerOptions.Builder().setConfidenceThreshold(0.5f).build(),
    )
    private val faces = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
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

    /** Procesa el fotograma y cierra el ImageProxy al terminar. */
    @androidx.annotation.OptIn(markerClass = [ExperimentalGetImage::class])
    fun process(proxy: ImageProxy, mirror: Boolean, onResult: (Detection) -> Unit) {
        val media = proxy.image
        if (media == null) { proxy.close(); return }
        busy = true
        val rotation = proxy.imageInfo.rotationDegrees
        val input = InputImage.fromMediaImage(media, rotation)
        // Tamaño del fotograma ya enderezado (como se ve en pantalla).
        val w = (if (rotation % 180 == 0) proxy.width else proxy.height).toFloat()
        val h = (if (rotation % 180 == 0) proxy.height else proxy.width).toFloat()

        val tLabels = labeler.process(input)
        val tFaces = faces.process(input)
        val tObjects = objects.process(input)

        Tasks.whenAllComplete(tLabels, tFaces, tObjects).addOnCompleteListener(executor) {
            proxy.close()
            busy = false
            fun norm(r: Rect): RectF {
                val left = r.left / w
                val right = r.right / w
                return RectF(
                    (if (mirror) 1f - right else left).coerceIn(0f, 1f),
                    (r.top / h).coerceIn(0f, 1f),
                    (if (mirror) 1f - left else right).coerceIn(0f, 1f),
                    (r.bottom / h).coerceIn(0f, 1f),
                )
            }
            val labels = if (tLabels.isSuccessful) tLabels.result.map { it.text to it.confidence } else emptyList()
            val faceBoxes = if (tFaces.isSuccessful) tFaces.result.map { norm(it.boundingBox) } else emptyList()
            val main = if (tObjects.isSuccessful) tObjects.result.firstOrNull()?.let { norm(it.boundingBox) } else null
            onResult(Detection(labels, faceBoxes, main))
        }
    }

    fun close() {
        labeler.close(); faces.close(); objects.close()
    }
}
