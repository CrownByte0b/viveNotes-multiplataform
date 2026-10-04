package com.vivenotes.ui.components

import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.snap
import androidx.compose.foundation.MutatorMutex
import androidx.compose.foundation.MutatePriority
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonColors
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipState
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Density
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.delay

internal const val HoverTooltipDelayMillis = 250L

/** Desktop popup layers must use the host window's density for their window coordinates. */
internal val LocalPopupLayerDensity = compositionLocalOf<Density?> { null }

/** SwingGraphics can miss subsequent draw-only animation frames, so start fully opaque. */
private class ImmediateTooltipMotionScheme(private val delegate: MotionScheme) : MotionScheme by delegate {
    override fun <T> fastSpatialSpec(): FiniteAnimationSpec<T> = snap()
    override fun <T> fastEffectsSpec(): FiniteAnimationSpec<T> = snap()
}

/**
 * Cancels a pending tooltip when the pointer leaves before the hover delay expires, and keeps a tap
 * from showing one.
 *
 * A press focuses the control it lands on — Compose does so for every click on the desktop — and
 * Material shows a focused control's tooltip for the keyboard. Under a mouse that is the tooltip the
 * hover shows anyway; under a finger it is a label flashing up after every tap, which Android, where
 * a tooltip needs a long press, never does. So the tooltip for a focus that arrives while a finger is
 * pressing the control is not shown. Material's own long-press tooltip has no focus change before it,
 * and still is.
 */
@OptIn(ExperimentalMaterial3Api::class)
internal class DelayedTooltipState(private val delegate: TooltipState) : TooltipState by delegate {
    private var request = 0

    /** A finger is down on the control. */
    var touching = false
    private var focusedByTap = false

    fun onFocusChanged(focused: Boolean) {
        focusedByTap = focused && touching
    }

    override suspend fun show(mutatePriority: MutatePriority) {
        if (mutatePriority == MutatePriority.PreventUserInput && focusedByTap) {
            focusedByTap = false
            return
        }
        val current = ++request
        if (mutatePriority == MutatePriority.UserInput || mutatePriority == MutatePriority.PreventUserInput) {
            delay(HoverTooltipDelayMillis)
        }
        if (current == request) delegate.show(mutatePriority)
    }

    override fun dismiss() {
        request++
        delegate.dismiss()
    }

    override fun onDispose() {
        request++
        delegate.onDispose()
    }
}

/**
 * Shows [label] in a Material 3 plain tooltip while the mouse is over [content].
 *
 * The app's rule for every control whose only visible label is an icon or a swatch: the words a
 * screen reader hears are the words the mouse finds, so pass the control's accessibility label.
 * Touch and keyboard users get the same tooltip from Material's long press and focus handling.
 *
 * Each tooltip has its own [MutatorMutex] rather than Material's app-wide one. A click focuses the
 * button it lands on, and Material shows that button's tooltip at keyboard-focus priority for 1.5 s;
 * through the shared mutex that refused every hover tooltip meanwhile — the swatches of the menu
 * the click had just opened, or the next ribbon button the mouse reached.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HoverTooltip(
    label: String,
    modifier: Modifier = Modifier,
    position: TooltipAnchorPosition = TooltipAnchorPosition.Below,
    content: @Composable () -> Unit,
) {
    val contentDensity = LocalDensity.current
    val layerDensity = LocalPopupLayerDensity.current ?: contentDensity
    val materialState = rememberTooltipState(mutatorMutex = remember { MutatorMutex() })
    val positionProvider = rememberHoverTooltipPositionProvider(position)
    val motionScheme = MaterialTheme.motionScheme
    val tooltipMotion = remember(motionScheme) { ImmediateTooltipMotionScheme(motionScheme) }
    val state = remember(materialState) { DelayedTooltipState(materialState) }
    CompositionLocalProvider(LocalDensity provides layerDensity) {
        MaterialTheme(motionScheme = tooltipMotion) {
            TooltipBox(
                positionProvider = positionProvider,
                tooltip = {
                    CompositionLocalProvider(LocalDensity provides contentDensity) {
                        PlainTooltip { Text(label) }
                    }
                },
                state = state,
                modifier = modifier.pointerInput(state) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            state.touching = event.changes.any { it.type == PointerType.Touch && it.pressed }
                        }
                    }
                }.onFocusChanged { state.onFocusChanged(it.isFocused) },
                content = {
                    CompositionLocalProvider(LocalDensity provides contentDensity) {
                        MaterialTheme(motionScheme = motionScheme, content = content)
                    }
                },
            )
        }
    }
}

/**
 * An [IconButton] whose accessibility label is also its hover tooltip, so the two cannot differ.
 * The icon inside should have a null content description: this button carries [label].
 */
@Composable
fun TooltipIconButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: IconButtonColors = IconButtonDefaults.iconButtonColors(),
    tooltipPosition: TooltipAnchorPosition = TooltipAnchorPosition.Below,
    icon: @Composable () -> Unit,
) {
    HoverTooltip(label, position = tooltipPosition) {
        IconButton(
            onClick = onClick,
            enabled = enabled,
            colors = colors,
            modifier = modifier.semantics { contentDescription = label },
            content = icon,
        )
    }
}
