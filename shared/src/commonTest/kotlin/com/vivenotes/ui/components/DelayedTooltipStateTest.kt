package com.vivenotes.ui.components

import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.foundation.MutatePriority
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.TooltipState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

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

    private class FakeTooltipState : TooltipState {
        override val transition = MutableTransitionState(false)
        override val isVisible: Boolean get() = transition.targetState
        override val isPersistent = false
        override suspend fun show(mutatePriority: MutatePriority) { transition.targetState = true }
        override fun dismiss() { transition.targetState = false }
        override fun onDispose() = Unit
    }
}
