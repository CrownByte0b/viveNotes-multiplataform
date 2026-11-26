package com.vivenotes.workspace

import com.vivenotes.model.Mark
import com.vivenotes.model.PageStyle
import com.vivenotes.model.PaperDimensions
import com.vivenotes.model.PaperSize
import com.vivenotes.model.RuleLines

/**
 * Device preferences for newly written text and newly created pages, and the ink the Document tab's
 * Highlight button lays on text.
 *
 * [highlight] is the text highlighter's own, apart from the Draw highlighter's colour: marking up
 * text in green does not change what the pen draws over a page (Android `EditorDefaults.highlight`).
 */
data class EditorDefaults(
    val fontFamily: String = "sans-serif",
    val fontSize: Int = 15,
    val paper: PaperSize = PaperSize.Auto,
    val customPaper: PaperDimensions? = null,
    val ruleLines: RuleLines = RuleLines.GridMedium,
    val highlight: Int = DEFAULT_HIGHLIGHT,
) {
    fun normalized(): EditorDefaults = copy(
        fontFamily = fontFamily.takeIf { it in SUPPORTED_FONTS } ?: "sans-serif",
        fontSize = fontSize.takeIf { it in SUPPORTED_SIZES } ?: 15,
        highlight = highlight.takeIf { it in SUPPORTED_HIGHLIGHTS } ?: DEFAULT_HIGHLIGHT,
        customPaper = customPaper?.takeIf {
            it.widthInches in PaperDimensions.MIN_INCHES..PaperDimensions.MAX_INCHES &&
                it.heightInches in PaperDimensions.MIN_INCHES..PaperDimensions.MAX_INCHES
        },
    )

    fun textMarks(family: String = fontFamily, size: Int = fontSize): Set<Mark> =
        setOf(Mark.FontFamily(family), Mark.FontSize(size))

    fun pageStyle(): PageStyle = PageStyle(ruleLines = ruleLines, paper = paper, customPaper = customPaper)

    companion object {
        val SUPPORTED_FONTS = setOf("sans-serif", "serif", "monospace", "inter", "lora", "jetbrains_mono")
        val SUPPORTED_SIZES = setOf(8, 9, 10, 11, 12, 14, 15, 16, 18, 20, 24, 28, 36, 48, 72)

        /** Yellow, the Draw highlighter's first ink as on Android. */
        const val DEFAULT_HIGHLIGHT = 0x66FFEB3B

        /** The Document tab's highlight palette: an ink from anywhere else is not one it offers. */
        val SUPPORTED_HIGHLIGHTS = setOf(
            0x66FFEB3B, 0x6676FF03, 0x6640C4FF, 0x66FF4081,
            0x66FF9100, 0x66B388FF, 0x66FFFFFF, 0x00000000,
        )
    }
}
