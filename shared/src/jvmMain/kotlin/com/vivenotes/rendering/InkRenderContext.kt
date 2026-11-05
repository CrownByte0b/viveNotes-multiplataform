package com.vivenotes.rendering

/** Capability for draw-command recording inside an accelerated frame, including picture canvases. */
object InkRenderContext {
    private val frame = ThreadLocal<Frame?>()
    internal val canReplay: Boolean get() = frame.get() != null

    @JvmStatic fun openGpuFrame(): AutoCloseable {
        return Frame(frame.get(), Thread.currentThread()).also { frame.set(it) }
    }

    private class Frame(val previous: Frame?, val thread: Thread) : AutoCloseable {
        override fun close() {
            check(Thread.currentThread() === thread) { "GPU frame must close on its drawing thread" }
            check(frame.get() === this) { "GPU frames must close once, in nesting order" }
            frame.set(previous)
        }
    }
}
