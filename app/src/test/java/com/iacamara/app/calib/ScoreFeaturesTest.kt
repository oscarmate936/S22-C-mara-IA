package com.iacamara.app.calib

import android.graphics.BitmapFactory
import com.iacamara.app.ai.PhotoScore
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Mide con el código de la app (PhotoScore.measure) las fotos de KonIQ-10k, que tienen la nota que
 * les dieron cientos de personas. tools/calibracion/ajustar_nota.py ajusta después los pesos para
 * que la nota de la app se parezca lo más posible a la humana. Solo corre si existe CALIB_DIR.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class ScoreFeaturesTest {
    @Test fun medirKonIq() {
        val root = System.getenv("CALIB_DIR")?.let { File(it) }
        assumeTrue(root != null && File(root, "koniq/1024x768").exists())
        val meta = File(root, "dl/koniq_meta.csv").readLines().drop(1).map { it.split(',') }
        val pick = meta.filter { it.last() == "training" }.take(3000) + meta.filter { it.last() == "test" }.take(1000)
        val out = StringBuilder("image,set,mos,sharp,noise,clipped,crushed,mean,tilt\n")
        var n = 0
        for (row in pick) {
            val f = File(root, "koniq/1024x768/${row[0]}")
            val b = BitmapFactory.decodeFile(f.path) ?: continue
            val w = b.width; val h = b.height
            val px = IntArray(w * h).also { b.getPixels(it, 0, w, 0, 0, w, h) }
            val m = PhotoScore.measure(px, w, h)
            out.append("${row[0]},${row.last()},${row[7]},${m.sharpness},${m.noise},${m.clipped},${m.crushed},${m.meanLuma},${m.tiltDeg ?: "nan"}\n")
            if (++n % 500 == 0) println("CAL medidas $n")
        }
        File(root, "koniq_features.csv").writeText(out.toString())
        println("CAL listo: $n fotos")
    }
}
