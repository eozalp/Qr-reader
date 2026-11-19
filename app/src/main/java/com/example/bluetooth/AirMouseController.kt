package com.example.bluetooth

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import com.example.debug.AppDebugLogger
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
    private val biasEstimator = GyroBiasEstimator()

    fun start() {
        val sm = sensorManager ?: return
        val gyro = gyroSensor ?: return
        if (isRunning) return

        lastGyroTimestampNs = 0L
        accumX = 0f
        accumY = 0f
        biasEstimator.reset()
        isRunning = true

        val samplingUs = 10_000
        runCatching {
            gravitySensor?.let { sm.registerListener(this, it, samplingUs) }
            accelSensor?.let { sm.registerListener(this, it, samplingUs) }
            val registered = sm.registerListener(this, gyro, samplingUs)
            if (!registered) {
                sm.registerListener(this, gyro, SensorManager.SENSOR_DELAY_GAME)
            }
            AppDebugLogger.d("BT-MOUSE", "Air Mouse sensörleri başlatıldı (gyro=${gyro.name}, gravity=${gravitySensor != null})")
        }.onFailure { err ->
            isRunning = false
            AppDebugLogger.e("BT-MOUSE", "Air Mouse sensör başlatma hatası", err)
        }
    }

    fun stop() {
        if (!isRunning) return
        isRunning = false
        lastGyroTimestampNs = 0L
        accumX = 0f
        accumY = 0f
        runCatching { sensorManager?.unregisterListener(this) }
            .onFailure { err -> AppDebugLogger.w("BT-MOUSE", "Air Mouse sensör durdurma hatası", err) }
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
                val (cleanWx, cleanWy, cleanWz) = biasEstimator.updateAndCompensate(
                    event.values[0],
                    event.values[1],
                    event.values[2]
                )
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
                    wx = cleanWx,
                    wy = cleanWy,
                    wz = cleanWz,
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

/**
 * Estimates and cancels hardware gyroscope DC bias whenever the device is held still,
 * eliminating continuous cursor drift without attenuating intentional wrist panning.
 */
class GyroBiasEstimator {
    var biasX: Float = 0f
        private set
    var biasY: Float = 0f
        private set
    var biasZ: Float = 0f
        private set
    var stationarySamples: Int = 0
        private set

    fun reset() {
        biasX = 0f
        biasY = 0f
        biasZ = 0f
        stationarySamples = 0
    }

    fun updateAndCompensate(wx: Float, wy: Float, wz: Float): Triple<Float, Float, Float> {
        val rawMag = sqrt(wx * wx + wy * wy + wz * wz)
        if (rawMag < STILL_THRESHOLD_RAD_S) {
            stationarySamples++
            val alpha = if (stationarySamples <= INITIAL_FAST_SAMPLES) 0.14f else 0.025f
            biasX += alpha * (wx - biasX)
            biasY += alpha * (wy - biasY)
            biasZ += alpha * (wz - biasZ)
        }
        return Triple(wx - biasX, wy - biasY, wz - biasZ)
    }

    companion object {
        private const val STILL_THRESHOLD_RAD_S = 0.12f
        private const val INITIAL_FAST_SAMPLES = 25
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
