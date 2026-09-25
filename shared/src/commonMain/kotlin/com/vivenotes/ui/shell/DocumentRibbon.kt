package com.vivenotes.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.unit.dp
import com.vivenotes.model.Align
import com.vivenotes.model.BlockType
import com.vivenotes.model.Mark
import com.vivenotes.richtext.RichTextBuffer
import com.vivenotes.ui.icons.DocumentSymbols
import com.vivenotes.ui.icons.fontColorGlyph
import com.vivenotes.ui.icons.rememberDocumentRibbonIcons

/** Android Document tab controls, in the same order and with the same icon artwork. */
internal object DocumentRibbonTags {
    const val Text = "document-text"
    const val Paste = "document-paste"
    const val Cut = "document-cut"
    const val Copy = "document-copy"
    const val FontFamily = "document-font-family"
    const val FontSize = "document-font-size"
    const val FontColor = "document-font-color"
    const val Highlight = "document-highlight"
    const val Equation = "document-equation"
    const val Link = "document-link"
    const val Picture = "document-picture"
}

private val FontSizes = listOf(8, 9, 10, 11, 12, 14, 15, 16, 18, 20, 24, 28, 36, 48, 72)
private val FontFamilies = listOf(
    "sans-serif" to "System sans",
    "serif" to "System serif",
    "monospace" to "System mono",
    "inter" to "Inter",
    "lora" to "Lora",
    "jetbrains_mono" to "JetBrains Mono",
)
private val TextColors = listOf(
    0xFFFFFFFF, 0xFFE6E6E6, 0xFF9A9A9A, 0xFF000000,
    0xFFE53935, 0xFFFB8C00, 0xFFFDD835, 0xFF43A047,
    0xFF1E88E5, 0xFF8E24AA, 0xFF00ACC1, 0xFFD81B60,
).map(Long::toInt)
private val HighlightColors = listOf(
    0x66FFEB3B, 0x6676FF03, 0x6640C4FF, 0x66FF4081,
    0x66FF9100, 0x66B388FF, 0x66FFFFFF, 0x00000000,
)

@Composable
internal fun DocumentRibbon(
    richText: RichTextBuffer?,
    textToolArmed: Boolean,
    onToggleTextTool: () -> Unit,
    onToggleMark: (Mark) -> Unit,
    onSetMark: (Mark) -> Unit,
    onClearMark: (Mark) -> Unit,
    onClearFormatting: () -> Unit,
    onBlockType: (BlockType) -> Unit,
    onAlign: (Align) -> Unit,
    onIndent: (Int) -> Unit,
    onCopy: () -> Unit,
    onCut: () -> Unit,
    onPaste: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val accent = if (colors.surface.luminance() < 0.5f) Color(0xFF3B9ADC) else Color(0xFF1B6FA8)
    val (idle, active) = rememberDocumentRibbonIcons(colors.onSurfaceVariant, colors.onPrimaryContainer, accent)
    val marks = richText?.activeMarks.orEmpty()
    val block = richText?.currentBlock
    Surface(color = colors.surface) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(76.dp)
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 8.dp, vertical = 5.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RibbonIcon(if (textToolArmed) active.insertText else idle.insertText, "Text",
                selected = textToolArmed, tag = DocumentRibbonTags.Text, twoTone = true,
                onClick = onToggleTextTool)
            RibbonDivider()
            RibbonIcon(DocumentSymbols.ContentPaste, "Paste", enabled = richText != null, tag = DocumentRibbonTags.Paste, onClick = onPaste)
            RibbonIcon(DocumentSymbols.ContentCut, "Cut", enabled = richText != null && !richText.selection.collapsed, tag = DocumentRibbonTags.Cut, onClick = onCut)
            RibbonIcon(DocumentSymbols.ContentCopy, "Copy", enabled = richText != null && !richText.selection.collapsed, tag = DocumentRibbonTags.Copy, onClick = onCopy)
            RibbonDivider()
            RibbonPicker(
                label = "Font family",
                current = marks.filterIsInstance<Mark.FontFamily>().firstOrNull()?.name ?: "sans-serif",
                choices = FontFamilies,
                enabled = richText != null,
                tag = DocumentRibbonTags.FontFamily,
                onPick = { onSetMark(Mark.FontFamily(it)) },
            )
            RibbonPicker(
                label = "Font size",
                current = marks.filterIsInstance<Mark.FontSize>().firstOrNull()?.sp?.toString() ?: "15",
                choices = FontSizes.map { it.toString() to it.toString() },
                enabled = richText != null,
                tag = DocumentRibbonTags.FontSize,
                onPick = { onSetMark(Mark.FontSize(it.toInt())) },
            )
            RibbonDivider()
            RibbonIcon(DocumentSymbols.FormatBold, "Bold", Mark.Bold in marks, richText != null, WorkspaceTestTags.documentMark(Mark.Bold)) { onToggleMark(Mark.Bold) }
            RibbonIcon(DocumentSymbols.FormatItalic, "Italic", Mark.Italic in marks, richText != null, WorkspaceTestTags.documentMark(Mark.Italic)) { onToggleMark(Mark.Italic) }
            RibbonIcon(DocumentSymbols.FormatUnderlined, "Underline", Mark.Underline in marks, richText != null, WorkspaceTestTags.documentMark(Mark.Underline)) { onToggleMark(Mark.Underline) }
            RibbonIcon(DocumentSymbols.FormatStrikethrough, "Strikethrough", Mark.Strikethrough in marks, richText != null, WorkspaceTestTags.documentMark(Mark.Strikethrough)) { onToggleMark(Mark.Strikethrough) }
            ColorPicker(
                label = "Font colour",
                colors = TextColors,
                current = marks.filterIsInstance<Mark.TextColor>().firstOrNull()?.argb,
                enabled = richText != null,
                tag = DocumentRibbonTags.FontColor,
                icon = { neutral, swatch -> fontColorGlyph(neutral, swatch) },
                onPick = { onSetMark(Mark.TextColor(it)) },
                onClear = { onClearMark(Mark.TextColor(0)) },
            )
            ColorPicker(
                label = "Highlight",
                colors = HighlightColors,
                current = marks.filterIsInstance<Mark.Highlight>().firstOrNull()?.argb,
                enabled = richText != null,
                tag = DocumentRibbonTags.Highlight,
                icon = { _, _ -> DocumentSymbols.StylusHighlighter },
                onPick = { onSetMark(Mark.Highlight(it)) },
                onClear = { onClearMark(Mark.Highlight(0)) },
                rotateIcon = true,
            )
            RibbonIcon(if (Mark.Subscript in marks) active.subscript else idle.subscript, "Subscript", Mark.Subscript in marks, richText != null, WorkspaceTestTags.documentMark(Mark.Subscript), twoTone = true) { onToggleMark(Mark.Subscript) }
            RibbonIcon(if (Mark.Superscript in marks) active.superscript else idle.superscript, "Superscript", Mark.Superscript in marks, richText != null, WorkspaceTestTags.documentMark(Mark.Superscript), twoTone = true) { onToggleMark(Mark.Superscript) }
            RibbonIcon(DocumentSymbols.FormatClear, "Clear formatting", enabled = richText != null, tag = WorkspaceTestTags.ClearFormatting, onClick = onClearFormatting)
            RibbonDivider()
            RibbonIcon(if (block?.type == BlockType.Bullet) active.bulletList else idle.bulletList, "Bulleted list", block?.type == BlockType.Bullet, richText != null, WorkspaceTestTags.blockType(BlockType.Bullet), twoTone = true) { onBlockType(BlockType.Bullet) }
            RibbonIcon(if (block?.type == BlockType.Numbered) active.numberedList else idle.numberedList, "Numbered list", block?.type == BlockType.Numbered, richText != null, WorkspaceTestTags.blockType(BlockType.Numbered), twoTone = true) { onBlockType(BlockType.Numbered) }
            RibbonIcon(if (block?.type == BlockType.Todo) active.todoList else idle.todoList, "To-do", block?.type == BlockType.Todo, richText != null, WorkspaceTestTags.blockType(BlockType.Todo), twoTone = true) { onBlockType(BlockType.Todo) }
            RibbonIcon(DocumentSymbols.FormatIndentDecrease, "Decrease indent", enabled = richText != null) { onIndent(-1) }
            RibbonIcon(DocumentSymbols.FormatIndentIncrease, "Increase indent", enabled = richText != null) { onIndent(1) }
            RibbonDivider()
            RibbonIcon(DocumentSymbols.FormatAlignLeft, "Align left", block?.align == Align.Start, richText != null, WorkspaceTestTags.alignment(Align.Start)) { onAlign(Align.Start) }
            RibbonIcon(DocumentSymbols.FormatAlignCenter, "Align centre", block?.align == Align.Center, richText != null, WorkspaceTestTags.alignment(Align.Center)) { onAlign(Align.Center) }
            RibbonIcon(DocumentSymbols.FormatAlignRight, "Align right", block?.align == Align.End, richText != null, WorkspaceTestTags.alignment(Align.End)) { onAlign(Align.End) }
            RibbonDivider()
            StylesPicker(block?.type ?: BlockType.Paragraph, idle.styles, richText != null, onBlockType)
            RibbonDivider()
            RibbonIcon(DocumentSymbols.Function, "Equation", enabled = false, tag = DocumentRibbonTags.Equation) {}
            RibbonIcon(DocumentSymbols.Link, "Link", enabled = false, tag = DocumentRibbonTags.Link) {}
            RibbonIcon(DocumentSymbols.Image, "Picture", enabled = false, tag = DocumentRibbonTags.Picture) {}
        }
    }
}

@Composable
private fun RibbonIcon(
    icon: ImageVector,
    label: String,
    selected: Boolean = false,
    enabled: Boolean = true,
    tag: String = "document-${label.lowercase().replace(' ', '-')}",
    twoTone: Boolean = false,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .size(40.dp)
            .testTag(tag)
            .semantics { this.selected = selected }
            .clip(RoundedCornerShape(4.dp))
            .background(if (selected) colors.primaryContainer else Color.Transparent),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = if (twoTone) Color.Unspecified else if (selected) colors.onPrimaryContainer else colors.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
    }
}

@Composable
private fun RibbonDivider() {
    Spacer(Modifier.padding(horizontal = 6.dp).width(1.dp).height(22.dp).background(MaterialTheme.colorScheme.outlineVariant))
}

@Composable
private fun RibbonPicker(
    label: String,
    current: String,
    choices: List<Pair<String, String>>,
    enabled: Boolean,
    tag: String,
    onPick: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        Row(
            modifier = Modifier
                .testTag(tag)
                .clip(RoundedCornerShape(8.dp))
                .then(if (enabled) Modifier.clickable { expanded = true } else Modifier)
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
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            choices.forEach { (value, shown) ->
                DropdownMenuItem(
                    text = { Text(shown) },
                    onClick = {
                        expanded = false
                        onPick(value)
                    },
                    modifier = Modifier.testTag("$tag-$value"),
                )
            }
        }
    }
}

@Composable
private fun ColorPicker(
    label: String,
    colors: List<Int>,
    current: Int?,
    enabled: Boolean,
    tag: String,
    icon: (Color, Color) -> ImageVector,
    onPick: (Int) -> Unit,
    onClear: () -> Unit,
    rotateIcon: Boolean = false,
) {
    var expanded by remember { mutableStateOf(false) }
    val neutral = MaterialTheme.colorScheme.onSurfaceVariant
    val swatch = current?.takeIf { it != 0 }?.let { Color(it).copy(alpha = 1f) } ?: neutral
    val image = remember(neutral, swatch) { icon(neutral, swatch) }
    Box {
        IconButton(
            onClick = { expanded = true },
            enabled = enabled,
            modifier = Modifier.size(40.dp).testTag(tag),
        ) {
            Icon(
                imageVector = image,
                contentDescription = label,
                tint = if (rotateIcon) swatch else Color.Unspecified,
                modifier = Modifier.size(20.dp).then(if (rotateIcon) Modifier.rotate(180f) else Modifier),
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            Column(Modifier.padding(8.dp)) {
                colors.chunked(4).forEach { row ->
                    Row {
                        row.forEach { argb ->
                            Box(
                                Modifier.padding(3.dp).size(28.dp)
                                    .clip(RoundedCornerShape(5.dp))
                                    .background(Color(argb))
                                    .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(5.dp))
                                    .clickable {
                                        expanded = false
                                        onPick(argb)
                                    }
                                    .testTag("$tag-$argb"),
                            )
                        }
                    }
                }
                DropdownMenuItem(text = { Text("None") }, onClick = {
                    expanded = false
                    onClear()
                }, modifier = Modifier.testTag("$tag-none"))
            }
        }
    }
}

@Composable
private fun StylesPicker(
    current: BlockType,
    icon: ImageVector,
    enabled: Boolean,
    onPick: (BlockType) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val styles = listOf(
        BlockType.Paragraph to "Normal",
        BlockType.Heading1 to "Heading 1",
        BlockType.Heading2 to "Heading 2",
        BlockType.Heading3 to "Heading 3",
        BlockType.Quote to "Quote",
        BlockType.Code to "Code",
    )
    Box {
        Row(
            modifier = Modifier.testTag(WorkspaceTestTags.Styles)
                .clip(RoundedCornerShape(8.dp))
                .then(if (enabled) Modifier.clickable { expanded = true } else Modifier)
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, tint = Color.Unspecified, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(6.dp))
            Text(if (current == BlockType.Paragraph) "Styles" else styles.firstOrNull { it.first == current }?.second ?: "Styles", style = MaterialTheme.typography.labelMedium)
            Icon(DocumentSymbols.ArrowDropDown, contentDescription = null, modifier = Modifier.size(16.dp))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            styles.forEach { (type, label) ->
                DropdownMenuItem(text = { Text(label) }, onClick = {
                    expanded = false
                    onPick(type)
                }, modifier = Modifier.testTag(WorkspaceTestTags.blockType(type)))
            }
        }
    }
}
