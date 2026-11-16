package io.github.robbieadi.gametap.platform

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.view.WindowManager
import io.github.robbieadi.gametap.gyro.Quaternion
import io.github.robbieadi.gametap.gyro.Vec3

/**
 * Reads the game rotation vector: gyroscope and accelerometer fused, without the magnetometer,
 * so a nearby magnet or speaker cannot make the angle jump. Phones without a gyroscope have no
 * such sensor, so the accelerometer alone is used instead, smoothed, which gives steering and
 * forward/back tilt but not turning. Readings arrive on a thread of their own, so a busy UI
 * thread does not delay them.
 */
class AndroidMotionSensor(private val context: Context, private val activities: ActivityProvider) : MotionSensor {
    private val manager: SensorManager? = context.getSystemService(SensorManager::class.java)
    private val rotation: Sensor? = manager?.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
    private val sensor: Sensor? = rotation ?: manager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private var thread: HandlerThread? = null
    private var listener: SensorEventListener? = null

    override val isAvailable: Boolean = sensor != null

    override val measuresTurning: Boolean = rotation != null

    override fun start(onSample: (sample: MotionSample, quarterTurns: Int) -> Unit) {
        if (manager == null || sensor == null || listener != null) return
        val thread = HandlerThread("gyro").apply { start() }
        val listener = if (rotation != null) rotationListener(onSample) else gravityListener(onSample)
        manager.registerListener(listener, sensor, SAMPLING_PERIOD_US, Handler(thread.looper))
        this.thread = thread
        this.listener = listener
    }

    private fun rotationListener(onSample: (MotionSample, Int) -> Unit) = object : SensorEventListener {
        private val q = FloatArray(4)

        override fun onSensorChanged(event: SensorEvent) {
            SensorManager.getQuaternionFromVector(q, event.values)
            onSample(MotionSample.Orientation(Quaternion(q[0], q[1], q[2], q[3])), displayRotation())
        }

        override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit
    }

    /**
     * At rest the accelerometer reads up at 1 g. Moving the phone adds its own acceleration, which
     * a low-pass filter mostly removes, at the cost of [SMOOTHING_S] of lag.
     */
    private fun gravityListener(onSample: (MotionSample, Int) -> Unit) = object : SensorEventListener {
        private var x = 0f
        private var y = 0f
        private var z = 0f
        private var lastNanos = 0L

        override fun onSensorChanged(event: SensorEvent) {
            val (ax, ay, az) = event.values
            if (lastNanos == 0L) {
                x = ax
                y = ay
                z = az
            } else {
                val dt = (event.timestamp - lastNanos).coerceAtLeast(0) / 1e9f
                val k = dt / (SMOOTHING_S + dt)
                x += (ax - x) * k
                y += (ay - y) * k
                z += (az - z) * k
            }
            lastNanos = event.timestamp
            onSample(MotionSample.Gravity(Vec3(x, y, z)), displayRotation())
        }

        override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit
    }

    override fun stop() {
        val listener = listener ?: return
        manager?.unregisterListener(listener)
        // Waits for a reading being handled right now, so none arrives after stop returns.
        thread?.quitSafely()
        thread?.join()
        this.listener = null
        thread = null
    }

    /** Surface.ROTATION_0..270 are 0..3, the quarter turns Tilt expects. */
    private fun displayRotation(): Int {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            activities.activity?.display?.let { return it.rotation }
        }
        @Suppress("DEPRECATION")
        return context.getSystemService(WindowManager::class.java)?.defaultDisplay?.rotation ?: 0
    }

    private companion object {
        /** 200 Hz, the most Android allows without the high sampling rate permission. */
        const val SAMPLING_PERIOD_US = 5_000

        /**
         * Time constant of the accelerometer filter, in seconds. 60 ms felt laggy on a Redmi Pad
         * SE, whose accelerometer is quiet (about 0.1 degree of noise at 200 Hz), so less is
         * enough. Raise it if a noisier sensor jitters past the deadzone.
         */
        const val SMOOTHING_S = 0.02f
    }
}
