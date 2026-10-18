package com.vivenotes.ui.canvas

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.key.*
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.vivenotes.localization.Strings
import com.vivenotes.ui.components.*
import com.vivenotes.ui.ribbon.draw.CustomShapeColorField
import com.vivenotes.ui.ribbon.draw.ColorWheelContent
import com.vivenotes.ui.ribbon.draw.DrawToolkitSymbols
import com.vivenotes.ui.ribbon.draw.DrawToolkitTheme
import com.vivenotes.ui.ribbon.draw.HUE_STOPS
import com.vivenotes.ui.ribbon.draw.ToolkitSwatch
import com.vivenotes.ui.shell.WorkspaceTestTags
import com.vivenotes.workspace.PenColors
import kotlin.math.roundToInt

internal object SelectionColorTags {
    const val Menu = "selection-color-menu"
    const val Custom = "selection-custom-color"
    const val Rainbow = "selection-color-custom"
    const val Add = "selection-color-add"
    const val Swatches = "selection-color-swatches"
    fun color(argb: Int) = "selection-color-$argb"
}

private val selectionColors get() = listOf(Strings.white, Strings.black, Strings.red, Strings.cyan,
    Strings.green, Strings.yellow, Strings.orange, Strings.purple, Strings.blue).zip(PenColors)

internal fun selectionColorFollowsTheme(argb: Int, canvasInk: Color): Boolean =
    argb == (if (canvasInk.luminance() > .5f) 0xffffffff.toInt() else 0xff000000.toInt())

internal class AboveSelectionColorPosition(private val gap: Int) : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize,
        layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
        val start = if (layoutDirection == LayoutDirection.Ltr) anchorBounds.left
            else anchorBounds.right - popupContentSize.width
        return IntOffset(start.coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0)),
            (anchorBounds.top - gap - popupContentSize.height).coerceAtLeast(0))
    }
}

@Composable
internal fun SelectionColorButton(swatch: Color, canvasInk: Color, onColor: (Int, Boolean) -> Unit,
    enabled: Boolean = true, colors: IconButtonColors = IconButtonDefaults.iconButtonColors()) {
    val current = if (swatch == canvasInk)
        (if (canvasInk.luminance() > .5f) Color.White else Color.Black).toArgb() else swatch.toArgb()
    SelectionColorPicker(onColor = { onColor(it, selectionColorFollowsTheme(it, canvasInk)) },
        selectedColor = current) { open ->
        TooltipIconButton(Strings.change_object_colour, onClick = open, enabled = enabled,
            tooltipPosition = TooltipAnchorPosition.Above, colors = colors,
            modifier = Modifier.size(32.dp).testTag(WorkspaceTestTags.ObjectColor)) {
            Box(Modifier.size(18.dp).clip(CircleShape).background(swatch))
        }
    }
}

/** One palette for selected ink, outline borders, equations and shape fills. */
@Composable
internal fun SelectionColorPicker(onColor: (Int) -> Unit, onNoFill: (() -> Unit)? = null,
    selectedColor: Int? = null,
    content: @Composable (open: () -> Unit) -> Unit) {
    var open by remember { mutableStateOf(false) }
    var customOpen by remember { mutableStateOf(false) }
    var mixed by remember { mutableStateOf<Int?>(null) }
    var anchorTop by remember { mutableFloatStateOf(0f) }
    val contentDensity = LocalDensity.current
    val layerDensity = LocalPopupLayerDensity.current ?: contentDensity
    val gap = with(contentDensity) { 4.dp.roundToPx() }
    val position = remember(gap) { AboveSelectionColorPosition(gap) }
    val focus = remember { FocusRequester() }
    fun close() { open = false; customOpen = false }
    fun pick(argb: Int) { close(); onColor(argb) }
    fun custom() { mixed = null; customOpen = true }
    Box(Modifier.onGloballyPositioned { anchorTop = it.boundsInWindow().top }) {
        content { open = true }
        if (open) CompositionLocalProvider(LocalDensity provides layerDensity) {
            Popup(popupPositionProvider = position, onDismissRequest = ::close,
                properties = PopupProperties(focusable = true)) {
                PopupTooltipScope("selection-color") {
                    CompositionLocalProvider(LocalDensity provides contentDensity) {
                        DrawToolkitTheme {
                            val focusManager = LocalFocusManager.current
                            LaunchedEffect(Unit) { focus.requestFocus() }
                            Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainer,
                                shadowElevation = 8.dp,
                                modifier = Modifier.testTag(SelectionColorTags.Menu).dismissPopupOnEscape(::close)
                                    .focusRequester(focus).focusable().onKeyEvent { event ->
                                        if (event.type != KeyEventType.KeyDown) false else when (event.key) {
                                            Key.DirectionDown -> focusManager.moveFocus(FocusDirection.Next)
                                            Key.DirectionUp -> focusManager.moveFocus(FocusDirection.Previous)
                                            else -> false
                                        }
                                    }) {
                                Column(Modifier.width(320.dp)
                                    .heightIn(max = with(contentDensity) { (anchorTop.roundToInt() - gap).coerceAtLeast(1).toDp() })
                                    .verticalScroll(rememberScrollState()).padding(horizontal = 12.dp,
                                        vertical = if (customOpen) 8.dp else 0.dp)) {
                                    if (customOpen) {
                                        Text(Strings.custom_color, style = MaterialTheme.typography.bodyMedium)
                                        ColorWheelContent(selectedColor ?: 0xff000000.toInt(),
                                            onPick = { mixed = it }, onDone = { mixed?.let(::pick) ?: close() })
                                        CustomShapeColorField(Strings.colour, SelectionColorTags.Custom, ::pick)
                                    } else {
                                        Row(Modifier.fillMaxWidth().height(24.dp), verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.SpaceBetween) {
                                            Text(Strings.color, style = MaterialTheme.typography.bodyMedium)
                                            TooltipIconButton(Strings.add_color, onClick = ::custom,
                                                colors = IconButtonDefaults.iconButtonColors(
                                                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .55f)),
                                                modifier = Modifier.size(24.dp).testTag(SelectionColorTags.Add)) {
                                                Icon(DrawToolkitSymbols.Add, null, modifier = Modifier.size(20.dp))
                                            }
                                        }
                                        Row(Modifier.fillMaxWidth().testTag(SelectionColorTags.Swatches),
                                            horizontalArrangement = Arrangement.SpaceBetween) {
                                            selectionColors.forEach { (name, argb) ->
                                                ToolkitSwatch(selectedColor == argb, SelectionColorTags.color(argb), name,
                                                    { pick(argb) }) { drawRect(Color(argb)) }
                                            }
                                            ToolkitSwatch(selectedColor != null && selectedColor !in PenColors,
                                                SelectionColorTags.Rainbow, Strings.custom_color, ::custom) {
                                                drawRect(Brush.sweepGradient(HUE_STOPS, center))
                                                drawCircle(Brush.radialGradient(listOf(Color.White, Color.Transparent), center, size.minDimension / 2f))
                                            }
                                        }
                                        onNoFill?.let { noFill ->
                                            TextButton(onClick = { close(); noFill() }, contentPadding = PaddingValues(horizontal = 4.dp),
                                                modifier = Modifier.height(28.dp)) { Text(Strings.no_fill) }
                                        }
                                        Spacer(Modifier.height(8.dp))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
