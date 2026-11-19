package com.iacamara.app.ai

import com.iacamara.app.processing.MaskRefine
import com.iacamara.app.processing.Straighten
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Mediciones objetivas de una foto (de una versión reducida, ~1024 px). */
class PhotoMetrics(
    /** Detalle fino respecto al detalle medio (≈0.5+ nítida, <0.3 movida o desenfocada). */
    val sharpness: Float,
    /** Ruido estimado (niveles de 0..255) en zonas lisas. */
    val noise: Float,
    /** Zona grande quemada (fracción). */
    val clipped: Float,
    /** Zona negra sin detalle (fracción). */
    val crushed: Float,
    val meanLuma: Float,
    /** Inclinación medida (grados) si hay líneas claras; null si no se puede saber. */
    val tiltDeg: Float?,
    val faces: List<FaceObs> = emptyList(),
)

/**
 * Calificación de la foto (0–100) como la daría un fotógrafo: nitidez, exposición, ruido,
 * horizonte y, si hay personas, ojos abiertos y caras sin cortar. Cada punto que resta viene con
 * un consejo concreto.
 */
object PhotoScore {
    class Factor(val name: String, val ok: Boolean, val detail: String, val penalty: Int)
    class Result(val score: Int, val label: String, val factors: List<Factor>) {
        val tips get() = factors.filter { !it.ok }.sortedByDescending { it.penalty }
    }

    fun score(m: PhotoMetrics): Result {
        val f = ArrayList<Factor>()
        // Nitidez
        val sharpPen = (((0.38f - m.sharpness) / 0.20f).coerceIn(0f, 1f) * 35).toInt()
        f += Factor(
            "Nitidez", sharpPen < 5,
            when {
                sharpPen < 5 -> "Nítida"
                sharpPen < 18 -> "Algo blanda: apoya el teléfono o usa una velocidad más rápida"
                else -> "Movida o desenfocada: toca el sujeto para enfocar y sujeta firme"
            }, sharpPen,
        )
        // Exposición
        val clipPen = ((m.clipped - 0.03f) / 0.22f).coerceIn(0f, 1f) * 20
        val crushPen = ((m.crushed - 0.10f) / 0.30f).coerceIn(0f, 1f) * 12
        val meanPen = when {
            m.meanLuma < 0.18f -> (0.18f - m.meanLuma) / 0.12f * 18
            m.meanLuma > 0.78f -> (m.meanLuma - 0.78f) / 0.15f * 12
            else -> 0f
        }.coerceIn(0f, 18f)
        val expPen = (clipPen + crushPen + meanPen).toInt().coerceAtMost(30)
        f += Factor(
            "Exposición", expPen < 5,
            when {
                expPen < 5 -> "Bien expuesta"
                clipPen >= crushPen && clipPen >= meanPen -> "Zonas quemadas: baja la exposición o usa HDR"
                crushPen >= meanPen -> "Sombras sin detalle: usa HDR o aclara en «Mejorar»"
                m.meanLuma < 0.18f -> "Oscura: aclara en «Mejorar» o usa el modo noche"
                else -> "Muy clara: baja la exposición"
            }, expPen,
        )
        // Ruido
        val noisePen = ((m.noise - 3f) / 7f).coerceIn(0f, 1f).let { (it * 15).toInt() }
        f += Factor("Ruido", noisePen < 4, if (noisePen < 4) "Limpia" else "Con grano: con poca luz usa el modo noche (combina tomas)", noisePen)
        // Horizonte
        m.tiltDeg?.let { t ->
            val tiltPen = ((abs(t) - 0.8f) / 3.2f).coerceIn(0f, 1f).let { (it * 10).toInt() }
            f += Factor("Horizonte", tiltPen < 2, if (tiltPen < 2) "Recto" else "Torcido %.1f°: usa «Enderezar»".format(abs(t)), tiltPen)
        }
        // Personas
        if (m.faces.isNotEmpty()) {
            val closed = m.faces.count { (it.eyesOpen ?: 1f) < 0.3f }
            val cut = m.faces.count { it.left < 0.01f || it.top < 0.01f || it.right > 0.99f || it.bottom > 0.99f }
            val pen = (if (closed > 0) 15 else 0) + (if (cut > 0) 8 else 0)
            f += Factor(
                "Personas", pen == 0,
                when {
                    closed > 0 && cut > 0 -> "Ojos cerrados y una cara cortada por el borde"
                    closed > 0 -> if (closed == 1) "Alguien tiene los ojos cerrados: usa «Acción» (elige la mejor toma)" else "$closed personas con los ojos cerrados"
                    cut > 0 -> "Una cara queda cortada por el borde"
                    else -> "Ojos abiertos, caras completas"
                }, pen,
            )
        }
        val score = (100 - f.sumOf { it.penalty }).coerceIn(0, 100)
        val label = when {
            score >= 85 -> "Excelente"
            score >= 70 -> "Buena"
            score >= 50 -> "Mejorable"
            else -> "Fallida"
        }
        return Result(score, label, f)
    }

    /** Mide una foto (píxeles ARGB, idealmente ~1024 px de lado mayor). */
    fun measure(px: IntArray, w: Int, h: Int, faces: List<FaceObs> = emptyList()): PhotoMetrics {
        val n = w * h
        val l = FloatArray(n)
        val lb = ByteArray(n)
        var clipped = 0; var crushed = 0; var sum = 0.0
        for (i in 0 until n) {
            val p = px[i]
            val r = (p shr 16) and 0xFF; val g = (p shr 8) and 0xFF; val b = p and 0xFF
            val y = (r * 2 + g * 5 + b) shr 3
            l[i] = y.toFloat(); lb[i] = y.toByte(); sum += y
            if (max(r, max(g, b)) >= 252 && y >= 235) clipped++
            if (y <= 6) crushed++
        }
        // Detalle fino (diferencia con un desenfoque de 1 px) frente a detalle medio (entre 1 y 4 px).
        val b1 = MaskRefine.box(l, w, h, 1)
        val b4 = MaskRefine.box(b1, w, h, 4)
        // Región: las caras si las hay; si no, las zonas con más estructura (el sujeto suele estar ahí).
        var fine = 0.0; var mid = 0.0
        val blocks = ArrayList<Triple<Double, Double, Double>>() // (fino, medio, energía)
        val B = 32
        for (by in 1 until h / B - 1) for (bx in 1 until w / B - 1) {
            var fs = 0.0; var ms = 0.0
            for (y in by * B until (by + 1) * B step 2) for (x in bx * B until (bx + 1) * B step 2) {
                val i = y * w + x
                fs += abs(l[i] - b1[i]); ms += abs(b1[i] - b4[i])
            }
            val inFace = faces.any { (bx + 0.5f) * B / w in it.left..it.right && (by + 0.5f) * B / h in it.top..it.bottom }
            blocks += Triple(fs, ms, if (inFace) Double.MAX_VALUE else ms)
        }
        val top = blocks.sortedByDescending { it.third }.take(max(4, blocks.size / 5))
        for (t in top) { fine += t.first; mid += t.second }
        val sharp = if (mid > 0) (fine / mid).toFloat() else 0f
        // Ruido: en las zonas más lisas, la diferencia fina es casi solo ruido.
        val flat = blocks.sortedBy { it.second }.take(max(4, blocks.size / 6))
        val noise = if (flat.isEmpty()) 0f else (flat.sumOf { it.first } / flat.size / (B * B / 4) * 1.25).toFloat()
        val st = Straighten.estimate(lb, w, h)
        return PhotoMetrics(
            sharpness = sharp, noise = noise, clipped = clipped.toFloat() / n, crushed = crushed.toFloat() / n,
            meanLuma = (sum / n / 255).toFloat(), tiltDeg = if (st.confidence >= 0.35f) st.tiltDeg else null, faces = faces,
        )
    }

}
