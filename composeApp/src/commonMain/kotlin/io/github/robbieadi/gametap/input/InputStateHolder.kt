package io.github.robbieadi.gametap.input

import io.github.robbieadi.gametap.domain.GamepadState
import io.github.robbieadi.gametap.gyro.GyroOutput
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Latest input snapshot. Touch handlers write [state] directly and gyro writes [motion]; the
 * sender combines the two. The UI only reads [state] to draw.
 */
class InputStateHolder {
    private val mutableState = MutableStateFlow(GamepadState())
    val state: StateFlow<GamepadState> = mutableState.asStateFlow()

    private val mutableMotion = MutableStateFlow(GyroOutput.Zero)
    val motion: StateFlow<GyroOutput> = mutableMotion.asStateFlow()

    fun set(state: GamepadState) {
        mutableState.value = state
    }

    fun setMotion(output: GyroOutput) {
        mutableMotion.value = output
    }
}
