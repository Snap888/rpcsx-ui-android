package net.rpcsx.utils

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import net.rpcsx.RPCSX
import kotlin.math.PI
import kotlin.math.abs

class MotionSensorManager(private val context: Context) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val magnetometer = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)

    private val accelerometerValues = FloatArray(3)
    private val magnetometerValues = FloatArray(3)
    private val rotationMatrix = FloatArray(9)
    private val orientationValues = FloatArray(3)

    private var lastUpdate = 0L
    private val updateInterval = 16L // ~60 FPS

    private var enabled = false
    private var sensitivity = 1.0f
    private var deadZone = 0.05f

    fun start() {
        enabled = GeneralSettings["motion_sensor_enabled"] as? Boolean ?: false
        if (!enabled) return

        sensitivity = ((GeneralSettings["motion_sensitivity"] as? Int) ?: 50) / 50.0f
        deadZone = ((GeneralSettings["motion_deadzone"] as? Int) ?: 5) / 100.0f

        accelerometer?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
        magnetometer?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
    }

    fun stop() {
        sensorManager.unregisterListener(this)
        RPCSX.motionRightStickX = 128
        RPCSX.motionRightStickY = 128
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null || !enabled) return

        val currentTime = System.currentTimeMillis()
        if (currentTime - lastUpdate < updateInterval) return
        lastUpdate = currentTime

        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> System.arraycopy(event.values, 0, accelerometerValues, 0, 3)
            Sensor.TYPE_MAGNETIC_FIELD -> System.arraycopy(event.values, 0, magnetometerValues, 0, 3)
        }

        updateOrientation()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun updateOrientation() {
        val success = SensorManager.getRotationMatrix(
            rotationMatrix,
            null,
            accelerometerValues,
            magnetometerValues
        )

        if (success) {
            SensorManager.getOrientation(rotationMatrix, orientationValues)

            var rawX = ((orientationValues[2] / PI) * 127.5f + 127.5f).toInt()
            var rawY = ((-orientationValues[1] / PI) * 127.5f + 127.5f).toInt()

            rawX = ((rawX - 128) * sensitivity + 128).toInt().coerceIn(0, 255)
            rawY = ((rawY - 128) * sensitivity + 128).toInt().coerceIn(0, 255)

            if (abs(rawX - 128) < deadZone * 128) rawX = 128
            if (abs(rawY - 128) < deadZone * 128) rawY = 128

            // Обновляем глобальные переменные RPCSX
            RPCSX.motionRightStickX = rawX
            RPCSX.motionRightStickY = rawY
        }
    }
}