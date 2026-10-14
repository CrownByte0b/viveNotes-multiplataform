package com.vivenotes.workspace

import com.vivenotes.diagnostics.DebugLog
import com.vivenotes.model.PageDoc
import com.vivenotes.ui.ribbon.draw.parseInkColor
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class InkSettingsTest {
    @Test
    fun widthsAreFiniteAndBoundedAndHighlightersRemainTranslucent() {
        assertEquals(InkSettings(), InkSettings().normalized())
        for (invalid in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            assertEquals(1.5f, InkPenSettings(sizeDp = invalid).normalized().sizeDp)
            assertEquals(18f, InkHighlighterSettings(sizeDp = invalid).normalized().sizeDp)
        }
        assertEquals(1f, InkPenSettings(sizeDp = -1f).normalized().sizeDp)
        assertEquals(8f, InkPenSettings(sizeDp = 1000f).normalized().sizeDp)
        assertEquals(6f, InkHighlighterSettings(sizeDp = -1f).normalized().sizeDp)
        assertEquals(40f, InkHighlighterSettings(sizeDp = 1000f).normalized().sizeDp)
        assertEquals(0xff123456.toInt(), InkPenSettings(colorArgb = 0x00123456).normalized().colorArgb)
        assertEquals(0x66123456, InkHighlighterSettings(colorArgb = 0xff123456.toInt()).normalized().colorArgb)
    }

    @Test
    fun hexColorsAcceptRgbAndRejectMalformedOrSignedValues() {
        for (hex in listOf("#aAbBcC", "aabbcc", "  #AABBCC  ")) assertEquals(0xffaabbcc.toInt(), parseInkColor(hex))
        for (invalid in listOf("", "fff", "#ffaabbcc", "-12345", "+12345", "##123456", "gggggg"))
            assertNull(parseInkColor(invalid), invalid)
    }

    @Test
    fun preferencesStayIndependentAndDoNotChangeSavedContentOrHistory() {
        val initial = WorkspaceState.demo().toggleInkTool(InkTool.Pen)
        val settings = InkSettings(InkPenSettings(InkBrush.Calligraphy, 7.5f, 0xff3584e4.toInt(), false),
            InkHighlighterSettings(32f, 0x66e01b24))
        val changed = initial.setInkSettings(settings)
        assertSame(initial.selectedPage!!.document, changed.selectedPage!!.document)
        assertEquals(initial.structuralUndo, changed.structuralUndo)
        assertEquals(initial.pendingInkEdits, changed.pendingInkEdits)
        assertEquals(settings, changed.toggleInkTool(InkTool.Highlighter).inkSettings)
        assertEquals(settings, changed.selectPointer().inkSettings)
        assertEquals(settings, changed.selectPage(changed.selectedSection!!.pages.last().id).inkSettings)
        assertEquals(8f, initial.setInkSettings(settings.copy(pen = settings.pen.copy(sizeDp = 100f))).inkSettings.pen.sizeDp)
    }

    @Test
    fun sessionSeedsPreferencesAndLogsChangesOnlyWhenDebugIsEnabled() = runTest {
        for (enabled in listOf(false, true)) {
            val store = FakeNotesStore()
            store.notebook("Notebook", "Section" to listOf("Page" to PageDoc.empty()))
            val settings = InkSettings(pen = InkPenSettings(InkBrush.DashedLine, 8f))
            val logs = mutableListOf<String>()
            val session = WorkspaceSession(store, backgroundScope, { "" }, inkSettings = settings,
                log = DebugLog(enabled, logs::add))
            session.start()
            runCurrent()
            assertEquals(settings, session.state.value!!.inkSettings)
            logs.clear()
            session.update { it.setInkSettings(settings.copy(pen = settings.pen.copy(sizeDp = 4f))) }
            runCurrent()
            assertTrue(store.saves.isEmpty())
            if (enabled) assertEquals(1, logs.count { it.contains("drawing preferences changed") })
            else assertTrue(logs.isEmpty())
        }
    }
}
