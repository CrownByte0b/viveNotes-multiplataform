package com.vivenotes.ui.components

import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.foundation.MutatePriority
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.TooltipState
import com.vivenotes.diagnostics.DebugLog
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertEquals

@OptIn(ExperimentalMaterial3Api::class, ExperimentalCoroutinesApi::class)
class DelayedTooltipStateTest {
    @Test
    fun hoverAppearsAfterQuarterSecond() = runTest {
        val delegate = FakeTooltipState()
        val state = DelayedTooltipState(delegate)
        launch { state.show(MutatePriority.UserInput) }
        runCurrent()
        advanceTimeBy(HoverTooltipDelayMillis - 1)
        runCurrent()
        assertFalse(delegate.isVisible)
        advanceTimeBy(1)
        runCurrent()
        assertTrue(delegate.isVisible)
    }

    @Test
    fun leavingBeforeDelayCancelsPendingTooltip() = runTest {
        val delegate = FakeTooltipState()
        val state = DelayedTooltipState(delegate)
        launch { state.show(MutatePriority.UserInput) }
        runCurrent()
        advanceTimeBy(100)
        state.dismiss()
        advanceTimeBy(200)
        runCurrent()
        assertFalse(delegate.isVisible)
    }

    @Test
    fun escapeCancelsAllPendingTooltipsWithoutUsingUpPanelDismissal() = runTest {
        val registry = TooltipDismissals()
        val states = List(2) { DelayedTooltipState(FakeTooltipState()).also(registry::register) }
        states.forEach { launch { it.show(MutatePriority.UserInput) } }
        runCurrent()
        assertTrue(states.all { it.hasOpenRequest })
        assertFalse(registry.dismiss())
        advanceTimeBy(HoverTooltipDelayMillis + 1)
        runCurrent()
        assertTrue(states.none { it.isVisible || it.hasOpenRequest })
    }

    @Test
    fun escapeDismissesEveryVisibleTooltipAndDisposedOnesAreUnregistered() = runTest {
        val registry = TooltipDismissals()
        val states = List(2) { DelayedTooltipState(FakeTooltipState()).also(registry::register) }
        states.forEach { launch { it.show(MutatePriority.Default) } }
        runCurrent()
        assertTrue(states.all { it.isVisible })
        assertTrue(registry.dismiss())
        assertTrue(states.none { it.isVisible })
        assertFalse(registry.dismiss())

        registry.unregister(states.first())
        states.first().show(MutatePriority.Default)
        assertFalse(registry.dismiss())
        assertTrue(states.first().isVisible)
    }

    @Test
    fun aCancelledHoverDoesNotLeaveAPendingRequest() = runTest {
        val state = DelayedTooltipState(FakeTooltipState())
        val job = launch { state.show(MutatePriority.UserInput) }
        runCurrent()
        job.cancel()
        runCurrent()
        assertFalse(state.hasOpenRequest)
    }

    @Test
    fun aMenuCancelsVisibleAndDelayedAnchorHintsAndRefusesFurtherRequests() = runTest {
        val registry = TooltipDismissals()
        val visible = DelayedTooltipState(FakeTooltipState()).also(registry::register)
        val pending = DelayedTooltipState(FakeTooltipState()).also(registry::register)
        visible.show(MutatePriority.Default)
        launch { pending.show(MutatePriority.PreventUserInput) }
        runCurrent()

        val menu = Any()
        registry.openPopup(menu, "dropdown")
        assertFalse(visible.isVisible)
        assertFalse(pending.hasOpenRequest)
        for (priority in MutatePriority.entries) visible.show(priority)
        advanceTimeBy(HoverTooltipDelayMillis + 1)
        runCurrent()
        assertTrue(listOf(visible, pending).none { it.isVisible || it.hasOpenRequest })

        val late = DelayedTooltipState(FakeTooltipState())
        registry.register(late)
        late.show(MutatePriority.Default)
        assertFalse(late.isVisible)
        registry.closePopup(menu)
        assertFalse(visible.isVisible)
        visible.show(MutatePriority.Default)
        assertTrue(visible.isVisible)
    }

    @Test
    fun onlyTheForemostMenusHintsShowAndClosingItRestoresTheParentScope() = runTest {
        val registry = TooltipDismissals()
        val parent = Any()
        val child = Any()
        val anchor = DelayedTooltipState(FakeTooltipState()).also(registry::register)
        val parentHint = DelayedTooltipState(FakeTooltipState())
        registry.register(parentHint, parent)
        registry.openPopup(parent, "dropdown")
        parentHint.show(MutatePriority.Default)
        assertTrue(parentHint.isVisible)

        registry.openPopup(child, "dropdown")
        assertFalse(parentHint.isVisible)
        val childHint = DelayedTooltipState(FakeTooltipState())
        registry.register(childHint, child)
        childHint.show(MutatePriority.Default)
        assertTrue(childHint.isVisible)
        assertTrue(registry.dismiss())
        assertFalse(childHint.isVisible)

        registry.closePopup(child)
        parentHint.show(MutatePriority.Default)
        anchor.show(MutatePriority.Default)
        assertTrue(parentHint.isVisible)
        assertFalse(anchor.isVisible)
        registry.unregister(childHint)
        registry.closePopup(parent)
        anchor.show(MutatePriority.Default)
        assertTrue(anchor.isVisible)
    }

    @Test
    fun removingABackgroundMenuKeepsTheForemostMenuInCharge() = runTest {
        val registry = TooltipDismissals()
        val parent = Any()
        val child = Any()
        val anchor = DelayedTooltipState(FakeTooltipState()).also(registry::register)
        registry.openPopup(parent, "dropdown")
        registry.openPopup(child, "context menu")
        registry.closePopup(parent)
        anchor.show(MutatePriority.Default)
        assertFalse(anchor.isVisible)
        registry.closePopup(child)
        anchor.show(MutatePriority.Default)
        assertTrue(anchor.isVisible)
    }

    @Test
    fun popupDiagnosticsAreOptInAndDescribeOnlyLifecycleChanges() {
        val lines = mutableListOf<String>()
        for (enabled in listOf(false, true)) {
            val registry = TooltipDismissals(DebugLog(enabled, lines::add))
            val menu = Any()
            registry.openPopup(menu, "dropdown")
            registry.closePopup(menu)
            registry.closePopup(menu)
            if (!enabled) assertTrue(lines.isEmpty())
        }
        assertEquals(listOf("ViveNotes DEBUG [popup] Opened dropdown", "ViveNotes DEBUG [popup] Closed dropdown"), lines)
    }

    private class FakeTooltipState : TooltipState {
        override val transition = MutableTransitionState(false)
        override val isVisible: Boolean get() = transition.targetState
        override val isPersistent = false
        override suspend fun show(mutatePriority: MutatePriority) { transition.targetState = true }
        override fun dismiss() { transition.targetState = false }
        override fun onDispose() = Unit
    }
}
