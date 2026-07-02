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
    
    // Фильтрованные значения для отделения тряски от наклона
    private val rawAccelerometerValues = FloatArray(3)
    private val filteredAccelerometerValues = FloatArray(3)
    
    private var shakeThreshold = 15.0f
    private val debounceTime = 500L // Увеличено для предотвращения ложных срабатываний
    private var lastShakeTime = 0L
    private val handler = Handler(Looper.getMainLooper())
    
    private var isRegistered = false
    private var isShaking = false
    
    // Low-pass filter для отделения гравитации от тряски
    private val gravityAlpha = 0.8f // Высокое значение = сильная фильтрация гравитации

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
        isShaking = false
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type == Sensor.TYPE_ACCELEROMETER) {
            // Сохраняем сырые значения
            rawAccelerometerValues[0] = event.values[0]
            rawAccelerometerValues[1] = event.values[1]
            rawAccelerometerValues[2] = event.values[2]
            
            // Low-pass filter для выделения гравитации
            filteredAccelerometerValues[0] = gravityAlpha * filteredAccelerometerValues[0] + (1 - gravityAlpha) * rawAccelerometerValues[0]
            filteredAccelerometerValues[1] = gravityAlpha * filteredAccelerometerValues[1] + (1 - gravityAlpha) * rawAccelerometerValues[1]
            filteredAccelerometerValues[2] = gravityAlpha * filteredAccelerometerValues[2] + (1 - gravityAlpha) * rawAccelerometerValues[2]
            
            // Вычисляем линейное ускорение (тряска) путем вычитания гравитации
            val linearX = rawAccelerometerValues[0] - filteredAccelerometerValues[0]
            val linearY = rawAccelerometerValues[1] - filteredAccelerometerValues[1]
            val linearZ = rawAccelerometerValues[2] - filteredAccelerometerValues[2]
            
            val linearAcceleration = sqrt(linearX * linearX + linearY * linearY + linearZ * linearZ)
            
            val currentTime = System.currentTimeMillis()
            
            // Проверяем только линейное ускорение (тряску), игнорируя гравитацию
            if (linearAcceleration > shakeThreshold && (currentTime - lastShakeTime) > debounceTime) {
                lastShakeTime = currentTime
                triggerShake()
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun triggerShake() {
        if (isShaking) return
        isShaking = true
        
        onShakeCallback(true)
        
        val pressDuration = ((GeneralSettings["shake_press_duration"] as? Int) ?: 100).toLong()
        handler.postDelayed({
            onShakeCallback(false)
            isShaking = false
        }, pressDuration)
    }
}