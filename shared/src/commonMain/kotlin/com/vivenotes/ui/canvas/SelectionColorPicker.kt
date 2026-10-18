package com.vivenotes.ui.canvas

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.key.*
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.vivenotes.localization.Strings
import com.vivenotes.ui.components.*
import com.vivenotes.ui.ribbon.draw.CustomShapeColorField
import com.vivenotes.ui.shell.WorkspaceTestTags
import kotlin.math.roundToInt

internal object SelectionColorTags {
    const val Menu = "selection-color-menu"
    const val Custom = "selection-custom-color"
}

private val selectionColors get() = listOf(
    Strings.white to 0xFFFFFFFF.toInt(), Strings.black to 0xFF000000.toInt(),
    Strings.gray to 0xFF6B7280.toInt(), Strings.red to 0xFFEF4444.toInt(),
    Strings.orange to 0xFFF59E0B.toInt(), Strings.green to 0xFF22C55E.toInt(),
    Strings.cyan to 0xFF06B6D4.toInt(), Strings.blue to 0xFF3B82F6.toInt(),
    Strings.purple to 0xFF8B5CF6.toInt(), Strings.pink to 0xFFEC4899.toInt(),
)

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
    SelectionColorPicker(onColor = { onColor(it, selectionColorFollowsTheme(it, canvasInk)) }) { open ->
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
    content: @Composable (open: () -> Unit) -> Unit) {
    var open by remember { mutableStateOf(false) }
    var anchorTop by remember { mutableFloatStateOf(0f) }
    val contentDensity = LocalDensity.current
    val layerDensity = LocalPopupLayerDensity.current ?: contentDensity
    val gap = with(contentDensity) { 4.dp.roundToPx() }
    val position = remember(gap) { AboveSelectionColorPosition(gap) }
    val focus = remember { FocusRequester() }
    fun close() { open = false }
    fun pick(argb: Int) { close(); onColor(argb) }
    Box(Modifier.onGloballyPositioned { anchorTop = it.boundsInWindow().top }) {
        content { open = true }
        if (open) CompositionLocalProvider(LocalDensity provides layerDensity) {
            Popup(popupPositionProvider = position, onDismissRequest = ::close,
                properties = PopupProperties(focusable = true)) {
                PopupTooltipScope("selection-color") {
                    CompositionLocalProvider(LocalDensity provides contentDensity) {
                        val focusManager = LocalFocusManager.current
                        LaunchedEffect(Unit) { focus.requestFocus() }
                        Surface(shape = MaterialTheme.shapes.medium, tonalElevation = 3.dp, shadowElevation = 8.dp,
                            modifier = Modifier.testTag(SelectionColorTags.Menu).dismissPopupOnEscape(::close)
                                .focusRequester(focus).focusable().onKeyEvent { event ->
                                    if (event.type != KeyEventType.KeyDown) false else when (event.key) {
                                        Key.DirectionDown -> focusManager.moveFocus(FocusDirection.Next)
                                        Key.DirectionUp -> focusManager.moveFocus(FocusDirection.Previous)
                                        else -> false
                                    }
                                }) {
                            Column(Modifier.width(224.dp)
                                .heightIn(max = with(contentDensity) { (anchorTop.roundToInt() - gap).coerceAtLeast(1).toDp() })
                                .verticalScroll(rememberScrollState()).padding(vertical = 6.dp)) {
                                onNoFill?.let { noFill ->
                                    TextButton(onClick = { close(); noFill() }) { Text(Strings.no_fill) }
                                }
                                selectionColors.chunked(5).forEach { row ->
                                    Row(Modifier.padding(horizontal = 8.dp, vertical = 2.dp)) {
                                        row.forEach { (name, argb) ->
                                            HoverTooltip(name) {
                                                Box(Modifier.size(40.dp).padding(4.dp).clip(CircleShape)
                                                    .background(Color(argb)).clickable(role = Role.Button) { pick(argb) }
                                                    .semantics { contentDescription = name })
                                            }
                                        }
                                    }
                                }
                                CustomShapeColorField(Strings.colour, SelectionColorTags.Custom, ::pick)
                            }
                        }
                    }
                }
            }
        }
    }
}
