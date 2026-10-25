package com.vivenotes.data

import com.vivenotes.diagnostics.DebugLog as ProcessLog

/**
 * Where the log lines of code copied from the Android app go: the desktop's opt-in `--debug` log.
 *
 * Android sends them to logcat — [Log] always, [DebugLog] in debug builds only. The desktop has one
 * switch for both, so a normal launch prints nothing. [NotesLibrary.open] installs the process's log
 * here; until then, and in tests, the lines are dropped.
 *
 * These two objects exist so the copied files keep their call sites word for word: only the
 * `import android.util.Log` line changes.
 */
internal object PortedLogs {
    @Volatile
    var sink: ProcessLog = ProcessLog()

    val enabled: Boolean get() = sink.enabled

    fun write(tag: String, message: String, failure: Throwable? = null) {
        if (!enabled) return
        sink.event(tag) {
            if (failure == null) {
                message
            } else {
                val detail = failure.message.orEmpty().replace('\n', ' ').replace('\r', ' ').take(160)
                "$message: ${failure::class.simpleName}: $detail"
            }
        }
    }
}

/** The calls `android.util.Log` answers, for files copied from the Android app. */
internal object Log {
    fun d(tag: String, message: String) = PortedLogs.write(tag, message)

    fun i(tag: String, message: String) = PortedLogs.write(tag, message)

    fun w(tag: String, message: String, failure: Throwable? = null) = PortedLogs.write(tag, message, failure)

    fun e(tag: String, message: String, failure: Throwable? = null) = PortedLogs.write(tag, message, failure)
}

/**
 * Android's `data/DebugLog.kt`: lines its debug builds print, which the desktop prints with `--debug`.
 *
 * `inline`, as on Android, so a message may call a suspending function at the call site, and is
 * never built at all while the log is off.
 */
internal object DebugLog {
    const val DB = "NotesDb"

    inline fun d(tag: String, message: () -> String) {
        if (PortedLogs.enabled) PortedLogs.write(tag, message())
    }

    inline fun i(tag: String, message: () -> String) {
        if (PortedLogs.enabled) PortedLogs.write(tag, message())
    }

    inline fun w(tag: String, failure: Throwable? = null, message: () -> String) {
        if (PortedLogs.enabled) PortedLogs.write(tag, message(), failure)
    }

    inline fun e(tag: String, failure: Throwable? = null, message: () -> String) {
        if (PortedLogs.enabled) PortedLogs.write(tag, message(), failure)
    }
}
