package com.vivenotes.ai

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Android's `RecognitionStemTest`: the stroke-width arithmetic every formula model's [FormulaEngine.stemPx]
 * is stated in. `simulations/formula-render` measured mean token accuracy 0.615 against 0.898 on the
 * strength of this arithmetic alone.
 *
 * The property is not the formula, it is what the formula is for: **whatever size a formula is
 * written at, its strokes must land the same number of pixels wide in the square the model reads.**
 * A regression here is silent — recognition keeps working, it simply gets worse — so it is asserted
 * by replaying the scaling FormulaNet's preprocessing performs rather than by restating the algebra.
 */
class RecognitionStemTest {

    /** What the formula preprocessing does to a stroke of [stem] page units in a selection [longest] across. */
    private fun stemInModelFrame(longest: Float, stem: Float): Float =
        stem * FORMULA_INPUT_PX / (longest + stem)

    @Test
    fun everySelectionSizeLandsAtTheSameStemWidth() {
        // A subscript, a formula, and a whole line of algebra written across the page.
        listOf(12f, 40f, 144f, 190f, 400f, 1000f).forEach { longest ->
            val stem = recognitionStemSize(longest)
            assertEquals(
                RECOGNITION_STEM_PX,
                stemInModelFrame(longest, stem),
                0.01f,
                "a selection $longest units across did not land at the target stem",
            )
        }
    }

    @Test
    fun theStoredWidthDoesNotLandAtTheSameStemWidth() {
        // The defect, stated as a test so nobody reverts to it: three formulas 144, 190 and 219 units
        // across, drawn at the stored 2 dp, hand the model three different stem widths.
        val widths = listOf(144f, 190f, 219f).map { stemInModelFrame(it, 2f) }
        assertTrue(
            widths.max() - widths.min() > 1.5f,
            "the stored width was expected to scale differently per formula, got $widths",
        )
        widths.forEach {
            assertTrue(it < RECOGNITION_STEM_PX, "$it should be thinner than the target stem")
        }
    }

    /** Each formula model's own width, in the same frame, whatever the selection's size. */
    @Test
    fun eachFormulaModelLandsAtItsOwnStemWidth() {
        FormulaEngine.entries.forEach { engine ->
            listOf(12f, 190f, 1000f).forEach { longest ->
                assertEquals(
                    engine.stemPx,
                    stemInModelFrame(longest, recognitionStemSize(longest, engine.stemPx)),
                    0.01f,
                    "$engine at $longest units across",
                )
            }
        }
        assertEquals(RECOGNITION_STEM_PX, FormulaEngine.FormulaNetS.stemPx)
    }

    @Test
    fun theWidthStaysPositiveForADegenerateSelection() {
        assertTrue(recognitionStemSize(0f) >= 0f)
        assertTrue(recognitionStemSize(1f) > 0f)
    }
}
