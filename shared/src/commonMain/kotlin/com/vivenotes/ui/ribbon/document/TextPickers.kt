package com.vivenotes.ui.ribbon.document

import com.vivenotes.localization.Strings
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
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
import com.vivenotes.ui.components.DefaultChoiceItem
import com.vivenotes.ui.components.ScaledDropdownMenu
import com.vivenotes.ui.components.onSecondaryPress
import com.vivenotes.ui.icons.DocumentSymbols
import com.vivenotes.ui.ribbon.RibbonCommand
import com.vivenotes.ui.ribbon.RibbonStyle

/** The Document tab's drop-down controls: font family and size, the two colours, and Styles. */

internal val FontSizes = listOf(8, 9, 10, 11, 12, 14, 15, 16, 18, 20, 24, 28, 36, 48, 72)
internal val FontFamilies get() = listOf(
    "sans-serif" to Strings.system_sans,
    "serif" to Strings.system_serif,
    "monospace" to Strings.system_mono,
    "inter" to Strings.inter,
    "lora" to Strings.lora,
    "jetbrains_mono" to Strings.jetbrains_mono,
)

/** Android's font colour palette, with the names a screen reader and the hover tooltip give them. */
internal val TextColors get() = listOf(
    0xFFFFFFFF to Strings.white, 0xFFE6E6E6 to Strings.light_grey, 0xFF9A9A9A to Strings.grey, 0xFF000000 to Strings.black,
    0xFFE53935 to Strings.red, 0xFFFB8C00 to Strings.orange, 0xFFFDD835 to Strings.yellow, 0xFF43A047 to Strings.green,
    0xFF1E88E5 to Strings.blue, 0xFF8E24AA to Strings.purple, 0xFF00ACC1 to Strings.cyan, 0xFFD81B60 to Strings.pink,
).map { (argb, name) -> argb.toInt() to name }

/** Android's highlight palette. */
internal val HighlightColors get() = listOf(
    0x66FFEB3B to Strings.yellow, 0x6676FF03 to Strings.green, 0x6640C4FF to Strings.blue, 0x66FF4081 to Strings.pink,
    0x66FF9100 to Strings.orange, 0x66B388FF to Strings.purple, 0x66FFFFFF to Strings.white, 0x00000000 to Strings.transparent,
)

/** The colours last chosen from the two palettes for this workspace window. */
internal class DocumentColorSelection {
    var font by mutableStateOf<Int?>(null)
    var highlight by mutableStateOf<Int?>(null)
}

internal val DocumentStyles get() = listOf(
    BlockType.Paragraph to Strings.normal,
    BlockType.Heading1 to Strings.heading_1,
    BlockType.Heading2 to Strings.heading_2,
    BlockType.Heading3 to Strings.heading_3,
    BlockType.Quote to Strings.quote,
    BlockType.Code to Strings.code,
)

@Composable
internal fun RibbonPicker(
    label: String,
    current: String,
    default: String,
    choices: List<Pair<String, String>>,
    enabled: Boolean,
    selection: TextSelection?,
    tag: String,
    onPick: (String, TextSelection?) -> Unit,
    onSetDefault: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    var selectionAtOpen by remember { mutableStateOf<TextSelection?>(null) }
    Box {
        HoverTooltip(label) {
            RibbonCommand(
                label = choices.firstOrNull { it.first == current }?.second ?: current,
                modifier = Modifier.testTag(tag)
                    .captureSelectionOnPress(selection) { selectionAtOpen = it }
                    .semantics { contentDescription = label },
                enabled = enabled,
                dropdown = true,
                onClick = {
                        if (selectionAtOpen == null) selectionAtOpen = selection
                        expanded = true
                },
            )
        }
        ScaledDropdownMenu(expanded = expanded, onDismissRequest = {
            expanded = false; selectionAtOpen = null
        }) {
            choices.forEach { (value, shown) ->
                DefaultChoiceItem(
                    label = shown, selected = value == current, isDefault = value == default,
                    tag = "$tag-$value",
                    onChoose = {
                        expanded = false
                        onPick(value, selectionAtOpen)
                    },
                    onSetDefault = {
                        expanded = false
                        onSetDefault(value)
                    },
                )
            }
        }
    }
}

@Composable
internal fun ColorPicker(
    label: String,
    colors: List<Pair<Int, String>>,
    defaultColor: Int,
    chosenColor: Int?,
    onChooseColor: (Int?) -> Unit,
    enabled: Boolean,
    selection: TextSelection?,
    tag: String,
    icon: (Color, Color) -> ImageVector,
    onPick: (Int, TextSelection?) -> Unit,
    onClear: (TextSelection?) -> Unit,
    rotateIcon: Boolean = false,
) {
    var expanded by remember { mutableStateOf(false) }
    var selectionAtPress by remember { mutableStateOf<TextSelection?>(null) }
    val appliedColor = chosenColor ?: defaultColor
    val neutral = RibbonStyle.normalText
    val swatch = Color(appliedColor).copy(alpha = 1f)
    val image = remember(neutral, swatch) { icon(neutral, swatch) }
    val mainInteraction = remember { MutableInteractionSource() }
    val mainHovered by mainInteraction.collectIsHoveredAsState()
    val menuInteraction = remember { MutableInteractionSource() }
    val menuHovered by menuInteraction.collectIsHoveredAsState()
    Box {
        Row(
            modifier = Modifier.captureSelectionOnPress(selection) { selectionAtPress = it },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            HoverTooltip(label) {
                Box(
                    modifier = Modifier.size(width = 40.dp, height = 46.dp)
                        .background(if (mainHovered && enabled) RibbonStyle.hover else Color.Transparent)
                        .hoverable(mainInteraction, enabled = enabled)
                        .clickable(interactionSource = mainInteraction, indication = null,
                            enabled = enabled, role = Role.Button) {
                            onPick(appliedColor, selectionAtPress ?: selection)
                            selectionAtPress = null
                        }
                        .onSecondaryPress(enabled) { if (enabled) expanded = true }
                        .testTag(tag)
                        .semantics { contentDescription = label },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(imageVector = image, contentDescription = null,
                        tint = if (rotateIcon) swatch else Color.Unspecified,
                        modifier = Modifier.size(20.dp).then(if (rotateIcon) Modifier.rotate(180f) else Modifier))
                }
            }
            HoverTooltip(Strings.choose_value(label)) {
                Box(
                    modifier = Modifier.size(width = 24.dp, height = 46.dp)
                        .background(if (menuHovered && enabled) RibbonStyle.hover else Color.Transparent)
                        .hoverable(menuInteraction, enabled = enabled)
                        .clickable(interactionSource = menuInteraction, indication = null,
                            enabled = enabled, role = Role.Button) { expanded = true }
                        .testTag(DocumentRibbonTags.colorMenu(tag))
                        .semantics { contentDescription = Strings.choose_value(label) },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(DocumentSymbols.ArrowDropDown, contentDescription = null,
                        tint = if (enabled) RibbonStyle.normalText else RibbonStyle.disabledText,
                        modifier = Modifier.size(16.dp))
                }
            }
        }
        ScaledDropdownMenu(expanded = expanded, onDismissRequest = {
            expanded = false; selectionAtPress = null
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
                                            onChooseColor(argb)
                                            onPick(argb, selectionAtPress ?: selection)
                                            selectionAtPress = null
                                        }
                                        .semantics { contentDescription = name }
                                        .testTag("$tag-$argb"),
                                )
                            }
                        }
                    }
                }
                DropdownMenuItem(text = { Text(Strings.none) }, onClick = {
                    expanded = false
                    onChooseColor(null)
                    onClear(selectionAtPress ?: selection)
                    selectionAtPress = null
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
        HoverTooltip(Strings.styles) {
            RibbonCommand(
                label = if (current == BlockType.Paragraph) Strings.styles
                    else DocumentStyles.firstOrNull { it.first == current }?.second ?: Strings.styles,
                onClick = {
                        if (selectionAtOpen == null) selectionAtOpen = selection
                        expanded = true
                },
                modifier = Modifier.testTag(DocumentRibbonTags.Styles)
                    .captureSelectionOnPress(selection) { selectionAtOpen = it }
                    .semantics { contentDescription = Strings.styles },
                active = current != BlockType.Paragraph,
                enabled = enabled,
                dropdown = true,
            ) {
                Icon(icon, contentDescription = null, tint = Color.Unspecified, modifier = Modifier.size(18.dp))
            }
        }
        ScaledDropdownMenu(expanded = expanded, onDismissRequest = {
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
