package com.vivenotes.workspace

import com.vivenotes.model.Mark
import com.vivenotes.model.PageStyle
import com.vivenotes.model.PaperDimensions
import com.vivenotes.model.PaperSize
import com.vivenotes.model.RuleLines

/** Device preferences for newly written text and newly created pages. */
data class EditorDefaults(
    val fontFamily: String = "sans-serif",
    val fontSize: Int = 15,
    val paper: PaperSize = PaperSize.Auto,
    val customPaper: PaperDimensions? = null,
    val ruleLines: RuleLines = RuleLines.GridMedium,
) {
    fun normalized(): EditorDefaults = copy(
        fontFamily = fontFamily.takeIf { it in SUPPORTED_FONTS } ?: "sans-serif",
        fontSize = fontSize.takeIf { it in SUPPORTED_SIZES } ?: 15,
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
    }
}
