package com.vivenotes.ui.shell

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.height
import com.vivenotes.data.VideoThumbnailSource
import com.vivenotes.diagnostics.DebugLog
import com.vivenotes.model.Block
import com.vivenotes.model.Outline
import com.vivenotes.ui.ribbon.settings.InterfaceSettings
import com.vivenotes.ui.theme.ViveNotesTheme
import com.vivenotes.workspace.WorkspaceState
import kotlinx.coroutines.CompletableDeferred
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class CanvasTextPreparationTest {
    @Test fun preparationCompletionUsesCurrentContentWhenEditingStartsDuringTheFirstPass() =
        runDesktopComposeUiTest(width = 1400, height = 900) {
            var state by mutableStateOf(fixture(256))
            var changed = false
            val logs = mutableListOf<String>()
            val log = DebugLog(true, logs::add)
            setContent {
                CompositionLocalProvider(LocalTextPreparationObserver provides { snapshot ->
                    if (!changed && snapshot.ready in 1 until 128) {
                        changed = true
                        state = state.updatePage(state.selectedPageId) { page -> page.copy(document = page.document.copy(
                            outlines = page.document.outlines.map {
                                if (it is Outline.Text && it.id == "text-0") it.copy(blocks = listOf(Block.of("Edited during preparation"))) else it
                            })) }
                    }
                }) {
                    ViveNotesTheme { WorkspaceScreen(state, { state = it(state) }, debugLog = log) }
                }
            }
            waitForIdle()
            assertTrue(changed)
            assertEquals(1, logs.count { "preparation complete boxes=256" in it })
            assertTrue(logs.none { "Edited during preparation" in it })
        }

    @Test fun firstPreparationIsBatchedAndReturningToAPageReusesExactOffscreenMeasurements() =
        runDesktopComposeUiTest(width = 1400, height = 900) {
            var state by mutableStateOf(fixture(1_000))
            val page = state.selectedPageId
            val snapshots = mutableListOf<TextPreparationSnapshot>()
            val logs = mutableListOf<String>()
            val log = DebugLog(true, logs::add)
            setContent {
                CompositionLocalProvider(LocalTextPreparationObserver provides { snapshots += it }) {
                    ViveNotesTheme { WorkspaceScreen(state, { state = it(state) }, debugLog = log) }
                }
            }
            waitForIdle()
            val first = snapshots.last { it.pageId == page }
            assertEquals(1_000, first.ready)
            assertEquals(1_000, first.editorStarts)
            assertTrue(snapshots.all { it.background <= TextPreparationBatchSize })
            assertTrue(snapshots.any { it.ready in 1 until 1_000 && it.editorStarts < 1_000 })
            val extent = onNodeWithTag(WorkspaceTestTags.CanvasExtent).getUnclippedBoundsInRoot().height.value
            val lastHeight = onNodeWithTag(WorkspaceTestTags.textBox("text-999")).getUnclippedBoundsInRoot().height.value
            assertTrue(lastHeight > 80f)
            onNodeWithTag(WorkspaceTestTags.textBox("text-999") + "-editor").assertDoesNotExist()
            runOnIdle { state = state.addPage() }
            waitForIdle()
            runOnIdle { snapshots.clear(); state = state.selectPage(page) }
            waitForIdle()
            val returned = snapshots.last { it.pageId == page }
            assertEquals(1_000, returned.ready)
            assertEquals(1_000, returned.reused)
            assertTrue(returned.editorStarts < 10, "only visible boxes reopen: $returned")
            assertEquals(extent, onNodeWithTag(WorkspaceTestTags.CanvasExtent).getUnclippedBoundsInRoot().height.value)
            assertEquals(lastHeight, onNodeWithTag(WorkspaceTestTags.textBox("text-999")).getUnclippedBoundsInRoot().height.value)
            val preparationLogs = logs.filter { "[text-canvas]" in it }
            assertTrue(preparationLogs.any { "reused=1000" in it })
            assertTrue(preparationLogs.size <= 6, "one start/finish per page visit")
            assertTrue(preparationLogs.none { "Private fixture text" in it })
        }

    @Test fun changedWidthContentAndFontScaleRemeasureBeforeCullingOnReopen() =
        runDesktopComposeUiTest(width = 1400, height = 900) {
            var state by mutableStateOf(fixture(24))
            var settings by mutableStateOf(InterfaceSettings(displayScale = 1f))
            val page = state.selectedPageId
            val snapshots = mutableListOf<TextPreparationSnapshot>()
            setContent {
                CompositionLocalProvider(LocalTextPreparationObserver provides { snapshots += it }) {
                    ViveNotesTheme { WorkspaceScreen(state, { state = it(state) }, interfaceSettings = settings) }
                }
            }
            waitForIdle()
            val height = onNodeWithTag(WorkspaceTestTags.textBox("text-23")).getUnclippedBoundsInRoot().height.value
            runOnIdle {
                state = state.addPage().updatePage(page) { old -> old.copy(document = old.document.copy(
                    outlines = old.document.outlines.map {
                        if (it is Outline.Text && it.id == "text-23") it.copy(width = 240f,
                            blocks = List(30) { Block.of("Changed content must be measured") }) else it
                    })) }
            }
            waitForIdle()
            runOnIdle { snapshots.clear(); state = state.selectPage(page) }
            waitForIdle()
            assertEquals(23, snapshots.last { it.pageId == page }.reused)
            val changed = onNodeWithTag(WorkspaceTestTags.textBox("text-23")).getUnclippedBoundsInRoot().height.value
            assertTrue(changed > height * 2)
            runOnIdle { state = state.addPage() }
            waitForIdle()
            runOnIdle { snapshots.clear(); settings = settings.copy(fontScale = 1.5f); state = state.selectPage(page) }
            waitForIdle()
            assertEquals(0, snapshots.last { it.pageId == page }.reused)
            assertEquals(24, snapshots.last { it.pageId == page }.ready)
            assertTrue(onNodeWithTag(WorkspaceTestTags.textBox("text-23")).getUnclippedBoundsInRoot().height.value > changed)
        }

    @Test fun focusedFarTextBypassesBackgroundAdmissionAndKeepsTyping() =
        runDesktopComposeUiTest(width = 1400, height = 900) {
            var state by mutableStateOf(fixture(80).focusTextBox("text-79"))
            val snapshots = mutableListOf<TextPreparationSnapshot>()
            setContent {
                CompositionLocalProvider(LocalTextPreparationObserver provides { snapshots += it }) {
                    ViveNotesTheme { WorkspaceScreen(state, { state = it(state) }) }
                }
            }
            onNodeWithTag(WorkspaceTestTags.textBox("text-79") + "-editor").performTextInput("Typed")
            runOnIdle { assertTrue(state.richTextFor("text-79")!!.text.startsWith("Typed")) }
            assertTrue(snapshots.first().composed > 0)
            assertTrue(snapshots.all { it.background <= TextPreparationBatchSize })
        }

    @Test fun changingThePlatformFontResolverInvalidatesActiveAndRetainedMeasurements() =
        runDesktopComposeUiTest(width = 1400, height = 900) {
            var state by mutableStateOf(fixture(24))
            val page = state.selectedPageId
            val snapshots = mutableListOf<TextPreparationSnapshot>()
            var resolver: FontFamily.Resolver? by mutableStateOf(null)
            setContent {
                CompositionLocalProvider(LocalFontFamilyResolver provides (resolver ?: LocalFontFamilyResolver.current),
                    LocalTextPreparationObserver provides { snapshots += it }) {
                    ViveNotesTheme { WorkspaceScreen(state, { state = it(state) }) }
                }
            }
            waitForIdle()
            val original = snapshots.last { it.pageId == page }.editorStarts
            runOnIdle { resolver = createFontFamilyResolver() }
            waitForIdle()
            assertEquals(24, snapshots.last { it.pageId == page }.ready)
            assertTrue(snapshots.last { it.pageId == page }.editorStarts >= original + 20,
                "culled boxes must be remeasured when the font resolver changes")
            runOnIdle { state = state.addPage() }
            waitForIdle()
            runOnIdle { resolver = createFontFamilyResolver(); snapshots.clear(); state = state.selectPage(page) }
            waitForIdle()
            assertEquals(0, snapshots.last { it.pageId == page }.reused)
            assertEquals(24, snapshots.last { it.pageId == page }.ready)
        }

    @Test fun delayedVideoHeightsArePreparedAgainOnReopenAndCancelledPagesCannotPublish() =
        runDesktopComposeUiTest(width = 1400, height = 900) {
            val release = CompletableDeferred<Unit>()
            val calls = AtomicInteger()
            val source = object : VideoThumbnailSource {
                override suspend fun load(videoId: String): ByteArray? {
                    calls.incrementAndGet()
                    release.await()
                    return null
                }
            }
            var state by mutableStateOf(fixture(2).let { original ->
                original.updatePage(original.selectedPageId) { page -> page.copy(document = page.document.copy(
                    outlines = page.document.outlines + Outline.Text("video", x = 0f, y = 8000f, width = 500f,
                        blocks = listOf(Block.of("https://youtu.be/video000000")))))}
            })
            val page = state.selectedPageId
            val snapshots = mutableListOf<TextPreparationSnapshot>()
            setContent {
                CompositionLocalProvider(LocalTextPreparationObserver provides { snapshots += it }) {
                    ViveNotesTheme { WorkspaceScreen(state, { state = it(state) }, thumbnails = source) }
                }
            }
            try {
                waitUntil(timeoutMillis = 5_000) { calls.get() == 1 }
                runOnIdle { state = state.addPage() }
                waitForIdle()
                release.complete(Unit)
                runOnIdle { snapshots.clear(); state = state.selectPage(page) }
                waitUntil(timeoutMillis = 5_000) { calls.get() == 2 }
                waitForIdle()
                assertEquals(3, snapshots.last { it.pageId == page }.ready)
                assertEquals(2, snapshots.last { it.pageId == page }.reused)
                runOnIdle { state = state.addPage() }
                waitForIdle()
                runOnIdle { state = state.selectPage(page) }
                waitUntil(timeoutMillis = 5_000) { calls.get() == 3 }
                waitForIdle()
                onNodeWithTag(WorkspaceTestTags.textBox("video") + "-editor").assertDoesNotExist()
            } finally { release.complete(Unit) }
        }

    private fun fixture(count: Int): WorkspaceState = WorkspaceState.demo().let { state ->
        state.updatePage(state.selectedPageId) { page -> page.copy(document = page.document.copy(
            outlines = List(count) { index -> Outline.Text("text-$index", x = 20f, y = 130f + index * 400f,
                width = 500f, blocks = listOf(Block("block-$index", runs = listOf(com.vivenotes.model.Run(
                    "Private fixture text $index. ".repeat(50).take(1_000)))))) }
        )) }.clearCanvasFocus()
    }

}
