package com.vivenotes.ui.icons

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Every ported symbol draws inside its own box.
 *
 * Regression: Link and Picture were given another symbol's viewport and offset, so both buttons
 * were blank although they worked. Found by reflection, so a symbol added later is checked too.
 */
@OptIn(ExperimentalTestApi::class)
class SymbolRenderingTest {

    private fun symbolsOf(holder: Any): Map<String, ImageVector> =
        holder.javaClass.methods
            .filter { it.parameterCount == 0 && it.returnType == ImageVector::class.java }
            .associate { it.name.removePrefix("get") to it.invoke(holder) as ImageVector }

    @Test
    fun everySymbolPaintsAVisibleShapeInItsBox() = runDesktopComposeUiTest {
        val symbols = symbolsOf(DocumentSymbols) + symbolsOf(ObjectSymbols).mapKeys { "Object.${it.key}" } +
            symbolsOf(ContextSymbols).mapKeys { "Context.${it.key}" } +
            symbolsOf(ShellSymbols).mapKeys { "Shell.${it.key}" } +
            symbolsOf(SettingsSymbols).mapKeys { "Settings.${it.key}" }
        assertTrue(symbols.size >= 26, "found only ${symbols.keys}")
        assertTrue(symbols.keys.containsAll(listOf("Context.Edit", "Context.SelectAll", "Context.PasteAsText",
            "Object.ExpandContent", "Shell.Menu", "Shell.MenuOpen", "Shell.Undo", "Shell.Redo",
            "Settings.Keyboard", "Settings.Stylus", "Settings.RestartAlt")))
        setContent {
            // A grid, so every symbol is inside the test window and can be captured.
            Column {
                symbols.entries.chunked(10).forEach { row ->
                    Row {
                        row.forEach { (name, icon) ->
                            Image(icon, contentDescription = null, colorFilter = ColorFilter.tint(Color.Black),
                                modifier = Modifier.size(48.dp).testTag(name))
                        }
                    }
                }
            }
        }
        val blank = symbols.keys.filter { name ->
            val pixels = onNodeWithTag(name).captureToImage().toPixelMap()
            val painted = (0 until pixels.width).sumOf { x -> (0 until pixels.height).count { y -> pixels[x, y].alpha > 0.5f } }
            painted < pixels.width * pixels.height / 50
        }
        assertTrue(blank.isEmpty(), "symbols that draw nothing in their box: $blank")
    }

    /**
     * Android `ic_background_none`: Background Replace with its top right corner cleared — the
     * clip's reversed circle must cut a hole rather than add a second fill — and an X in the hole.
     */
    @Test
    fun backgroundNoneIsBackgroundReplaceWithAnXInAClearedCorner() = runDesktopComposeUiTest {
        setContent {
            Row {
                listOf("replace" to ObjectSymbols.BackgroundReplace, "none" to ObjectSymbols.BackgroundNone).forEach { (tag, icon) ->
                    Image(icon, contentDescription = null, colorFilter = ColorFilter.tint(Color.Black),
                        modifier = Modifier.size(96.dp).testTag(tag))
                }
            }
        }
        val replace = onNodeWithTag("replace").captureToImage().toPixelMap()
        val none = onNodeWithTag("none").captureToImage().toPixelMap()
        // Viewport units to pixels. The hole is the circle of radius 220 about (800, 160).
        val scale = replace.width / 960f
        fun PixelMap.inked(x: Float, y: Float) = this[(x * scale).toInt(), (y * scale).toInt()].alpha > .5f
        assertTrue(replace.inked(700f, 160f) && !none.inked(700f, 160f), "the stripe beside the X is cut away")
        assertTrue(none.inked(800f, 160f), "the X is drawn in the hole")
        // Outside the hole the glyphs are one drawing, give or take the clip's edge anti-aliasing.
        val outside = (0 until replace.height).flatMap { y -> (0 until replace.width).map { x -> x to y } }.filter { (x, y) ->
            val dx = x / scale - 800f
            val dy = y / scale - 160f
            dx * dx + dy * dy > 240f * 240f
        }
        val worst = outside.maxOf { (x, y) -> abs(replace[x, y].alpha - none[x, y].alpha) }
        assertTrue(worst < .1f, "outside the hole the glyphs differ by up to $worst")
    }
}
