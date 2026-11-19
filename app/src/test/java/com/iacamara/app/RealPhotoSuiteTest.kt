package com.iacamara.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.iacamara.app.camera.HorizonDetector
import com.iacamara.app.processing.Align
import com.iacamara.app.processing.FocusStacker
import com.iacamara.app.processing.HdrMerger
import com.iacamara.app.processing.MeanStacker
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Batería con fotos reales descargadas por tools/descargar_imagenes.sh (no se suben al repo).
 * Si no están, las pruebas se omiten. Resultados en app/build/sim/real/.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class RealPhotoSuiteTest {
    private val dir = File("build/fotos_prueba")
    private val out = File("build/sim/real").apply { mkdirs() }
    private val report = StringBuilder()
    private fun line(s: String) { report.appendLine(s); println("SIM | $s") }

    private fun photos(): List<File> {
        assumeTrue("fotos de prueba no descargadas", dir.isDirectory && (dir.listFiles()?.isNotEmpty() == true))
        return dir.listFiles()!!.filter { it.extension.lowercase() in setOf("jpg", "png") }.sortedBy { it.name }
    }

    private fun load(f: File, maxSide: Int = 900): Bitmap {
        val b = BitmapFactory.decodeFile(f.path)!!
        val s = minOf(1f, maxSide.toFloat() / maxOf(b.width, b.height))
        return if (s < 1f) Bitmap.createScaledBitmap(b, (b.width * s).toInt(), (b.height * s).toInt(), true) else b.copy(Bitmap.Config.ARGB_8888, true)
    }

    private fun px(b: Bitmap) = IntArray(b.width * b.height).also { b.getPixels(it, 0, b.width, 0, 0, b.width, b.height) }

    private fun save(px: IntArray, w: Int, h: Int, name: String) =
        File(out, name).outputStream().use { Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.JPEG, 90, it) }

    // ------------------------------------------------ horizonte

    @Test fun horizonteEnFotosReales() {
        line("=== HORIZONTE EN FOTOS REALES ===")
        for (f in photos()) {
            val b = load(f, 640)
            val w = b.width; val h = b.height
            val p = px(b)
            // Igual que en la app: imagen reducida a ¼, luminancia.
            val sw = w / 4; val sh = h / 4
            val small = ByteArray(sw * sh) { i ->
                val x = (i % sw) * 4; val y = (i / sw) * 4
                var s = 0
                for (oy in 0 until 4 step 2) for (ox in 0 until 4 step 2) {
                    val q = p[(y + oy) * w + x + ox]
                    s += (0.299 * ((q shr 16) and 0xFF) + 0.587 * ((q shr 8) and 0xFF) + 0.114 * (q and 0xFF)).toInt()
                }
                (s / 4).toByte()
            }
            val hz = HorizonDetector.detect(small, sw, sh)
            val shown = hz?.takeIf { it.confidence >= 0.45f }
            line("%-22s %s".format(f.name, if (hz == null) "sin horizonte" else
                "y=%.2f  %+5.1f°  confianza %.2f%s".format(hz.y, hz.tiltDeg, hz.confidence, if (shown == null) " (descartado)" else "")))
            if (shown != null) {
                val slope = kotlin.math.tan(Math.toRadians(shown.tiltDeg.toDouble())).toFloat()
                for (x in 0 until w) {
                    val y = (shown.y * h + slope * (x - w / 2f)).toInt()
                    for (t in -1..1) if (y + t in 0 until h) p[(y + t) * w + x] = (0xFF shl 24) or (0xFF shl 16) or (0xB5 shl 8) or 0x47
                }
            }
            save(p, w, h, "horizonte_${f.nameWithoutExtension}.jpg")
        }
        File(out, "reporte_horizonte.txt").writeText(report.toString())
    }

    // ------------------------------------------------ modo noche, HDR y macro con fotos reales

    private fun gauss(r: Random): Double {
        val u1 = r.nextDouble().coerceAtLeast(1e-12); val u2 = r.nextDouble()
        return sqrt(-2 * kotlin.math.ln(u1)) * kotlin.math.cos(2 * Math.PI * u2)
    }

    private fun psnr(a: IntArray, b: IntArray, w: Int, h: Int): Double {
        var se = 0.0; var n = 0
        for (y in 24 until h - 24) for (x in 24 until w - 24) {
            val i = y * w + x
            for (c in 0..2) { val d = ((a[i] shr (16 - 8 * c)) and 0xFF) - ((b[i] shr (16 - 8 * c)) and 0xFF); se += d * d; n++ }
        }
        return 10 * log10(255.0 * 255 / (se / n))
    }

    @Test fun modoNocheEnFotosReales() {
        line("=== MODO NOCHE EN FOTOS REALES (8 tomas, pulso ±10 px, ruido de sensor) ===")
        var worst = 99.0
        for (f in photos()) {
            val b = load(f, 640)
            val w = b.width; val h = b.height
            val gt = px(b)
            val rnd = Random(f.name.hashCode())
            val frames = (0 until 8).map { k ->
                val dx = if (k == 0) 0 else rnd.nextInt(-10, 11); val dy = if (k == 0) 0 else rnd.nextInt(-10, 11)
                IntArray(w * h) { i ->
                    val x = (i % w - dx).coerceIn(0, w - 1); val y = (i / w - dy).coerceIn(0, h - 1)
                    val q = gt[y * w + x]
                    var o = 0xFF shl 24
                    for (c in 0..2) {
                        val lin = (((q shr (16 - 8 * c)) and 0xFF) / 255.0).pow(2.2) * 150
                        val v = ((lin + sqrt(lin) * gauss(rnd)) / 150 + 0.012 * gauss(rnd)).coerceAtLeast(0.0).pow(1 / 2.2) * 255
                        o = o or (v.toInt().coerceIn(0, 255) shl (16 - 8 * c))
                    }
                    o
                }
            }
            val ref = Align.luma(frames[0])
            val st = MeanStacker(w, h)
            st.add(frames[0])
            var wrongShift = 0
            for (k in 1 until 8) {
                val (sx, sy) = Align.estimateShift(ref, Align.luma(frames[k]), w, h)
                st.add(frames[k], sx, sy)
            }
            val res = st.result(0f)
            val p1 = psnr(frames[0], gt, w, h)
            val p8 = psnr(res, gt, w, h)
            worst = minOf(worst, p8 - p1)
            line("%-22s 1 toma %5.1f dB → 8 tomas %5.1f dB  (%+.1f dB)".format(f.name, p1, p8, p8 - p1))
            if (f.name.startsWith("autobus") || f.name.startsWith("perro_bici")) save(res, w, h, "noche_${f.nameWithoutExtension}.jpg")
            if (wrongShift > 0) line("   alineación dudosa")
        }
        line("peor mejora: %+.1f dB".format(worst))
        File(out, "reporte_noche.txt").writeText(report.toString())
    }

    @Test fun hdrEnFotosReales() {
        line("=== HDR EN FOTOS REALES (cielo/zonas claras 5× más luminosas) ===")
        for (name in listOf("playa_tf.jpg", "caballos.jpg", "persona_campo.jpg", "casa.jpg", "jirafas_sabana.jpg")) {
            val f = File(dir, name)
            if (!f.exists()) continue
            val b = load(f, 800)
            val w = b.width; val h = b.height
            val p = px(b)
            // Escena con mucha más luz en las zonas claras (como un cielo real).
            val lin = Array(3) { FloatArray(w * h) }
            for (i in p.indices) for (c in 0..2) {
                val v = (((p[i] shr (16 - 8 * c)) and 0xFF) / 255.0).pow(2.2).toFloat()
                val l = (0.3 * (((p[i] shr 16) and 0xFF)) + 0.59 * (((p[i] shr 8) and 0xFF)) + 0.11 * (p[i] and 0xFF)) / 255.0
                lin[c][i] = v * (1f + 4f * ((l - 0.55) / 0.3).coerceIn(0.0, 1.0).toFloat())
            }
            fun develop(gain: Float) = IntArray(w * h) { i ->
                var o = 0xFF shl 24
                for (c in 0..2) o = o or (((lin[c][i] * gain).coerceIn(0f, 1f).toDouble().pow(1 / 2.2) * 255 + .5).toInt() shl (16 - 8 * c))
                o
            }
            val normal = develop(1f)
            val under = develop(0.25f)
            val (dx, dy) = Align.estimateShift(Align.luma(normal), Align.lumaWithGain(under, 4f), w, h)
            val merger = HdrMerger(w, h)
            val hdr = merger.merge(normal, under, dx, dy, 4f)
            fun blown(a: IntArray) = a.count { ((it shr 16) and 0xFF) >= 254 && ((it shr 8) and 0xFF) >= 254 && (it and 0xFF) >= 254 } * 100f / a.size
            line("%-22s quemado %5.1f%% → %5.1f%%   alineación (%d,%d)   relación medida %.2f (real 4.00)".format(name, blown(normal), blown(hdr), dx, dy, merger.measuredRatio))
            val side = IntArray(w * 2 * h)
            for (y in 0 until h) for (x in 0 until w) { side[y * 2 * w + x] = normal[y * w + x]; side[y * 2 * w + w + x] = hdr[y * w + x] }
            save(side, 2 * w, h, "hdr_${f.nameWithoutExtension}.jpg")
        }
        File(out, "reporte_hdr.txt").writeText(report.toString())
    }

    @Test fun macroEnFotosReales() {
        line("=== MACRO+ EN FOTOS REALES (5 enfoques) ===")
        for (name in listOf("mariposa.jpg", "frutas.jpg", "quokka.jpg")) {
            val f = File(dir, name)
            if (!f.exists()) continue
            val b = load(f, 600)
            val w = b.width; val h = b.height
            val gt = px(b)
            fun blurred(sigma: Int): IntArray {
                if (sigma == 0) return gt
                val s = Bitmap.createScaledBitmap(Bitmap.createScaledBitmap(b, w / (sigma + 1), h / (sigma + 1), true), w, h, true)
                return px(s)
            }
            val levels = (0..5).map { blurred(it) }
            val planes = listOf(0.1f, 0.3f, 0.5f, 0.7f, 0.9f)
            val frames = planes.map { fp ->
                IntArray(w * h) { i ->
                    val depth = (i / w) / h.toFloat() // superficie inclinada
                    val s = (kotlin.math.abs(depth - fp) * 10).toInt().coerceIn(0, 5)
                    levels[s][i]
                }
            }
            val fs = FocusStacker(w, h)
            frames.forEach { fs.add(it) }
            val res = fs.result()
            val best = frames.maxOf { psnr(it, gt, w, h) }
            val pr = psnr(res, gt, w, h)
            line("%-22s mejor toma sola %5.1f dB → apilado %5.1f dB  (%+.1f dB)".format(name, best, pr, pr - best))
            save(res, w, h, "macro_${f.nameWithoutExtension}.jpg")
        }
        File(out, "reporte_macro.txt").writeText(report.toString())
    }
}
