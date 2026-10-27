package com.vivenotes.ui.ribbon.draw

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.Velocity
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class CalculatorTouchGestureTest {
    @Test fun theFirstFingerLiftEndsTheThrowAndTheRemainingFingerCannotCarryItAgain() = runDesktopComposeUiTest {
        var moved = Offset.Zero
        val releases = mutableListOf<Velocity>()
        setContent { Box(Modifier.fillMaxSize().testTag("throw").pointerInput(Unit) {
            detectCalculatorThrow({ moved += it }, { releases += it })
        }) }
        onNodeWithTag("throw").performTouchInput {
            down(0, Offset(100f, 100f)); down(1, Offset(200f, 100f))
            repeat(5) { updatePointerBy(0, Offset(15f, 0f)); updatePointerBy(1, Offset(15f, 0f)); move(16) }
            up(0)
        }
        runOnIdle { assertEquals(1, releases.size); assertTrue(releases.single().x > 0f) }
        val carried = moved
        onNodeWithTag("throw").performTouchInput {
            down(2, Offset(250f, 100f))
            updatePointerBy(1, Offset(50f, 0f)); updatePointerBy(2, Offset(50f, 0f)); move(16)
            up(1); up(2)
        }
        runOnIdle { assertEquals(carried, moved); assertEquals(1, releases.size) }
    }
}
