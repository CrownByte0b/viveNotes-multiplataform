// Ported from viveNotes Android fileimport/docx/WordFonts.kt. Skia typefaces stand in for
// android.graphics.Typeface; the desktop also uses the document's own font when it is installed.
package com.vivenotes.fileimport.docx

import org.jetbrains.skia.FontMgr
import org.jetbrains.skia.FontStyle
import org.jetbrains.skia.Typeface
import org.jetbrains.skia.paragraph.FontCollection
import org.jetbrains.skia.paragraph.ParagraphBuilder
import org.jetbrains.skia.paragraph.ParagraphStyle
import org.jetbrains.skia.paragraph.TextStyle
import org.jetbrains.skia.paragraph.TypefaceFontProvider

/**
 * A device typeface standing in for one of Word's, and what it takes to keep the document's shape.
 *
 * [sizeScale] makes a line of text as long as it was in Word, so lines break where Word broke them;
 * [lineHeightFactor] makes it as tall. [family] is the name the layout's paragraphs ask for it by.
 */
internal class WordFace(
    val typeface: Typeface,
    val family: String,
    val sizeScale: Float,
    val lineHeightFactor: Float,
)

/**
 * Word's fonts, as this computer has them, and a page count depends on nothing so much as the font.
 *
 * Android has none of Word's fonts, so it draws each in the system family closest to it, at the size
 * that makes a line the same length. A desktop often has the font itself — Windows ships Calibri,
 * Cambria and Arial — or a metric-compatible one that fontconfig answers for its name (Carlito for
 * Calibri, Caladea for Cambria, Liberation for Arial, Times New Roman and Courier New). So the
 * document's own name is asked for first and the family closest to it only when nothing answers.
 * Either way the width is measured here and scaled to [KNOWN]'s, as Android does: a face that really
 * is the font, or its metric twin, comes out at a scale of one. The numbers in [KNOWN] are measured
 * from the metric-compatible fonts, or else taken from the font's published metrics.
 *
 * Every face is registered under a name of its own in [collection], which the layout's paragraphs
 * use, and which falls back to the system's fonts for characters a face does not have.
 */
internal class WordFonts(private val fontTable: Map<String, String> = emptyMap()) {
    private class Known(val generic: String, val widthPerEm: Float, val lineHeightPerEm: Float)

    private class FaceMetrics(val widthPerEm: Float, val lineHeightPerEm: Float)

    private val provider = TypefaceFontProvider()

    /** The fonts a paragraph of this document may ask for: these faces first, then the system's. */
    val collection: FontCollection = FontCollection().apply {
        setAssetFontManager(provider)
        setDefaultFontManager(FontMgr.default)
    }

    private val metrics = HashMap<Int, FaceMetrics>()
    private val faces = HashMap<Triple<String, Boolean, Boolean>, WordFace>()
    private val registered = HashMap<Int, String>()

    private fun metricsOf(typeface: Typeface): FaceMetrics = metrics.getOrPut(typeface.uniqueId) {
        val family = familyFor(typeface)
        val style = TextStyle().setFontFamilies(arrayOf(family)).setFontSize(MEASURE_SIZE)
        // Measured as the layout will set it, shaped and kerned, and not glyph by glyph.
        val width = ParagraphBuilder(ParagraphStyle(), collection).use { builder ->
            builder.pushStyle(style)
            builder.addText(SAMPLE)
            builder.build().use { paragraph -> paragraph.layout(Float.POSITIVE_INFINITY).maxIntrinsicWidth }
        }
        val font = org.jetbrains.skia.Font(typeface, MEASURE_SIZE)
        val fontMetrics = font.metrics
        font.close()
        FaceMetrics(width / MEASURE_SIZE / SAMPLE.length, (fontMetrics.descent - fontMetrics.ascent + fontMetrics.leading) / MEASURE_SIZE)
    }

    private fun familyFor(typeface: Typeface): String = registered.getOrPut(typeface.uniqueId) {
        val name = "vive-word-${registered.size}"
        provider.registerTypeface(typeface, name)
        name
    }

    private fun genericFor(name: String): String {
        val lower = name.lowercase()
        if (isMonospaceFontName(lower)) return "monospace"
        when (fontTable[lower]) {
            "modern" -> return "monospace"
            "roman" -> return "serif"
            "swiss" -> return "sans-serif"
        }
        return if (SERIF_NAME_PARTS.any { it in lower }) "serif" else "sans-serif"
    }

    /** The document's own font, or the one this system answers for its name; null when there is none. */
    private fun installed(name: String, style: FontStyle): Typeface? =
        name.takeIf { it.isNotBlank() }?.let { FontMgr.default.matchFamilyStyle(it, style) }

    private fun generic(generic: String, style: FontStyle): Typeface =
        GENERIC_FAMILIES.getValue(generic).firstNotNullOfOrNull { FontMgr.default.matchFamilyStyle(it, style) }
            ?: FontMgr.default.legacyMakeTypeface("", style)
            ?: requireNotNull(FontMgr.default.matchFamilyStyle(FontMgr.default.getFamilyName(0), style))

    fun face(name: String?, bold: Boolean, italic: Boolean): WordFace {
        val key = Triple(name?.lowercase().orEmpty(), bold, italic)
        return faces.getOrPut(key) {
            val known = name?.lowercase()?.let(KNOWN::get)
            val genericName = known?.generic ?: name?.let(::genericFor) ?: "sans-serif"
            val style = when {
                bold && italic -> FontStyle.BOLD_ITALIC
                bold -> FontStyle.BOLD
                italic -> FontStyle.ITALIC
                else -> FontStyle.NORMAL
            }
            val own = name?.let { installed(it, FontStyle.NORMAL) }
            // The width is the regular face's, as Android measures its generic family's.
            val measured = metricsOf(own ?: generic(genericName, FontStyle.NORMAL))
            val typeface = own?.let { installed(it.familyName, style) ?: installed(name, style) }
                ?: generic(genericName, style)
            val sizeScale = known?.let { (it.widthPerEm / measured.widthPerEm).coerceIn(0.7f, 1.3f) } ?: 1f
            val lineHeight = known?.lineHeightPerEm ?: (measured.lineHeightPerEm * sizeScale)
            WordFace(
                typeface = typeface,
                family = familyFor(typeface),
                sizeScale = sizeScale,
                lineHeightFactor = lineHeight / (measured.lineHeightPerEm * sizeScale),
            )
        }
    }

    private companion object {
        const val MEASURE_SIZE = 1000f

        /** A run of ordinary English, because a font's width is an average over the letters used. */
        const val SAMPLE =
            "The IEEE Floating Point Standard was adopted in 1985 and is the standard still used today. " +
                "We have learned how to represent binary integers and digits to the right would not be much " +
                "more difficult, but the question remains: how to store the decimal point in binary? " +
                "Scientific notation expresses a real value this way, with a single non-zero digit."

        /**
         * Android's three generic families, as Compose's desktop resolver spells them: fontconfig
         * answers the generic name on Linux, and Windows needs the family that stands for it.
         */
        val GENERIC_FAMILIES: Map<String, List<String>> = mapOf(
            "sans-serif" to listOf("sans-serif", "Arial", "Liberation Sans", "DejaVu Sans", "Noto Sans", "Helvetica"),
            "serif" to listOf("serif", "Times New Roman", "Liberation Serif", "DejaVu Serif", "Noto Serif", "Times"),
            "monospace" to listOf("monospace", "Consolas", "Courier New", "Liberation Mono", "DejaVu Sans Mono", "Noto Sans Mono", "Courier"),
        )

        val SERIF_NAME_PARTS = listOf(
            "serif", "times", "georgia", "garamond", "palatino", "book antiqua", "cambria", "minion", "baskerville",
            "bodoni", "constantia", "century", "roman", "didot",
        )

        val KNOWN: Map<String, Known> = mapOf(
            "calibri" to Known("sans-serif", 0.400f, 1.2207f),
            "calibri light" to Known("sans-serif", 0.395f, 1.2207f),
            "aptos" to Known("sans-serif", 0.440f, 1.221f),
            "aptos display" to Known("sans-serif", 0.430f, 1.221f),
            "aptos narrow" to Known("sans-serif", 0.380f, 1.221f),
            "arial" to Known("sans-serif", 0.4362f, 1.1499f),
            "helvetica" to Known("sans-serif", 0.4362f, 1.1499f),
            "helvetica neue" to Known("sans-serif", 0.4362f, 1.1499f),
            "arial narrow" to Known("sans-serif", 0.358f, 1.1499f),
            "tahoma" to Known("sans-serif", 0.449f, 1.207f),
            "verdana" to Known("sans-serif", 0.511f, 1.215f),
            "segoe ui" to Known("sans-serif", 0.441f, 1.33f),
            "trebuchet ms" to Known("sans-serif", 0.439f, 1.161f),
            "century gothic" to Known("sans-serif", 0.483f, 1.18f),
            "cambria" to Known("serif", 0.420f, 1.1724f),
            "times new roman" to Known("serif", 0.3966f, 1.1499f),
            "times" to Known("serif", 0.3966f, 1.1499f),
            "georgia" to Known("serif", 0.457f, 1.136f),
            "garamond" to Known("serif", 0.371f, 1.125f),
            "palatino linotype" to Known("serif", 0.420f, 1.35f),
            "book antiqua" to Known("serif", 0.420f, 1.24f),
            "courier new" to Known("monospace", 0.6001f, 1.1328f),
            "courier" to Known("monospace", 0.6001f, 1.1328f),
            "consolas" to Known("monospace", 0.5498f, 1.1709f),
            "lucida console" to Known("monospace", 0.6025f, 1.14f),
        )
    }
}
