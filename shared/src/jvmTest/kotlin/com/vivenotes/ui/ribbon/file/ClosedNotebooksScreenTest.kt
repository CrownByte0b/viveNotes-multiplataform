package com.vivenotes.ui.ribbon.file

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import com.vivenotes.data.DeletedItem
import com.vivenotes.data.db.ClosedNotebook
import com.vivenotes.data.db.NotebookEntity
import com.vivenotes.localization.Strings
import com.vivenotes.ui.icons.cloudNotebookGlyph
import com.vivenotes.ui.icons.notebookGlyph
import com.vivenotes.ui.shell.WorkspaceScreen
import com.vivenotes.ui.theme.ViveNotesTheme
import com.vivenotes.workspace.FileActions
import com.vivenotes.workspace.FilePane
import com.vivenotes.workspace.FilePaneState
import com.vivenotes.workspace.RibbonTab
import com.vivenotes.workspace.WorkspaceState
import kotlinx.coroutines.flow.MutableStateFlow
import org.jetbrains.skia.Data
import org.jetbrains.skia.Surface
import org.jetbrains.skia.svg.SVGDOM
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * File → Closed Notebooks. The first half is Android's `ClosedNotebooksScreenTest`, driving the
 * presentational screen through `mutableStateOf` inputs; the second covers what the desktop adds —
 * the screen covering the workspace window, Back and Escape, and the glyphs.
 */
@OptIn(ExperimentalTestApi::class)
class ClosedNotebooksScreenTest {

    private var notebooks by mutableStateOf(emptyList<ClosedNotebook>())
    private var connected by mutableStateOf(false)
    private var busy by mutableStateOf<String?>(null)
    private var message by mutableStateOf<String?>(null)

    private val opened = mutableListOf<String>()
    private val moved = mutableListOf<String>()
    private val broughtBack = mutableListOf<String>()

    private fun ComposeUiTest.setScreen(cloudAvailable: Boolean = true, reopening: Boolean = false,
        loading: Boolean = false) {
        setContent {
            ViveNotesTheme(darkTheme = true) {
                ClosedNotebooksScreen(
                    notebooks = notebooks,
                    onBack = {},
                    onOpen = { opened += it },
                    onMoveToCloud = { moved += it },
                    onBringBack = { broughtBack += it },
                    accountConnected = connected,
                    cloudAvailable = cloudAvailable,
                    busyNotebookId = busy,
                    reopening = reopening,
                    loading = loading,
                    message = message,
                )
            }
        }
    }

    @Test
    fun anEmptyShelfSaysWhatClosingDoesRatherThanShowingNothing() = runDesktopComposeUiTest(1200, 800) {
        setScreen()

        onNodeWithTag(ClosedNotebooksTags.Empty).assertIsDisplayed()
        onNodeWithText(Strings.closed_notebooks_empty).assertIsDisplayed()
        onNodeWithTag(ClosedNotebooksTags.OnDeviceHeading).assertDoesNotExist()
        onNodeWithTag(ClosedNotebooksTags.InCloudHeading).assertDoesNotExist()
    }

    @Test
    fun aNotebookOnTheDeviceOffersOpenAndMoveToCloud() = runDesktopComposeUiTest(1200, 800) {
        notebooks = listOf(closed("here", cloudOnly = false))
        connected = true
        setScreen()

        onNodeWithTag(ClosedNotebooksTags.OnDeviceHeading).assertIsDisplayed()
        onNodeWithTag(ClosedNotebooksTags.InCloudHeading).assertDoesNotExist()
        onNodeWithText("2 sections · 7 pages").assertIsDisplayed()

        onNodeWithTag(ClosedNotebooksTags.open("here")).performClick()
        onNodeWithTag(ClosedNotebooksTags.moveToCloud("here")).performClick()

        runOnIdle {
            assertEquals(listOf("here"), opened)
            assertEquals(listOf("here"), moved)
        }
    }

    /** It is the section heading that explains why the notebook cannot be searched. */
    @Test
    fun aCloudOnlyNotebookIsListedSeparatelyAndOnlyOffersBringBack() = runDesktopComposeUiTest(1200, 800) {
        notebooks = listOf(closed("elsewhere", cloudOnly = true))
        connected = true
        setScreen()

        onNodeWithTag(ClosedNotebooksTags.InCloudHeading).assertIsDisplayed()
        onNodeWithText(Strings.closed_notebooks_in_cloud_supporting).assertIsDisplayed()
        onNodeWithTag(ClosedNotebooksTags.OnDeviceHeading).assertDoesNotExist()
        onNodeWithTag(ClosedNotebooksTags.open("elsewhere")).assertDoesNotExist()
        onNodeWithTag(ClosedNotebooksTags.moveToCloud("elsewhere")).assertDoesNotExist()

        onNodeWithTag(ClosedNotebooksTags.bringBack("elsewhere")).performClick()

        runOnIdle { assertEquals(listOf("elsewhere"), broughtBack) }
    }

    @Test
    fun aClosedNotebookDeferredByThisDeviceOffersTheSameDownloadAction() = runDesktopComposeUiTest(1200, 800) {
        notebooks = listOf(closed("deferred", cloudOnly = false, contentOnDevice = false))
        connected = true
        setScreen()

        onNodeWithTag(ClosedNotebooksTags.InCloudHeading).assertIsDisplayed()
        onNodeWithTag(ClosedNotebooksTags.OnDeviceHeading).assertDoesNotExist()
        onNodeWithTag(ClosedNotebooksTags.open("deferred")).assertDoesNotExist()
        onNodeWithTag(ClosedNotebooksTags.bringBack("deferred")).performClick()

        runOnIdle { assertEquals(listOf("deferred"), broughtBack) }
    }

    /** Without a server a button that looked live and did nothing would be the worse answer. */
    @Test
    fun withoutAnAccountTheCloudButtonsAreOfferedButNotLive() = runDesktopComposeUiTest(1200, 800) {
        notebooks = listOf(closed("here", cloudOnly = false), closed("elsewhere", cloudOnly = true))
        connected = false
        setScreen()

        onNodeWithText(Strings.closed_notebooks_no_account).assertIsDisplayed()
        onNodeWithTag(ClosedNotebooksTags.moveToCloud("here")).assertIsNotEnabled()
        onNodeWithTag(ClosedNotebooksTags.bringBack("elsewhere")).assertIsNotEnabled()
        onNodeWithTag(ClosedNotebooksTags.moveToCloud("here")).performTouchInput { click() }
        runOnIdle { assertEquals(emptyList<String>(), moved) }

        // Opening one that is already here needs no server at all.
        onNodeWithTag(ClosedNotebooksTags.open("here")).performClick()
        runOnIdle { assertEquals(listOf("here"), opened) }
    }

    /** A build without accounts has nowhere to move anything, so a device row offers Open alone. */
    @Test
    fun withoutAccountsADeviceRowOffersOnlyOpen() = runDesktopComposeUiTest(1200, 800) {
        notebooks = listOf(closed("here", cloudOnly = false), closed("elsewhere", cloudOnly = true))
        setScreen(cloudAvailable = false)

        onNodeWithTag(ClosedNotebooksTags.open("here")).assertIsEnabled()
        onNodeWithTag(ClosedNotebooksTags.moveToCloud("here")).assertDoesNotExist()
        onNodeWithTag(ClosedNotebooksTags.bringBack("elsewhere")).assertIsNotEnabled()
    }

    /** One spinner, on the row it belongs to, and the rest of the list held still. */
    @Test
    fun aRunningMoveSpinsOnItsOwnRowAndFreezesTheOthers() = runDesktopComposeUiTest(1200, 800) {
        notebooks = listOf(closed("here", cloudOnly = false), closed("other", cloudOnly = false))
        connected = true
        busy = "here"
        setScreen()

        onNodeWithTag(ClosedNotebooksTags.busy("here")).assertIsDisplayed()
        onNodeWithTag(ClosedNotebooksTags.moveToCloud("here")).assertDoesNotExist()
        onNodeWithTag(ClosedNotebooksTags.moveToCloud("other")).assertIsNotEnabled()
        onNodeWithTag(ClosedNotebooksTags.open("other")).assertIsNotEnabled()

        busy = null
        waitForIdle()
        onNodeWithTag(ClosedNotebooksTags.busy("here")).assertDoesNotExist()
        onNodeWithTag(ClosedNotebooksTags.moveToCloud("here")).performClick()
        runOnIdle { assertEquals(listOf("here"), moved) }
    }

    /** The ring is drawn around the button, so the thing that was pressed stays where it was pressed. */
    @Test
    fun aRunningRestoreRingsItsDownloadButtonRatherThanReplacingIt() = runDesktopComposeUiTest(1200, 800) {
        notebooks = listOf(closed("elsewhere", cloudOnly = true))
        connected = true
        busy = "elsewhere"
        setScreen()

        onNodeWithTag(ClosedNotebooksTags.busy("elsewhere")).assertIsDisplayed()
        onNodeWithTag(ClosedNotebooksTags.bringBack("elsewhere")).assertIsDisplayed()
        onNodeWithTag(ClosedNotebooksTags.bringBack("elsewhere")).assertIsNotEnabled()

        busy = null
        waitForIdle()
        onNodeWithTag(ClosedNotebooksTags.busy("elsewhere")).assertDoesNotExist()
        onNodeWithTag(ClosedNotebooksTags.bringBack("elsewhere")).performClick()
        runOnIdle { assertEquals(listOf("elsewhere"), broughtBack) }
    }

    /** A reopen being stored holds the shelf still too, as a cloud operation does. */
    @Test
    fun aReopenInFlightHoldsEveryButton() = runDesktopComposeUiTest(1200, 800) {
        notebooks = listOf(closed("here", cloudOnly = false), closed("elsewhere", cloudOnly = true))
        connected = true
        setScreen(reopening = true)

        onNodeWithTag(ClosedNotebooksTags.open("here")).assertIsNotEnabled()
        onNodeWithTag(ClosedNotebooksTags.moveToCloud("here")).assertIsNotEnabled()
        onNodeWithTag(ClosedNotebooksTags.bringBack("elsewhere")).assertIsNotEnabled()
    }

    @Test
    fun openLeadsAndMoveToCloudFollows() = runDesktopComposeUiTest(1200, 800) {
        notebooks = listOf(closed("here", cloudOnly = false))
        connected = true
        setScreen()

        val open = onNodeWithTag(ClosedNotebooksTags.open("here")).getUnclippedBoundsInRoot()
        val toCloud = onNodeWithTag(ClosedNotebooksTags.moveToCloud("here")).getUnclippedBoundsInRoot()
        assertTrue(open.left < toCloud.left, "Open should sit left of Move to cloud")
    }

    /** A refusal has to be visible, and a success has nothing to say the list has not said. */
    @Test
    fun aRefusalIsShownAndClearedWithTheNextAttempt() = runDesktopComposeUiTest(1200, 800) {
        notebooks = listOf(closed("here", cloudOnly = false))
        connected = true
        setScreen()
        onNodeWithTag(ClosedNotebooksTags.Message).assertDoesNotExist()

        message = "This device still has 3 changes to upload."
        waitForIdle()
        onNodeWithTag(ClosedNotebooksTags.Message).assertIsDisplayed()

        message = null
        waitForIdle()
        onNodeWithTag(ClosedNotebooksTags.Message).assertDoesNotExist()
        assertNull(message)
    }

    @Test
    fun loadingSaysSoInsteadOfClaimingTheShelfIsEmpty() = runDesktopComposeUiTest(1200, 800) {
        setScreen(loading = true)

        onNodeWithTag(ClosedNotebooksTags.Loading).assertIsDisplayed()
        onNodeWithTag(ClosedNotebooksTags.Empty).assertDoesNotExist()
    }

    /**
     * Each row wears its shelf's notebook in the notebook's own colour: the plain notebook on this
     * device, the cloud-badged one in the cloud. Compared against the glyphs drawn on their own.
     */
    @Test
    fun eachRowWearsItsShelfsGlyphInTheNotebooksColour() = runDesktopComposeUiTest(1200, 800) {
        val pink = 0xFFE91E63.toInt()
        val grey = 0xFF9EB7CF.toInt()
        notebooks = listOf(closed("here", cloudOnly = false, color = pink), closed("elsewhere", cloudOnly = true, color = grey))
        connected = true
        setContent {
            ViveNotesTheme(darkTheme = true) {
                Row {
                    ClosedNotebooksScreen(notebooks, onBack = {}, accountConnected = true,
                        modifier = Modifier.size(900.dp))
                    listOf("here" to notebookGlyph(Color(pink)), "elsewhere" to cloudNotebookGlyph(Color(grey)))
                        .forEach { (id, glyph) ->
                            Icon(glyph, null, tint = Color.Unspecified, modifier = Modifier.size(32.dp)
                                .background(MaterialTheme.colorScheme.surfaceContainer)
                                .testTag("reference-$id"))
                        }
                }
            }
        }
        listOf("here", "elsewhere").forEach { id ->
            val actual = onNodeWithTag(ClosedNotebooksTags.icon(id), useUnmergedTree = true).captureToImage().toPixelMap()
            val expected = onNodeWithTag("reference-$id").captureToImage().toPixelMap()
            assertTrue(mismatch(actual, expected, threshold = .02f) == 0, "$id row does not wear its own glyph")
        }
        // And the two shelves' glyphs are not the same drawing in two colours.
        val device = onNodeWithTag("reference-here").captureToImage().toPixelMap()
        val cloud = onNodeWithTag("reference-elsewhere").captureToImage().toPixelMap()
        assertTrue(mismatch(device, cloud, threshold = .1f) > 50)
    }

    /**
     * The glyphs are the project's own artwork: rendered by Skia's SVG renderer from the original
     * files, in the azure they were drawn in, they and the traced vectors paint the same pixels.
     */
    @Test
    fun theGlyphsPaintTheProjectsNotebookArtwork() = runDesktopComposeUiTest(800, 400) {
        val px = 128
        val azure = Color(0xFF007FFF)
        val references = mapOf(
            "notebook" to (notebookGlyph(azure) to svgImage("notebook_regular.svg", px)),
            "cloud" to (cloudNotebookGlyph(azure) to svgImage("cloud-notebook.svg", px)),
        )
        setContent {
            val side = with(LocalDensity.current) { px.toDp() }
            Row {
                references.forEach { (name, pair) ->
                    val (glyph, svg) = pair
                    Icon(glyph, null, tint = Color.Unspecified,
                        modifier = Modifier.size(side).background(Color.White).testTag("glyph-$name"))
                    Image(svg, null, modifier = Modifier.size(side).testTag("svg-$name"))
                }
            }
        }
        references.keys.forEach { name ->
            val glyph = onNodeWithTag("glyph-$name").captureToImage().toPixelMap()
            val svg = onNodeWithTag("svg-$name").captureToImage().toPixelMap()
            assertEquals(svg.width, glyph.width)
            // Anti-aliasing along curves may differ by a shade; the drawing itself may not.
            val off = mismatch(glyph, svg, threshold = .25f)
            assertTrue(off <= px * px / 400, "$name glyph differs from its SVG at $off pixels")
            val ink = (0 until svg.height).sumOf { y -> (0 until svg.width).count { x -> svg[x, y].red < .9f } }
            assertTrue(ink > px * px / 20, "$name SVG rendered nearly blank ($ink pixels)")
        }
    }

    // ---- The desktop destination, inside the workspace window ----

    private val calls = mutableListOf<String>()
    private var workspace by mutableStateOf(WorkspaceState.demo().copy(activeTab = RibbonTab.File))

    private val actions = object : FileActions {
        override fun openPane(pane: FilePane) {
            calls += "pane:$pane"
            workspace = workspace.copy(filePane = FilePaneState(pane = pane, closedNotebooks = notebooks))
        }
        override fun closePane() {
            calls += "close-pane"
            workspace = workspace.copy(filePane = FilePaneState())
        }
        override fun selectRevision(id: String) {}
        override fun restoreRevision() {}
        override fun restoreDeletedItem(item: DeletedItem) {}
        override fun reopenNotebook(id: String) { calls += "reopen:$id" }
        override val cloudConnected = MutableStateFlow(true)
        override fun moveNotebookToCloud(id: String) { calls += "to-cloud:$id" }
        override fun bringNotebookBack(id: String) { calls += "back:$id" }
        override fun closeNotebook() {}
        override fun deleteNotebook() {}
        override fun exportNotebook() {}
        override fun importNotebook() {}
        override fun dismissTransfer() {}
    }

    private fun ComposeUiTest.setWorkspace() {
        setContent {
            ViveNotesTheme(darkTheme = true) {
                WorkspaceScreen(state = workspace, onStateChange = { workspace = it(workspace) }, fileActions = actions)
            }
        }
    }

    /** Android opens the shelf as its own screen, so here it covers the window, not a side pane. */
    @Test
    fun theRibbonCommandOpensTheShelfOverTheWholeWindow() = runDesktopComposeUiTest(1600, 900) {
        notebooks = listOf(closed("here", cloudOnly = false), closed("elsewhere", cloudOnly = true))
        setWorkspace()

        onNodeWithTag(FileRibbonTags.ClosedNotebooks).performClick()

        onNodeWithTag(ClosedNotebooksTags.Screen).assertIsDisplayed()
        onNodeWithTag(FilePaneTags.Pane).assertDoesNotExist()
        assertEquals(onRoot().getUnclippedBoundsInRoot(), onNodeWithTag(ClosedNotebooksTags.Screen).getUnclippedBoundsInRoot())
        onNodeWithText(Strings.closed_notebooks_in_cloud_supporting).assertIsDisplayed()
        onNodeWithTag(ClosedNotebooksTags.moveToCloud("here")).assertIsEnabled().performClick()
        onNodeWithTag(ClosedNotebooksTags.bringBack("elsewhere")).assertIsEnabled().performClick()
        onNodeWithTag(ClosedNotebooksTags.open("here")).performClick()
        runOnIdle { assertEquals(listOf("pane:ClosedNotebooks", "to-cloud:here", "back:elsewhere", "reopen:here"), calls) }
    }

    @Test
    fun backReturnsToTheWorkspace() = runDesktopComposeUiTest(1600, 900) {
        setWorkspace()
        onNodeWithTag(FileRibbonTags.ClosedNotebooks).performClick()

        onNodeWithTag(ClosedNotebooksTags.Back).performClick()

        onNodeWithTag(ClosedNotebooksTags.Screen).assertDoesNotExist()
        onNodeWithTag(FileRibbonTags.ClosedNotebooks).assertIsDisplayed()
        runOnIdle { assertEquals(listOf("pane:ClosedNotebooks", "close-pane"), calls) }
    }

    /** Escape is system Back, and it reaches the screen without a click on it first. */
    @Test
    fun escapeReturnsToTheWorkspace() = runDesktopComposeUiTest(1600, 900) {
        setWorkspace()
        onNodeWithTag(FileRibbonTags.ClosedNotebooks).performClick()
        onNodeWithTag(ClosedNotebooksTags.Screen).assertIsDisplayed()

        onRoot().performKeyInput { pressKey(Key.Escape) }

        onNodeWithTag(ClosedNotebooksTags.Screen).assertDoesNotExist()
        runOnIdle { assertEquals(listOf("pane:ClosedNotebooks", "close-pane"), calls) }
    }

    private fun mismatch(a: PixelMap, b: PixelMap, threshold: Float): Int {
        assertEquals(b.width, a.width)
        assertEquals(b.height, a.height)
        var count = 0
        for (y in 0 until a.height) for (x in 0 until a.width) {
            val p = a[x, y]
            val q = b[x, y]
            if (abs(p.red - q.red) > threshold || abs(p.green - q.green) > threshold ||
                abs(p.blue - q.blue) > threshold) count++
        }
        return count
    }

    /**
     * The original SVG, rendered by Skia's own SVG renderer onto white at [px] square. The files
     * fix their own size at their 16-unit view box, so the canvas is scaled up to [px] instead.
     */
    private fun svgImage(file: String, px: Int) = run {
        val bytes = requireNotNull(javaClass.getResourceAsStream("/icons/$file")).use { it.readBytes() }
        val dom = SVGDOM(Data.makeFromBytes(bytes))
        val box = requireNotNull(dom.root?.viewBox)
        dom.setContainerSize(box.width, box.height)
        val surface = Surface.makeRasterN32Premul(px, px)
        surface.canvas.clear(0xFFFFFFFF.toInt())
        surface.canvas.scale(px / box.width, px / box.height)
        dom.render(surface.canvas)
        surface.makeImageSnapshot().toComposeImageBitmap()
    }

    private fun closed(
        id: String,
        cloudOnly: Boolean,
        contentOnDevice: Boolean = !cloudOnly,
        color: Int = 0xFF336699.toInt(),
    ) = ClosedNotebook(
        notebook = NotebookEntity(
            id = id,
            name = "Notebook $id",
            colorArgb = color,
            sortIndex = 0,
            createdAt = 1L,
            updatedAt = 2L,
            closedAt = 3L,
            cloudOnlyAt = if (cloudOnly) 4L else null,
        ),
        sectionCount = 2,
        pageCount = 7,
        contentOnDevice = contentOnDevice,
    )
}
