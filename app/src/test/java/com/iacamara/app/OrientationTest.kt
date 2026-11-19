package com.iacamara.app

import android.graphics.Bitmap
import androidx.exifinterface.media.ExifInterface
import com.iacamara.app.processing.Editor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Las fotos se muestran derechas aunque el sensor las guarde de lado (orientación EXIF). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class OrientationTest {
    @Test fun fotoDeLadoSeMuestraDerecha() {
        // Como guarda la cámara: imagen apaisada 400×300 (sensor) con «girar 90°» en el EXIF.
        // Arriba de la escena real = lado izquierdo del sensor, que pintamos de rojo.
        val w = 400; val h = 300
        val px = IntArray(w * h) { i -> if (i % w < 40) 0xFFFF0000.toInt() else 0xFF808080.toInt() }
        val f = File.createTempFile("foto", ".jpg")
        f.outputStream().use { Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.JPEG, 95, it) }
        ExifInterface(f.absolutePath).apply { setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString()); saveAttributes() }
        val b = Editor.decodeUpright(f.readBytes(), 2048)!!
        assertEquals(300, b.width); assertEquals(400, b.height) // vertical, como se tomó
        val top = b.getPixel(150, 10)
        assertTrue("arriba debe ser rojo", (top shr 16 and 0xFF) > 200 && (top shr 8 and 0xFF) < 80)
        // Tamaño limitado sin perder la orientación.
        val s = Editor.decodeUpright(f.readBytes(), 200)!!
        assertTrue(s.height == 200 && s.width == 150)
    }
}
