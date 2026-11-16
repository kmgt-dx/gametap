package io.github.robbieadi.gametap.ui.components

import androidx.compose.foundation.ScrollState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * A thin thumb along the right edge while the content is taller than the element, so a panel
 * that can scroll looks like it. Place it before `verticalScroll` in the chain, so it draws over
 * the visible area rather than scrolling with the content.
 */
fun Modifier.scrollIndicator(state: ScrollState, color: Color): Modifier = drawWithContent {
    drawContent()
    val max = state.maxValue
    if (max == 0 || max == Int.MAX_VALUE) return@drawWithContent
    val visible = size.height
    val total = visible + max
    val thumb = (visible * visible / total).coerceAtLeast(24.dp.toPx())
    val top = (visible - thumb) * state.value / max
    val width = 3.dp.toPx()
    drawRoundRect(
        color = color,
        topLeft = Offset(size.width - width - 4.dp.toPx(), top),
        size = Size(width, thumb),
        cornerRadius = CornerRadius(width / 2f),
    )
}
