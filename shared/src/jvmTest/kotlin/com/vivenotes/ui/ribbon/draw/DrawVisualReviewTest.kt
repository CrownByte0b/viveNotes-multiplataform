package com.vivenotes.ui.ribbon.draw

import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import com.vivenotes.ui.shell.WorkspaceScreen
import com.vivenotes.ui.theme.ViveNotesTheme
import com.vivenotes.workspace.RibbonTab
import com.vivenotes.workspace.WorkspaceState
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import java.io.File
import kotlin.test.Test

@OptIn(ExperimentalTestApi::class)
class DrawVisualReviewTest {
    @Test
    fun drawToolsAndTheirDropdownsFitTheDesktopRibbon() = runDesktopComposeUiTest(width = 1440, height = 1000) {
        val state = androidx.compose.runtime.mutableStateOf(WorkspaceState.demo().copy(activeTab = RibbonTab.Draw))
        setContent { ViveNotesTheme(darkTheme = false) { WorkspaceScreen(state.value, { state.value = it(state.value) }) } }
        for (tag in listOf(DrawRibbonTags.penKit(0), DrawRibbonTags.penKit(1), DrawRibbonTags.penKit(2),
            DrawRibbonTags.HighlighterKit, DrawRibbonTags.EraserModePicker, DrawRibbonTags.ShapeKit,
            TableTags.Kit, EquationTags.Kit, DrawRibbonTags.RulerKit)) onNodeWithTag(tag).assertIsDisplayed().assertIsEnabled()
        save(onRoot(), "draw-ribbon.png")
        onNodeWithTag(DrawRibbonTags.penKit(0)).performClick()
        onNodeWithTag(InkControlsTags.Preview).assertIsDisplayed()
        save(onNode(isRoot() and hasAnyDescendant(hasTestTag(InkControlsTags.Preview))), "draw-pen-kit.png")
    }

    private fun save(node: SemanticsNodeInteraction, name: String) {
        val directory = File("build/test-artifacts").apply { mkdirs() }
        Image.makeFromBitmap(node.captureToImage().asSkiaBitmap()).use { image ->
            image.encodeToData(EncodedImageFormat.PNG)!!.use { File(directory, name).writeBytes(it.bytes) }
        }
    }
}
