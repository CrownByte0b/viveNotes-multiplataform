package com.vivenotes.ui.ribbon

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** The flat ribbon treatment shared by every workspace tab. */
internal data class RibbonColors(
    val background: Color,
    val divider: Color,
    val hover: Color,
    val normalText: Color,
    val hoverText: Color,
    val activeText: Color,
    val disabledText: Color,
    val accent: Color,
    val disabledAccent: Color,
    val indicator: Color,
)

internal val DarkRibbonColors = RibbonColors(
    background = Color(0xFF292A2F),
    divider = Color(0xFF393B42),
    hover = Color(0xFF32343A),
    normalText = Color(0xFFE5E7ED),
    hoverText = Color.White,
    activeText = Color.White,
    disabledText = Color(0xFFA8ABB4),
    accent = Color(0xFF5DAFFF),
    disabledAccent = Color(0xFF7B9AB8),
    indicator = Color(0xFF007FFF),
)

internal val LightRibbonColors = RibbonColors(
    background = Color(0xFFF3F3F5),
    divider = Color(0xFFD4D4D8),
    hover = Color(0xFFE4E4E7),
    normalText = Color(0xFF242428),
    hoverText = Color(0xFF242428),
    activeText = Color(0xFF242428),
    disabledText = Color(0xFF777780),
    accent = Color(0xFF3584E4),
    disabledAccent = Color(0xFF72869C),
    indicator = Color(0xFF3584E4),
)

internal val LocalRibbonColors = staticCompositionLocalOf { DarkRibbonColors }

internal val ribbonStyle: RibbonColors
    @Composable get() = LocalRibbonColors.current

@Composable
internal fun Modifier.ribbonBottomBorder(): Modifier {
    val divider = ribbonStyle.divider
    return drawBehind {
        drawRect(divider, topLeft = Offset(0f, size.height - 1.dp.toPx()),
            size = Size(size.width, 1.dp.toPx()))
    }
}

@Composable
internal fun Modifier.ribbonActiveIndicator(active: Boolean): Modifier {
    val indicator = ribbonStyle.indicator
    return drawBehind {
        if (active) {
            drawRect(indicator, topLeft = Offset(12.dp.toPx(), size.height - 2.dp.toPx()),
                size = Size(size.width - 24.dp.toPx(), 2.dp.toPx()))
        }
    }
}
