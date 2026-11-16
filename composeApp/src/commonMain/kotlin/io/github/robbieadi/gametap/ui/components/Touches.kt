package io.github.robbieadi.gametap.ui.components

import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToDownIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput

/**
 * Marks each touch-down on this element as consumed once its children (buttons, rows) have seen
 * it. The gamepad screen skips consumed touch-downs, so a touch on an overlay never also presses a
 * control underneath, and it ignores the later moves of a pointer it never saw go down.
 *
 * Only downs: a scroll container inside gives up its drag if a parent consumes a move before the
 * finger passes touch slop, so consuming moves made slow drags fail to scroll.
 */
fun Modifier.consumeTouchDowns(): Modifier = pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) {
            awaitPointerEvent(PointerEventPass.Main).changes.forEach {
                if (it.changedToDownIgnoreConsumed()) it.consume()
            }
        }
    }
}
