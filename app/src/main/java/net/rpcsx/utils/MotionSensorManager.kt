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
    private val gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

    private val accelerometerValues = FloatArray(3)
    private val gyroscopeValues = FloatArray(3)
    
    private var integratedPitch = 0f
    private var integratedRoll = 0f
    
    private var accelPitch = 0f
    private var accelRoll = 0f
    
    private var pitchOffset = 0f
    private var rollOffset = 0f
    private var calibrationSamples = 0
    private var calibrationSumPitch = 0f
    private var calibrationSumRoll = 0f
    private var isCalibrated = false
    
    private var lastTimestamp = 0L
    
    private var enabled = false
    private var sensitivity = 1.0f
    private var deadZone = 0.05f
    private var targetStick = 1
    
    private val gyroDriftCorrection = 0.02f
    private val maxAngle = PI.toFloat() / 2f
    private val gyroDeadZone = 0.01f

    fun start() {
        enabled = GeneralSettings["motion_sensor_enabled"] as? Boolean ?: false
        if (!enabled) return

        sensitivity = ((GeneralSettings["motion_sensitivity"] as? Int) ?: 50) / 50.0f
        deadZone = ((GeneralSettings["motion_deadzone"] as? Int) ?: 5) / 100.0f
        targetStick = GeneralSettings["motion_target_stick"] as? Int ?: 1
        
        integratedPitch = 0f
        integratedRoll = 0f
        calibrationSamples = 0
        calibrationSumPitch = 0f
        calibrationSumRoll = 0f
        isCalibrated = false
        lastTimestamp = 0L
        
        startCalibration()

        accelerometer?.let {
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
    }
    
    private fun startCalibration() {
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            if (calibrationSamples > 10) {
                pitchOffset = calibrationSumPitch / calibrationSamples
                rollOffset = calibrationSumRoll / calibrationSamples
                isCalibrated = true
            }
        }, 1500)
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null || !enabled) return

        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                val alpha = 0.8f
                accelerometerValues[0] = alpha * accelerometerValues[0] + (1 - alpha) * event.values[0]
                accelerometerValues[1] = alpha * accelerometerValues[1] + (1 - alpha) * event.values[1]
                accelerometerValues[2] = alpha * accelerometerValues[2] + (1 - alpha) * event.values[2]
                
                val ax = accelerometerValues[0]
                val ay = accelerometerValues[1]
                val az = accelerometerValues[2]
                
                accelPitch = kotlin.math.atan2(-ax, kotlin.math.sqrt(ay * ay + az * az))
                accelRoll = kotlin.math.atan2(ay, az)
                
                if (!isCalibrated) {
                    calibrationSumPitch += accelPitch
                    calibrationSumRoll += accelRoll
                    calibrationSamples++
                }
            }
            Sensor.TYPE_GYROSCOPE -> {
                val currentTime = event.timestamp / 1_000_000L
                if (lastTimestamp == 0L) {
                    lastTimestamp = currentTime
                    return
                }
                val dt = (currentTime - lastTimestamp) / 1000f
                lastTimestamp = currentTime
                
                val gyroX = if (abs(event.values[0]) > gyroDeadZone) event.values[0] else 0f
                val gyroY = if (abs(event.values[1]) > gyroDeadZone) event.values[1] else 0f
                
                integratedPitch += gyroY * dt
                integratedRoll += gyroX * dt
                
                if (isCalibrated) {
                    val correctedAccelPitch = accelPitch - pitchOffset
                    val correctedAccelRoll = accelRoll - rollOffset
                    
                    integratedPitch = (1 - gyroDriftCorrection) * integratedPitch + gyroDriftCorrection * correctedAccelPitch
                    integratedRoll = (1 - gyroDriftCorrection) * integratedRoll + gyroDriftCorrection * correctedAccelRoll
                }
                
                integratedPitch = integratedPitch.coerceIn(-maxAngle, maxAngle)
                integratedRoll = integratedRoll.coerceIn(-maxAngle, maxAngle)
                
                updateStickPosition()
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun updateStickPosition() {
        var rawX = ((integratedRoll / maxAngle) * 127.5f + 127.5f).toInt()
        var rawY = ((-integratedPitch / maxAngle) * 127.5f + 127.5f).toInt()

        rawX = applyNonLinearSensitivity(rawX, sensitivity)
        rawY = applyNonLinearSensitivity(rawY, sensitivity)

        rawX = applySmoothDeadZone(rawX, deadZone)
        rawY = applySmoothDeadZone(rawY, deadZone)

        rawX = rawX.coerceIn(0, 255)
        rawY = rawY.coerceIn(0, 255)

        if (targetStick == 0) {
            RPCSX.motionLeftStickX = rawX
            RPCSX.motionLeftStickY = rawY
        } else {
            RPCSX.motionRightStickX = rawX
            RPCSX.motionRightStickY = rawY
        }
    }
    
    private fun applyNonLinearSensitivity(value: Int, sensitivity: Float): Int {
        val normalized = (value - 128) / 127.5f
        val exponent = 1.0f / (sensitivity.coerceIn(0.2f, 3.0f))
        val eased = sign(normalized) * kotlin.math.pow(abs(normalized), exponent)
        return (eased * 127.5f + 128).toInt()
    }
    
    private fun applySmoothDeadZone(value: Int, deadZone: Float): Int {
        val center = 128
        val distance = value - center
        val deadZoneSize = (deadZone * 128).toInt()
        
        if (abs(distance) < deadZoneSize) {
            return center
        }
        
        val sign = sign(distance.toFloat())
        val adjustedDistance = abs(distance) - deadZoneSize
        val maxDistance = 128 - deadZoneSize
        
        return (center + sign * (adjustedDistance.toFloat() / maxDistance) * (128 - deadZoneSize)).toInt()
    }
}