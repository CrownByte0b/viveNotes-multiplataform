package com.vivenotes.ui.canvas

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import com.vivenotes.model.ink.Ruler
import com.vivenotes.workspace.RulerKind
import kotlin.math.PI
import kotlin.test.*

/** Compatibility specifications ported from Android's RulerGestureTest. */
@OptIn(ExperimentalTestApi::class)
class AndroidRulerGestureTest {
    @Test fun oneDialTapTurnsExactlyOneEighthAndBodyAndOutsideTapsDoNotTurnIt() = runDesktopComposeUiTest(width = 1000, height = 1000) {
        var ruler = Ruler(500f, 500f, 0f, RulerKind.Straight, 900f)
        setContent { Box(Modifier.fillMaxSize().testTag("ruler").pointerInput(Unit) {
            detectRulerGestures({ ruler }, { it }, { ruler = it })
        }) }
        onNodeWithTag("ruler").performTouchInput { click(Offset(500f, 500f)) }
        runOnIdle { assertEquals((-PI / 4).toFloat(), ruler.angleRadians, 0.001f) }
        onNodeWithTag("ruler").performTouchInput { click(Offset(500f, 900f)) }
        runOnIdle { assertEquals((-PI / 4).toFloat(), ruler.angleRadians, 0.001f) }
    }

    @Test fun aFingerOnTheBodyOrDialSlidesTheRulerWithoutSteppingIt() = runDesktopComposeUiTest(width = 1000, height = 1000) {
        var ruler = Ruler(500f, 500f, 0f, RulerKind.Straight, 900f)
        setContent { Box(Modifier.fillMaxSize().testTag("ruler").pointerInput(Unit) {
            detectRulerGestures({ ruler }, { it }, { ruler = it })
        }) }
        onNodeWithTag("ruler").performTouchInput { click(Offset(800f, 500f)); swipe(Offset(700f, 500f), Offset(700f, 620f)) }
        runOnIdle { assertEquals(620f, ruler.centerY, 1f); assertEquals(0f, ruler.angleRadians) }
        onNodeWithTag("ruler").performTouchInput { swipe(Offset(500f, 620f), Offset(700f, 640f)) }
        runOnIdle { assertEquals(700f, ruler.centerX, 1f); assertEquals(640f, ruler.centerY, 1f); assertEquals(0f, ruler.angleRadians) }
    }

    @Test fun twoFingersCarryWithoutJumpingWhenTheFirstLiftsAndTwistInPlace() = runDesktopComposeUiTest(width = 1000, height = 1000) {
        var ruler = Ruler(500f, 500f, 0f, RulerKind.Straight, 900f)
        setContent { Box(Modifier.fillMaxSize().testTag("ruler").pointerInput(Unit) {
            detectRulerGestures({ ruler }, { it }, { ruler = it })
        }) }
        onNodeWithTag("ruler").performTouchInput {
            down(0, Offset(300f, 500f)); down(1, Offset(700f, 500f))
            updatePointerTo(0, Offset(300f, 600f)); updatePointerTo(1, Offset(700f, 600f)); move()
            up(0); up(1)
        }
        runOnIdle { assertEquals(500f, ruler.centerX, 1f); assertEquals(600f, ruler.centerY, 1f); assertEquals(0f, ruler.angleRadians) }
        onNodeWithTag("ruler").performTouchInput {
            down(0, Offset(300f, 600f)); down(1, Offset(700f, 600f))
            updatePointerTo(0, Offset(500f, 400f)); updatePointerTo(1, Offset(500f, 800f)); move()
            up(0); up(1)
        }
        runOnIdle { assertEquals((PI / 2).toFloat(), ruler.angleRadians, 0.05f); assertEquals(500f, ruler.centerX, 1f); assertEquals(600f, ruler.centerY, 1f) }
    }
}
