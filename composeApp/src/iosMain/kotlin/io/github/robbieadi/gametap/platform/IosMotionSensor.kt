@file:OptIn(ExperimentalForeignApi::class)

package io.github.robbieadi.gametap.platform

import io.github.robbieadi.gametap.gyro.Quaternion
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import platform.CoreMotion.CMAttitudeReferenceFrameXArbitraryZVertical
import platform.CoreMotion.CMMotionManager
import platform.Foundation.NSOperationQueue
import platform.Foundation.NSTimer
import platform.UIKit.UIApplication
import platform.UIKit.UIInterfaceOrientationLandscapeLeft
import platform.UIKit.UIInterfaceOrientationLandscapeRight
import platform.UIKit.UIInterfaceOrientationPortraitUpsideDown
import platform.UIKit.UIWindowScene
import kotlin.concurrent.AtomicInt
import kotlin.math.sqrt

/**
 * Reads CoreMotion's device motion: gyroscope and accelerometer fused, against a reference frame
 * with z vertical and x arbitrary, so the magnetometer is not used and a nearby magnet cannot
 * make the angle jump. Every device iOS 16 runs on has a gyroscope, so there is no
 * accelerometer-only fallback. Readings arrive on a serial queue of their own, so a busy main
 * thread does not delay them.
 *
 * Call [start] and [stop] from the main thread.
 */
class IosMotionSensor : MotionSensor {
    private val manager = CMMotionManager()
    private val queue =
        NSOperationQueue().apply {
            name = "gyro"
            maxConcurrentOperationCount = 1
        }
    private var orientationTimer: NSTimer? = null
    private val quarterTurns = AtomicInt(0)
    private var running = false

    override val isAvailable: Boolean = manager.deviceMotionAvailable

    override val measuresTurning: Boolean = true

    override fun start(onSample: (sample: MotionSample, quarterTurns: Int) -> Unit) {
        if (!isAvailable || running) return
        running = true
        // The interface orientation can only be read on the main thread, so it is polled there.
        // A late value only delays the recenter that GyroController does on a flip.
        updateQuarterTurns()
        orientationTimer =
            NSTimer.scheduledTimerWithTimeInterval(ORIENTATION_POLL_S, repeats = true) {
                updateQuarterTurns()
            }
        manager.deviceMotionUpdateInterval = UPDATE_INTERVAL_S
        manager.startDeviceMotionUpdatesUsingReferenceFrame(
            CMAttitudeReferenceFrameXArbitraryZVertical,
            toQueue = queue,
        ) { motion, _ ->
            val rotation =
                motion?.attitude?.rotationMatrix?.useContents {
                    deviceToWorld(m11, m12, m13, m21, m22, m23, m31, m32, m33)
                } ?: return@startDeviceMotionUpdatesUsingReferenceFrame
            onSample(MotionSample.Orientation(rotation), quarterTurns.value)
        }
    }

    override fun stop() {
        if (!running) return
        running = false
        manager.stopDeviceMotionUpdates()
        // Waits for a reading being handled right now, so none arrives after stop returns.
        queue.waitUntilAllOperationsAreFinished()
        orientationTimer?.invalidate()
        orientationTimer = null
    }

    private fun updateQuarterTurns() {
        val scene = UIApplication.sharedApplication.connectedScenes.firstOrNull { it is UIWindowScene } as? UIWindowScene
        quarterTurns.value =
            when (scene?.interfaceOrientation) {
                // Tilt counts the graphics' counterclockwise quarter turns from portrait, the opposite
                // of the device's. Landscape left has the home button on the left, so the device is
                // turned clockwise and the graphics counterclockwise.
                UIInterfaceOrientationLandscapeLeft -> 1

                UIInterfaceOrientationPortraitUpsideDown -> 2

                UIInterfaceOrientationLandscapeRight -> 3

                else -> 0
            }
    }

    private companion object {
        /** 100 Hz, the rate CoreMotion documents for device motion. */
        const val UPDATE_INTERVAL_S = 0.01

        const val ORIENTATION_POLL_S = 0.25
    }
}

/**
 * CMAttitude's rotation matrix maps reference-frame vectors into the device frame, the opposite
 * of what Tilt expects, so the quaternion is built from its transpose. CoreMotion's device frame
 * is Android's: x to the right, y to the top of the screen in portrait, z out of the screen.
 */
private fun deviceToWorld(
    m11: Double,
    m12: Double,
    m13: Double,
    m21: Double,
    m22: Double,
    m23: Double,
    m31: Double,
    m32: Double,
    m33: Double,
): Quaternion {
    // a = transpose(m): device to world.
    val a11 = m11
    val a12 = m21
    val a13 = m31
    val a21 = m12
    val a22 = m22
    val a23 = m32
    val a31 = m13
    val a32 = m23
    val a33 = m33
    // Shepperd's method: divide by the largest of w, x, y, z to stay accurate near every angle.
    val trace = a11 + a22 + a33
    val q =
        when {
            trace > 0 -> {
                val s = sqrt(trace + 1.0) * 2
                doubleArrayOf(s / 4, (a32 - a23) / s, (a13 - a31) / s, (a21 - a12) / s)
            }

            a11 > a22 && a11 > a33 -> {
                val s = sqrt(1.0 + a11 - a22 - a33) * 2
                doubleArrayOf((a32 - a23) / s, s / 4, (a12 + a21) / s, (a13 + a31) / s)
            }

            a22 > a33 -> {
                val s = sqrt(1.0 + a22 - a11 - a33) * 2
                doubleArrayOf((a13 - a31) / s, (a12 + a21) / s, s / 4, (a23 + a32) / s)
            }

            else -> {
                val s = sqrt(1.0 + a33 - a11 - a22) * 2
                doubleArrayOf((a21 - a12) / s, (a13 + a31) / s, (a23 + a32) / s, s / 4)
            }
        }
    return Quaternion(q[0].toFloat(), q[1].toFloat(), q[2].toFloat(), q[3].toFloat())
}
