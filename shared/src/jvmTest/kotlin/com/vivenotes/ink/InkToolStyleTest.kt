package com.vivenotes.ink

import com.vivenotes.byteink.kit.InkBrushCatalog
import com.vivenotes.model.ink.LineType
import com.vivenotes.workspace.InkBrush
import com.vivenotes.workspace.InkHighlighterSettings
import com.vivenotes.workspace.InkPenSettings
import com.vivenotes.workspace.InkSettings
import com.vivenotes.workspace.InkTool
import kotlin.test.Test
import kotlin.test.assertEquals

/** The shared pen and highlighter styles both desktop and the browser draw new strokes with. */
class InkToolStyleTest {
    private val automatic = 0xff1e1e1e.toInt()

    @Test
    fun everyPenBrushNamesItsCatalogFamily() {
        val families = mapOf(InkBrush.Marker to InkBrushCatalog.MARKER, InkBrush.PressurePen to InkBrushCatalog.PRESSURE_PEN,
            InkBrush.DashedLine to InkBrushCatalog.DASHED_LINE)
        for ((brush, family) in families)
            assertEquals(family, InkSettings(pen = InkPenSettings(brush)).inkToolStyle(InkTool.Pen, automatic).familyId)
        for (pressure in 0..5) assertEquals(InkBrushCatalog.calligraphy(pressure),
            InkSettings(pen = InkPenSettings(InkBrush.Calligraphy, pressure = pressure)).inkToolStyle(InkTool.Pen, automatic).familyId)
    }

    @Test
    fun aBrokenLineTypeDrawsTheDashedFamilyWhateverTheBrush() {
        for (brush in InkBrush.entries) for (type in LineType.entries.filter { it != LineType.Solid })
            assertEquals(InkBrushCatalog.DASHED_LINE,
                InkSettings(pen = InkPenSettings(brush, lineType = type)).inkToolStyle(InkTool.Pen, automatic).familyId)
    }

    @Test
    fun anAutomaticPenTakesThePageInkAndAFixedOneItsOwnOpaqueColour() {
        val follows = InkSettings(pen = InkPenSettings(colorArgb = 0xff00ff00.toInt(), colorFollowsTheme = true))
            .inkToolStyle(InkTool.Pen, automatic)
        assertEquals(InkToolStyle(InkBrushCatalog.MARKER, 1.5f, automatic, true, 1), follows)
        val fixed = InkSettings(pen = InkPenSettings(sizeDp = 99f, colorArgb = 0x2200ff00, colorFollowsTheme = false,
            stabilization = 9)).inkToolStyle(InkTool.Pen, automatic)
        assertEquals(InkToolStyle(InkBrushCatalog.MARKER, InkPenSettings.MAX_SIZE, 0xff00ff00.toInt(), false, 5), fixed,
            "settings are normalized: size and stabilization clamp, the pen colour is opaque")
    }

    @Test
    fun eachPenSlotIsItsOwnPresetAndOtherToolsUseTheChosenPen() {
        val settings = InkSettings(pen = InkPenSettings(sizeDp = 2f), pen2 = InkPenSettings(sizeDp = 3f, colorFollowsTheme = false),
            pen3 = InkPenSettings(InkBrush.PressurePen, sizeDp = 4f, colorFollowsTheme = false))
        assertEquals(listOf(2f, 3f, 4f), (0..2).map { settings.inkToolStyle(InkTool.Pen, automatic, it).sizeDp })
        assertEquals(InkBrushCatalog.PRESSURE_PEN, settings.inkToolStyle(InkTool.Pen, automatic, 2).familyId)
        for (tool in listOf(null, InkTool.Eraser, InkTool.Laser))
            assertEquals(settings.inkToolStyle(InkTool.Pen, automatic, 1), settings.inkToolStyle(tool, automatic, 1))
    }

    @Test
    fun theHighlighterKeepsItsStoredTranslucentColourAndNoStabilization() {
        val settings = InkSettings(highlighter = InkHighlighterSettings(27.5f, 0x66654321))
        assertEquals(InkToolStyle(InkBrushCatalog.HIGHLIGHTER, 27.5f, 0x66654321, false, 0),
            settings.inkToolStyle(InkTool.Highlighter, automatic, penIndex = 2))
    }

    @Test
    fun desktopBuildsAValidBrushForEveryStyle() {
        val settings = InkBrush.entries.flatMap { brush -> LineType.entries.flatMap { type -> (0..5).map { pressure ->
            InkSettings(pen = InkPenSettings(brush, lineType = type, pressure = pressure))
        } } } + InkSettings()
        for (value in settings) for (tool in listOf(InkTool.Pen, InkTool.Highlighter)) {
            val style = value.inkToolStyle(tool, automatic)
            val desktop = value.byteInkTool(tool, automatic)
            assertEquals(style, InkToolStyle(desktop.familyId, desktop.sizeDp, desktop.colorArgb,
                desktop.colorFollowsTheme == true, desktop.stabilization))
            desktop.brush
        }
    }
}
