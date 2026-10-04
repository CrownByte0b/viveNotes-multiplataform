package com.vivenotes.ui.components

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.vivenotes.ui.icons.DocumentSymbols
import com.vivenotes.ui.icons.ViewSymbols
import com.vivenotes.ui.theme.LocalDesktopColors
import kotlin.math.roundToInt

/** Semantics identifiers for docked tool panes and their fields. */
object ToolPaneTags {
    const val Pane = "tool-pane"
    const val Close = "tool-pane-close"
    fun field(label: String): String = "tool-pane-field-$label"
    fun option(label: String, option: String): String = "tool-pane-field-$label-$option"
}

private val PaneWidth = 300.dp
private val FieldHeight = 32.dp
private const val DisabledAlpha = 0.42f

/**
 * A settings pane docked to the right of the canvas — Android's `ToolPanel`. Some controls do not
 * belong in a drop-down: Paper Size is six fields in two groups, and a docked pane stays open while
 * the page changes shape beside it, which is the point of a control that alters the page.
 */
@Composable
internal fun ToolPane(
    title: String,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        color = LocalDesktopColors.current.sidebar,
        // Android's tool panel leaves to the right under a finger, the way it came in.
        modifier = modifier.width(PaneWidth).fillMaxHeight().testTag(ToolPaneTags.Pane).swipeRight(onClose),
    ) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(title, style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
                TooltipIconButton("Close $title", onClick = onClose,
                    modifier = Modifier.size(32.dp).testTag(ToolPaneTags.Close)) {
                    Icon(ViewSymbols.Close, contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                }
            }
            Column(
                modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                    .padding(start = 12.dp, end = 12.dp, bottom = 16.dp),
                content = content,
            )
        }
    }
}

/** A named group of settings as one boxed list, the way GNOME preference pages group them. */
@Composable
internal fun ColumnScope.PaneGroup(title: String, content: @Composable ColumnScope.() -> Unit) {
    Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.padding(start = 4.dp, top = 14.dp, bottom = 6.dp))
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHighest, shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth()) {
        Column(content = content)
    }
}

/** One labelled row of a [PaneGroup]; every row but the first draws the separator above itself. */
@Composable
internal fun PaneRow(label: String, first: Boolean = false, content: @Composable () -> Unit) {
    if (!first) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 46.dp).padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f))
        content()
    }
}

/** A drop-down field for choices with names rather than numbers. */
@Composable
internal fun <T> PaneChoice(
    field: String,
    current: T,
    options: List<T>,
    label: (T) -> String,
    onPick: (T) -> Unit,
    enabled: Boolean = true,
    default: T? = null,
    onSetDefault: ((T) -> Unit)? = null,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        Row(
            modifier = Modifier
                .width(140.dp)
                .height(FieldHeight)
                .clip(MaterialTheme.shapes.small)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.small)
                .clickable(enabled = enabled, role = Role.DropdownList) { open = true }
                .alpha(if (enabled) 1f else DisabledAlpha)
                .testTag(ToolPaneTags.field(field))
                .padding(start = 10.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label(current), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface, maxLines = 1, modifier = Modifier.weight(1f))
            Icon(DocumentSymbols.ArrowDropDown, contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
        }
        ScaledDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { option ->
                if (onSetDefault != null) DefaultChoiceItem(
                    label = label(option), selected = option == current, isDefault = option == default,
                    tag = ToolPaneTags.option(field, label(option)),
                    onChoose = {
                        open = false
                        onPick(option)
                    },
                    onSetDefault = {
                        open = false
                        onSetDefault(option)
                    },
                )
                else DropdownMenuItem(
                    text = { Text(label(option)) },
                    onClick = { open = false; onPick(option) },
                    modifier = Modifier.testTag(ToolPaneTags.option(field, label(option))),
                )
            }
        }
    }
}

/**
 * A measurement in inches. The text is held as typed, because "8." and "" are states a number field
 * passes through; only a number inside [range] is committed, and anything else is marked until it
 * becomes one.
 */
@Composable
internal fun PaneMeasure(
    field: String,
    value: Float,
    onCommit: (Float) -> Unit,
    range: ClosedFloatingPointRange<Float>,
    enabled: Boolean = true,
) {
    var text by remember(value, enabled) { mutableStateOf(value.trimZero()) }
    val parsed = text.trim().toFloatOrNull()
    val valid = parsed != null && parsed in range
    Row(
        modifier = Modifier
            .width(104.dp)
            .height(FieldHeight)
            .clip(MaterialTheme.shapes.small)
            .border(1.dp, if (valid) MaterialTheme.colorScheme.outlineVariant else MaterialTheme.colorScheme.error,
                MaterialTheme.shapes.small)
            .alpha(if (enabled) 1f else DisabledAlpha)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicTextField(
            value = text,
            onValueChange = {
                text = it
                val next = it.trim().toFloatOrNull()
                if (next != null && next in range) onCommit(next)
            },
            enabled = enabled,
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyMedium.copy(
                color = MaterialTheme.colorScheme.onSurface, textAlign = TextAlign.End),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.weight(1f).testTag(ToolPaneTags.field(field)),
        )
        Spacer(Modifier.width(6.dp))
        Text("in", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Two decimals at most, without a trailing zero: 8.27, 11, 0.25. */
internal fun Float.trimZero(): String {
    val hundredths = (this * 100).roundToInt()
    val whole = hundredths / 100
    val fraction = hundredths % 100
    return when {
        fraction == 0 -> "$whole"
        fraction % 10 == 0 -> "$whole.${fraction / 10}"
        else -> "$whole.${fraction.toString().padStart(2, '0')}"
    }
}
