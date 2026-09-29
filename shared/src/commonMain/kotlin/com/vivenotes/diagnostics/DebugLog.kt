package com.vivenotes.diagnostics

/** Small, opt-in process log. Messages must describe operations, never document contents. */
class DebugLog(
    private val enabled: Boolean = false,
    private val output: (String) -> Unit = ::println,
) {
    fun event(area: String, message: () -> String) {
        if (enabled) output("ViveNotes DEBUG [$area] ${message()}")
    }

    fun failure(area: String, operation: String, error: Throwable) {
        event(area) {
            val detail = error.message.orEmpty().replace('\n', ' ').replace('\r', ' ').take(160)
            "$operation failed: ${error::class.simpleName}: $detail"
        }
    }
}
