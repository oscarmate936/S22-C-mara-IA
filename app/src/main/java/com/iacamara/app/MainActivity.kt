package com.iacamara.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.iacamara.app.camera.CameraEngine
import com.iacamara.app.sensors.DeviceSensors
import com.iacamara.app.ui.CameraScreen
import com.iacamara.app.ui.PermissionScreen
import com.iacamara.app.ui.theme.IACamaraTheme

object BuildConfigInfo {
    val VERSION: String get() = BuildConfig.VERSION_NAME
}

class MainActivity : ComponentActivity() {

    private var volumeShutter: (() -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // Brillo máximo en el visor, como las apps de cámara de fábrica.
        window.attributes = window.attributes.apply { screenBrightness = 1f }
        WindowCompat.getInsetsController(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.navigationBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        val engine = CameraEngine(applicationContext)
        val prefs = Prefs(applicationContext)
        val sensors = DeviceSensors(applicationContext)

        setContent {
            IACamaraTheme {
                var granted by remember { mutableStateOf(hasCameraPermission()) }
                var askedOnce by remember { mutableStateOf(false) }
                val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
                    granted = it
                    askedOnce = true
                }
                val permanentlyDenied = askedOnce && !granted &&
                    !shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)

                Crossfade(granted, label = "permission") { ok ->
                    if (ok) {
                        CameraScreen(engine, prefs, sensors, registerVolumeShutter = { volumeShutter = it })
                    } else {
                        PermissionScreen(permanentlyDenied) {
                            if (permanentlyDenied) {
                                startActivity(
                                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)),
                                )
                            } else {
                                launcher.launch(Manifest.permission.CAMERA)
                            }
                        }
                    }
                }
            }
        }
    }

    private fun hasCameraPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    /** Botones de volumen = disparador físico, como en una cámara de verdad. */
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if ((keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_UP) && volumeShutter != null) {
            if (event.repeatCount == 0) volumeShutter?.invoke()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }
}
