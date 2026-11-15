package io.github.robbieadi.gametap.gyro

import io.github.robbieadi.gametap.domain.Stick
import io.github.robbieadi.gametap.layout.GyroAxis
import io.github.robbieadi.gametap.layout.GyroBinding
import io.github.robbieadi.gametap.layout.GyroHorizontal
import io.github.robbieadi.gametap.layout.GyroSetup
import io.github.robbieadi.gametap.layout.Side
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sign
import kotlin.math.sin

data class Vec3(val x: Float, val y: Float, val z: Float)

/** A unit rotation quaternion. */
data class Quaternion(val w: Float, val x: Float, val y: Float, val z: Float) {
    operator fun times(o: Quaternion) = Quaternion(
        w * o.w - x * o.x - y * o.y - z * o.z,
        w * o.x + x * o.w + y * o.z - z * o.y,
        w * o.y - x * o.z + y * o.w + z * o.x,
        w * o.z + x * o.y - y * o.x + z * o.w,
    )

    fun conjugate() = Quaternion(w, -x, -y, -z)

    fun rotate(v: Vec3): Vec3 {
        val r = this * Quaternion(0f, v.x, v.y, v.z) * conjugate()
        return Vec3(r.x, r.y, r.z)
    }

    companion object {
        val Identity = Quaternion(1f, 0f, 0f, 0f)

        fun about(axis: Vec3, degrees: Float): Quaternion {
            val half = degrees * DEG_TO_RAD / 2f
            val s = sin(half)
            return Quaternion(cos(half), axis.x * s, axis.y * s, axis.z * s)
        }
    }
}

/**
 * How far the phone is turned from its neutral angle, in degrees, in the frame of the screen as
 * the player sees it. Each is positive in the direction its [GyroAxis] calls positive.
 */
data class Tilt(
    /** Turned like a steering wheel, clockwise (right edge down) positive. */
    val roll: Float = 0f,
    /** Turned around the screen's vertical axis, right edge away from the player positive. */
    val yaw: Float = 0f,
    /**
     * Top edge tipped toward the player positive, so the back of the phone points higher, like a
     * camera looking up.
     */
    val pitch: Float = 0f,
) {
    companion object {
        /**
         * [neutral] and [current] map device coordinates to world coordinates, as Android's
         * rotation vector sensors report them. The device frame has x to the right and y to the
         * top of the screen in its natural orientation, z out of the screen. [quarterTurns] is
         * the display rotation (Android's Surface.ROTATION_*), the drawn graphics turned that
         * many quarter turns counterclockwise from natural.
         */
        fun between(neutral: Quaternion, current: Quaternion, quarterTurns: Int): Tilt {
            // Current device axes expressed in the neutral device frame...
            val relative = neutral.conjugate() * current
            // ...then both in the screen frame. The device is turned the opposite way to the graphics.
            val toScreen = Quaternion.about(Z, -90f * quarterTurns)
            val r = toScreen * relative * toScreen.conjugate()
            val right = r.rotate(X)
            val up = r.rotate(Y)
            return Tilt(
                roll = -asin(right.y.coerceIn(-1f, 1f)) * RAD_TO_DEG,
                yaw = atan2(-right.z, right.x) * RAD_TO_DEG,
                pitch = asin(up.z.coerceIn(-1f, 1f)) * RAD_TO_DEG,
            )
        }

        /**
         * The same from the direction of up alone, in device coordinates, for phones without a
         * gyroscope. Yaw is always 0. Roll is the angle of up within the screen plane, so a
         * steering turn reads the same however far the phone is tipped back; pitch is the change
         * in how far up rises out of that plane. Roll becomes unsteady as the phone nears flat.
         */
        fun between(neutralUp: Vec3, currentUp: Vec3, quarterTurns: Int): Tilt {
            val toScreen = Quaternion.about(Z, -90f * quarterTurns)
            val neutral = toScreen.rotate(neutralUp)
            val current = toScreen.rotate(currentUp)
            var roll = lean(current) - lean(neutral)
            if (roll > 180f) roll -= 360f
            if (roll < -180f) roll += 360f
            // Tipping the top edge toward the player turns the screen away from up.
            return Tilt(roll = roll, pitch = elevation(neutral) - elevation(current))
        }

        /** Turning clockwise moves up toward the left edge. */
        private fun lean(up: Vec3): Float = atan2(-up.x, up.y) * RAD_TO_DEG

        private fun elevation(up: Vec3): Float = atan2(up.z, hypot(up.x, up.y)) * RAD_TO_DEG

        private val X = Vec3(1f, 0f, 0f)
        private val Y = Vec3(0f, 1f, 0f)
        private val Z = Vec3(0f, 0f, 1f)
    }
}

/** What gyro adds to the gamepad state. Sticks use the HID convention, up negative. */
data class GyroOutput(
    val leftStick: Stick = Stick.Center,
    val rightStick: Stick = Stick.Center,
    val leftTrigger: Float = 0f,
    val rightTrigger: Float = 0f,
) {
    companion object {
        val Zero = GyroOutput()
    }
}

/** Turns a tilt into output for every binding. */
fun GyroSetup.output(tilt: Tilt): GyroOutput {
    val horizontalAngle = if (horizontal == GyroHorizontal.ROLL) tilt.roll else tilt.yaw
    var out = GyroOutput.Zero
    for (binding in bindings) {
        fun scaled(angle: Float): Float {
            val past = max(abs(angle) - deadzone, 0f)
            if (past == 0f) return 0f // Not sign(angle) * 0, which can be -0.
            return (sign(angle) * past / (binding.range - deadzone)).coerceIn(-1f, 1f)
        }
        when (binding) {
            is GyroBinding.StickBinding -> {
                val x = if (binding.horizontal) scaled(horizontalAngle) else 0f
                val up = if (binding.vertical) scaled(tilt.pitch) else 0f
                // 0 - up rather than -up, so no tilt gives 0 and not -0.
                val stick = Stick(x, if (binding.invertVertical) up else 0f - up)
                out = when (binding.side) {
                    Side.LEFT -> out.copy(leftStick = stick)
                    Side.RIGHT -> out.copy(rightStick = stick)
                }
            }
            is GyroBinding.TriggerBinding -> {
                val angle = if (binding.axis == GyroAxis.HORIZONTAL) horizontalAngle else tilt.pitch
                val value = scaled(if (binding.invert) -angle else angle).coerceAtLeast(0f)
                out = when (binding.side) {
                    Side.LEFT -> out.copy(leftTrigger = value)
                    Side.RIGHT -> out.copy(rightTrigger = value)
                }
            }
        }
    }
    return out
}

private const val DEG_TO_RAD = (PI / 180.0).toFloat()
private const val RAD_TO_DEG = (180.0 / PI).toFloat()
