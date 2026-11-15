package com.vivenotes.ui.update

import kotlinx.coroutines.flow.StateFlow

/** A release newer than the running app, as the update dialog offers it. */
data class AvailableUpdate(
    val version: String,
    val currentVersion: String,
    /** The release's notes as plain text; empty when it has none. */
    val notes: String = "",
    /** The installer's size, for the download's progress. */
    val bytes: Long = 0,
)

/** Where an update stands: [Idle] shows nothing, every other state shows the update dialog. */
sealed interface UpdateState {
    /** Not checked yet, up to date, skipped or put off until the next launch, or the check failed. */
    data object Idle : UpdateState

    data class Available(val update: AvailableUpdate) : UpdateState

    /**
     * [bytes] of the [total] the update needs have arrived; [reused] more came from the version on
     * this computer. A [total] of zero before anything is reused means the two are still being compared.
     */
    data class Downloading(
        val update: AvailableUpdate,
        val bytes: Long,
        val total: Long = update.bytes,
        val reused: Long = 0,
    ) : UpdateState

    /** Downloaded and verified: the notes are being saved so the app can close for the installer. */
    data class Installing(val update: AvailableUpdate) : UpdateState

    /** Nothing was installed; [reason] says why, and the app keeps running its current version. */
    data class Failed(val update: AvailableUpdate, val reason: String) : UpdateState
}

/** What the last look for a newer release found, for About to show. */
sealed interface UpdateCheck {
    /** Nothing asked yet: the check at launch waits a few seconds. */
    data object NotChecked : UpdateCheck

    data object Checking : UpdateCheck

    data object UpToDate : UpdateCheck

    /** [version] is newer than this one, offered or not (the user may have skipped it). */
    data class Found(val version: String) : UpdateCheck

    data class Failed(val reason: String) : UpdateCheck
}

/**
 * The platform's updater: the installed Windows app and the AppImage check for a newer release once
 * after they open, and About checks again on request. Other installations update through their own
 * package manager and pass none.
 */
interface AppUpdates {
    val state: StateFlow<UpdateState>

    val check: StateFlow<UpdateCheck>

    /** Looks for a newer release now and offers it, even a version the user chose to skip. */
    fun checkNow()

    /** Downloads the offered release, verifies it, then closes the app and runs its installer. */
    fun install()

    /** Stops a download under way and offers the update again. */
    fun cancel()

    /** Puts the update off until the app next opens. */
    fun later()

    /** Stops offering this version; a newer one is offered again. */
    fun skip()
}
