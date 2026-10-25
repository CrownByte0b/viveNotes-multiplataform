package com.vivenotes.desktop

import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.tooling.ComposeToolingApi
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.awt.ComposePanel
import androidx.compose.ui.awt.RenderSettings
import androidx.compose.ui.semantics.*
import com.vivenotes.ui.ribbon.draw.DrawRibbonTags
import com.vivenotes.ui.ribbon.settings.InterfaceSettings
import com.vivenotes.ui.shell.WorkspaceScreen
import com.vivenotes.ui.theme.ViveNotesTheme
import com.vivenotes.workspace.RibbonTab
import com.vivenotes.workspace.WorkspaceState
import com.vivenotes.model.Outline
import com.vivenotes.model.PageDoc
import java.awt.Component
import java.awt.GraphicsEnvironment
import java.awt.KeyboardFocusManager
import java.awt.event.KeyEvent
import javax.swing.JFrame
import javax.swing.SwingUtilities
import kotlin.test.*

@OptIn(ExperimentalComposeUiApi::class, ComposeToolingApi::class)
class ToolkitPopupKeyboardContractTest {
    @Test
    fun selectedObjectPaletteKeepsNativeTypingAndEscapeInsideItsPopup() {
        if (GraphicsEnvironment.isHeadless()) return
        val base = WorkspaceState.demo()
        val shape = Outline.Shape("selected-shape", x = 250f, y = 350f)
        val state = mutableStateOf(base.copy(notebooks = base.notebooks.map { notebook ->
            notebook.copy(sections = notebook.sections.map { section ->
                section.copy(pages = section.pages.map { page ->
                    if (page.id == base.selectedPageId) page.copy(document = PageDoc(outlines = listOf(shape))) else page
                })
            })
        }).selectObject(shape.id))
        requireWaylandPopupLayers()
        lateinit var frame: JFrame
        lateinit var panel: ComposePanel
        SwingUtilities.invokeAndWait {
            frame = JFrame("Selection color keyboard contract")
            panel = ComposePanel(renderSettings = RenderSettings.SwingGraphics())
            val host = PopupLayerHost(panel)
            panel.windowContainer = host
            frame.contentPane.add(host)
            frame.setSize(1600, 1100)
            frame.isVisible = true
            panel.setContent {
                ViveNotesTheme(darkTheme = true) {
                    WorkspaceScreen(state.value, { state.value = it(state.value) },
                        interfaceSettings = InterfaceSettings(displayScale = .75f))
                }
            }
        }
        try {
            click(panel, "workspace-object-color")
            click(panel, "selection-color-custom")
            click(panel, "selection-custom-color")
            awaitKeyboardIn(panel, "selection-custom-color")
            Thread.sleep(200)
            for (char in "#345678") key(KeyEvent.getExtendedKeyCodeForChar(char.uppercaseChar().code), char)
            waitFor("native typing in the shared selection palette") {
                node(panel, "selection-custom-color")?.config?.getOrNull(SemanticsProperties.EditableText)?.text == "#345678"
            }
            click(panel, "selection-custom-color-apply")
            waitFor("the shared selection color being applied") {
                (state.value.selectedPage!!.document.outlines.single() as Outline.Shape).borderArgb == 0xff345678.toInt()
            }
            waitFor("the palette closing after Apply") { node(panel, "selection-custom-color") == null }
            click(panel, "workspace-object-color")
            waitFor("the reopened selection palette") { node(panel, "selection-color-menu") != null }
            key(KeyEvent.VK_ESCAPE)
            waitFor("Escape dismissing the selection palette") { node(panel, "selection-color-menu") == null }
            assertEquals(setOf(shape.id), state.value.selectedObjectIds)
            key(KeyEvent.VK_EQUALS, '=', KeyEvent.CTRL_DOWN_MASK)
            waitFor("workspace shortcuts after the selection palette closes") {
                node(panel, "workspace-zoom-indicator")?.config?.getOrNull(SemanticsProperties.StateDescription) == "Canvas zoom 125 percent"
            }
        } finally {
            onEdt { panel.setContent { } }
            waitFor("the selection composition to close") { node(panel, "workspace-object-color") == null }
            SwingUtilities.invokeAndWait { frame.dispose() }
        }
    }

    @Test
    fun embeddedHostKeepsKeyboardInputInTheHexPickerAndRestoresItAfterEscape() {
        if (GraphicsEnvironment.isHeadless()) return
        val state = mutableStateOf(WorkspaceState.demo().copy(activeTab = RibbonTab.Draw))
        val original = state.value.selectedPage!!.document
        requireWaylandPopupLayers()
        lateinit var frame: JFrame
        lateinit var panel: ComposePanel
        SwingUtilities.invokeAndWait {
            frame = JFrame("Toolkit keyboard contract")
            panel = ComposePanel(renderSettings = RenderSettings.SwingGraphics())
            val host = PopupLayerHost(panel)
            panel.windowContainer = host
            frame.contentPane.add(host)
            frame.setSize(1600, 1100)
            frame.isVisible = true
            panel.setContent {
                ViveNotesTheme(darkTheme = true) {
                    WorkspaceScreen(state.value, { state.value = it(state.value) },
                        interfaceSettings = InterfaceSettings(displayScale = 1f))
                }
            }
        }
        try {
            click(panel, DrawRibbonTags.penKit(0))
            click(panel, "draw-ink-wheel-button")
            click(panel, "draw-ink-custom-color")
            awaitKeyboardIn(panel, "draw-ink-custom-color")
            // The workspace fallback used to steal focus on the frame after a component popup opened.
            Thread.sleep(200)
            for (char in "#123456") key(KeyEvent.getExtendedKeyCodeForChar(char.uppercaseChar().code), char)
            waitFor("native keyboard characters reaching the color field") {
                node(panel, "draw-ink-custom-color")?.config?.getOrNull(SemanticsProperties.EditableText)?.text == "#123456"
            }
            click(panel, "draw-ink-custom-color-apply")
            waitFor("the typed color being applied") { state.value.inkSettings.pen.colorArgb == 0xFF123456.toInt() }
            assertSame(original, state.value.selectedPage!!.document)
            key(KeyEvent.VK_ESCAPE)
            waitFor("Escape closing the nested picker") { node(panel, "draw-ink-custom-color") == null }
            assertNotNull(onEdt { node(panel, "draw-ink-preview") })
            key(KeyEvent.VK_ESCAPE)
            waitFor("Escape closing the parent toolkit") { node(panel, "draw-ink-preview") == null }
            key(KeyEvent.VK_EQUALS, '=', KeyEvent.CTRL_DOWN_MASK)
            waitFor("keyboard shortcuts after closing the toolkit") {
                node(panel, "workspace-zoom-indicator")?.config?.getOrNull(SemanticsProperties.StateDescription) == "Canvas zoom 125 percent"
            }
        } finally {
            onEdt { panel.setContent { } }
            waitFor("the test composition to close before disposing its Swing host") { node(panel, DrawRibbonTags.penKit(0)) == null }
            SwingUtilities.invokeAndWait { frame.dispose() }
        }
    }

    /**
     * Compose takes its layer type from the first window or panel the JVM makes, so setting it here
     * comes too late whenever another test made one first; `:desktopApp:waylandPopupTest` starts
     * the JVM with it, as the Wayland host does.
     */
    private fun requireWaylandPopupLayers() = assertEquals("COMPONENT", System.getProperty("compose.layers.type"),
        "the Wayland host's popup layers; run by :desktopApp:waylandPopupTest")

    private fun click(panel: ComposePanel, tag: String) {
        waitFor("clickable $tag") { node(panel, tag)?.config?.getOrNull(SemanticsActions.OnClick)?.action != null }
        onEdt { assertTrue(node(panel, tag)!!.config[SemanticsActions.OnClick].action!!.invoke(), tag) }
    }

    /** Actual AWT key press/type/release events go to whichever component currently owns focus. */
    private fun key(code: Int, char: Char = KeyEvent.CHAR_UNDEFINED, modifiers: Int = 0) {
        onEdt {
            val manager = KeyboardFocusManager.getCurrentKeyboardFocusManager()
            fun send(id: Int, keyCode: Int) {
                val target = assertNotNull(manager.focusOwner, "the embedded window needs an AWT focus owner")
                manager.redispatchEvent(target, KeyEvent(target, id, System.currentTimeMillis(), modifiers, keyCode, char))
            }
            send(KeyEvent.KEY_PRESSED, code)
            if (char != KeyEvent.CHAR_UNDEFINED) send(KeyEvent.KEY_TYPED, KeyEvent.VK_UNDEFINED)
            send(KeyEvent.KEY_RELEASED, code)
        }
    }

    /**
     * Waits until typing would reach [tag]: the field holds Compose focus and AWT focus, where [key]
     * sends its events, is in the scene that shows it. A loaded machine can take longer than the
     * click to move focus into a popup that has just opened.
     */
    private fun awaitKeyboardIn(panel: ComposePanel, tag: String) = waitFor("keyboard focus in $tag") {
        val owner = KeyboardFocusManager.getCurrentKeyboardFocusManager().focusOwner
        val (component, field) = located(panel, tag) ?: return@waitFor false
        owner != null && SwingUtilities.isDescendingFrom(owner, component) &&
            field.config.getOrNull(SemanticsProperties.Focused) == true
    }

    private fun node(panel: ComposePanel, tag: String): SemanticsNode? = located(panel, tag)?.second

    /** [tag]'s node with the component its scene takes keys on, the main scene's or a native popup's. */
    private fun located(panel: ComposePanel, tag: String): Pair<Component, SemanticsNode>? =
        scenes(panel).firstNotNullOfOrNull { (component, owners) ->
            owners.firstNotNullOfOrNull { owner ->
                owner.getAllSemanticsNodes(mergingEnabled = false).firstOrNull { it.config.getOrNull(SemanticsProperties.TestTag) == tag }
            }?.let { component to it }
        }

    /** Inspect each native popup's owner as well as the main scene in the pinned Compose version. */
    private fun scenes(panel: ComposePanel): List<Pair<Component, Collection<SemanticsOwner>>> {
        val container = ComposePanel::class.java.getDeclaredField("_composeContainer")
            .apply { isAccessible = true }.get(panel) ?: return emptyList()
        val layers = container.javaClass.getDeclaredField("layers").apply { isAccessible = true }.get(container) as List<*>
        return listOf(panel to panel.semanticsOwners) + layers.toList().mapNotNull { layer ->
            val mediator = getter(layer!!, "getMediator") ?: return@mapNotNull null
            @Suppress("UNCHECKED_CAST")
            (getter(mediator, "getContentComponent") as Component) to
                (getter(mediator, "getSemanticsOwners") as? Collection<SemanticsOwner>).orEmpty()
        }
    }

    private fun getter(instance: Any, name: String): Any? =
        generateSequence(instance.javaClass) { it.superclass }.flatMap { it.declaredMethods.asSequence() }
            .first { it.name.startsWith(name) && it.parameterCount == 0 }
            .apply { isAccessible = true }.invoke(instance)

    private fun waitFor(what: String, condition: () -> Boolean) {
        repeat(250) {
            if (onEdt(condition)) return
            Thread.sleep(20)
        }
        fail("Timed out waiting for $what")
    }

    private fun <T> onEdt(block: () -> T): T {
        var result: Result<T>? = null
        SwingUtilities.invokeAndWait { result = runCatching(block) }
        return result!!.getOrThrow()
    }
}
