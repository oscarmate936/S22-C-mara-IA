package com.iacamara.app.calib

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import com.iacamara.app.processing.NaturalProfile
import org.json.JSONArray
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.math.abs
import kotlin.math.cbrt
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Valida el perfil natural con fotos que NO se usaron para calibrarlo (FiveK, bloques t00008+):
 * se simula el "aspecto de celular" (color empujado, contraste fuerte) sobre la toma original y se
 * mide cuánto se acerca a la edición del fotógrafo profesional antes y después del perfil.
 * Solo corre si existe CALIB_DIR.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class NaturalProfileValidationTest {
    private fun lin(v: Int) = (v / 255.0).let { if (it <= 0.04045) it / 12.92 else ((it + 0.055) / 1.055).pow(2.4) }
    private fun enc(v: Double) = (if (v <= 0.0031308) v * 12.92 else 1.055 * v.coerceAtLeast(0.0).pow(1 / 2.4) - 0.055).let { (it * 255 + 0.5).toInt().coerceIn(0, 255) }

    private fun lab(p: Int): DoubleArray {
        val r = lin((p shr 16) and 0xFF); val g = lin((p shr 8) and 0xFF); val b = lin(p and 0xFF)
        fun f(t: Double) = if (t > 0.008856) cbrt(t) else 7.787 * t + 16.0 / 116
        val fx = f((0.4124 * r + 0.3576 * g + 0.1805 * b) / 0.95047); val fy = f(0.2126 * r + 0.7152 * g + 0.0722 * b); val fz = f((0.0193 * r + 0.1192 * g + 0.9505 * b) / 1.08883)
        return doubleArrayOf(116 * fy - 16, 500 * (fx - fy), 200 * (fy - fz))
    }

    private fun deltaE(a: IntArray, b: IntArray): Double {
        var s = 0.0; var n = 0
        var i = 0
        while (i < a.size) { val x = lab(a[i]); val y = lab(b[i]); s += sqrt((x[0] - y[0]).pow(2) + (x[1] - y[1]).pow(2) + (x[2] - y[2]).pow(2)); n++; i += 7 }
        return s / n
    }

    private fun px(b: Bitmap) = IntArray(b.width * b.height).also { b.getPixels(it, 0, b.width, 0, 0, b.width, b.height) }

    /** "Aspecto de celular": misma exposición que el experto, contraste en S y color ×1.4. */
    private fun phoneLook(p: IntArray, gainToExpert: Double): IntArray = IntArray(p.size) { i ->
        val q = p[i]
        var r = lin((q shr 16) and 0xFF) * gainToExpert; var g = lin((q shr 8) and 0xFF) * gainToExpert; var b = lin(q and 0xFF) * gainToExpert
        val y = 0.2126 * r + 0.7152 * g + 0.0722 * b
        r = y + (r - y) * 1.4; g = y + (g - y) * 1.4; b = y + (b - y) * 1.4
        fun s(v: Double): Int { val e = enc(v) / 255.0; val c = e + 0.25 * e * (1 - e) * (2 * e - 1) * 2; return (c * 255).toInt().coerceIn(0, 255) }
        (0xFF shl 24) or (s(r) shl 16) or (s(g) shl 8) or s(b)
    }

    private fun meanLogY(p: IntArray): Double {
        var s = 0.0; var i = 0; var n = 0
        while (i < p.size) { val q = p[i]; s += kotlin.math.ln((0.2126 * lin((q shr 16) and 0xFF) + 0.7152 * lin((q shr 8) and 0xFF) + 0.0722 * lin(q and 0xFF)).coerceAtLeast(1e-4)); n++; i += 5 }
        return s / n
    }

    private fun corr(a: IntArray, b: IntArray): Double {
        var sa = 0.0; var sb = 0.0; var saa = 0.0; var sbb = 0.0; var sab = 0.0; var n = 0; var i = 0
        while (i < a.size) { val x = ((a[i] shr 8) and 0xFF).toDouble(); val y = ((b[i] shr 8) and 0xFF).toDouble(); sa += x; sb += y; saa += x * x; sbb += y * y; sab += x * y; n++; i += 13 }
        return (sab / n - sa / n * sb / n) / sqrt((saa / n - (sa / n).pow(2)) * (sbb / n - (sb / n).pow(2)) + 1e-9)
    }

    @Test fun validarPerfilNatural() {
        val root = System.getenv("CALIB_DIR")?.let { File(it, "fivek") }
        assumeTrue(root != null && root.exists())
        val meta = HashMap<String, String>()
        root!!.listFiles { f -> f.name.endsWith("_meta.json") }!!.forEach { f ->
            val a = JSONArray(f.readText())
            for (k in 0 until a.length()) {
                val o = a.getJSONObject(k)
                val cat = when {
                    o.getString("time") == "night" -> "NIGHT"
                    o.getString("time") == "dusk" && o.getString("location") != "indoor" -> "SUNSET"
                    o.getString("location") == "indoor" -> "INDOOR"
                    o.getString("subject") == "people" -> "PORTRAIT"
                    o.getString("subject") == "nature" -> "LANDSCAPE"
                    o.getString("subject") == "man_made" -> "CITY"
                    else -> "ALL"
                }
                meta[o.getString("id")] = cat
            }
        }
        val ids = meta.keys.filter { it.substring(1, 6).toInt() >= VALIDATION_FROM }.sorted()
        class Acc { var n = 0; var deIn = 0.0; var dePhone = 0.0; var deNat = 0.0; var cExp = 0.0; var cPhone = 0.0; var cNat = 0.0; var cInNat = 0.0; var deInNat = 0.0 }
        val acc = HashMap<String, Acc>()
        for (id in ids) {
            val fe = File(root, "${id}_exp.png"); val fi = File(root, "${id}_in.png")
            if (!fe.exists() || !fi.exists()) continue
            val eb = BitmapFactory.decodeFile(fe.path) ?: continue
            var ib = BitmapFactory.decodeFile(fi.path) ?: continue
            // La original puede venir girada: se elige el giro que más se parece al experto.
            val cands = listOf(0f, 90f, 270f, 180f).map { d ->
                val r = if (d == 0f) ib else Bitmap.createBitmap(ib, 0, 0, ib.width, ib.height, Matrix().apply { postRotate(d) }, true)
                Bitmap.createScaledBitmap(r, eb.width, eb.height, true)
            }
            val e = px(eb)
            val best = cands.maxBy { corr(px(it), e) }
            if (corr(px(best), e) < 0.6) continue
            ib = best
            val inp = px(ib)
            val cat = meta.getValue(id)
            val gain = kotlin.math.exp(meanLogY(e) - meanLogY(inp))
            val phone = phoneLook(inp, gain)
            val nat = NaturalProfile.apply(phone, ib.width, ib.height, cat)
            val inNat = NaturalProfile.apply(inp, ib.width, ib.height, cat)
            // Ejemplo visual (celular · perfil natural · profesional) del primero de cada escena.
            if (cat in setOf("SUNSET", "LANDSCAPE", "PORTRAIT", "CITY") && !File(System.getenv("CALIB_DIR"), "visual_$cat.png").exists()) {
                val w = ib.width; val h = ib.height
                val o = IntArray((w * 3 + 16) * h)
                for (y in 0 until h) for ((c, src) in listOf(phone, nat, e).withIndex()) System.arraycopy(src, y * w, o, y * (w * 3 + 16) + c * (w + 8), w)
                val bmp = Bitmap.createScaledBitmap(Bitmap.createBitmap(o, w * 3 + 16, h, Bitmap.Config.ARGB_8888), 1200, (1200f * h / (w * 3 + 16)).toInt(), true)
                File(System.getenv("CALIB_DIR"), "visual_$cat.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
            for (k in listOf(cat, "TODAS")) {
                val a = acc.getOrPut(k) { Acc() }
                a.n++
                a.deIn += deltaE(inp, e); a.dePhone += deltaE(phone, e); a.deNat += deltaE(nat, e); a.deInNat += deltaE(inNat, e)
                a.cExp += NaturalProfile.chroma95(e); a.cPhone += NaturalProfile.chroma95(phone); a.cNat += NaturalProfile.chroma95(nat); a.cInNat += abs(NaturalProfile.chroma95(inNat) - NaturalProfile.chroma95(inp))
            }
        }
        val sb = StringBuilder("PERFIL NATURAL — validación con fotos de FiveK no usadas para calibrar\n")
        sb.append("escena     n   ΔE celular→experto  ΔE perfil natural→experto  color p95: experto / celular / natural\n")
        for ((k, a) in acc.toSortedMap()) {
            sb.append("%-9s %3d   %5.1f              %5.1f (%+.0f %%)             %4.0f / %4.0f / %4.0f\n".format(
                k, a.n, a.dePhone / a.n, a.deNat / a.n, (a.deNat / a.dePhone - 1) * 100, a.cExp / a.n, a.cPhone / a.n, a.cNat / a.n,
            ))
        }
        acc["TODAS"]?.let { sb.append("Sobre la toma original sin saturar: el perfil cambia el color %.1f de croma de media (casi nada) y ΔE %.1f → %.1f\n".format(it.cInNat / it.n, it.deIn / it.n, it.deInNat / it.n)) }
        println("CAL " + sb.toString().replace("\n", "\nCAL "))
        File(System.getenv("CALIB_DIR"), "perfil_natural.txt").writeText(sb.toString())
    }

    companion object {
        /** Bloques de FiveK reservados para validar (los anteriores se usan para calibrar). */
        const val VALIDATION_FROM = 8
    }
}
