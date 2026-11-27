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
    private val yawFilter = OneEuroFilter(minCutoffHz = 2.2f, beta = 0.08f, dCutoffHz = 1.0f)
    private val pitchFilter = OneEuroFilter(minCutoffHz = 2.2f, beta = 0.08f, dCutoffHz = 1.0f)

    fun recalibrate() {
        lastGyroTimestampNs = 0L
        accumX = 0f
        accumY = 0f
        biasEstimator.reset()
        yawFilter.reset()
        pitchFilter.reset()
        AppDebugLogger.i("BT-MOUSE", "Air Mouse jiroskop ve 1€ filtresi yeniden kalibre edildi")
    }

    private val stepScratch = FloatArray(4) // [dx, dy, nextAccumX, nextAccumY]

    fun start() {
        val sm = sensorManager ?: return
        val gyro = gyroSensor ?: return
        if (isRunning) return

        recalibrate()
        isRunning = true

        val gyroSamplingUs = 10_000        // 100Hz crisp angular velocity
        val gravitySamplingUs = 50_000     // 20Hz palm tilt vector (80% fewer gravity wakeups, zero latency impact)
        runCatching {
            gravitySensor?.let { sm.registerListener(this, it, gravitySamplingUs) }
            accelSensor?.let { sm.registerListener(this, it, gravitySamplingUs) }
            val registered = sm.registerListener(this, gyro, gyroSamplingUs)
            if (!registered) {
                sm.registerListener(this, gyro, SensorManager.SENSOR_DELAY_GAME)
            }
            AppDebugLogger.d("BT-MOUSE", "Air Mouse sensörleri başlatıldı (gyro=100Hz, gravity=20Hz, hasGravity=${gravitySensor != null})")
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
        yawFilter.reset()
        pitchFilter.reset()
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
                val rawWx = event.values[0]
                val rawWy = event.values[1]
                val rawWz = event.values[2]
                biasEstimator.updateBias(rawWx, rawWy, rawWz)
                val cleanWx = rawWx - biasEstimator.biasX
                val cleanWy = rawWy - biasEstimator.biasY
                val cleanWz = rawWz - biasEstimator.biasZ

                if (!isRunning || isClutchPaused) {
                    lastGyroTimestampNs = event.timestamp
                    accumX = 0f
                    accumY = 0f
                    yawFilter.reset()
                    pitchFilter.reset()
                    return
                }
                val prevTs = lastGyroTimestampNs
                lastGyroTimestampNs = event.timestamp
                if (prevTs == 0L) return

                val dtSec = ((event.timestamp - prevTs) / 1_000_000_000f).coerceIn(0.001f, 0.05f)
                AirMouseMath.accumulateStepInto(
                    wx = cleanWx,
                    wy = cleanWy,
                    wz = cleanWz,
                    gx = gx,
                    gy = gy,
                    gz = gz,
                    dtSec = dtSec,
                    sensitivity = sensitivity,
                    prevAccumX = accumX,
                    prevAccumY = accumY,
                    yawFilter = yawFilter,
                    pitchFilter = pitchFilter,
                    out = stepScratch
                )
                val dx = stepScratch[0].toInt()
                val dy = stepScratch[1].toInt()
                accumX = stepScratch[2]
                accumY = stepScratch[3]
                if (dx != 0 || dy != 0) {
                    onCursorDelta(dx, dy)
                }
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // No-op
    }
}

/**
 * 1€ (One Euro) adaptive low-pass filter (Casiez et al., CHI 2012).
 * Heavily dampens hand tremor / micro-jitter at low speeds while raising the cutoff
 * frequency dynamically during fast wrist flicks for near-zero cursor lag.
 */
class OneEuroFilter(
    private val minCutoffHz: Float = 2.2f,
    private val beta: Float = 0.08f,
    private val dCutoffHz: Float = 1.0f
) {
    private var hasPrev: Boolean = false
    private var prevFilteredValue: Float = 0f
    private var prevFilteredDerivative: Float = 0f

    fun reset() {
        hasPrev = false
        prevFilteredValue = 0f
        prevFilteredDerivative = 0f
    }

    fun filter(rawValue: Float, dtSec: Float): Float {
        val safeDt = dtSec.coerceIn(0.001f, 0.1f)
        if (!hasPrev) {
            hasPrev = true
            prevFilteredValue = rawValue
            prevFilteredDerivative = 0f
            return rawValue
        }

        val rawDerivative = (rawValue - prevFilteredValue) / safeDt
        val dAlpha = computeAlpha(dCutoffHz, safeDt)
        val smoothedDerivative = prevFilteredDerivative + dAlpha * (rawDerivative - prevFilteredDerivative)
        prevFilteredDerivative = smoothedDerivative

        val dynamicCutoff = minCutoffHz + beta * abs(smoothedDerivative)
        val alpha = computeAlpha(dynamicCutoff, safeDt)
        val filtered = prevFilteredValue + alpha * (rawValue - prevFilteredValue)
        prevFilteredValue = filtered
        return filtered
    }

    private fun computeAlpha(cutoffHz: Float, dtSec: Float): Float {
        val safeCutoff = cutoffHz.coerceAtLeast(0.05f)
        val tau = 1.0f / (2.0f * kotlin.math.PI.toFloat() * safeCutoff)
        return 1.0f / (1.0f + tau / dtSec)
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

    fun updateBias(wx: Float, wy: Float, wz: Float) {
        val rawMag = sqrt(wx * wx + wy * wy + wz * wz)
        if (rawMag < STILL_THRESHOLD_RAD_S) {
            stationarySamples++
            val alpha = if (stationarySamples <= INITIAL_FAST_SAMPLES) 0.14f else 0.025f
            biasX += alpha * (wx - biasX)
            biasY += alpha * (wy - biasY)
            biasZ += alpha * (wz - biasZ)
        }
    }

    fun updateAndCompensate(wx: Float, wy: Float, wz: Float): Triple<Float, Float, Float> {
        updateBias(wx, wy, wz)
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

    /**
     * Zero-allocation fast path used at 100Hz in [AirMouseController.onSensorChanged].
     * Writes `[dx.toFloat(), dy.toFloat(), nextAccumX, nextAccumY]` into [out].
     */
    fun accumulateStepInto(
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
        out: FloatArray,
        deadzone: Float = DEFAULT_DEADZONE_RAD_S,
        yawFilter: OneEuroFilter? = null,
        pitchFilter: OneEuroFilter? = null
    ) {
        val gNorm = sqrt(gx * gx + gy * gy + gz * gz)
        val nx: Float
        val ny: Float
        val nz: Float
        if (gNorm > 0.5f) {
            val invNorm = 1f / gNorm
            nx = gx * invNorm
            ny = gy * invNorm
            nz = gz * invNorm
        } else {
            // Default natural ~35° palm hold if gravity hasn't converged yet
            nx = 0f
            ny = 0.57f
            nz = 0.82f
        }

        // World-vertical yaw projection: panning right -> negative angular velocity around +g -> +dx
        val rawYaw = -(wx * nx + wy * ny + wz * nz)
        // Device pitch around X: tilting top edge downward -> negative wx -> +dy
        val rawPitch = -wx

        val filteredYaw = yawFilter?.filter(rawYaw, dtSec) ?: rawYaw
        val filteredPitch = pitchFilter?.filter(rawPitch, dtSec) ?: rawPitch

        val cleanYaw = applySoftDeadzone(filteredYaw, deadzone)
        val cleanPitch = applySoftDeadzone(filteredPitch, deadzone)

        val gain = BASE_PIXELS_PER_RADIAN * sensitivity.coerceIn(0.3f, 3.0f)
        val totalX = prevAccumX + cleanYaw * dtSec * gain
        val totalY = prevAccumY + cleanPitch * dtSec * gain

        val dx = totalX.toInt().coerceIn(-127, 127)
        val dy = totalY.toInt().coerceIn(-127, 127)

        out[0] = dx.toFloat()
        out[1] = dy.toFloat()
        out[2] = totalX - dx
        out[3] = totalY - dy
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
        deadzone: Float = DEFAULT_DEADZONE_RAD_S,
        yawFilter: OneEuroFilter? = null,
        pitchFilter: OneEuroFilter? = null
    ): AirMouseStepResult {
        val scratch = FloatArray(4)
        accumulateStepInto(
            wx = wx,
            wy = wy,
            wz = wz,
            gx = gx,
            gy = gy,
            gz = gz,
            dtSec = dtSec,
            sensitivity = sensitivity,
            prevAccumX = prevAccumX,
            prevAccumY = prevAccumY,
            out = scratch,
            deadzone = deadzone,
            yawFilter = yawFilter,
            pitchFilter = pitchFilter
        )
        return AirMouseStepResult(
            dx = scratch[0].toInt(),
            dy = scratch[1].toInt(),
            nextAccumX = scratch[2],
            nextAccumY = scratch[3]
        )
    }
}
