package com.iacamara.app.sensors

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.view.OrientationEventListener
import android.view.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * Nivel de burbuja + orientación física del teléfono.
 * La app está fija en vertical (como las cámaras profesionales) y los iconos giran solos.
 */
class DeviceSensors(context: Context) : SensorEventListener {
    private val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val gravity = sm.getDefaultSensor(Sensor.TYPE_GRAVITY) ?: sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val gyroscope = sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

    /** Velocidad de giro del teléfono (rad/s), suavizada: mide el pulso. */
    var shake by mutableFloatStateOf(0f)
        private set

    /** true si el teléfono lleva un rato totalmente quieto (trípode o apoyado). */
    var steady by mutableStateOf(false)
        private set
    private var stillSince = 0L

    /** Inclinación respecto al horizonte más cercano, en grados (-45..45). */
    var tilt by mutableFloatStateOf(0f)
        private set

    /** true si el teléfono apunta casi recto hacia abajo o hacia arriba (el nivel no aplica). */
    var isFlat by mutableStateOf(false)
        private set

    /** Rotación de la interfaz en grados (0, 90, 180, 270) para girar iconos. */
    var uiRotation by mutableIntStateOf(0)
        private set

    /** Rotación de la foto (Surface.ROTATION_*). */
    var surfaceRotation by mutableIntStateOf(Surface.ROTATION_0)
        private set

    private var smooth = 0f

    private val orientationListener = object : OrientationEventListener(context) {
        override fun onOrientationChanged(o: Int) {
            if (o == ORIENTATION_UNKNOWN) return
            val snapped = when (o) {
                in 45 until 135 -> 270
                in 135 until 225 -> 180
                in 225 until 315 -> 90
                else -> 0
            }
            if (snapped != uiRotation) {
                uiRotation = snapped
                surfaceRotation = when (snapped) {
                    90 -> Surface.ROTATION_90
                    180 -> Surface.ROTATION_180
                    270 -> Surface.ROTATION_270
                    else -> Surface.ROTATION_0
                }
            }
        }
    }

    fun start() {
        gravity?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
        gyroscope?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        if (orientationListener.canDetectOrientation()) orientationListener.enable()
    }

    fun stop() {
        sm.unregisterListener(this)
        orientationListener.disable()
    }

    override fun onSensorChanged(e: SensorEvent) {
        if (e.sensor.type == Sensor.TYPE_GYROSCOPE) {
            val w = sqrt(e.values[0] * e.values[0] + e.values[1] * e.values[1] + e.values[2] * e.values[2])
            shake += (w - shake) * 0.15f
            val now = System.currentTimeMillis()
            if (shake < 0.012f) {
                if (stillSince == 0L) stillSince = now
                if (!steady && now - stillSince > 1500) steady = true
            } else {
                stillSince = 0L
                if (steady && shake > 0.03f) steady = false
            }
            return
        }
        val (x, y, z) = Triple(e.values[0], e.values[1], e.values[2])
        val g = sqrt(x * x + y * y + z * z).coerceAtLeast(0.1f)
        isFlat = abs(z) / g > 0.85f
        val roll = Math.toDegrees(atan2(x.toDouble(), y.toDouble())).toFloat()
        // Distancia al horizonte más cercano (vertical u horizontal).
        var dev = roll
        while (dev > 45f) dev -= 90f
        while (dev < -45f) dev += 90f
        smooth += (dev - smooth) * 0.25f
        tilt = smooth
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}
