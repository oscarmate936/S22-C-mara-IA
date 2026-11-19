package com.iacamara.app.processing

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.iacamara.app.ai.Candidate
import com.iacamara.app.ai.FaceObs
import com.iacamara.app.ai.MomentScorer
import com.iacamara.app.ai.Orient
import com.iacamara.app.camera.RingFrame

/**
 * "El momento perfecto": de una ráfaga (tomas de resolución completa + fotogramas de la memoria
 * de antes de tocar el botón) elige la más nítida con todos los ojos abiertos y, en fotos de
 * grupo, corrige a quien parpadeó con su cara de otra toma.
 */
class MomentProcessor(private val context: Context) {

    class Result(val uri: Uri?, val candidates: Int, val fromMemory: Boolean, val facesFixed: Int)

    /** Lo que se mide de cada candidata (en una versión reducida, para que sea rápido). */
    private class Probe(val candidate: Candidate, val rotation: Int)

    fun process(jpegs: List<ByteArray>, rotation: Int, ring: List<RingFrame>, onProgress: (String) -> Unit): Result {
        val detector = FaceDetection.getClient(
            FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
                .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
                .setMinFaceSize(0.05f)
                .build(),
        )
        try {
            val probes = ArrayList<Probe>()
            // Tomas de resolución completa (al disparar)
            jpegs.forEachIndexed { k, bytes ->
                onProgress("Eligiendo el mejor momento ${k + 1}/${jpegs.size + ring.size}…")
                val small = decodeSmall(bytes) ?: return@forEachIndexed
                probes += Probe(analyze(small, rotation, k, true, detector), rotation)
                small.recycle()
            }
            // Instantes previos (memoria de fotogramas)
            ring.forEachIndexed { k, f ->
                onProgress("Eligiendo el mejor momento ${jpegs.size + k + 1}/${jpegs.size + ring.size}…")
                val full = Bitmap.createBitmap(f.toArgb(), f.width, f.height, Bitmap.Config.ARGB_8888)
                val s = scaleTo(full, PROBE_SIDE)
                if (s !== full) full.recycle()
                probes += Probe(analyze(s, f.rotation, jpegs.size + k, false, detector), f.rotation)
                s.recycle()
            }
            if (probes.isEmpty()) return Result(null, 0, false, 0)
            val cands = probes.map { it.candidate }
            val bestIndex = MomentScorer.choose(cands)
            val best = cands.first { it.index == bestIndex }

            onProgress("Preparando la foto…")
            if (!best.fullRes) {
                // Ganó un instante previo (menor resolución): se guarda tal cual.
                val f = ring[bestIndex - jpegs.size]
                val bmp = Bitmap.createBitmap(f.toArgb(), f.width, f.height, Bitmap.Config.ARGB_8888)
                val uri = PhotoSaver.save(context, bmp, jpegs.firstOrNull(), f.rotation, "_MOMENTO", "IA Cámara · mejor momento (instante previo)")
                bmp.recycle()
                return Result(uri, cands.size, true, 0)
            }

            val opts = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
            val baseBmp = BitmapFactory.decodeByteArray(jpegs[bestIndex], 0, jpegs[bestIndex].size, opts)
                ?: return Result(null, cands.size, false, 0)
            val w = baseBmp.width
            val h = baseBmp.height
            var px = IntArray(w * h).also { baseBmp.getPixels(it, 0, w, 0, 0, w, h) }
            baseBmp.recycle()

            // Fotos de grupo: corregir a quien parpadeó con su cara de otra toma.
            val swaps = MomentScorer.faceSwaps(best, cands)
            if (swaps.isNotEmpty()) {
                onProgress("Corrigiendo ojos cerrados…")
                val baseLuma = Align.luma(px)
                for ((donor, list) in swaps.groupBy { it.donor }) {
                    val dBmp = BitmapFactory.decodeByteArray(jpegs[donor], 0, jpegs[donor].size, opts) ?: continue
                    if (dBmp.width != w || dBmp.height != h) { dBmp.recycle(); continue }
                    val dPx = IntArray(w * h).also { dBmp.getPixels(it, 0, w, 0, 0, w, h) }
                    dBmp.recycle()
                    val t = TileAlign.estimate(baseLuma, Align.luma(dPx), w, h).transform
                    val aligned = TileAlign.warp(dPx, w, h, t)
                    for (sw in list) {
                        val f = best.faces[sw.baseFace]
                        px = FaceBlend.blend(px, aligned, w, h, f.left * w, f.top * h, f.right * w, f.bottom * h)
                    }
                }
            }
            val out = Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
            val uri = PhotoSaver.save(
                context, out, jpegs[bestIndex], rotation, "_MOMENTO",
                "IA Cámara · mejor momento de ${cands.size}" + if (swaps.isNotEmpty()) " · ${swaps.size} cara(s) corregida(s)" else "",
            )
            out.recycle()
            return Result(uri, cands.size, false, swaps.size)
        } finally {
            detector.close()
        }
    }

    /** Mide nitidez y caras (ojos, sonrisa) de una candidata. */
    private fun analyze(small: Bitmap, rotation: Int, index: Int, fullRes: Boolean, detector: com.google.mlkit.vision.face.FaceDetector): Candidate {
        val w = small.width
        val h = small.height
        val px = IntArray(w * h).also { small.getPixels(it, 0, w, 0, 0, w, h) }
        val luma = Align.luma(px)
        // La IA ve la imagen enderezada; las caras se devuelven al marco sin girar del fotograma.
        val rw = if (rotation % 180 == 0) w else h
        val rh = if (rotation % 180 == 0) h else w
        val faces = runCatching { Tasks.await(detector.process(InputImage.fromBitmap(small, rotation))) }.getOrNull().orEmpty().map { f ->
            val b = f.boundingBox
            val r = Orient.rectToPortrait(b.left / rw.toFloat(), b.top / rh.toFloat(), b.right / rw.toFloat(), b.bottom / rh.toFloat(), rotation, false)
            val eyes = listOfNotNull(f.leftEyeOpenProbability, f.rightEyeOpenProbability).minOrNull()
            FaceObs(r.left, r.top, r.right, r.bottom, eyes, f.smilingProbability)
        }
        // Nitidez donde importa: las caras si las hay; si no, el centro.
        val region = if (faces.isNotEmpty()) intArrayOf(
            (faces.minOf { it.left } * w).toInt(), (faces.minOf { it.top } * h).toInt(),
            (faces.maxOf { it.right } * w).toInt(), (faces.maxOf { it.bottom } * h).toInt(),
        ) else null
        return Candidate(index, fullRes, Sharpness.of(luma, w, h, region), faces)
    }

    private fun decodeSmall(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= PROBE_SIDE) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888 }
        val b = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: return null
        return scaleTo(b, PROBE_SIDE).also { if (it !== b) b.recycle() }
    }

    private fun scaleTo(b: Bitmap, side: Int): Bitmap {
        val s = side.toFloat() / maxOf(b.width, b.height)
        return if (s >= 1f) b else Bitmap.createScaledBitmap(b, (b.width * s).toInt(), (b.height * s).toInt(), true)
    }

    companion object {
        /** Tamaño de la versión reducida con la que se compara (lado mayor). */
        const val PROBE_SIDE = 1024
    }
}
