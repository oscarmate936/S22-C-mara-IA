package com.iacamara.app

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import com.iacamara.app.camera.CameraEngine
import com.iacamara.app.sensors.DeviceSensors
import com.iacamara.app.ui.CameraScreen
import com.iacamara.app.ui.GalleryPhoto
import com.iacamara.app.ui.PermissionScreen
import com.iacamara.app.ui.ViewerScreen
import com.iacamara.app.ui.theme.IACamaraTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Genera capturas de la interfaz en build/screenshots/ para revisar el diseño. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-420dpi")
class ScreenshotTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private fun save(name: String) {
        val view = rule.activity.window.decorView
        val bmp = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        rule.runOnUiThread { view.draw(android.graphics.Canvas(bmp)) }
        val dir = File("build/screenshots").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun permission() {
        rule.activity.actionBar?.hide(); rule.setContent { IACamaraTheme { PermissionScreen(false) {} } }
        rule.waitForIdle(); save("1_bienvenida")
    }

    private fun camera(pro: Boolean, name: String, landscape: Boolean = false) {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val engine = CameraEngine(ctx)
        rule.activity.actionBar?.hide(); rule.setContent { IACamaraTheme { CameraScreen(engine, Prefs(ctx), DeviceSensors(ctx)) {} } }
        rule.waitForIdle()
        rule.runOnIdle { engine.loadDemoState(pro, landscape) }
        rule.mainClock.advanceTimeBy(1500)
        rule.waitForIdle(); save(name)
    }

    @Test fun cameraAuto() = camera(false, "2_auto")
    @Test fun cameraPro() = camera(true, "3_pro")
    @Test fun cameraHorizon() = camera(false, "4_horizonte", landscape = true)

    @Test fun viewer() {
        val photo = android.graphics.BitmapFactory.decodeStream(javaClass.classLoader!!.getResourceAsStream("sim/chelsea.png"))!!
        val photos = listOf(
            GalleryPhoto(android.net.Uri.parse("content://demo/1"), "IACAM_20261119_181500_RETRATO.jpg", 1795630500000L),
            GalleryPhoto(android.net.Uri.parse("content://demo/2"), "IACAM_20261119_181000_NOCHE.jpg", 1795630200000L),
        )
        rule.activity.actionBar?.hide()
        rule.setContent { IACamaraTheme { ViewerScreen({}, photosOverride = photos, bitmapOverride = { photo }, portraitOverride = { 0.6f }) } }
        rule.mainClock.advanceTimeBy(800)
        rule.waitForIdle(); save("5_visor_retrato")
    }
}
