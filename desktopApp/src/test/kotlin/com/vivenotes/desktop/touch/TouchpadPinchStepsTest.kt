package com.vivenotes.desktop.touch

import com.vivenotes.ui.canvas.TouchpadPinch
import com.vivenotes.ui.canvas.TouchpadPinch.Phase.Begin
import com.vivenotes.ui.canvas.TouchpadPinch.Phase.End
import com.vivenotes.ui.canvas.TouchpadPinch.Phase.Update
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** A platform's pinch, measured from where it began, as the steps the page zooms by. */
class TouchpadPinchStepsTest {
    private val steps = TouchpadPinchSteps()

    private fun sample(phase: TouchpadPinch.Phase, scale: Double = 1.0) = PinchSample(phase, null, 10.0, 20.0, 0, scale)

    @Test
    fun eachStepIsTheSpreadSinceTheOneBefore() {
        assertEquals(TouchpadPinch(Begin), steps.accept(sample(Begin)))
        assertEquals(1.25f, steps.accept(sample(Update, 1.25))!!.zoomChange, 1e-6f)
        assertEquals(1.6f, steps.accept(sample(Update, 2.0))!!.zoomChange, 1e-6f)
        // Fingers drawn back together: below one.
        assertEquals(0.75f, steps.accept(sample(Update, 1.5))!!.zoomChange, 1e-6f)
        assertEquals(TouchpadPinch(End), steps.accept(sample(End)))
    }

    @Test
    fun theStepsMultiplyToTheWholePinch() {
        steps.accept(sample(Begin))
        val zoom = listOf(1.1, 1.3, 0.9, 2.4, 3.0).fold(1f) { zoom, scale -> zoom * steps.accept(sample(Update, scale))!!.zoomChange }
        assertEquals(3f, zoom, 1e-5f)
    }

    @Test
    fun aScaleThatIsNotASpreadIsLeftOutAndTheNextStepCountsFromTheLastGoodOne() {
        steps.accept(sample(Begin))
        steps.accept(sample(Update, 1.5))
        for (bad in listOf(0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY)) assertNull(steps.accept(sample(Update, bad)), "$bad")
        assertEquals(2f, steps.accept(sample(Update, 3.0))!!.zoomChange, 1e-6f)
    }

    @Test
    fun nothingOutsideABeginAndItsEndIsAStep() {
        assertNull(steps.accept(sample(Update, 2.0)), "an update with no begin")
        assertNull(steps.accept(sample(End)), "an end with no begin")
        steps.accept(sample(Begin))
        steps.accept(sample(End))
        assertNull(steps.accept(sample(Update, 2.0)), "an update after the end")
        assertNull(steps.accept(sample(End)), "a second end")
    }

    @Test
    fun aBeginDuringAPinchStartsAgainFromOne() {
        steps.accept(sample(Begin))
        steps.accept(sample(Update, 2.0))
        // Its end was lost: the new pinch's scale is measured from its own start.
        assertEquals(TouchpadPinch(Begin), steps.accept(sample(Begin)))
        assertEquals(1.2f, steps.accept(sample(Update, 1.2))!!.zoomChange, 1e-6f)
    }
}
