package com.vivenotes.ui.ribbon.document

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.vivenotes.model.BlockType
import com.vivenotes.richtext.TextSelection
import com.vivenotes.ui.components.HoverTooltip
import com.vivenotes.ui.components.TooltipIconButton
import com.vivenotes.ui.icons.DocumentSymbols

/** The Document tab's drop-down controls: font family and size, the two colours, and Styles. */

internal val FontSizes = listOf(8, 9, 10, 11, 12, 14, 15, 16, 18, 20, 24, 28, 36, 48, 72)
internal val FontFamilies = listOf(
    "sans-serif" to "System sans",
    "serif" to "System serif",
    "monospace" to "System mono",
    "inter" to "Inter",
    "lora" to "Lora",
    "jetbrains_mono" to "JetBrains Mono",
)

/** Android's font colour palette, with the names a screen reader and the hover tooltip give them. */
internal val TextColors = listOf(
    0xFFFFFFFF to "White", 0xFFE6E6E6 to "Light grey", 0xFF9A9A9A to "Grey", 0xFF000000 to "Black",
    0xFFE53935 to "Red", 0xFFFB8C00 to "Orange", 0xFFFDD835 to "Yellow", 0xFF43A047 to "Green",
    0xFF1E88E5 to "Blue", 0xFF8E24AA to "Purple", 0xFF00ACC1 to "Cyan", 0xFFD81B60 to "Pink",
).map { (argb, name) -> argb.toInt() to name }

/** Android's highlight palette. */
internal val HighlightColors = listOf(
    0x66FFEB3B to "Yellow", 0x6676FF03 to "Green", 0x6640C4FF to "Blue", 0x66FF4081 to "Pink",
    0x66FF9100 to "Orange", 0x66B388FF to "Purple", 0x66FFFFFF to "White", 0x00000000 to "Transparent",
)

internal val DocumentStyles = listOf(
    BlockType.Paragraph to "Normal",
    BlockType.Heading1 to "Heading 1",
    BlockType.Heading2 to "Heading 2",
    BlockType.Heading3 to "Heading 3",
    BlockType.Quote to "Quote",
    BlockType.Code to "Code",
)

@Composable
internal fun RibbonPicker(
    label: String,
    current: String,
    choices: List<Pair<String, String>>,
    enabled: Boolean,
    selection: TextSelection?,
    tag: String,
    onPick: (String, TextSelection?) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    var selectionAtOpen by remember { mutableStateOf<TextSelection?>(null) }
    Box {
        HoverTooltip(label) {
            Row(
                modifier = Modifier
                    .testTag(tag)
                    .clip(RoundedCornerShape(8.dp))
                    .captureSelectionOnPress(selection) { selectionAtOpen = it }
                    .clickable(enabled = enabled, role = Role.DropdownList) {
                        if (selectionAtOpen == null) selectionAtOpen = selection
                        expanded = true
                    }
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = choices.firstOrNull { it.first == current }?.second ?: current,
                    color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelMedium,
                )
                Icon(DocumentSymbols.ArrowDropDown, contentDescription = label, modifier = Modifier.size(16.dp))
            }
        }
        DropdownMenu(expanded = expanded, onDismissRequest = {
            expanded = false; selectionAtOpen = null
        }) {
            choices.forEach { (value, shown) ->
                DropdownMenuItem(
                    text = { Text(shown) },
                    onClick = {
                        expanded = false
                        onPick(value, selectionAtOpen)
                    },
                    modifier = Modifier.testTag("$tag-$value"),
                )
            }
        }
    }
}

@Composable
internal fun ColorPicker(
    label: String,
    colors: List<Pair<Int, String>>,
    current: Int?,
    enabled: Boolean,
    selection: TextSelection?,
    tag: String,
    icon: (Color, Color) -> ImageVector,
    onPick: (Int, TextSelection?) -> Unit,
    onClear: (TextSelection?) -> Unit,
    rotateIcon: Boolean = false,
) {
    var expanded by remember { mutableStateOf(false) }
    var selectionAtOpen by remember { mutableStateOf<TextSelection?>(null) }
    val neutral = MaterialTheme.colorScheme.onSurfaceVariant
    val swatch = current?.takeIf { it != 0 }?.let { Color(it).copy(alpha = 1f) } ?: neutral
    val image = remember(neutral, swatch) { icon(neutral, swatch) }
    Box {
        TooltipIconButton(
            label = label,
            onClick = {
                if (selectionAtOpen == null) selectionAtOpen = selection
                expanded = true
            },
            enabled = enabled,
            modifier = Modifier.size(40.dp).testTag(tag)
                .captureSelectionOnPress(selection) { selectionAtOpen = it },
        ) {
            Icon(
                imageVector = image,
                contentDescription = null,
                tint = if (rotateIcon) swatch else Color.Unspecified,
                modifier = Modifier.size(20.dp).then(if (rotateIcon) Modifier.rotate(180f) else Modifier),
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = {
            expanded = false; selectionAtOpen = null
        }) {
            Column(Modifier.padding(8.dp)) {
                colors.chunked(4).forEach { row ->
                    Row {
                        row.forEach { (argb, name) ->
                            HoverTooltip(name) {
                                Box(
                                    Modifier.padding(3.dp).size(28.dp)
                                        .clip(RoundedCornerShape(5.dp))
                                        .background(Color(argb))
                                        .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(5.dp))
                                        .clickable(role = Role.Button) {
                                            expanded = false
                                            onPick(argb, selectionAtOpen)
                                        }
                                        .semantics { contentDescription = name }
                                        .testTag("$tag-$argb"),
                                )
                            }
                        }
                    }
                }
                DropdownMenuItem(text = { Text("None") }, onClick = {
                    expanded = false
                    onClear(selectionAtOpen)
                }, modifier = Modifier.testTag("$tag-none"))
            }
        }
    }
}

@Composable
internal fun StylesPicker(
    current: BlockType,
    icon: ImageVector,
    enabled: Boolean,
    selection: TextSelection?,
    onPick: (BlockType, TextSelection?) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    var selectionAtOpen by remember { mutableStateOf<TextSelection?>(null) }
    Box {
        HoverTooltip("Styles") {
            Row(
                modifier = Modifier.testTag(DocumentRibbonTags.Styles)
                    .clip(RoundedCornerShape(8.dp))
                    .captureSelectionOnPress(selection) { selectionAtOpen = it }
                    .clickable(enabled = enabled, role = Role.DropdownList) {
                        if (selectionAtOpen == null) selectionAtOpen = selection
                        expanded = true
                    }
                    .semantics { contentDescription = "Styles" }
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(icon, contentDescription = null, tint = Color.Unspecified, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(6.dp))
                Text(if (current == BlockType.Paragraph) "Styles"
                    else DocumentStyles.firstOrNull { it.first == current }?.second ?: "Styles",
                    style = MaterialTheme.typography.labelMedium)
                Icon(DocumentSymbols.ArrowDropDown, contentDescription = null, modifier = Modifier.size(16.dp))
            }
        }
        DropdownMenu(expanded = expanded, onDismissRequest = {
            expanded = false; selectionAtOpen = null
        }) {
            DocumentStyles.forEach { (type, label) ->
                DropdownMenuItem(text = { Text(label) }, onClick = {
                    expanded = false
                    onPick(type, selectionAtOpen)
                }, modifier = Modifier.testTag(DocumentRibbonTags.blockType(type)))
            }
        }
    }
}
