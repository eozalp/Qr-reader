package com.example.bluetooth

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.abs
import kotlin.math.sign
import kotlin.math.sqrt

/**
 * Sensor-fused Gyroscope Air Mouse engine.
 *
 * Uses `TYPE_GYROSCOPE` angular velocity projected against the `TYPE_GRAVITY` (or low-pass
 * accelerometer) vector so left/right wrist panning always maps cleanly to PC horizontal cursor
 * motion regardless of whether the phone is held flat, tilted at 45°, or upright.
 */
class AirMouseController(
    context: Context,
    private val onCursorDelta: (dx: Int, dy: Int) -> Unit
) : SensorEventListener {

    private val sensorManager: SensorManager? =
        context.applicationContext.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val gyroSensor: Sensor? = sensorManager?.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private val gravitySensor: Sensor? = sensorManager?.getDefaultSensor(Sensor.TYPE_GRAVITY)
    private val accelSensor: Sensor? =
        if (gravitySensor == null) sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) else null

    val isGyroSupported: Boolean = gyroSensor != null

    @Volatile
    var isRunning: Boolean = false
        private set

    @Volatile
    var isClutchPaused: Boolean = false

    @Volatile
    var sensitivity: Float = 1.25f

    private var gx: Float = 0f
    private var gy: Float = 5.5f
    private var gz: Float = 8.1f
    private var hasGravity: Boolean = false

    private var lastGyroTimestampNs: Long = 0L
    private var accumX: Float = 0f
    private var accumY: Float = 0f

    fun start() {
        val sm = sensorManager ?: return
        val gyro = gyroSensor ?: return
        if (isRunning) return

        lastGyroTimestampNs = 0L
        accumX = 0f
        accumY = 0f
        isRunning = true

        gravitySensor?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        accelSensor?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        sm.registerListener(this, gyro, SensorManager.SENSOR_DELAY_GAME)
    }

    fun stop() {
        if (!isRunning) return
        isRunning = false
        lastGyroTimestampNs = 0L
        accumX = 0f
        accumY = 0f
        runCatching { sensorManager?.unregisterListener(this) }
    }

    override fun onSensorChanged(event: SensorEvent?) {
        event ?: return
        when (event.sensor.type) {
            Sensor.TYPE_GRAVITY -> {
                gx = event.values[0]
                gy = event.values[1]
                gz = event.values[2]
                hasGravity = true
            }
            Sensor.TYPE_ACCELEROMETER -> {
                if (!hasGravity) {
                    // Initial seed or gentle low-pass if dedicated gravity sensor is absent
                    val alpha = 0.85f
                    gx = alpha * gx + (1f - alpha) * event.values[0]
                    gy = alpha * gy + (1f - alpha) * event.values[1]
                    gz = alpha * gz + (1f - alpha) * event.values[2]
                }
            }
            Sensor.TYPE_GYROSCOPE -> {
                if (!isRunning || isClutchPaused) {
                    lastGyroTimestampNs = event.timestamp
                    accumX = 0f
                    accumY = 0f
                    return
                }
                val prevTs = lastGyroTimestampNs
                lastGyroTimestampNs = event.timestamp
                if (prevTs == 0L) return

                val dtSec = ((event.timestamp - prevTs) / 1_000_000_000f).coerceIn(0.001f, 0.05f)
                val step = AirMouseMath.accumulateStep(
                    wx = event.values[0],
                    wy = event.values[1],
                    wz = event.values[2],
                    gx = gx,
                    gy = gy,
                    gz = gz,
                    dtSec = dtSec,
                    sensitivity = sensitivity,
                    prevAccumX = accumX,
                    prevAccumY = accumY
                )
                accumX = step.nextAccumX
                accumY = step.nextAccumY
                if (step.dx != 0 || step.dy != 0) {
                    onCursorDelta(step.dx, step.dy)
                }
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // No-op
    }
}

data class AirMouseStepResult(
    val dx: Int,
    val dy: Int,
    val nextAccumX: Float,
    val nextAccumY: Float
)

object AirMouseMath {
    private const val DEFAULT_DEADZONE_RAD_S = 0.045f
    private const val BASE_PIXELS_PER_RADIAN = 900f

    fun applySoftDeadzone(rateRadPerSec: Float, deadzone: Float = DEFAULT_DEADZONE_RAD_S): Float {
        val mag = abs(rateRadPerSec)
        return if (mag <= deadzone) 0f else sign(rateRadPerSec) * (mag - deadzone)
    }

    fun accumulateStep(
        wx: Float,
        wy: Float,
        wz: Float,
        gx: Float,
        gy: Float,
        gz: Float,
        dtSec: Float,
        sensitivity: Float,
        prevAccumX: Float,
        prevAccumY: Float,
        deadzone: Float = DEFAULT_DEADZONE_RAD_S
    ): AirMouseStepResult {
        val gNorm = sqrt(gx * gx + gy * gy + gz * gz)
        val (nx, ny, nz) = if (gNorm > 0.5f) {
            Triple(gx / gNorm, gy / gNorm, gz / gNorm)
        } else {
            // Default natural ~35° palm hold if gravity hasn't converged yet
            Triple(0f, 0.57f, 0.82f)
        }

        // World-vertical yaw projection: panning right -> negative angular velocity around +g -> +dx
        val rawYaw = -(wx * nx + wy * ny + wz * nz)
        // Device pitch around X: tilting top edge downward -> negative wx -> +dy
        val rawPitch = -wx

        val cleanYaw = applySoftDeadzone(rawYaw, deadzone)
        val cleanPitch = applySoftDeadzone(rawPitch, deadzone)

        val gain = BASE_PIXELS_PER_RADIAN * sensitivity.coerceIn(0.3f, 3.0f)
        val totalX = prevAccumX + cleanYaw * dtSec * gain
        val totalY = prevAccumY + cleanPitch * dtSec * gain

        val dx = totalX.toInt().coerceIn(-127, 127)
        val dy = totalY.toInt().coerceIn(-127, 127)

        return AirMouseStepResult(
            dx = dx,
            dy = dy,
            nextAccumX = totalX - dx,
            nextAccumY = totalY - dy
        )
    }
}
