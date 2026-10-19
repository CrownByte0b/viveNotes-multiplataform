package com.vivenotes.ui.ribbon.document

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.click
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.width
import com.vivenotes.data.CameraDevice
import com.vivenotes.data.CameraFrame
import com.vivenotes.data.ImageSource
import com.vivenotes.data.ImportedPicture
import com.vivenotes.data.OnlineImage
import com.vivenotes.data.OnlineImageSearchResult
import com.vivenotes.data.OnlineImages
import com.vivenotes.data.PictureCamera
import com.vivenotes.data.PictureLibrary
import com.vivenotes.data.SearchCursor
import com.vivenotes.model.Outline
import com.vivenotes.ui.ribbon.RibbonIconTags
import com.vivenotes.ui.shell.WorkspaceScreen
import com.vivenotes.ui.theme.ViveNotesTheme
import com.vivenotes.workspace.RibbonTab
import com.vivenotes.workspace.WorkspaceState
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import kotlin.test.Test

/**
 * The Picture button's menu, and its "Search online" window against a stand-in for both sources —
 * Android's `InsertPictureTest`, plus the desktop's keyboard, dismissal and paging.
 */
@OptIn(ExperimentalTestApi::class)
class InsertPictureTest {

    @Test
    fun eachMenuEntryReportsItsSource() = runDesktopComposeUiTest(width = 900, height = 600) {
        val picked = mutableListOf<PictureSource>()
        setContent {
            ViveNotesTheme(darkTheme = true) {
                PictureButton(enabled = true, sources = { PictureSource.entries }) { picked += it }
            }
        }

        onNodeWithTag(DocumentRibbonTags.Picture).performClick()
        onNodeWithTag(DocumentRibbonTags.PictureDevice).performClick()
        onNodeWithTag(DocumentRibbonTags.Picture).performClick()
        onNodeWithTag(DocumentRibbonTags.PictureOnline).performClick()
        onNodeWithTag(DocumentRibbonTags.Picture).performClick()
        onNodeWithTag(DocumentRibbonTags.PictureCamera).performClick()

        runOnIdle { assertEquals(listOf(PictureSource.Device, PictureSource.Online, PictureSource.Camera), picked) }
    }

    /** Android 2c16403: an icon that opens a menu wears the triangle, and is a little wider for it. */
    @Test
    fun thePictureButtonReadsAsAMenu() = runDesktopComposeUiTest(width = 900, height = 600) {
        setContent {
            ViveNotesTheme(darkTheme = true) {
                PictureButton(enabled = true, sources = { listOf(PictureSource.Device) }) {}
            }
        }

        onNodeWithTag(RibbonIconTags.dropdown(DocumentRibbonTags.Picture), useUnmergedTree = true).assertIsDisplayed()
        onNodeWithTag(DocumentRibbonTags.Picture)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.DropdownList))
        val width = onNodeWithTag(DocumentRibbonTags.Picture).getUnclippedBoundsInRoot().width
        assertTrue("the slot widens for the triangle, $width", width > 40.dp)
    }

    @Test
    fun theMenuOffersOnlyTheSourcesThisComputerHas() = runDesktopComposeUiTest(width = 1400, height = 900) {
        var plugged = FakeCamera(devices = emptyList())
        val pictures = object : FakePictures() {
            override val online: OnlineImages = FakeImages(hits = 1)
            override val camera: PictureCamera get() = plugged
        }
        setWorkspace(pictures)

        onNodeWithTag(DocumentRibbonTags.Picture).performScrollTo().performClick()
        onNodeWithTag(DocumentRibbonTags.PictureDevice).assertIsDisplayed()
        onNodeWithTag(DocumentRibbonTags.PictureOnline).assertIsDisplayed()
        onAllNodesWithTag(DocumentRibbonTags.PictureCamera).assertCountEquals(0)
        onNodeWithTag(DocumentRibbonTags.PictureDevice).performKeyInput { pressKey(Key.Escape) }

        // A camera plugged in between two menus is offered by the second.
        plugged = FakeCamera()
        onNodeWithTag(DocumentRibbonTags.Picture).performClick()
        onNodeWithTag(DocumentRibbonTags.PictureCamera).assertIsDisplayed()
    }

    @Test
    fun withNeitherSearchNorCameraTheMenuIsTheFileDialogAlone() = runDesktopComposeUiTest(width = 1400, height = 900) {
        setWorkspace(FakePictures())

        onNodeWithTag(DocumentRibbonTags.Picture).performScrollTo().performClick()
        onNodeWithTag(DocumentRibbonTags.PictureDevice).assertIsDisplayed()
        onAllNodesWithTag(DocumentRibbonTags.PictureOnline).assertCountEquals(0)
        onAllNodesWithTag(DocumentRibbonTags.PictureCamera).assertCountEquals(0)
    }

    @Test
    fun aSearchFillsTheGridAndATapInsertsTheDownloadedBytes() = runDesktopComposeUiTest(width = 1400, height = 900) {
        val images = FakeImages(hits = 3)
        var inserted: ByteArray? = null
        var dismissed = false
        setDialog(images, onInsert = { inserted = it }, onDismiss = { dismissed = true })

        onNodeWithTag(OnlineImageTags.QUERY).performTextInput("fox")
        onNodeWithTag(OnlineImageTags.QUERY).performImeAction()
        waitUntil { onAllNodesWithTag(OnlineImageTags.RESULT).fetchSemanticsNodes().size == 3 }
        onAllNodesWithTag(OnlineImageTags.RESULT)[1].performClick()
        waitUntil { dismissed }

        assertEquals(listOf("fox" to null), images.searches)
        assertArrayEquals(FakeImages.bytesFor("fox-1"), inserted)
    }

    @Test
    fun whereOpenverseTakesOverIsMarked() = runDesktopComposeUiTest(width = 1400, height = 900) {
        setDialog(FakeImages(hits = 4, openverseFrom = 2))

        onNodeWithTag(OnlineImageTags.QUERY).performTextInput("cell")
        onNodeWithTag(OnlineImageTags.QUERY).performImeAction()
        waitUntil { onAllNodesWithTag(OnlineImageTags.RESULT).fetchSemanticsNodes().size == 4 }

        onNodeWithTag(OnlineImageTags.SOURCE_HEADER).assertIsDisplayed()
    }

    @Test
    fun noMarkerWhenOneSourceAnsweredEverything() = runDesktopComposeUiTest(width = 1400, height = 900) {
        setDialog(FakeImages(hits = 3))

        onNodeWithTag(OnlineImageTags.QUERY).performTextInput("cell")
        onNodeWithTag(OnlineImageTags.QUERY).performImeAction()
        waitUntil { onAllNodesWithTag(OnlineImageTags.RESULT).fetchSemanticsNodes().size == 3 }

        onAllNodesWithTag(OnlineImageTags.SOURCE_HEADER).assertCountEquals(0)
    }

    @Test
    fun nothingFoundSaysSoInsteadOfShowingAnEmptyGrid() = runDesktopComposeUiTest(width = 1400, height = 900) {
        setDialog(FakeImages(hits = 0))

        onNodeWithTag(OnlineImageTags.QUERY).performTextInput("zzz")
        onNodeWithTag(OnlineImageTags.QUERY).performImeAction()

        waitUntil { onAllNodes(hasText("No pictures found", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        onAllNodesWithTag(OnlineImageTags.RESULT).assertCountEquals(0)
    }

    @Test
    fun aRateLimitIsExplainedRatherThanShownAsNoResults() = runDesktopComposeUiTest(width = 1400, height = 900) {
        setDialog(FakeImages(hits = 0, result = OnlineImageSearchResult.RateLimited))

        onNodeWithTag(OnlineImageTags.QUERY).performTextInput("cat")
        onNodeWithTag(OnlineImageTags.QUERY).performImeAction()

        waitUntil { onAllNodes(hasText("Try again in a minute", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithTag(OnlineImageTags.MESSAGE).assertIsDisplayed()
    }

    @Test
    fun aFailedDownloadKeepsTheDialogOpenAndSaysSo() = runDesktopComposeUiTest(width = 1400, height = 900) {
        var dismissed = false
        setDialog(FakeImages(hits = 2, downloads = false), onDismiss = { dismissed = true })

        onNodeWithTag(OnlineImageTags.QUERY).performTextInput("owl")
        onNodeWithTag(OnlineImageTags.QUERY).performImeAction()
        waitUntil { onAllNodesWithTag(OnlineImageTags.RESULT).fetchSemanticsNodes().size == 2 }
        onAllNodesWithTag(OnlineImageTags.RESULT)[0].performClick()

        waitUntil { onAllNodes(hasText("couldn’t be downloaded", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        assertTrue(!dismissed)
    }

    @Test
    fun aTileWithNoPreviewSaysSoAndCanStillBeInserted() = runDesktopComposeUiTest(width = 1400, height = 900) {
        var inserted: ByteArray? = null
        setDialog(FakeImages(hits = 1, previews = false), onInsert = { inserted = it })

        onNodeWithTag(OnlineImageTags.QUERY).performTextInput("meiosis")
        onNodeWithTag(OnlineImageTags.QUERY).performImeAction()
        // The tile is one merged button; the icon is only findable beneath it.
        waitUntil {
            onAllNodesWithTag(OnlineImageTags.NO_PREVIEW, useUnmergedTree = true)
                .fetchSemanticsNodes().size == 1
        }
        onNodeWithTag(OnlineImageTags.RESULT).performClick()
        waitUntil { inserted != null }

        assertArrayEquals(FakeImages.bytesFor("meiosis-0"), inserted)
    }

    /** From a fresh window, with nothing clicked first: the field has the keyboard, and Enter searches. */
    @Test
    fun theFieldHasTheKeyboardAndEnterSearches() = runDesktopComposeUiTest(width = 1400, height = 900) {
        val images = FakeImages(hits = 2)
        setDialog(images)

        onNodeWithTag(OnlineImageTags.QUERY).assertIsFocused()
        onNodeWithTag(OnlineImageTags.QUERY).performTextInput("red fox")
        onRoot().performKeyInput { keyDown(Key.Enter); keyUp(Key.Enter) }

        waitUntil { onAllNodesWithTag(OnlineImageTags.RESULT).fetchSemanticsNodes().size == 2 }
        assertEquals(listOf("red fox" to null), images.searches)
    }

    /** Found in the real app: clearing the field with a click left the keyboard on the clear button. */
    @Test
    fun afterClearingTheFieldTheNextWordsGoIntoIt() = runDesktopComposeUiTest(width = 1400, height = 900) {
        val images = FakeImages(hits = 1)
        setDialog(images)
        onNodeWithTag(OnlineImageTags.QUERY).performTextInput("eiffel tower")

        // With the mouse, as in the app: a touch click takes no focus, and would hide the problem.
        onNodeWithTag(OnlineImageTags.CLEAR).performMouseInput { click() }

        onNodeWithTag(OnlineImageTags.QUERY).assertIsFocused()
        onNodeWithTag(OnlineImageTags.QUERY).performTextInput("notre dame")
        onRoot().performKeyInput { keyDown(Key.Enter); keyUp(Key.Enter) }
        waitUntil { images.searches.isNotEmpty() }
        assertEquals(listOf("notre dame" to null), images.searches)
    }

    @Test
    fun escapeTheBackdropAndCloseEachDismiss() = runDesktopComposeUiTest(width = 1400, height = 900) {
        var dismissals = 0
        // Left open after each, so the three ways can be tried in turn.
        setDialog(FakeImages(hits = 0), onDismiss = { dismissals++ })

        onRoot().performKeyInput { keyDown(Key.Escape); keyUp(Key.Escape) }
        waitUntil { dismissals == 1 }
        onNodeWithTag(OnlineImageTags.CLOSE).performClick()
        waitUntil { dismissals == 2 }
        // The backdrop's middle is under the window; its corner is not.
        onNodeWithTag(OnlineImageTags.BACKDROP).performMouseInput { click(Offset(8f, 8f)) }
        waitUntil { dismissals == 3 }
        // A click on the window itself is not a click on the backdrop behind it.
        onNodeWithTag(OnlineImageTags.DIALOG).performClick()
        waitForIdle()
        assertEquals(3, dismissals)
    }

    /** Twenty tiles leave room on a tall window, so the next page comes without scrolling, once. */
    @Test
    fun theNextPageLoadsAsTheGridNearsItsEnd() = runDesktopComposeUiTest(width = 1400, height = 900) {
        val images = FakeImages(hits = 20, next = { from -> if (from == null) SearchCursor.Commons(20) else null })
        setDialog(images)

        onNodeWithTag(OnlineImageTags.QUERY).performTextInput("fox")
        onNodeWithTag(OnlineImageTags.QUERY).performImeAction()

        waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag(OnlineImageTags.RESULT).fetchSemanticsNodes().size >= 21 }
        runOnIdle { assertEquals(listOf("fox" to null, "fox" to SearchCursor.Commons(20)), images.searches) }
    }

    /**
     * Regression, found in the real app: a tile scrolled out from under a still pointer gets no hover
     * exit, and its title stayed up over the tile that took its place.
     */
    @Test
    fun aTilesTitleGoesWhenTheGridScrollsAwayUnderAStillPointer() = runDesktopComposeUiTest(width = 1400, height = 900) {
        val pictures = object : FakePictures() {
            override val online: OnlineImages = FakeImages(hits = 60)
        }
        setWorkspace(pictures)
        onNodeWithTag(DocumentRibbonTags.Picture).performScrollTo().performClick()
        onNodeWithTag(DocumentRibbonTags.PictureOnline).performClick()
        onNodeWithTag(OnlineImageTags.QUERY).performTextInput("fox")
        onNodeWithTag(OnlineImageTags.QUERY).performImeAction()
        waitUntil { onAllNodesWithTag(OnlineImageTags.RESULT).fetchSemanticsNodes().size >= 10 }

        // A tile in the second row, so a little scrolling moves it from under the pointer but keeps it.
        onAllNodesWithTag(OnlineImageTags.RESULT)[6].performMouseInput { moveTo(center) }
        waitUntil(timeoutMillis = 3_000) { onAllNodes(hasText("fox 6")).fetchSemanticsNodes().isNotEmpty() }

        // The wheel where the pointer is, as a person scrolls: the pointer itself does not move.
        onNodeWithTag(OnlineImageTags.GRID).performMouseInput { scroll(2f) }
        waitForIdle()
        mainClock.advanceTimeBy(1_000)
        assertTrue("the tile is still shown, only moved",
            onAllNodesWithTag(OnlineImageTags.RESULT).fetchSemanticsNodes().any { node ->
                node.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("fox 6") == true
            })
        onAllNodes(hasText("fox 6")).assertCountEquals(0)
    }

    @Test
    fun searchOnlinePutsThePickedPictureOnTheOpenPage() = runDesktopComposeUiTest(width = 1400, height = 900) {
        val id = "d".repeat(64)
        val pictures = object : FakePictures(ImportedPicture(id, 40, 20)) {
            override val online: OnlineImages = FakeImages(hits = 2)
        }
        var observed = document
        setWorkspace(pictures) { observed = it }

        onNodeWithTag(DocumentRibbonTags.Picture).performScrollTo().performClick()
        onNodeWithTag(DocumentRibbonTags.PictureOnline).performClick()
        onNodeWithTag(OnlineImageTags.QUERY).performTextInput("fox")
        onRoot().performKeyInput { keyDown(Key.Enter); keyUp(Key.Enter) }
        waitUntil { onAllNodesWithTag(OnlineImageTags.RESULT).fetchSemanticsNodes().size == 2 }
        onAllNodesWithTag(OnlineImageTags.RESULT)[1].performClick()

        waitUntil(timeoutMillis = 5_000) { observed.selectedPage!!.document.outlines.any { it is Outline.Image } }
        val image = observed.selectedPage!!.document.outlines.filterIsInstance<Outline.Image>().single()
        assertEquals(id, image.attachmentId)
        assertEquals(Outline.Image.DEFAULT_WIDTH to Outline.Image.DEFAULT_WIDTH / 2, image.width to image.height)
        assertArrayEquals(FakeImages.bytesFor("fox-1"), pictures.imported.single())
        onAllNodesWithTag(OnlineImageTags.DIALOG).assertCountEquals(0)
    }

    @Test
    fun aPhotoFromTheCameraGoesOnTheOpenPage() = runDesktopComposeUiTest(width = 1400, height = 900) {
        val id = "e".repeat(64)
        val pictures = object : FakePictures(ImportedPicture(id, 32, 24)) {
            override val camera: PictureCamera = FakeCamera()
        }
        var observed = document
        setWorkspace(pictures) { observed = it }

        onNodeWithTag(DocumentRibbonTags.Picture).performScrollTo().performClick()
        onNodeWithTag(DocumentRibbonTags.PictureCamera).performClick()
        waitUntil { onAllNodesWithTag(CameraTags.PREVIEW).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithTag(CameraTags.SHUTTER).performClick()

        waitUntil(timeoutMillis = 5_000) { observed.selectedPage!!.document.outlines.any { it is Outline.Image } }
        assertEquals(id, observed.selectedPage!!.document.outlines.filterIsInstance<Outline.Image>().single().attachmentId)
        assertArrayEquals(FakeCamera.photoOf(FakeCamera.INTEGRATED), pictures.imported.single())
        onAllNodesWithTag(CameraTags.DIALOG).assertCountEquals(0)
    }

    private val document = WorkspaceState.demo().copy(activeTab = RibbonTab.Document)

    private fun ComposeUiTest.setDialog(
        images: OnlineImages,
        onInsert: (ByteArray) -> Unit = {},
        onDismiss: () -> Unit = {},
    ) {
        setContent {
            ViveNotesTheme(darkTheme = true) {
                OnlineImageDialog(images = images, onInsert = onInsert, onDismiss = onDismiss)
            }
        }
    }

    private fun ComposeUiTest.setWorkspace(pictures: PictureLibrary, onStateChange: (WorkspaceState) -> Unit = {}) {
        setContent {
            var state by remember { mutableStateOf(document) }
            ViveNotesTheme(darkTheme = true) {
                WorkspaceScreen(state = state, onStateChange = { state = it(state); onStateChange(state) },
                    pictures = pictures)
            }
        }
    }

    /** Picture storage that keeps what it is handed and answers with [stored]. */
    private open class FakePictures(private val stored: ImportedPicture? = null) : PictureLibrary {
        val imported = mutableListOf<ByteArray>()
        override suspend fun choose(): ImportedPicture? = null
        override suspend fun import(bytes: ByteArray): ImportedPicture? = stored.also { imported += bytes }
        override suspend fun bytes(attachmentId: String): ByteArray? = null
    }

    private class FakeImages(
        private val hits: Int,
        private val result: OnlineImageSearchResult? = null,
        private val downloads: Boolean = true,
        private val previews: Boolean = true,
        /** Hits from this index on are Openverse's; the ones before it are Commons'. */
        private val openverseFrom: Int = hits,
        /** Where the page after [SearchCursor] leads; the first page is the one after null. */
        private val next: (SearchCursor?) -> SearchCursor? = { null },
    ) : OnlineImages {
        val searches = mutableListOf<Pair<String, SearchCursor?>>()

        override suspend fun search(query: String, from: SearchCursor?): OnlineImageSearchResult {
            searches += query to from
            // The first page keeps Android's ids; later ones are told apart by where they start.
            val page = (from as? SearchCursor.Commons)?.let { "-${it.offset}" }.orEmpty()
            return result ?: OnlineImageSearchResult.Found(
                images = List(hits) { index ->
                    OnlineImage(
                        id = "$query$page-$index",
                        title = "$query $index",
                        url = "https://example.org/$query-$index.jpg",
                        thumbnail = "https://example.org/$query-$index/thumb",
                        width = 400,
                        height = 300,
                        source = if (index < openverseFrom) ImageSource.WikimediaCommons else ImageSource.Openverse,
                    )
                },
                next = next(from),
            )
        }

        override suspend fun thumbnail(image: OnlineImage): ImageBitmap? = if (previews) ImageBitmap(8, 6) else null

        override suspend fun download(image: OnlineImage): ByteArray? = if (downloads) bytesFor(image.id) else null

        companion object {
            fun bytesFor(id: String): ByteArray = id.encodeToByteArray()
        }
    }
}

/** A camera that shows one frame and keeps streaming until the window stops collecting. */
internal class FakeCamera(
    private val devices: List<CameraDevice> = listOf(INTEGRATED),
    private val stream: (CameraDevice) -> Flow<CameraFrame> = { device ->
        flow {
            emit(CameraFrame(ImageBitmap(32, 24)) { photoOf(device) })
            awaitCancellation()
        }
    },
) : PictureCamera {
    val opened = mutableListOf<CameraDevice>()
    var streaming = 0
        private set

    override suspend fun cameras(): List<CameraDevice> = devices

    override fun frames(device: CameraDevice): Flow<CameraFrame> = flow {
        opened += device
        streaming++
        try {
            stream(device).collect { emit(it) }
        } finally {
            streaming--
        }
    }

    companion object {
        val INTEGRATED = CameraDevice("/dev/video0", "Integrated Camera")
        fun photoOf(device: CameraDevice): ByteArray = "photo of ${device.id}".encodeToByteArray()
    }
}
