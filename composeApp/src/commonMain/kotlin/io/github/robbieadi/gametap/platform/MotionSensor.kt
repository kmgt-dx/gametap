package io.github.robbieadi.gametap.platform

import io.github.robbieadi.gametap.gyro.Quaternion
import io.github.robbieadi.gametap.gyro.Vec3

/** One reading of how the phone is held. */
sealed interface MotionSample {
    /** The device-to-world rotation, from a gyroscope fused with the accelerometer. */
    data class Orientation(val rotation: Quaternion) : MotionSample

    /**
     * The direction of up in device coordinates, of any length, from the accelerometer alone.
     * It gives steering and forward/back tilt but not turning, which does not change it.
     */
    data class Gravity(val up: Vec3) : MotionSample
}

/** The phone's orientation, for gyro. */
interface MotionSensor {
    /** False when the device has neither a gyroscope nor an accelerometer. */
    val isAvailable: Boolean

    /** True when samples are [MotionSample.Orientation], which can measure turning. */
    val measuresTurning: Boolean

    /**
     * Calls [onSample] for each reading until [stop], on a thread of the sensor's choosing, with
     * the display rotation in quarter turns (see [io.github.robbieadi.gametap.gyro.Tilt.between]).
     * Does nothing if already started.
     */
    fun start(onSample: (sample: MotionSample, quarterTurns: Int) -> Unit)

    fun stop()
}

/** For platforms without a sensor implementation yet. */
object NoMotionSensor : MotionSensor {
    override val isAvailable: Boolean = false

    override val measuresTurning: Boolean = false

    override fun start(onSample: (sample: MotionSample, quarterTurns: Int) -> Unit) = Unit

    override fun stop() = Unit
}
