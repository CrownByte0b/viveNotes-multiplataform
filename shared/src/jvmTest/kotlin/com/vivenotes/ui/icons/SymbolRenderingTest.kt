package com.vivenotes.ui.icons

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
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
            symbolsOf(ShellSymbols).mapKeys { "Shell.${it.key}" }
        assertTrue(symbols.size >= 26, "found only ${symbols.keys}")
        assertTrue(symbols.keys.containsAll(listOf("Context.Edit", "Context.SelectAll", "Context.PasteAsText",
            "Object.ExpandContent", "Shell.Menu", "Shell.Undo", "Shell.Redo")))
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
}
