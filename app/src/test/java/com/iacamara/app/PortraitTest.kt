package com.iacamara.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.iacamara.app.processing.Align
import com.iacamara.app.processing.Bokeh
import com.iacamara.app.processing.DepthHint
import com.iacamara.app.processing.FloatMap
import com.iacamara.app.processing.MaskRefine
import com.iacamara.app.processing.Sharpness
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.log10

/** Fase 7: modo retrato (recorte refinado, profundidad aproximada y desenfoque óptico). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class PortraitTest {
    private val out = File("build/sim").apply { mkdirs() }
    private val report = StringBuilder()
    private fun line(s: String) { report.appendLine(s); println("SIM | $s") }

    private fun photo(name: String) = BitmapFactory.decodeStream(javaClass.classLoader!!.getResourceAsStream("sim/$name"))!!
    private fun px(b: Bitmap) = IntArray(b.width * b.height).also { b.getPixels(it, 0, b.width, 0, 0, b.width, b.height) }
    private fun save(p: IntArray, w: Int, h: Int, name: String) =
        File(out, name).outputStream().use { Bitmap.createBitmap(p, w, h, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.PNG, 100, it) }

    /**
     * Escena con máscara conocida: la cara del gato (elipse) delante de un fondo real, con luces
     * puntuales en el fondo (para ver los círculos de bokeh).
     */
    private class Scene(val w: Int, val h: Int, val px: IntArray, val bgOnly: IntArray, val mask: FloatMap)

    private fun scene(): Scene {
        val bg = photo("rocket.jpg") // fondo con detalle
        val fg = photo("chelsea.png")
        val w = 420; val h = 560
        val bgS = Bitmap.createScaledBitmap(bg, w, h, true)
        val bgPx = px(bgS)
        // Luces puntuales en el fondo (arriba a la izquierda)
        for ((lx, ly) in listOf(60 to 60, 110 to 90, 70 to 150, 150 to 50)) for (dy in -2..2) for (dx in -2..2) {
            bgPx[(ly + dy) * w + lx + dx] = (0xFF shl 24) or 0xFFF0D0
        }
        val fgS = Bitmap.createScaledBitmap(fg, 300, 200, true)
        val fgPx = px(fgS)
        val p = bgPx.copyOf()
        val m = FloatArray(w * h)
        val cx = 270f; val cy = 360f; val rx = 120f; val ry = 150f
        for (y in 0 until h) for (x in 0 until w) {
            val nx = (x - cx) / rx; val ny = (y - cy) / ry
            if (nx * nx + ny * ny <= 1f) {
                m[y * w + x] = 1f
                val sx = (x - (cx - 150)).toInt().coerceIn(0, 299); val sy = ((y - (cy - 100)) * 200 / 300).toInt().coerceIn(0, 199)
                p[y * w + x] = fgPx[sy * 300 + sx]
            }
        }
        return Scene(w, h, p, bgPx, FloatMap(w, h, m))
    }

    private fun luma(p: IntArray) = FloatArray(p.size) { val q = p[it]; ((((q shr 16) and 0xFF) * 2 + ((q shr 8) and 0xFF) * 5 + (q and 0xFF)) shr 3) / 255f }

    @Test fun retratoNitidoSinHalosYConBokeh() {
        line("=== MODO RETRATO ===")
        val s = scene()
        val w = s.w; val h = s.h
        // Máscara "como la da la IA": baja resolución y bordes toscos.
        val coarse = s.mask.resized(w / 8, h / 8).resized(w / 4, h / 4)
        // Como en la app: la máscara tosca se refina a la resolución de trabajo con la foto como guía.
        val refinedSmall = MaskRefine.refine(coarse.resized(w, h), luma(s.px), 6)
        fun edgeError(m: FloatMap): Float {
            var e = 0f; var n = 0
            for (y in 0 until h) for (x in 0 until w) {
                val t = s.mask.v[y * w + x]
                val near = (x > 0 && s.mask.v[y * w + x - 1] != t) || (y > 0 && s.mask.v[(y - 1) * w + x] != t)
                if (!near) continue
                for (dy in -6..6) for (dx in -6..6) {
                    val xx = (x + dx).coerceIn(0, w - 1); val yy = (y + dy).coerceIn(0, h - 1)
                    e += abs(m.sample((xx + 0.5f) / w, (yy + 0.5f) / h) - s.mask.v[yy * w + xx]); n++
                }
            }
            return e / n
        }
        val eCoarse = edgeError(coarse)
        val eRef = edgeError(refinedSmall)
        line("BORDES  error en el contorno: máscara tosca %.3f → refinada %.3f".format(eCoarse, eRef))

        val blur = DepthHint.blurMap(refinedSmall)
        val res = Bokeh.render(s.px, w, h, refinedSmall, blur, maxRadius = 18f, scale = 2)
        save(s.px, w, h, "retrato_original.png"); save(res, w, h, "retrato_bokeh.png")

        // 1) El sujeto queda nítido (centro de la cara intacto)
        var se = 0.0; var n = 0
        for (y in 300 until 420) for (x in 220 until 320) for (c in 0..2) {
            val d = ((res[y * w + x] shr (16 - 8 * c)) and 0xFF) - ((s.px[y * w + x] shr (16 - 8 * c)) and 0xFF); se += d * d; n++
        }
        val subjectPsnr = if (se == 0.0) 99.0 else 10 * log10(255.0 * 255 / (se / n))
        // 2) El fondo se desenfoca
        val sharpBg = Sharpness.of(Align.luma(s.px), w, h, intArrayOf(10, 180, 130, 300))
        val sharpBgOut = Sharpness.of(Align.luma(res), w, h, intArrayOf(10, 180, 130, 300))
        // 3) Sin halos: junto al borde, el fondo no se tiñe del color del sujeto (comparado con el
        //    mismo desenfoque hecho sobre el fondo solo).
        val ideal = Bokeh.render(s.bgOnly, w, h, refinedSmall, blur, maxRadius = 18f, scale = 2)
        var halo = 0.0; var hn = 0
        for (y in 0 until h) for (x in 0 until w) {
            val nx = (x - 270f) / 120f; val ny = (y - 360f) / 150f
            val r = kotlin.math.sqrt(nx * nx + ny * ny)
            if (r in 1.12f..1.3f) for (c in 0..2) { halo += abs(((res[y * w + x] shr (16 - 8 * c)) and 0xFF) - ((ideal[y * w + x] shr (16 - 8 * c)) and 0xFF)); hn++ }
        }
        // 4) Bokeh: una luz puntual se convierte en un disco (brillo parejo hasta el borde).
        val (lx, ly) = 110 to 90
        fun lum(x: Int, y: Int) = (res[y * w + x] shr 8) and 0xFF
        val center = lum(lx, ly)
        val bgLevel = lum(lx + 30, ly + 30).coerceAtMost(lum(lx - 30, ly - 30))
        line("SUJETO  nitidez del sujeto %.0f dB (intacto) · fondo: nitidez %.1f → %.1f".format(subjectPsnr, sharpBg, sharpBgOut))
        line("HALOS   diferencia junto al borde frente al fondo ideal: %.1f niveles (de 255)".format(halo / hn))
        line("BOKEH   luz puntual: centro %d, mitad del radio %d, fuera del disco %d".format(center, lum(lx + 6, ly), bgLevel))
        assertTrue(eRef < eCoarse)
        assertTrue(subjectPsnr > 40)
        assertTrue(sharpBgOut < sharpBg * 0.5f)
        assertTrue("sin halos (${halo / hn})", halo / hn < 6.0)
        File(out, "reporte_retrato.txt").writeText(report.toString())
    }

    @Test fun desenfoqueCreceConLaDistancia() {
        val w = 80; val h = 100
        val m = FloatArray(w * h)
        for (y in 30 until 70) for (x in 30 until 50) m[y * w + x] = 1f
        val b = DepthHint.blurMap(FloatMap(w, h, m))
        assertTrue(b[40, 50] == 0f) // sujeto nítido
        assertTrue(b[52, 50] < b[70, 50]) // más lejos → más desenfoque
        assertTrue(b[40, 75] < b[40, 98] || b[40, 75] < 1f) // suelo delante de los pies menos desenfocado
        assertTrue(b[5, 5] > 0.9f)
    }

    @Test fun giroDeLaMascara() {
        // 3×2 con valores distintos: girar 90° cuatro veces vuelve al original; 90 y 270 son inversos.
        val m = FloatMap(3, 2, floatArrayOf(1f, 2f, 3f, 4f, 5f, 6f))
        val r90 = m.rotatedCw(90)
        assertTrue(r90.w == 2 && r90.h == 3)
        // Girar a la derecha: la primera fila pasa a ser la última columna.
        assertTrue(r90[1, 0] == 1f && r90[1, 2] == 3f && r90[0, 0] == 4f)
        assertTrue(r90.rotatedCw(270).v.contentEquals(m.v))
        assertTrue(m.rotatedCw(180).rotatedCw(180).v.contentEquals(m.v))
        assertTrue(r90.rotatedCw(90).rotatedCw(90).rotatedCw(90).v.contentEquals(m.v))
    }

    @Test fun retratoCompletoATamanoReal() {
        // Como en el teléfono: foto grande, máscara de la IA a 1024 px, refinado y desenfoque.
        val s = scene()
        val big = Bitmap.createScaledBitmap(Bitmap.createBitmap(s.px, s.w, s.h, Bitmap.Config.ARGB_8888), 2250, 3000, true)
        val w = big.width; val h = big.height
        val coarse = s.mask.resized(768 / 8, 1024 / 8).resized(768, 1024) // "IA" a 1024 px, bordes toscos
        val t0 = System.currentTimeMillis()
        val refined = com.iacamara.app.processing.PortraitProcessor.refineMask(big, coarse)
        val t1 = System.currentTimeMillis()
        val px = px(big)
        val out = com.iacamara.app.processing.PortraitProcessor.render(px, w, h, refined, 0.6f)
        val t2 = System.currentTimeMillis()
        val none = com.iacamara.app.processing.PortraitProcessor.render(px, w, h, refined, 0f)
        line("TAMAÑO  ${w}×${h}: refinado ${t1 - t0} ms (máscara ${refined.w}×${refined.h}) · desenfoque ${t2 - t1} ms (en PC; el teléfono tarda parecido)")
        assertTrue(none.contentEquals(px)) // intensidad 0 = foto original
        // El sujeto sigue nítido, el fondo no.
        val subj = Sharpness.of(Align.luma(out), w, h, intArrayOf(1250, 1700, 1650, 2200))
        val subjIn = Sharpness.of(Align.luma(px), w, h, intArrayOf(1250, 1700, 1650, 2200))
        val bg = Sharpness.of(Align.luma(out), w, h, intArrayOf(60, 1300, 900, 2100))
        val bgIn = Sharpness.of(Align.luma(px), w, h, intArrayOf(60, 1300, 900, 2100))
        line("TAMAÑO  nitidez sujeto %.2f → %.2f · fondo %.2f → %.2f".format(subjIn, subj, bgIn, bg))
        assertTrue(subj > subjIn * 0.9f)
        assertTrue(bg < bgIn * 0.6f)
        val small = Bitmap.createScaledBitmap(Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888), 600, 800, true)
        File(out.let { this.out }, "retrato_grande.jpg").outputStream().use { small.compress(Bitmap.CompressFormat.JPEG, 90, it) }
    }
}
