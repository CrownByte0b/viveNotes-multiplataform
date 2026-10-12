package com.vivenotes.math

import kotlinx.coroutines.flow.StateFlow

/**
 * Whether this device can run the math engine: a uv-managed CPython with SymPy, installed into the
 * app's own data directory on request. Android bundles Python in the APK; the desktop installs it.
 */
sealed interface MathRuntimeState {
    /** Reading what is installed, at launch. */
    data object Checking : MathRuntimeState

    /** Nothing is installed and uv, which installs it, cannot be used. [reason] says why. */
    data class UvUnavailable(val reason: String) : MathRuntimeState

    data object NotInstalled : MathRuntimeState

    data class Installing(val step: MathInstallStep) : MathRuntimeState

    /** Installed and answering: the versions are the running interpreter's and SymPy's own. */
    data class Ready(val python: String, val sympy: String) : MathRuntimeState

    data class Failed(val message: String) : MathRuntimeState
}

/** Where an install is, in the order it happens. */
enum class MathInstallStep(val label: String) {
    Preparing("Preparing…"),
    DownloadingPython("Downloading Python…"),
    InstallingPackages("Installing SymPy…"),
    Checking("Checking…"),
}

/** What Settings → Models can ask of the math engine's runtime. */
interface MathRuntime {
    val state: StateFlow<MathRuntimeState>

    /** Installs Python and SymPy, or repairs them. A request while one is under way is ignored. */
    fun install()

    /** Stops the engine and removes everything the install put on this device. */
    fun remove()
}
