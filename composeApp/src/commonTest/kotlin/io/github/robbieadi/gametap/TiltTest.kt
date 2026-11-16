package io.github.robbieadi.gametap

import io.github.robbieadi.gametap.domain.Stick
import io.github.robbieadi.gametap.gyro.GyroOutput
import io.github.robbieadi.gametap.gyro.Quaternion
import io.github.robbieadi.gametap.gyro.Tilt
import io.github.robbieadi.gametap.gyro.Vec3
import io.github.robbieadi.gametap.gyro.output
import io.github.robbieadi.gametap.layout.GyroAxis
import io.github.robbieadi.gametap.layout.GyroBinding.StickBinding
import io.github.robbieadi.gametap.layout.GyroBinding.TriggerBinding
import io.github.robbieadi.gametap.layout.GyroHorizontal
import io.github.robbieadi.gametap.layout.GyroSetup
import io.github.robbieadi.gametap.layout.Side
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Rotations are given in the device frame: x right and y up the screen in its natural (portrait)
 * orientation, z out of the screen toward the player.
 */
class TiltTest {
    private val x = Vec3(1f, 0f, 0f)
    private val y = Vec3(0f, 1f, 0f)
    private val z = Vec3(0f, 0f, 1f)
    private val identity = Quaternion.Identity

    private fun assertTilt(expected: Tilt, actual: Tilt) {
        val close = abs(expected.roll - actual.roll) < 0.01f &&
            abs(expected.yaw - actual.yaw) < 0.01f &&
            abs(expected.pitch - actual.pitch) < 0.01f
        assertTrue(close, "expected $expected, was $actual")
    }

    @Test
    fun noRotationIsNoTilt() {
        assertTilt(Tilt(), Tilt.between(identity, identity, 0))
    }

    @Test
    fun clockwiseAsSeenByThePlayerIsPositiveRoll() {
        // Clockwise seen from the front is negative around z, which points at the player.
        assertTilt(Tilt(roll = 20f), Tilt.between(identity, Quaternion.about(z, -20f), 0))
    }

    @Test
    fun rightEdgeAwayIsPositiveYaw() {
        assertTilt(Tilt(yaw = 20f), Tilt.between(identity, Quaternion.about(y, 20f), 0))
    }

    @Test
    fun topEdgeTowardThePlayerIsPositivePitch() {
        assertTilt(Tilt(pitch = 20f), Tilt.between(identity, Quaternion.about(x, 20f), 0))
    }

    /** ROTATION_90: graphics turned counterclockwise, so the phone's top edge is on the right. */
    @Test
    fun landscapeMapsDeviceAxesToTheScreen() {
        assertTilt(Tilt(roll = 20f), Tilt.between(identity, Quaternion.about(z, -20f), 1))
        // The screen's top edge is the device's -x side, so tipping it toward the player turns around +y.
        assertTilt(Tilt(pitch = 20f), Tilt.between(identity, Quaternion.about(y, 20f), 1))
        // The screen's right edge is the device's +y side.
        assertTilt(Tilt(yaw = 20f), Tilt.between(identity, Quaternion.about(x, -20f), 1))
    }

    @Test
    fun reverseLandscapeFlipsTheDeviceAxes() {
        assertTilt(Tilt(pitch = 20f), Tilt.between(identity, Quaternion.about(y, -20f), 3))
        assertTilt(Tilt(yaw = 20f), Tilt.between(identity, Quaternion.about(x, 20f), 3))
    }

    @Test
    fun tiltIsMeasuredFromTheNeutralAngleNotFromTheWorld() {
        val neutral = Quaternion.about(x, 50f) * Quaternion.about(z, 30f)
        val current = neutral * Quaternion.about(z, -20f)
        assertTilt(Tilt(roll = 20f), Tilt.between(neutral, current, 1))
    }

    // ---------- Accelerometer only ----------

    /** Up in device coordinates, as an accelerometer at rest reads it, for a device-to-world rotation. */
    private fun up(rotation: Quaternion): Vec3 = rotation.conjugate().rotate(z).let { Vec3(it.x * 9.81f, it.y * 9.81f, it.z * 9.81f) }

    /** Held in landscape (ROTATION_90, top edge on device -x) and tipped back 40 degrees from upright. */
    private val heldLandscape = Quaternion.about(y, 50f)

    @Test
    fun gravitySteeringMatchesTheGyroAtAnyBackTilt() {
        val current = heldLandscape * Quaternion.about(z, -20f)
        assertTilt(Tilt(roll = 20f), Tilt.between(up(heldLandscape), up(current), 1))
        val portrait = Quaternion.about(x, 60f)
        val steered = portrait * Quaternion.about(z, 25f)
        assertTilt(Tilt(roll = -25f), Tilt.between(up(portrait), up(steered), 0))
    }

    @Test
    fun gravityPitchMatchesTheGyro() {
        val current = heldLandscape * Quaternion.about(y, 20f)
        assertTilt(Tilt(pitch = 20f), Tilt.between(up(heldLandscape), up(current), 1))
        val portrait = Quaternion.about(x, 60f)
        assertTilt(Tilt(pitch = -15f), Tilt.between(up(portrait), up(portrait * Quaternion.about(x, -15f)), 0))
    }

    @Test
    fun gravityCannotSeeTurning() {
        val turned = Quaternion.about(z, 30f) * heldLandscape // Around world up.
        assertTilt(Tilt(), Tilt.between(up(heldLandscape), up(turned), 1))
    }

    @Test
    fun gravityRollTakesTheShortWayAround() {
        val upsideDown = Quaternion.about(x, 90f) * Quaternion.about(z, 170f)
        val current = upsideDown * Quaternion.about(z, 20f) // Through 180.
        assertTilt(Tilt(roll = -20f), Tilt.between(up(upsideDown), up(current), 0))
    }

    // ---------- Output ----------

    private fun setup(vararg bindings: io.github.robbieadi.gametap.layout.GyroBinding, deadzone: Float = 0f, horizontal: GyroHorizontal = GyroHorizontal.ROLL) =
        GyroSetup(bindings.toList(), horizontal, deadzone)

    private fun assertStick(expected: Stick, actual: Stick) =
        assertTrue(abs(expected.x - actual.x) < 0.001f && abs(expected.y - actual.y) < 0.001f, "expected $expected, was $actual")

    @Test
    fun stickTakesBothAxesWithUpNegative() {
        val out = setup(StickBinding(Side.RIGHT, range = 30f)).output(Tilt(roll = 15f, pitch = 15f))
        assertStick(Stick(0.5f, -0.5f), out.rightStick)
        assertEquals(Stick.Center, out.leftStick)
    }

    @Test
    fun stickTakesOnlyTheChosenAxis() {
        val tilt = Tilt(roll = 15f, pitch = 15f)
        assertStick(Stick(0.5f, 0f), setup(StickBinding(Side.LEFT, vertical = false, range = 30f)).output(tilt).leftStick)
        assertStick(Stick(0f, -0.5f), setup(StickBinding(Side.LEFT, horizontal = false, range = 30f)).output(tilt).leftStick)
        assertStick(Stick(0f, 0.5f), setup(StickBinding(Side.LEFT, horizontal = false, range = 30f, invertVertical = true)).output(tilt).leftStick)
    }

    @Test
    fun horizontalFollowsTheChosenMotion() {
        val binding = StickBinding(Side.LEFT, vertical = false, range = 30f)
        val tilt = Tilt(roll = 15f, yaw = -30f)
        assertStick(Stick(0.5f, 0f), setup(binding).output(tilt).leftStick)
        assertStick(Stick(-1f, 0f), setup(binding, horizontal = GyroHorizontal.YAW).output(tilt).leftStick)
    }

    @Test
    fun outputStopsAtFullDeflection() {
        assertStick(Stick(1f, 1f), setup(StickBinding(Side.LEFT, range = 30f)).output(Tilt(roll = 80f, pitch = -80f)).leftStick)
    }

    @Test
    fun deadzoneIsCutOutAndFullStillComesAtTheRange() {
        val s = setup(StickBinding(Side.LEFT, vertical = false, range = 31f), deadzone = 1f)
        assertStick(Stick.Center, s.output(Tilt(roll = 0.9f)).leftStick)
        assertStick(Stick(-0.5f, 0f), s.output(Tilt(roll = -16f)).leftStick)
        assertStick(Stick(1f, 0f), s.output(Tilt(roll = 31f)).leftStick)
    }

    @Test
    fun triggerPullsOnOneSideOnly() {
        val right = setup(TriggerBinding(Side.RIGHT, GyroAxis.HORIZONTAL, range = 20f))
        assertEquals(0.5f, right.output(Tilt(roll = 10f)).rightTrigger, 0.001f)
        assertEquals(0f, right.output(Tilt(roll = -10f)).rightTrigger)

        val down = setup(TriggerBinding(Side.LEFT, GyroAxis.VERTICAL, invert = true, range = 20f))
        assertEquals(0.5f, down.output(Tilt(pitch = -10f)).leftTrigger, 0.001f)
        assertEquals(0f, down.output(Tilt(pitch = 10f)).leftTrigger)
    }

    @Test
    fun oneDirectionCanDriveSeveralOutputs() {
        val out = setup(
            StickBinding(Side.LEFT, vertical = false, range = 20f),
            TriggerBinding(Side.RIGHT, GyroAxis.HORIZONTAL, range = 20f),
            TriggerBinding(Side.LEFT, GyroAxis.HORIZONTAL, invert = true, range = 20f),
        ).output(Tilt(roll = 10f))
        assertEquals(GyroOutput(leftStick = Stick(0.5f, 0f), rightTrigger = 0.5f), out)
    }
}
