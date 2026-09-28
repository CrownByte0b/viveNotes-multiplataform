package com.vivenotes.ui.ribbon.document

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import com.vivenotes.model.Align
import com.vivenotes.model.BlockType
import com.vivenotes.model.Mark
import com.vivenotes.richtext.LinkTarget
import com.vivenotes.richtext.RichTextBuffer
import com.vivenotes.richtext.TextSelection
import com.vivenotes.ui.icons.DocumentSymbols
import com.vivenotes.ui.icons.fontColorGlyph
import com.vivenotes.ui.icons.rememberDocumentRibbonIcons
import com.vivenotes.ui.ribbon.RibbonBar
import com.vivenotes.ui.ribbon.RibbonDivider
import com.vivenotes.ui.ribbon.RibbonIcon
import com.vivenotes.workspace.EditorDefaults

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
    fun colorMenu(tag: String): String = "$tag-menu"
    const val ClearFormatting = "workspace-clear-formatting"
    const val DecreaseIndent = "document-decrease-indent"
    const val IncreaseIndent = "document-increase-indent"
    const val Styles = "workspace-document-styles"
    const val Equation = "document-equation"
    const val Link = "document-link"
    const val LinkPanel = "document-link-panel"
    const val LinkText = "document-link-text"
    const val LinkAddress = "document-link-address"
    const val LinkSubmit = "document-link-submit"
    const val LinkCancel = "document-link-cancel"
    const val Picture = "document-picture"

    fun blockType(type: BlockType): String = "workspace-document-block-${type.name}"
    fun alignment(align: Align): String = "workspace-document-align-${align.name}"
    fun mark(mark: Mark): String = "workspace-document-${mark::class.simpleName}"
}

/**
 * The editor range a ribbon press acts on, read as the pointer goes down: by the time the click
 * lands, the editor may have lost focus and collapsed its selection.
 */
internal fun Modifier.captureSelectionOnPress(
    selection: TextSelection?,
    onCapture: (TextSelection?) -> Unit,
): Modifier = pointerInput(selection) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        onCapture(selection)
        waitForUpOrCancellation(pass = PointerEventPass.Initial)
    }
}

@Composable
internal fun DocumentRibbon(
    richText: RichTextBuffer?,
    textToolArmed: Boolean,
    commands: DocumentCommands,
    colorSelection: DocumentColorSelection,
    fontFamily: String,
    fontSize: Int,
    defaults: EditorDefaults,
    onLinkRequest: (LinkEditorRequest) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val accent = if (colors.surface.luminance() < 0.5f) Color(0xFF3B9ADC) else Color(0xFF1B6FA8)
    val (idle, active) = rememberDocumentRibbonIcons(colors.onSurfaceVariant, colors.onPrimaryContainer, accent)
    val marks = richText?.activeMarks.orEmpty()
    val block = richText?.currentBlock
    val editable = richText != null
    var selectionAtPress by remember { mutableStateOf<TextSelection?>(null) }
    fun takeSelection(): TextSelection? {
        val captured = selectionAtPress
        selectionAtPress = null
        return captured
    }
    val hasRange = editable && (!richText.selection.collapsed || selectionAtPress?.collapsed == false)
    RibbonBar(Modifier.captureSelectionOnPress(richText?.selection) { selectionAtPress = it }) {
        RibbonIcon(if (textToolArmed) active.insertText else idle.insertText, "Text",
            selected = textToolArmed, tag = DocumentRibbonTags.Text, twoTone = true,
            onClick = commands.toggleTextTool)
        RibbonDivider()
        RibbonIcon(DocumentSymbols.ContentPaste, "Paste", enabled = editable, tag = DocumentRibbonTags.Paste) { commands.paste(takeSelection()) }
        RibbonIcon(DocumentSymbols.ContentCut, "Cut", enabled = hasRange, tag = DocumentRibbonTags.Cut) { commands.cut(takeSelection()) }
        RibbonIcon(DocumentSymbols.ContentCopy, "Copy", enabled = hasRange, tag = DocumentRibbonTags.Copy) { commands.copy(takeSelection()) }
        RibbonDivider()
        RibbonPicker(
            label = "Font family",
            current = fontFamily,
            default = defaults.fontFamily,
            choices = FontFamilies,
            enabled = commands.canChooseFont,
            selection = richText?.selection,
            tag = DocumentRibbonTags.FontFamily,
            onPick = commands.chooseFontFamily,
            onSetDefault = commands.setDefaultFontFamily,
        )
        RibbonPicker(
            label = "Font size",
            current = fontSize.toString(),
            default = defaults.fontSize.toString(),
            choices = FontSizes.map { it.toString() to it.toString() },
            enabled = commands.canChooseFont,
            selection = richText?.selection,
            tag = DocumentRibbonTags.FontSize,
            onPick = { value, selection -> commands.chooseFontSize(value.toInt(), selection) },
            onSetDefault = { commands.setDefaultFontSize(it.toInt()) },
        )
        RibbonDivider()
        MarkButton(DocumentSymbols.FormatBold, "Bold", Mark.Bold, marks, editable) { commands.toggleMark(Mark.Bold, takeSelection()) }
        MarkButton(DocumentSymbols.FormatItalic, "Italic", Mark.Italic, marks, editable) { commands.toggleMark(Mark.Italic, takeSelection()) }
        MarkButton(DocumentSymbols.FormatUnderlined, "Underline", Mark.Underline, marks, editable) { commands.toggleMark(Mark.Underline, takeSelection()) }
        MarkButton(DocumentSymbols.FormatStrikethrough, "Strikethrough", Mark.Strikethrough, marks, editable) { commands.toggleMark(Mark.Strikethrough, takeSelection()) }
        ColorPicker(
            label = "Font colour",
            colors = TextColors,
            defaultColor = 0xFFE53935.toInt(),
            chosenColor = colorSelection.font,
            onChooseColor = { colorSelection.font = it },
            enabled = editable,
            selection = richText?.selection,
            tag = DocumentRibbonTags.FontColor,
            icon = { neutral, swatch -> fontColorGlyph(neutral, swatch) },
            onPick = { value, selection -> commands.setMark(Mark.TextColor(value), selection) },
            onClear = { selection -> commands.clearMark(Mark.TextColor(0), selection) },
        )
        ColorPicker(
            label = "Highlight",
            colors = HighlightColors,
            defaultColor = 0x66FFEB3B,
            chosenColor = colorSelection.highlight,
            onChooseColor = { colorSelection.highlight = it },
            enabled = editable,
            selection = richText?.selection,
            tag = DocumentRibbonTags.Highlight,
            icon = { _, _ -> DocumentSymbols.StylusHighlighter },
            onPick = { value, selection -> commands.setMark(Mark.Highlight(value), selection) },
            onClear = { selection -> commands.clearMark(Mark.Highlight(0), selection) },
            rotateIcon = true,
        )
        MarkButton(if (Mark.Subscript in marks) active.subscript else idle.subscript, "Subscript", Mark.Subscript, marks, editable, twoTone = true) { commands.toggleMark(Mark.Subscript, takeSelection()) }
        MarkButton(if (Mark.Superscript in marks) active.superscript else idle.superscript, "Superscript", Mark.Superscript, marks, editable, twoTone = true) { commands.toggleMark(Mark.Superscript, takeSelection()) }
        RibbonIcon(DocumentSymbols.FormatClear, "Clear formatting", enabled = editable, tag = DocumentRibbonTags.ClearFormatting) { commands.clearFormatting(takeSelection()) }
        RibbonDivider()
        ListButton(if (block?.type == BlockType.Bullet) active.bulletList else idle.bulletList, "Bulleted list", BlockType.Bullet, block?.type, editable) { commands.setBlockType(BlockType.Bullet, takeSelection()) }
        ListButton(if (block?.type == BlockType.Numbered) active.numberedList else idle.numberedList, "Numbered list", BlockType.Numbered, block?.type, editable) { commands.setBlockType(BlockType.Numbered, takeSelection()) }
        ListButton(if (block?.type == BlockType.Todo) active.todoList else idle.todoList, "To-do", BlockType.Todo, block?.type, editable) { commands.setBlockType(BlockType.Todo, takeSelection()) }
        RibbonIcon(DocumentSymbols.FormatIndentDecrease, "Decrease indent", enabled = editable,
            tag = DocumentRibbonTags.DecreaseIndent) { commands.indent(-1, takeSelection()) }
        RibbonIcon(DocumentSymbols.FormatIndentIncrease, "Increase indent", enabled = editable,
            tag = DocumentRibbonTags.IncreaseIndent) { commands.indent(1, takeSelection()) }
        RibbonDivider()
        AlignButton(DocumentSymbols.FormatAlignLeft, "Align left", Align.Start, block?.align, editable) { commands.align(Align.Start, takeSelection()) }
        AlignButton(DocumentSymbols.FormatAlignCenter, "Align centre", Align.Center, block?.align, editable) { commands.align(Align.Center, takeSelection()) }
        AlignButton(DocumentSymbols.FormatAlignRight, "Align right", Align.End, block?.align, editable) { commands.align(Align.End, takeSelection()) }
        RibbonDivider()
        StylesPicker(block?.type ?: BlockType.Paragraph, idle.styles, editable, richText?.selection, commands.setBlockType)
        RibbonDivider()
        // An inline equation needs a LaTeX renderer on desktop; until then the button stays disabled.
        RibbonIcon(DocumentSymbols.Function, "Equation", enabled = false, tag = DocumentRibbonTags.Equation) {}
        LinkButton(
            enabled = editable,
            onClick = {
                val captured = takeSelection() ?: richText?.selection
                onLinkRequest(LinkEditorRequest(
                    captured,
                    captured?.let { richText?.select(it)?.linkTarget } ?: LinkTarget("", null),
                    commands.insertLink,
                ))
            },
        )
        RibbonIcon(DocumentSymbols.Image, "Picture", enabled = commands.insertPicture != null,
            tag = DocumentRibbonTags.Picture) { commands.insertPicture?.invoke() }
    }
}

@Composable
private fun MarkButton(
    icon: ImageVector,
    label: String,
    mark: Mark,
    active: Set<Mark>,
    enabled: Boolean,
    twoTone: Boolean = false,
    onClick: () -> Unit,
) = RibbonIcon(icon, label, mark in active, enabled, DocumentRibbonTags.mark(mark), twoTone, onClick)

@Composable
private fun ListButton(
    icon: ImageVector,
    label: String,
    type: BlockType,
    current: BlockType?,
    enabled: Boolean,
    onClick: () -> Unit,
) = RibbonIcon(icon, label, current == type, enabled, DocumentRibbonTags.blockType(type), twoTone = true, onClick = onClick)

@Composable
private fun AlignButton(
    icon: ImageVector,
    label: String,
    align: Align,
    current: Align?,
    enabled: Boolean,
    onClick: () -> Unit,
) = RibbonIcon(icon, label, current == align, enabled, DocumentRibbonTags.alignment(align), onClick = onClick)
