package net.rpcsx.utils

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import net.rpcsx.RPCSX
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sign

class MotionSensorManager(private val context: Context) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val magnetometer = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
    private val gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

    private val accelerometerValues = FloatArray(3)
    private val magnetometerValues = FloatArray(3)
    private val gyroscopeValues = FloatArray(3)
    private val rotationMatrix = FloatArray(9)
    private val orientationValues = FloatArray(3)
    
    // Фильтрованные значения для плавности
    private var filteredPitch = 0f
    private var filteredRoll = 0f
    
    // Калибровка дрейфа
    private var pitchOffset = 0f
    private var rollOffset = 0f
    private var isCalibrated = false
    
    // Low-pass filter coefficients
    private val alpha = 0.15f // Чем меньше, тем плавнее (0.1-0.2 оптимально)
    
    private var lastUpdate = 0L
    private val updateInterval = 16L // ~60 FPS

    private var enabled = false
    private var sensitivity = 1.0f
    private var deadZone = 0.05f
    private var targetStick = 1 // 0 = left, 1 = right

    fun start() {
        enabled = GeneralSettings["motion_sensor_enabled"] as? Boolean ?: false
        if (!enabled) return

        sensitivity = ((GeneralSettings["motion_sensitivity"] as? Int) ?: 50) / 50.0f
        deadZone = ((GeneralSettings["motion_deadzone"] as? Int) ?: 5) / 100.0f
        targetStick = GeneralSettings["motion_target_stick"] as? Int ?: 1
        
        // Калибровка при старте
        calibrate()

        accelerometer?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
        magnetometer?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
        gyroscope?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
    }

    fun stop() {
        sensorManager.unregisterListener(this)
        RPCSX.motionLeftStickX = 128
        RPCSX.motionLeftStickY = 128
        RPCSX.motionRightStickX = 128
        RPCSX.motionRightStickY = 128
        isCalibrated = false
    }
    
    private fun calibrate() {
        // Собираем средние значения за 1 секунду для компенсации дрейфа
        var sumPitch = 0f
        var sumRoll = 0f
        var count = 0
        
        val calibrateRunnable = object : Runnable {
            override fun run() {
                if (count < 60) { // 60 samples = 1 second at 60 FPS
                    sumPitch += orientationValues[1]
                    sumRoll += orientationValues[2]
                    count++
                    accelerometer?.let {
                        sensorManager.registerListener(this@MotionSensorManager, it, SensorManager.SENSOR_DELAY_GAME)
                    }
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(this, 16)
                } else {
                    pitchOffset = sumPitch / count
                    rollOffset = sumRoll / count
                    isCalibrated = true
                }
            }
        }
        
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(calibrateRunnable, 100)
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null || !enabled) return

        val currentTime = System.currentTimeMillis()
        if (currentTime - lastUpdate < updateInterval) return
        lastUpdate = currentTime

        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                // Low-pass filter для сглаживания
                accelerometerValues[0] = alpha * event.values[0] + (1 - alpha) * accelerometerValues[0]
                accelerometerValues[1] = alpha * event.values[1] + (1 - alpha) * accelerometerValues[1]
                accelerometerValues[2] = alpha * event.values[2] + (1 - alpha) * accelerometerValues[2]
            }
            Sensor.TYPE_MAGNETIC_FIELD -> {
                magnetometerValues[0] = alpha * event.values[0] + (1 - alpha) * magnetometerValues[0]
                magnetometerValues[1] = alpha * event.values[1] + (1 - alpha) * magnetometerValues[1]
                magnetometerValues[2] = alpha * event.values[2] + (1 - alpha) * magnetometerValues[2]
            }
            Sensor.TYPE_GYROSCOPE -> {
                gyroscopeValues[0] = event.values[0]
                gyroscopeValues[1] = event.values[1]
                gyroscopeValues[2] = event.values[2]
            }
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

            // Применяем калибровку дрейфа
            val rawPitch = if (isCalibrated) orientationValues[1] - pitchOffset else orientationValues[1]
            val rawRoll = if (isCalibrated) orientationValues[2] - rollOffset else orientationValues[2]
            
            // Low-pass filter для плавности
            filteredPitch = alpha * rawPitch + (1 - alpha) * filteredPitch
            filteredRoll = alpha * rawRoll + (1 - alpha) * filteredRoll

            // Преобразуем в значения стика (0-255)
            var rawX = ((filteredRoll / PI) * 127.5f + 127.5f).toInt()
            var rawY = ((-filteredPitch / PI) * 127.5f + 127.5f).toInt()

            // Применяем нелинейную чувствительность (ease-in curve)
            rawX = applyNonLinearSensitivity(rawX, sensitivity)
            rawY = applyNonLinearSensitivity(rawY, sensitivity)

            // Применяем улучшенную мертвую зону с плавным переходом
            rawX = applySmoothDeadZone(rawX, deadZone)
            rawY = applySmoothDeadZone(rawY, deadZone)

            // Ограничиваем диапазон
            rawX = rawX.coerceIn(0, 255)
            rawY = rawY.coerceIn(0, 255)

            // Обновляем нужный стик
            if (targetStick == 0) {
                RPCSX.motionLeftStickX = rawX
                RPCSX.motionLeftStickY = rawY
            } else {
                RPCSX.motionRightStickX = rawX
                RPCSX.motionRightStickY = rawY
            }
        }
    }
    
    private fun applyNonLinearSensitivity(value: Int, sensitivity: Float): Int {
        // Нормализуем к -1..1
        val normalized = (value - 128) / 127.5f
        
        // Применяем ease-in кривую для более точного управления в центре
        val eased = sign(normalized) * kotlin.math.pow(abs(normalized), 1.0f / sensitivity)
        
        // Возвращаем к диапазону 0-255
        return (eased * 127.5f + 128).toInt()
    }
    
    private fun applySmoothDeadZone(value: Int, deadZone: Float): Int {
        val center = 128
        val distance = value - center
        val deadZoneSize = deadZone * 128
        
        if (abs(distance) < deadZoneSize) {
            return center
        }
        
        // Плавный переход после мертвой зоны
        val sign = sign(distance.toFloat())
        val adjustedDistance = abs(distance) - deadZoneSize
        val maxDistance = 128 - deadZoneSize
        
        return (center + sign * (adjustedDistance / maxDistance) * (128 - deadZoneSize)).toInt()
    }
}