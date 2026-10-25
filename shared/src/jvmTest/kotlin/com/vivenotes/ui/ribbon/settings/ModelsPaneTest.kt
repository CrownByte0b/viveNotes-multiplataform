package com.vivenotes.ui.ribbon.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import com.vivenotes.ai.AiModelInstallState
import com.vivenotes.ai.AiModels
import com.vivenotes.ai.AiModelsState
import com.vivenotes.ai.FormulaEngine
import com.vivenotes.ui.components.assertIsDeterminateWavyBar
import com.vivenotes.ui.components.assertIsIndeterminateWavyBar
import com.vivenotes.ui.components.ToolPaneTags
import com.vivenotes.ui.shell.WorkspaceScreen
import com.vivenotes.ui.theme.ViveNotesTheme
import com.vivenotes.workspace.RibbonTab
import com.vivenotes.workspace.WorkspaceState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Settings → Models: Android's Integrated pane, docked like Hardware. Each formula model offers
 * what its install state allows; the automatically installed math engine has no management UI.
 */
@OptIn(ExperimentalTestApi::class)
class ModelsPaneTest {
    private val models = FakeModels()

    @Test
    fun modelsIsUnavailableWithoutRecognitionModels() = runDesktopComposeUiTest(width = 1600, height = 900) {
        setWorkspace(models = null)

        onNodeWithTag(ModelsTags.Open).assertIsNotEnabled()
    }

    @Test
    fun modelsDocksItsPaneAndUnderlinesWhileOpen() = runDesktopComposeUiTest(width = 1600, height = 900) {
        setWorkspace()

        onNodeWithTag(ModelsTags.Open).assertIsEnabled().assertIsNotSelected().performClick()

        onNodeWithTag(ModelsTags.Open).assertIsSelected()
        onNodeWithTag(ToolPaneTags.Pane).assertExists()
        FormulaEngine.entries.forEach { onNodeWithTag(ModelsTags.formula(it)).assertExists() }
        onNodeWithTag(ModelsTags.TextModel).assertExists()
        val tab = onNodeWithTag(ModelsTags.Open).captureToImage().toPixelMap()
        val underline = tab[tab.width / 2, tab.height - 2]
        assertTrue(abs(underline.red - 0f) < 0.03f && abs(underline.blue - 1f) < 0.03f, "underline painted $underline")

        onNodeWithTag(ToolPaneTags.Close).performClick()
        onNodeWithTag(ToolPaneTags.Pane).assertDoesNotExist()
        onNodeWithTag(ModelsTags.Open).assertIsNotSelected()
    }

    @Test
    fun modelsPaneDoesNotExposeMathEngineManagement() = runDesktopComposeUiTest(width = 1600, height = 900) {
        setWorkspace()
        openModels()

        onNodeWithText("Math engine").assertDoesNotExist()
        onNodeWithText("SymPy", substring = true).assertDoesNotExist()
        onNodeWithText("Install").assertDoesNotExist()
        onNodeWithText("Remove").assertDoesNotExist()
        FormulaEngine.entries.forEach { onNodeWithTag(ModelsTags.formula(it)).assertExists() }
        onNodeWithTag(ModelsTags.TextModel).assertExists()
    }

    @Test
    fun aModelNotInstalledOffersItsDownloadWithItsSize() = runDesktopComposeUiTest(width = 1600, height = 900) {
        setWorkspace()
        openModels()

        onNodeWithTag(ModelsTags.formula(FormulaEngine.UniMerNetSmall))
            .assertTextContains("UniMERNet Small")
            .assertTextContains("Best on algebra · 209 MB")
        onNodeWithTag(ModelsTags.formula(FormulaEngine.UniMerNetTiny))
            .assertTextContains("UniMERNet-T")
            .assertTextContains("Smaller download · 113 MB")
        onNodeWithTag(ModelsTags.formula(FormulaEngine.FormulaNetS))
            .assertTextContains("PP-FormulaNet-S")
            .assertTextContains("Reads matrices · 234 MB")
        onNodeWithTag(ModelsTags.download(FormulaEngine.FormulaNetS)).performClick()

        assertEquals(listOf(FormulaEngine.FormulaNetS), models.downloads)
    }

    @Test
    fun smallOffersDownloadProgressRetryUseAndDelete() = runDesktopComposeUiTest(width = 1600, height = 900) {
        val small = FormulaEngine.UniMerNetSmall
        setWorkspace()
        openModels()
        onNodeWithTag(ModelsTags.download(small)).performClick()
        assertEquals(listOf(small), models.downloads)

        models.state.value = models.state.value.withFormula(small,
            AiModelInstallState.Downloading(104_517_967, 209_035_935))
        onNodeWithTag(ModelsTags.progress(small)).assertTextContains("Downloading 50%")
        onNodeWithTag(ModelsTags.download(small)).assertDoesNotExist()
        models.state.value = models.state.value.withFormula(small, AiModelInstallState.Verifying)
        onNodeWithTag(ModelsTags.progress(small)).assertTextContains("Verifying…")
        models.state.value = models.state.value.withFormula(small, AiModelInstallState.Failed("Download interrupted"))
        onNodeWithTag(ModelsTags.formula(small)).assertTextContains("Download interrupted")
        onNodeWithTag(ModelsTags.download(small)).assertTextContains("Retry").performClick()
        assertEquals(listOf(small, small), models.downloads)

        models.state.value = models.state.value.withFormula(small, AiModelInstallState.Installed)
            .withFormula(FormulaEngine.UniMerNetTiny, AiModelInstallState.Installed)
            .copy(formulaEngine = FormulaEngine.UniMerNetTiny)
        onNodeWithTag(ModelsTags.use(small)).assertIsNotSelected().performClick()
        assertEquals(listOf(small), models.selections)
        models.state.value = models.state.value.copy(formulaEngine = small)
        onNodeWithTag(ModelsTags.use(small)).assertIsSelected().assertTextContains("In use")
        onNodeWithTag(ModelsTags.use(FormulaEngine.UniMerNetTiny)).assertIsNotSelected()
        onNodeWithTag(ModelsTags.delete(small)).performClick()
        assertEquals(listOf(small), models.deletes)
    }

    @Test
    fun aDownloadShowsHowFarItHasGotThenItsVerification() = runDesktopComposeUiTest(width = 1600, height = 900) {
        setWorkspace()
        openModels()

        models.state.value = models.state.value.withFormula(FormulaEngine.UniMerNetTiny,
            AiModelInstallState.Downloading(downloadedBytes = 45_000_000, totalBytes = 113_330_067))
        onNodeWithTag(ModelsTags.progress(FormulaEngine.UniMerNetTiny)).assertTextContains("Downloading 40%")
        onNodeWithTag(ModelsTags.download(FormulaEngine.UniMerNetTiny)).assertDoesNotExist()

        models.state.value = models.state.value.withFormula(FormulaEngine.UniMerNetTiny, AiModelInstallState.Verifying)
        onNodeWithTag(ModelsTags.progress(FormulaEngine.UniMerNetTiny)).assertTextContains("Verifying…")
    }

    @Test
    fun everyModelUsesExpressiveWavesForDownloadAndVerification() = runDesktopComposeUiTest(width = 1600, height = 900) {
        setWorkspace()
        openModels()
        for (engine in FormulaEngine.entries) {
            for (fraction in listOf(0f, 0.5f, 1f)) {
                models.state.value = models.state.value.withFormula(engine,
                    AiModelInstallState.Downloading((fraction * 1000).toLong(), 1000))
                onNodeWithTag(ModelsTags.progress(engine))
                    .assertTextContains("Downloading ${(fraction * 100).toInt()}%")
                onNodeWithTag(ModelsTags.progressIndicator(engine), useUnmergedTree = true)
                    .assertIsDeterminateWavyBar(fraction)
            }
            models.state.value = models.state.value.withFormula(engine, AiModelInstallState.Verifying)
            onNodeWithTag(ModelsTags.progress(engine)).assertTextContains("Verifying…")
            onNodeWithTag(ModelsTags.progressIndicator(engine), useUnmergedTree = true)
                .assertIsIndeterminateWavyBar()
        }
    }

    @Test
    fun aFailedDownloadSaysWhyAndCanBeRetried() = runDesktopComposeUiTest(width = 1600, height = 900) {
        models.state.value = models.state.value.withFormula(FormulaEngine.UniMerNetTiny,
            AiModelInstallState.Failed("Server returned HTTP 503"))
        setWorkspace()
        openModels()

        onNodeWithTag(ModelsTags.formula(FormulaEngine.UniMerNetTiny)).assertTextContains("Server returned HTTP 503")
        onNodeWithTag(ModelsTags.download(FormulaEngine.UniMerNetTiny)).assertTextContains("Retry").performClick()

        assertEquals(listOf(FormulaEngine.UniMerNetTiny), models.downloads)
    }

    /** The two installed rows are one choice: the model in use says so, the other offers Use. */
    @Test
    fun installedModelsOfferUseAndDelete() = runDesktopComposeUiTest(width = 1600, height = 900) {
        models.state.value = AiModelsState(
            handwritingText = AiModelInstallState.Installed,
            formulaLatex = AiModelInstallState.Installed,
            uniMerNet = AiModelInstallState.Installed,
            formulaEngine = FormulaEngine.UniMerNetTiny,
        )
        setWorkspace()
        openModels()

        onNodeWithTag(ModelsTags.use(FormulaEngine.UniMerNetTiny)).assertIsSelected().assertTextContains("In use")
        onNodeWithTag(ModelsTags.use(FormulaEngine.FormulaNetS)).assertIsNotSelected().assertTextContains("Use")
        // The choice made is in the accent colour, and the other is a button you can see. Material's
        // outlined button drew a hairline and a grey label here, which read as disabled.
        assertPaints(ModelsTags.use(FormulaEngine.UniMerNetTiny), Color(0xFF007FFF))
        // Tight, because the group's own background is only a shade lighter than the button's fill.
        assertPaints(ModelsTags.use(FormulaEngine.FormulaNetS), Color(0xFF333333), tolerance = 0.015f)
        assertPaints(ModelsTags.use(FormulaEngine.FormulaNetS), Color(0xFFE6E6E6))
        onNodeWithTag(ModelsTags.use(FormulaEngine.FormulaNetS))
            .performClick()
        assertEquals(listOf(FormulaEngine.FormulaNetS), models.selections)
        // Pressing the model in use again changes nothing.
        onNodeWithTag(ModelsTags.use(FormulaEngine.UniMerNetTiny)).performClick()
        assertEquals(listOf(FormulaEngine.FormulaNetS), models.selections)

        onNodeWithTag(ModelsTags.delete(FormulaEngine.UniMerNetTiny)).performClick()
        assertEquals(listOf(FormulaEngine.UniMerNetTiny), models.deletes)
    }

    @Test
    fun theBundledOcrSaysWhatItIs() = runDesktopComposeUiTest(width = 1600, height = 900) {
        setWorkspace()
        openModels()

        onNodeWithTag(ModelsTags.TextModel)
            .assertTextContains("PP-OCRv6 Small")
            .assertTextContains("Handwriting and pictures · 31.1 MB · built in")

        models.state.value = models.state.value.copy(handwritingText = AiModelInstallState.Failed("Bundled OCR model is unavailable"))
        onNodeWithTag(ModelsTags.TextModel).assertTextContains("Bundled OCR model is unavailable")
    }

    private fun ComposeUiTest.assertPaints(tag: String, expected: Color, tolerance: Float = 0.05f) {
        val pixels = onNodeWithTag(tag).captureToImage().toPixelMap()
        assertTrue((0 until pixels.width).any { x ->
            (0 until pixels.height).any { y ->
                val pixel = pixels[x, y]
                abs(pixel.red - expected.red) < tolerance && abs(pixel.green - expected.green) < tolerance &&
                    abs(pixel.blue - expected.blue) < tolerance
            }
        }, "$tag should paint $expected")
    }

    private fun ComposeUiTest.openModels() {
        onNodeWithTag(ModelsTags.Open).performClick()
        onNodeWithTag(ToolPaneTags.Pane).assertExists()
    }

    private fun ComposeUiTest.setWorkspace(models: AiModels? = this@ModelsPaneTest.models) {
        setContent {
            var state by remember { mutableStateOf(WorkspaceState.demo().copy(activeTab = RibbonTab.Settings)) }
            ViveNotesTheme(darkTheme = true) {
                WorkspaceScreen(state = state, onStateChange = { state = it(state) },
                    aiModels = models)
            }
        }
    }

    private class FakeModels : AiModels {
        override val state = MutableStateFlow(
            AiModelsState(
                handwritingText = AiModelInstallState.Installed,
                formulaLatex = AiModelInstallState.NotInstalled,
                uniMerNet = AiModelInstallState.NotInstalled,
                uniMerNetSmall = AiModelInstallState.NotInstalled,
            ),
        )
        val downloads = mutableListOf<FormulaEngine>()
        val deletes = mutableListOf<FormulaEngine>()
        val selections = mutableListOf<FormulaEngine>()
        override fun download(engine: FormulaEngine) { downloads += engine }
        override fun delete(engine: FormulaEngine) { deletes += engine }
        override fun selectFormulaEngine(engine: FormulaEngine) { selections += engine }
    }
}
