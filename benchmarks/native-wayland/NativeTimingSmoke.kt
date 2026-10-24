package com.vivenotes.runtimeprobe

import java.awt.EventQueue
import java.awt.Frame
import java.awt.Toolkit
import java.awt.event.WindowEvent
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.FutureTask
import javax.accessibility.Accessible

/** Separate startup/resize contract; this is not the full ink/text workload. */
object NativeTimingSmoke {
    @JvmStatic
    fun main(args: Array<String>) {
        System.setProperty("awt.toolkit.name", "WLToolkit")
        Thread({
            var frame: Frame? = null
            var probe: RuntimeRenderProbe? = null
            try {
                val deadline = System.nanoTime() + 60_000_000_000L
                while (frame == null && System.nanoTime() < deadline) {
                    frame = edt { Frame.getFrames().firstOrNull { it.isShowing && it.title == "Vive Notes" && hasInkPage(it, 0) } }
                    if (frame == null) Thread.sleep(100)
                }
                val own = checkNotNull(frame) { "Native app window did not open" }
                probe = edt { RuntimeRenderProbe.install(own) }
                Thread.sleep(2_000)
                repeat(4) { index ->
                    edt { own.setSize(if (index % 2 == 0) 1100 else 1200, 850) }
                    Thread.sleep(500)
                }
                edt { probe.close(); probe.close() }
                Files.writeString(Path.of("rendering.json"), probe.summary().toString() + "\n")
                println("NATIVE_TIMING_OBSERVER_PASS")
            } catch (failure: Throwable) {
                failure.printStackTrace()
                Files.writeString(Path.of("native-smoke-failed.txt"), failure.toString())
            } finally {
                frame?.let { own -> edt {
                    runCatching { probe?.close() }
                    own.dispatchEvent(WindowEvent(own, WindowEvent.WINDOW_CLOSING))
                } }
            }
        }, "native-timing-smoke").apply { isDaemon = true; start() }
        Class.forName("com.vivenotes.desktop.MainKt").getMethod("main", Array<String>::class.java)
            .invoke(null, arrayOf("--debug"))
    }

    private fun hasInkPage(node: Accessible, depth: Int): Boolean {
        if (depth > 30) return false
        val context = node.accessibleContext ?: return false
        if (context.accessibleName?.startsWith("01 Runtime ink") == true) return true
        return (0 until minOf(context.accessibleChildrenCount, 5000)).any {
            context.getAccessibleChild(it)?.let { child -> hasInkPage(child, depth + 1) } == true
        }
    }

    private fun <T> edt(block: () -> T): T {
        val task = FutureTask(block)
        EventQueue.invokeAndWait(task)
        return task.get()
    }
}

/** WLRobotPeer silently ignores input unless both gates are enabled in this JBR. */
object NativeInputPreflight {
    @JvmStatic
    fun main(args: Array<String>) {
        System.setProperty("awt.toolkit.name", "WLToolkit")
        check(Toolkit.getDefaultToolkit().javaClass.name == "sun.awt.wl.WLToolkit")
        check(java.lang.Boolean.getBoolean("sun.awt.wl.UseRemoteDesktopRobot")) {
            "Native input unavailable: JBR requires -Dsun.awt.wl.UseRemoteDesktopRobot=true and an isolated Remote Desktop portal"
        }
        val supported = Class.forName("sun.awt.screencast.XdgDesktopPortalRobot")
            .getMethod("useForInput").invoke(null) as Boolean
        check(supported) { "Native input unavailable: this isolated session has no usable Remote Desktop portal" }
        println("NATIVE_INPUT_CAPABILITY_PRESENT; actual delivery is still checked by workload/persistence assertions")
    }
}
