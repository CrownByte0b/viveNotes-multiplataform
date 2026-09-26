package com.vivenotes.ui.components

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isPopup
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.click
import androidx.compose.ui.test.rightClick
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import com.vivenotes.model.Mark
import com.vivenotes.model.Outline
import com.vivenotes.ui.ribbon.document.DocumentRibbonTags
import com.vivenotes.ui.shell.WorkspaceScreen
import com.vivenotes.ui.shell.WorkspaceTestTags
import com.vivenotes.ui.theme.ViveNotesTheme
import com.vivenotes.workspace.RibbonTab
import com.vivenotes.workspace.WorkspaceState
import com.vivenotes.workspace.focusBody
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The app's tooltip rule, checked over the whole workspace rather than one tab: every clickable
 * control that has alt text shows that same text in a tooltip while the mouse is over it.
 */
@OptIn(ExperimentalTestApi::class)
class HoverTooltipRuleTest {

    private val controlsWithAltText = hasClickAction() and !hasSetTextAction() and
        SemanticsMatcher.keyIsDefined(SemanticsProperties.ContentDescription)

    private val inTooltip = hasAnyAncestor(SemanticsMatcher.keyIsDefined(SemanticsProperties.PaneTitle))

    @Test
    fun ribbonNavigationAndTextToolkitControlsShowTheirAltTextOnHover() =
        runDesktopComposeUiTest(width = 2000, height = 900) {
            setWorkspace(WorkspaceState.demo().copy(activeTab = RibbonTab.Document).focusBody())
            onNodeWithTag(WorkspaceTestTags.BodyEditor).performClick()

            val labels = hoverEveryControl()
            listOf(
                "Hide notebook navigation", "Undo canvas action", "Redo canvas action", "Text", "Paste",
                "Font family", "Font size", "Bold", "Font colour", "Highlight", "Clear formatting",
                "Bulleted list", "Numbered list", "To-do", "Decrease indent", "Increase indent",
                "Align centre", "Styles", "Link", "Copy text box", "Select all text", "Delete text box",
            ).forEach { assertTrue(it in labels, "no control labelled \"$it\" was checked: $labels") }
        }

    @Test
    fun objectToolkitAndSwatchesShowTheirAltTextOnHover() =
        runDesktopComposeUiTest(width = 2000, height = 900) {
            val base = WorkspaceState.demo().copy(activeTab = RibbonTab.Document)
            val shape = Outline.Shape(id = "tooltip-shape", x = 300f, y = 350f)
            setWorkspace(base.copy(notebooks = base.notebooks.map { notebook ->
                notebook.copy(sections = notebook.sections.map { section ->
                    section.copy(pages = section.pages.map { page ->
                        if (page.id != base.selectedPageId) page
                        else page.copy(document = page.document.copy(outlines = page.document.outlines + shape))
                    })
                })
            }))
            onNodeWithTag(WorkspaceTestTags.primeObject(shape.id)).performClick()
            val toolkit = hoverEveryControl()
            listOf("Change object colour", "Copy selection", "Lock selection", "Delete selection")
                .forEach { assertTrue(it in toolkit, "no control labelled \"$it\" was checked: $toolkit") }

            onNodeWithTag(WorkspaceTestTags.ObjectColor).performClick()
            assertTrue("Purple" in hoverEveryControl(inMenu = true), "the object colour swatches were not checked")
        }

    @Test
    fun textColourSwatchesShowTheirNamesOnHover() = runDesktopComposeUiTest(width = 2000, height = 900) {
        setWorkspace(WorkspaceState.demo().copy(activeTab = RibbonTab.Document).focusBody())
        onNodeWithTag(DocumentRibbonTags.FontColor).performClick()

        val labels = hoverEveryControl(inMenu = true)
        listOf("Red", "Light grey", "Cyan").forEach {
            assertTrue(it in labels, "no swatch labelled \"$it\" was checked: $labels")
        }
    }

    /**
     * The right-click menus are outside the rule: each item shows its name beside its icon, so no
     * item may be an icon with alt text alone.
     */
    @Test
    fun rightClickMenuItemsShowTheirNamesInsteadOfTooltips() = runDesktopComposeUiTest(width = 2000, height = 900) {
        setWorkspace(WorkspaceState.demo())

        onNodeWithTag(WorkspaceTestTags.page("lecture-notes")).performMouseInput { rightClick(center) }
        assertMenuNamesItsItems("Rename page", "Delete page")
        onNodeWithText("Notebooks").performMouseInput { click(center) }

        onNodeWithTag(WorkspaceTestTags.BodyEditor).performMouseInput { rightClick(Offset(15f, 10f)) }
        assertMenuNamesItsItems("Cut", "Copy", "Paste", "Paste as plain text", "Select all")
    }

    private fun ComposeUiTest.assertMenuNamesItsItems(vararg names: String) {
        waitForIdle()
        val inMenu = hasClickAction() and hasAnyAncestor(isPopup())
        val iconOnly = onAllNodes(controlsWithAltText and hasAnyAncestor(isPopup())).fetchSemanticsNodes()
            .map { it.config[SemanticsProperties.ContentDescription] }
        assertTrue(iconOnly.isEmpty(), "menu items named only by alt text: $iconOnly")
        names.forEach { assertTrue(onAllNodes(inMenu and hasText(it)).fetchSemanticsNodes().size == 1, "no item \"$it\"") }
    }

    /** Regression: the clicked button's focus tooltip held a shared lock that refused hover tooltips. */
    @Test
    fun aClickedButtonDoesNotBlockTheNextButtonsTooltip() = runDesktopComposeUiTest(width = 2000, height = 900) {
        setWorkspace(WorkspaceState.demo().copy(activeTab = RibbonTab.Document).focusBody())
        onNodeWithTag(DocumentRibbonTags.mark(Mark.Bold)).performClick()
        onNodeWithTag(DocumentRibbonTags.mark(Mark.Italic)).performMouseInput { moveTo(center) }
        waitForIdle()
        assertTrue(onAllNodes(hasText("Italic") and inTooltip).fetchSemanticsNodes().isNotEmpty())
    }

    /**
     * Hovers each control with alt text in turn — only those in an open menu when [inMenu] — and
     * returns their labels, failing on the first without a tooltip.
     */
    private fun ComposeUiTest.hoverEveryControl(inMenu: Boolean = false): List<String> {
        waitForIdle()
        val controls = onAllNodes(if (inMenu) controlsWithAltText and hasAnyAncestor(isPopup())
            else controlsWithAltText).fetchSemanticsNodes()
        assertTrue(controls.isNotEmpty())
        val missing = mutableListOf<String>()
        val labels = controls.map { node ->
            val label = node.config[SemanticsProperties.ContentDescription].joinToString(" ")
            onNode(SemanticsMatcher("node ${node.id}") { it.id == node.id })
                .performMouseInput { moveTo(center) }
            waitForIdle()
            if (onAllNodes(hasText(label) and inTooltip).fetchSemanticsNodes().isEmpty()) missing += label
            label
        }
        if (missing.isNotEmpty()) fail("No tooltip on hover for: $missing")
        return labels
    }

    private fun ComposeUiTest.setWorkspace(initial: WorkspaceState) {
        setContent {
            var state by remember { mutableStateOf(initial) }
            ViveNotesTheme(darkTheme = true) {
                WorkspaceScreen(state = state, onStateChange = { state = it(state) })
            }
        }
    }
}
