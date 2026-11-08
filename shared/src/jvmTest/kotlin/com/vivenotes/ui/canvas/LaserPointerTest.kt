package com.vivenotes.ui.canvas

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.vivenotes.ink.LaserPointer
import com.vivenotes.ui.ribbon.draw.DrawRibbon
import com.vivenotes.ui.ribbon.draw.DrawRibbonTags
import com.vivenotes.ui.theme.ViveNotesTheme
import com.vivenotes.workspace.InkTool
import com.vivenotes.workspace.WorkspaceState
import kotlin.test.*

/**
 * The laser pointer (Android's `DrawTabTest` and `InkOverlayTest` laser cases, and the pointer's own
 * rules): picked up from the Draw tab, lit where it points, gone afterwards, and never ink.
 *
 * Its trace is aged on the real clock, as Android's is on the uptime clock: the test clock drives
 * frames, and waiting for idle waits out the trace and the fade.
 */
@OptIn(ExperimentalTestApi::class)
class LaserPointerTest {

    // --- the Draw tab ----------------------------------------------------------------------------

    @Test
    fun theLaserPointerIsPickedUp() = runDesktopComposeUiTest(width = 1300, height = 300) {
        val state = mutableStateOf(WorkspaceState.demo())
        setContent { ViveNotesTheme { DrawRibbon(state.value, { state.value = it(state.value) }) } }

        onNodeWithTag(DrawRibbonTags.LaserTool).assertContentDescriptionEquals("Laser pointer").performClick()

        runOnIdle { assertEquals(InkTool.Laser, state.value.inkTool) }
        onNodeWithTag(DrawRibbonTags.LaserTool).assertIsSelected()
    }

    /** Held like a pen, so it sits with them — after the highlighter, before the eraser. */
    @Test
    fun theLaserPointerSitsBetweenTheHighlighterAndTheEraser() = runDesktopComposeUiTest(width = 1300, height = 300) {
        setContent { ViveNotesTheme { DrawRibbon(WorkspaceState.demo(), { }) } }

        val highlighter = onNodeWithTag(DrawRibbonTags.control(DrawRibbonTags.HighlighterTool)).getUnclippedBoundsInRoot()
        val laser = onNodeWithTag(DrawRibbonTags.LaserTool).getUnclippedBoundsInRoot()
        val eraser = onNodeWithTag(DrawRibbonTags.control(DrawRibbonTags.EraserTool)).getUnclippedBoundsInRoot()

        assertTrue(laser.left >= highlighter.right, "$laser is not right of $highlighter")
        assertTrue(laser.right <= eraser.left, "$laser is not left of $eraser")
    }

    /** Nothing it leaves behind, so nothing to configure: clicking it armed opens no pane. */
    @Test
    fun clickingTheArmedLaserPointerOpensNoSettings() = runDesktopComposeUiTest(width = 1300, height = 300) {
        val state = mutableStateOf(WorkspaceState.demo().selectInkTool(InkTool.Laser))
        setContent { ViveNotesTheme { DrawRibbon(state.value, { state.value = it(state.value) }) } }

        onNodeWithTag(DrawRibbonTags.LaserTool).performClick()

        runOnIdle { assertEquals(InkTool.Laser, state.value.inkTool) }
        onNodeWithTag(DrawRibbonTags.penKit(0)).assertExists()
        assertTrue(onAllNodes(isPopup()).fetchSemanticsNodes().isEmpty(), "a settings pane opened")
    }

    // --- the light -------------------------------------------------------------------------------

    /**
     * The dot is under the pen while it touches, and it is light rather than ink: once the pen lifts
     * it goes out. Waiting for idle waits out the trace and the fade, since the layer keeps asking
     * for frames until both are spent.
     */
    @Test
    fun theLaserLightsWhereItPointsThenGoesOut() = runDesktopComposeUiTest(width = 600, height = 600) {
        setLaserOnWhite()
        val layer = onNodeWithTag(LASER_LAYER_TAG)

        layer.performTouchInput {
            down(Offset(200f, 200f))
            moveTo(Offset(300f, 200f))
        }
        waitForIdle()

        assertTrue(laserPixels(290, 190, 310, 210) > 0, "no dot under the pen")
        assertEquals(0, laserPixels(180, 180, 220, 220), "the dot was left where the pen started")

        layer.performTouchInput { up() }
        waitForIdle()

        assertEquals(0, laserPixels(150, 150, 350, 250), "the dot outlived the gesture")
    }

    /**
     * The trace outlives the pen: lifting it puts the dot out, but what was drawn stays up for its
     * second. Captured on a held clock straight after the lift, which is well inside that second.
     */
    @Test
    fun theLaserTraceStaysUpAfterThePenLifts() = runDesktopComposeUiTest(width = 600, height = 600) {
        setLaserOnWhite()
        val layer = onNodeWithTag(LASER_LAYER_TAG)
        mainClock.autoAdvance = false

        // Real milliseconds apart, as a pen's samples are: inputs sharing one timestamp are a single
        // instant to Ink's time window, which averages them to one point.
        layer.performTouchInput { down(Offset(200f, 200f)) }
        Thread.sleep(30)
        layer.performTouchInput { moveTo(Offset(300f, 200f)) }
        Thread.sleep(30)
        layer.performTouchInput { moveTo(Offset(400f, 200f)) }
        Thread.sleep(30)
        layer.performTouchInput { up() }
        mainClock.advanceTimeByFrame()

        assertTrue(laserPixels(240, 190, 260, 210) > 0, "the trace went with the pen")

        mainClock.autoAdvance = true
        waitForIdle()

        assertEquals(0, laserPixels(150, 150, 450, 250), "the trace never went")
    }

    /** Circling something twice leaves both circles up: the next press does not wipe the last. */
    @Test
    fun aSecondPressLeavesTheFirstTraceUp() = runDesktopComposeUiTest(width = 600, height = 600) {
        setLaserOnWhite()
        val layer = onNodeWithTag(LASER_LAYER_TAG)
        mainClock.autoAdvance = false

        layer.performTouchInput { down(Offset(200f, 200f)) }
        Thread.sleep(30)
        layer.performTouchInput { moveTo(Offset(400f, 200f)) }
        Thread.sleep(30)
        layer.performTouchInput { up() }
        layer.performTouchInput { down(Offset(200f, 400f)) }
        Thread.sleep(30)
        layer.performTouchInput { moveTo(Offset(400f, 400f)) }
        Thread.sleep(30)
        mainClock.advanceTimeByFrame()

        assertTrue(laserPixels(290, 190, 310, 210) > 0, "the second press wiped the first trace")
        assertTrue(laserPixels(290, 390, 310, 410) > 0, "the second trace is missing")

        layer.performTouchInput { up() }
        mainClock.autoAdvance = true
        waitForIdle()

        assertEquals(0, laserPixels(150, 150, 450, 450))
    }

    /** The mouse points too, with the primary button held — the desktop's pen when there is none. */
    @Test
    fun theMousePointsWhileItsButtonIsDown() = runDesktopComposeUiTest(width = 600, height = 600) {
        setLaserOnWhite()
        val layer = onNodeWithTag(LASER_LAYER_TAG)

        layer.performMouseInput { moveTo(Offset(300f, 300f)) }
        waitForIdle()
        assertEquals(0, laserPixels(280, 280, 320, 320), "hovering lit the laser")

        layer.performMouseInput { press(); moveTo(Offset(320f, 300f)) }
        waitForIdle()
        assertTrue(laserPixels(310, 290, 330, 310) > 0, "no dot under the pressed mouse")

        layer.performMouseInput { release() }
        waitForIdle()
        assertEquals(0, laserPixels(250, 250, 350, 350))
    }

    /** Put away, it goes dark at once, whatever was still up. */
    @Test
    fun puttingTheLaserAwayTakesItsLightWithIt() = runDesktopComposeUiTest(width = 600, height = 600) {
        val active = mutableStateOf(true)
        setLaserOnWhite(active)
        val layer = onNodeWithTag(LASER_LAYER_TAG)
        mainClock.autoAdvance = false
        layer.performTouchInput { down(Offset(200f, 200f)) }
        Thread.sleep(30)
        layer.performTouchInput { moveTo(Offset(300f, 200f)) }
        mainClock.advanceTimeByFrame()
        assertTrue(laserPixels(190, 190, 310, 210) > 0)

        active.value = false
        mainClock.advanceTimeByFrame()

        assertEquals(0, laserPixels(150, 150, 350, 250, tag = "laser-host"))
    }

    private fun ComposeUiTest.setLaserOnWhite(active: androidx.compose.runtime.State<Boolean> = mutableStateOf(true)) {
        setContent {
            ViveNotesTheme {
                Box(Modifier.fillMaxSize().background(Color.White).testTag("laser-host")) {
                    LaserLayer(DpSize(600.dp, 600.dp), zoom = 1f, active = active.value)
                }
            }
        }
        waitForIdle()
    }

    /** Pixels in the region the laser's reds have touched — the paper is white, the core near it. */
    private fun ComposeUiTest.laserPixels(left: Int, top: Int, right: Int, bottom: Int, tag: String = LASER_LAYER_TAG): Int {
        val node = if (tag == LASER_LAYER_TAG) onNodeWithTag(tag, useUnmergedTree = true) else onNodeWithTag(tag)
        val pixels = node.captureToImage().toPixelMap()
        var count = 0
        for (x in left until minOf(right, pixels.width)) {
            for (y in top until minOf(bottom, pixels.height)) {
                val pixel = pixels[x, y]
                if (pixel.red - pixel.green > 0.08f) count++
            }
        }
        return count
    }

    // --- the pointer's rules ---------------------------------------------------------------------

    @Test
    fun eachPointRunsOutItsOwnLifetimeAndTheDotFadesAfterTheLift() {
        val laser = LaserPointer()
        laser.press(Offset(10f, 10f), 0L, traceWidth = 6f, epsilon = 0.25f)
        laser.follow(listOf(Offset(20f, 10f) to 500L, Offset(30f, 10f) to 1_000L))
        laser.lift(1_000L)
        assertTrue(laser.animating)
        assertEquals(Offset(30f, 10f), laser.dot)

        laser.tick(1_000L + LaserPointer.FADE_MILLIS)
        assertNull(laser.dot, "the dot outlived its fade")
        assertTrue(laser.animating, "the trace went with the dot")

        laser.tick(1_000L + LaserPointer.TRAIL_MILLIS + 1)
        assertFalse(laser.animating, "the trace never went")
        laser.close()
    }

    @Test
    fun aCancelledGestureTakesItsTraceAndLeavesTheOthers() {
        val laser = LaserPointer()
        laser.press(Offset(10f, 10f), 0L, 6f, 0.25f)
        laser.follow(listOf(Offset(20f, 10f) to 20L))
        laser.lift(20L)
        laser.tick(20L + LaserPointer.FADE_MILLIS)
        laser.press(Offset(10f, 50f), 400L, 6f, 0.25f)
        laser.follow(listOf(Offset(20f, 50f) to 420L))

        laser.cancelGesture()

        assertNull(laser.dot)
        assertTrue(laser.animating, "the earlier trace was taken too")
        laser.tick(20L + LaserPointer.TRAIL_MILLIS + 1)
        assertFalse(laser.animating, "the cancelled trace stayed up")
        laser.close()
    }

    /** With animations off there is no trace and no fade: a steady dot, gone the moment it lifts. */
    @Test
    fun withoutAnimationsTheDotIsAllThereIs() {
        val laser = LaserPointer(animationsEnabled = { false })
        laser.press(Offset(10f, 10f), 0L, 6f, 0.25f)
        laser.follow(listOf(Offset(20f, 10f) to 20L))
        assertEquals(Offset(20f, 10f), laser.dot)
        assertFalse(laser.animating, "a trace was kept")

        laser.lift(20L)

        assertNull(laser.dot)
        assertFalse(laser.animating)
        laser.close()
    }

    @Test
    fun theFrameClockIsWokenOnlyWhenThePictureStartsChangingOnItsOwn() {
        val laser = LaserPointer()
        val before = laser.wakeups
        laser.press(Offset(10f, 10f), 0L, 6f, 0.25f)
        val pressed = laser.wakeups
        assertEquals(before + 1, pressed, "the first mark did not wake the clock")
        laser.follow(listOf(Offset(20f, 10f) to 20L, Offset(30f, 10f) to 40L))
        assertEquals(pressed, laser.wakeups, "every mark woke the clock")
        laser.close()
    }
}
