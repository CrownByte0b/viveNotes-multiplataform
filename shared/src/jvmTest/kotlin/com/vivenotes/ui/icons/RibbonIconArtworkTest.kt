package com.vivenotes.ui.icons

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.graphics.vector.group
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import com.vivenotes.ui.ribbon.RibbonStyle
import com.vivenotes.ui.ribbon.draw.DrawRibbon
import com.vivenotes.ui.ribbon.draw.DrawRibbonTags
import com.vivenotes.ui.ribbon.file.FileRibbon
import com.vivenotes.ui.ribbon.file.FileRibbonTags
import com.vivenotes.ui.ribbon.settings.SettingsRibbon
import com.vivenotes.ui.theme.ViveNotesTheme
import com.vivenotes.data.DeletedItem
import com.vivenotes.workspace.FileActions
import com.vivenotes.workspace.FilePane
import com.vivenotes.workspace.WorkspaceState
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class RibbonIconArtworkTest {
    @Test
    fun interfaceAndEraserPaintTheOfficialRequestedArtwork() = runDesktopComposeUiTest(width = 1200, height = 300) {
        val references = mapOf(
            "settings-icon-interface" to officialSvg("display_settings_24px.svg"),
            DrawRibbonTags.icon(DrawRibbonTags.EraserTool) to officialSvg("ic_fluent_eraser_24_regular.svg"),
        )
        setContent {
            ViveNotesTheme {
                Column {
                    SettingsRibbon(onInterface = {}, hardwareOpen = false, onHardware = {},
                        linkPreviews = false, onLinkPreviewsChange = {}, onAbout = {})
                    DrawRibbon(WorkspaceState.demo(), {})
                    references.forEach { (tag, icon) ->
                        Icon(icon, null, tint = RibbonStyle.normalText,
                            modifier = Modifier.size(18.dp).background(RibbonStyle.background).testTag("reference-$tag"))
                    }
                }
            }
        }
        assertArtworkMatchesReferences(references.keys)
    }

    @Test
    fun notebookCommandsPaintAndroidsTwoToneArtworkAndRunTheirActions() = runDesktopComposeUiTest(width = 2000, height = 300) {
        val references = mapOf(
            FileRibbonTags.icon(FileRibbonTags.CloseNotebook) to officialSvg("close_notebook_android.svg"),
            FileRibbonTags.icon(FileRibbonTags.ClosedNotebooks) to officialSvg("closed_notebooks_android.svg"),
        )
        val calls = mutableListOf<String>()
        val actions = object : FileActions {
            override fun openPane(pane: FilePane) { calls += "pane:$pane" }
            override fun closePane() {}
            override fun selectRevision(id: String) {}
            override fun restoreRevision() {}
            override fun restoreDeletedItem(item: DeletedItem) {}
            override fun reopenNotebook(id: String) {}
            override fun closeNotebook() {}
            override fun deleteNotebook() {}
            override fun exportNotebook() {}
            override fun importNotebook() {}
            override fun dismissTransfer() {}
        }
        setContent {
            ViveNotesTheme {
                Column {
                    FileRibbon(notebookOpen = true, pageOpen = true, transferRunning = false,
                        actions = actions, onCloseNotebook = { calls += "close-notebook" }, onDeleteNotebook = {})
                    references.forEach { (tag, icon) ->
                        Icon(icon, null, tint = Color.Unspecified,
                            modifier = Modifier.size(18.dp).background(RibbonStyle.background).testTag("reference-$tag"))
                    }
                }
            }
        }
        assertArtworkMatchesReferences(references.keys)
        onNodeWithTag(FileRibbonTags.ClosedNotebooks).assertIsEnabled().performClick()
        onNodeWithTag(FileRibbonTags.CloseNotebook).assertIsEnabled().performClick()
        runOnIdle { assertEquals(listOf("pane:ClosedNotebooks", "close-notebook"), calls) }
    }

    private fun ComposeUiTest.assertArtworkMatchesReferences(tags: Collection<String>) {
        tags.forEach { tag ->
            val actual = onNodeWithTag(tag, useUnmergedTree = true).captureToImage().toPixelMap()
            val expected = onNodeWithTag("reference-$tag", useUnmergedTree = true).captureToImage().toPixelMap()
            assertEquals(expected.width, actual.width)
            assertEquals(expected.height, actual.height)
            for (y in 0 until actual.height) for (x in 0 until actual.width) {
                val a = actual[x, y]
                val e = expected[x, y]
                assertTrue(abs(a.red - e.red) < .02f && abs(a.green - e.green) < .02f &&
                    abs(a.blue - e.blue) < .02f && abs(a.alpha - e.alpha) < .02f,
                    "$tag differs from the official SVG at ($x, $y): $a vs $e")
            }
        }
    }

    /** Upstream SVG exports and exact Android glyph paths serve as independent artwork fixtures. */
    private fun officialSvg(file: String): ImageVector {
        val svg = requireNotNull(javaClass.getResourceAsStream("/icons/$file")).use {
            DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(it).documentElement
        }
        val viewBox = svg.getAttribute("viewBox").split(' ').map(String::toFloat)
        return ImageVector.Builder(file, 24.dp, 24.dp, viewBox[2], viewBox[3])
            .group(translationX = -viewBox[0], translationY = -viewBox[1]) {
                val paths = svg.getElementsByTagName("path")
                for (index in 0 until paths.length) {
                    val attributes = paths.item(index).attributes
                    val fill = attributes.getNamedItem("fill")?.nodeValue
                    val color = if (fill?.startsWith('#') == true) Color(0xFF000000L or fill.drop(1).toLong(16)) else Color.Black
                    addPath(addPathNodes(attributes.getNamedItem("d").nodeValue), fill = SolidColor(color))
                }
            }.build()
    }
}
