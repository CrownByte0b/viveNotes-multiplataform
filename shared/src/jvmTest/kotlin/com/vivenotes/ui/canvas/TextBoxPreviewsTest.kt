package com.vivenotes.ui.canvas

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.ParagraphStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.unit.sp
import com.vivenotes.data.VideoThumbnailSource
import com.vivenotes.model.Block
import com.vivenotes.richtext.RichTextBuffer
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import org.jetbrains.compose.resources.decodeToImageBitmap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class TextBoxPreviewsTest {
    @Test
    fun latexPreviewIsVisibleAndOpensItsSourceForEditing() = runDesktopComposeUiTest {
        var edits = 0
        val source = AnnotatedString("Area is \$\\frac{a}{b}\$.")
        setContent {
            TextBoxPreviews(source, TextStyle(fontSize = 15.sp), Color.Black, 300f,
                thumbnails = null, onEdit = { edits++ }, onOpenVideo = {})
        }
        onNodeWithTag("text-box-preview").assertIsDisplayed().performClick()
        assertEquals(1, edits)
        assertEquals("Area is \$\\frac{a}{b}\$.", source.text)
    }

    @Test
    fun multilineDisplayEquationUsesOnePreview() = runDesktopComposeUiTest {
        setContent {
            TextBoxPreviews(AnnotatedString("Before\n\$\$\nx^2\n\$\$\nAfter"),
                TextStyle(fontSize = 15.sp), Color.Black, 300f,
                thumbnails = null, onEdit = {}, onOpenVideo = {})
        }
        onNodeWithTag("text-box-preview").assertIsDisplayed()
    }

    @Test
    fun previewDoesNotCrossStyledParagraphBoundaries() = runDesktopComposeUiTest {
        val buffer = RichTextBuffer(listOf(
            Block.of("Before"), Block.of("\$\$"), Block.of("x^2"),
            Block.of("\$\$"), Block.of("After"),
        ))
        setContent {
            TextBoxPreviews(buffer.asAnnotatedString(RichTextColors(Color.Green, Color.Blue, Color.Gray)),
                TextStyle(fontSize = 15.sp), Color.Black, 300f,
                thumbnails = null, onEdit = {}, onOpenVideo = {})
        }
        onNodeWithTag("text-box-preview").assertIsDisplayed()
    }

    @Test
    fun leadingDisplayFormulaFollowedByEmptyBlockDoesNotCrashLayout() = runDesktopComposeUiTest {
        val buffer = RichTextBuffer(listOf(Block.of("\$\$123456789\$\$ "), Block.of("")))
        setContent {
            TextBoxPreviews(buffer.asAnnotatedString(RichTextColors(Color.Green, Color.Blue, Color.Gray)),
                TextStyle(fontSize = 15.sp), Color.Black, 300f,
                thumbnails = null, onEdit = {}, onOpenVideo = {})
        }
        onNodeWithTag("text-box-preview").assertIsDisplayed()
    }

    @Test
    fun aPlaceholderNeverOverlapsAnyParagraphStyleBoundary() = runDesktopComposeUiTest {
        val text = "A \$x^2\$ Z"
        setContent {
            Column {
                (1 until text.length).forEach { boundary ->
                    val annotated = AnnotatedString(text, paragraphStyles = listOf(
                        AnnotatedString.Range(ParagraphStyle(textAlign = TextAlign.Start), 0, boundary),
                        AnnotatedString.Range(ParagraphStyle(textAlign = TextAlign.End), boundary, text.length),
                    ))
                    TextBoxPreviews(annotated, TextStyle(fontSize = 15.sp), Color.Black, 300f,
                        thumbnails = null, onEdit = {}, onOpenVideo = {})
                }
                (0..text.length).forEach { boundary ->
                    val annotated = AnnotatedString(text, paragraphStyles = listOf(
                        AnnotatedString.Range(ParagraphStyle(textAlign = TextAlign.Center),
                            boundary, boundary),
                    ))
                    TextBoxPreviews(annotated, TextStyle(fontSize = 15.sp), Color.Black, 300f,
                        thumbnails = null, onEdit = {}, onOpenVideo = {})
                }
            }
        }
        waitForIdle()
    }

    @Test
    fun storedAndroidEquationMarkRendersInAReadOnlyTextBox() = runDesktopComposeUiTest {
        setContent {
            TextBoxPreviews(AnnotatedString("Value \uFFFC"), TextStyle(fontSize = 15.sp),
                Color.Black, 300f, thumbnails = null, onEdit = {}, onOpenVideo = {},
                storedEquations = listOf(EquationPreview(6, 7, "\\frac{1}{2}")))
        }
        onNodeWithTag("text-box-preview").assertIsDisplayed()
    }

    @Test
    fun loadedYouTubeCardOpensTheOriginalTimedUrl() = runDesktopComposeUiTest {
        val id = "dQw4w9WgXcQ"
        val url = "https://youtu.be/$id?t=90"
        val png = ByteArrayOutputStream().also { stream ->
            ImageIO.write(BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), "png", stream)
        }.toByteArray()
        assertEquals(2, png.decodeToImageBitmap().width)
        var loads = 0
        val source = object : VideoThumbnailSource {
            override suspend fun load(videoId: String): ByteArray? {
                loads++
                return png.takeIf { videoId == id }
            }
        }
        var opened: String? = null
        var edits = 0
        setContent {
            TextBoxPreviews(AnnotatedString("Watch $url later"), TextStyle(fontSize = 15.sp),
                Color.Black, 340f, source, onEdit = { edits++ }, onOpenVideo = { opened = it })
        }
        waitForIdle()
        assertEquals(1, loads)
        waitUntil(timeoutMillis = 5_000) {
            runCatching { onNodeWithTag("video-preview-$id", useUnmergedTree = true).assertIsDisplayed(); true }.getOrDefault(false)
        }
        onNodeWithTag("video-play-$id", useUnmergedTree = true).performClick()
        assertEquals(url, opened)
        onNodeWithTag("video-preview-$id", useUnmergedTree = true)
            .performTouchInput { click(Offset(8f, 8f)) }
        assertEquals(1, edits)
        assertTrue(url.contains("t=90"))
    }
}
