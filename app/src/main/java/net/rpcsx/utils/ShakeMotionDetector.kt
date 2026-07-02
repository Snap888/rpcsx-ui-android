package net.rpcsx.utils

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper
import kotlin.math.sqrt

class ShakeMotionDetector(
    private val context: Context,
    private val onShakeCallback: (Boolean) -> Unit
) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    
    private var shakeThreshold = 15.0f
    private val debounceTime = 300L
    private var lastShakeTime = 0L
    private val handler = Handler(Looper.getMainLooper())
    
    private var isRegistered = false

    fun start() {
        val enabled = (GeneralSettings["shake_enabled"] as? Boolean) ?: false
        if (!enabled) return
        
        shakeThreshold = ((GeneralSettings["shake_sensitivity"] as? Int) ?: 15).toFloat()
        
        if (!isRegistered && accelerometer != null) {
            sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_GAME)
            isRegistered = true
        }
    }

    fun stop() {
        if (isRegistered) {
            sensorManager.unregisterListener(this)
            isRegistered = false
        }
        handler.removeCallbacksAndMessages(null)
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type == Sensor.TYPE_ACCELEROMETER) {
            val x = event.values[0]
            val y = event.values[1]
            val z = event.values[2]

            val acceleration = sqrt(x * x + y * y + z * z) - SensorManager.GRAVITY_EARTH
            val currentTime = System.currentTimeMillis()
            
            if (acceleration > shakeThreshold && (currentTime - lastShakeTime) > debounceTime) {
                lastShakeTime = currentTime
                triggerShake()
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun triggerShake() {
        onShakeCallback(true)
        
        val pressDuration = ((GeneralSettings["shake_press_duration"] as? Int) ?: 100).toLong()
        handler.postDelayed({
            onShakeCallback(false)
        }, pressDuration)
    }
}