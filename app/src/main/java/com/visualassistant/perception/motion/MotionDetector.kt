package com.visualassistant.perception.motion

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log
import kotlin.math.sqrt

/**
 * MotionDetector tracks device acceleration using the hardware accelerometer
 * to determine whether the user is stationary (sitting/standing still) or walking/moving.
 *
 * It uses a rolling window of acceleration magnitudes to compute variance.
 * Stationary users produce near-zero variance (< 0.2 m/s^2), while walking produces
 * periodic rhythmic accelerations with variance > 0.6 m/s^2.
 */
class MotionDetector(context: Context) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val accelerometer: Sensor? = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    // Circular buffer for rolling window of magnitude samples (~1.5 seconds at SENSOR_DELAY_GAME/NORMAL)
    private val windowSize = 30
    private val samples = FloatArray(windowSize)
    private var sampleCount = 0
    private var sampleIndex = 0

    @Volatile
    var isUserMoving: Boolean = false
        private set

    // Timestamp of last detected movement to provide hysteresis (avoid jittering between walking/idle)
    private var lastMovementTimestampMs: Long = 0L
    private val movementHoldTimeMs: Long = 2000L // Stay in "moving" state for 2s after last movement surge

    private var isRunning = false

    fun start() {
        if (isRunning) return
        if (sensorManager != null && accelerometer != null) {
            sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_UI)
            isRunning = true
            Log.d(TAG, "MotionDetector started with hardware accelerometer")
        } else {
            Log.w(TAG, "Accelerometer not available on this device")
        }
    }

    fun stop() {
        if (!isRunning) return
        sensorManager?.unregisterListener(this)
        isRunning = false
        sampleCount = 0
        sampleIndex = 0
        isUserMoving = false
        Log.d(TAG, "MotionDetector stopped")
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null || event.sensor.type != Sensor.TYPE_ACCELEROMETER) return

        val x = event.values[0]
        val y = event.values[1]
        val z = event.values[2]

        // Calculate total acceleration magnitude (including gravity ~9.8 m/s^2)
        val magnitude = sqrt((x * x + y * y + z * z).toDouble()).toFloat()

        // Insert into circular buffer
        samples[sampleIndex] = magnitude
        sampleIndex = (sampleIndex + 1) % windowSize
        if (sampleCount < windowSize) {
            sampleCount++
        }

        // Only evaluate once buffer has enough samples (~0.5s of data)
        if (sampleCount >= 10) {
            var sum = 0f
            for (i in 0 until sampleCount) {
                sum += samples[i]
            }
            val mean = sum / sampleCount

            var varianceSum = 0f
            for (i in 0 until sampleCount) {
                val diff = samples[i] - mean
                varianceSum += diff * diff
            }
            val variance = varianceSum / sampleCount

            val now = System.currentTimeMillis()
            // When walking, acceleration variance typically exceeds 0.5 - 1.0 m^2/s^4
            val currentlyActive = variance > 0.5f

            if (currentlyActive) {
                lastMovementTimestampMs = now
                isUserMoving = true
            } else {
                // Hysteresis: Keep moving state active for 2 seconds before declaring idle
                isUserMoving = (now - lastMovementTimestampMs) < movementHoldTimeMs
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // No-op
    }

    /**
     * For testing or manual override
     */
    fun setManualMovingState(moving: Boolean) {
        isUserMoving = moving
        if (moving) {
            lastMovementTimestampMs = System.currentTimeMillis()
        }
    }

    companion object {
        private const val TAG = "MotionDetector"
    }
}
