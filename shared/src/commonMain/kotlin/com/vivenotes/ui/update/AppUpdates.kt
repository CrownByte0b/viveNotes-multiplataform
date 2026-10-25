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

    data class Downloading(val update: AvailableUpdate, val bytes: Long) : UpdateState

    /** Downloaded and verified: the notes are being saved so the app can close for the installer. */
    data class Installing(val update: AvailableUpdate) : UpdateState

    /** Nothing was installed; [reason] says why, and the app keeps running its current version. */
    data class Failed(val update: AvailableUpdate, val reason: String) : UpdateState
}

/**
 * The platform's updater: the installed Windows app checks for a newer release when it opens. Other
 * platforms update through their own package manager and pass none.
 */
interface AppUpdates {
    val state: StateFlow<UpdateState>

    /** Downloads the offered release, verifies it, then closes the app and runs its installer. */
    fun install()

    /** Stops a download under way and offers the update again. */
    fun cancel()

    /** Puts the update off until the app next opens. */
    fun later()

    /** Stops offering this version; a newer one is offered again. */
    fun skip()
}
