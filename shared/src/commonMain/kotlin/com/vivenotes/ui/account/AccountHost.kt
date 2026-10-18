package com.vivenotes.ui.account

import androidx.compose.runtime.Composable
import kotlinx.coroutines.flow.StateFlow

/**
 * The account feature as the workspace shell sees it: what the top bar's account button shows, and
 * the full-window Account destination it opens.
 *
 * The account and sync layer is the Android app's JVM code, so the destination is supplied by the
 * platform. [Destination] is composed for as long as the workspace is, open or not, because it holds
 * the state Android keeps one level above its Account screen: a sign-in in flight must outlive the
 * screen it was started from, since the device token it ends in is issued exactly once.
 */
interface AccountHost {

    /** Whether this installation holds a device registration — the button's dot. */
    val connected: StateFlow<Boolean>

    /**
     * Whether the last sync could not reach that server at all — Cloud Off in place of the dot.
     * Android's `SyncStatus.serverUnreachable`, and only with a registration behind it.
     */
    val serverUnreachable: StateFlow<Boolean>

    @Composable
    fun Destination(visible: Boolean, onBack: () -> Unit)
}
