package com.vivenotes.ink

import com.vivenotes.byteink.portable.WasmInkEngine
import com.vivenotes.diagnostics.DebugLog
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

@OptIn(ExperimentalWasmJsInterop::class)
private fun engineBesidePage(): String = js("new URL('ink/byteink-engine.mjs', document.baseURI).href")

@OptIn(ExperimentalWasmJsInterop::class)
private fun debugRequested(): Boolean = js("new URL(location.href).searchParams.has('debug')")

/**
 * The browser's one Ink engine: ByteInk's C++ engine compiled to Wasm, served at `ink/` beside the
 * page (see `webApp/build.gradle.kts`). Fetched and compiled once; ink appears when it is ready.
 */
internal object WebInk {
    private val ready = MutableStateFlow<WebInkPages?>(null)
    private var started = false

    /** The engine's page replays, null until the engine has loaded. */
    val pages: StateFlow<WebInkPages?> get() = ready

    /** The browser's opt-in debug log, the desktop's `--debug`: on with `?debug` in the page address. */
    var log: DebugLog = DebugLog(enabled = debugRequested())

    /** Starts loading the engine from [url], once; later calls do nothing. */
    fun load(url: String = engineBesidePage()) {
        if (started) return
        started = true
        MainScope().launch {
            try {
                ready.value = WebInkPages(WasmInkEngine.load(url))
                log.event("ink") { "engine loaded" }
            } catch (failure: Throwable) {
                log.failure("ink", "engine load", failure)
            }
        }
    }
}
