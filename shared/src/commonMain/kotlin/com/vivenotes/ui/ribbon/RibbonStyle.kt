package com.vivenotes.ui.ribbon

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** The dark, flat ribbon treatment shared by every workspace tab. */
internal object RibbonStyle {
    val background = Color(0xFF292A2F)
    val divider = Color(0xFF393B42)
    val hover = Color(0xFF32343A)
    val normalText = Color(0xFFE5E7ED)
    val hoverText = Color.White
    val activeText = Color.White
    val disabledText = Color(0xFFA8ABB4)
    val accent = Color(0xFF5DAFFF)
    val disabledAccent = Color(0xFF7B9AB8)
    val indicator = Color(0xFF007FFF)
}

internal fun Modifier.ribbonBottomBorder(): Modifier = drawBehind {
    drawRect(RibbonStyle.divider, topLeft = Offset(0f, size.height - 1.dp.toPx()),
        size = Size(size.width, 1.dp.toPx()))
}

internal fun Modifier.ribbonActiveIndicator(active: Boolean): Modifier = drawBehind {
    if (active) {
        drawRect(RibbonStyle.indicator, topLeft = Offset(12.dp.toPx(), size.height - 2.dp.toPx()),
            size = Size(size.width - 24.dp.toPx(), 2.dp.toPx()))
    }
}
