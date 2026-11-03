package com.vivenotes.desktop.rendering

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.vivenotes.desktop.PopupLayerHost
import com.vivenotes.diagnostics.DebugLog
import java.awt.Toolkit
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.FutureTask
import java.io.File
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO
import javax.swing.JFrame
import javax.swing.SwingUtilities
import org.jetbrains.skia.Bitmap
import org.jetbrains.skiko.GraphicsApi
import org.jetbrains.skiko.SkiaLayerAnalytics
import org.jetbrains.skiko.SkiaLayerProperties
import org.jetbrains.skiko.ExperimentalSkikoApi
import org.jetbrains.skiko.swing.SkiaSwingLayer
import org.junit.Assume.assumeTrue
import kotlin.test.*

/** Uses real EGL and compositor buffers on a private Wayland display, rather than mocked graphics. */
@OptIn(ExperimentalComposeUiApi::class, ExperimentalSkikoApi::class)
class NativeWaylandRenderingTest {
    @Test fun failedEglDrawReleasesGpuResourcesAndFallsBackWithinNativeWayland() {
        assumeTrue(Toolkit.getDefaultToolkit().javaClass.name == "sun.awt.wl.WLToolkit")
        WaylandRendering.prepare()
        val draws = java.util.concurrent.atomic.AtomicInteger()
        val failures = java.util.concurrent.atomic.AtomicInteger()
        val before = edt { WaylandRendering.statistics() }
        lateinit var frame: JFrame
        lateinit var layer: SkiaSwingLayer
        try {
            edt {
                WaylandRendering.frameObserver = java.util.function.BiConsumer { _, _ ->
                    failures.incrementAndGet()
                    throw IllegalStateException("injected EGL presentation failure")
                }
                layer = SkiaSwingLayer({ canvas, _, _, _ ->
                    canvas.clear(0xff123456.toInt())
                    draws.incrementAndGet()
                }, SkiaLayerAnalytics.Empty, null, SkiaLayerProperties())
                WaylandRendering.observe(layer, DebugLog())
                frame = JFrame("Native EGL fallback regression")
                frame.add(layer)
                frame.setSize(400, 300)
                frame.isVisible = true
            }
            await("software fallback after an EGL failure") {
                edt { layer.renderApi in setOf(GraphicsApi.SOFTWARE_FAST, GraphicsApi.SOFTWARE_COMPAT) && draws.get() >= 2 }
            }
            assertEquals(1, failures.get(), "A failed native context must not be retried every frame")
            assertEquals(before.active(), edt { WaylandRendering.statistics().active() })
            assertEquals("sun.awt.wl.WLToolkit", Toolkit.getDefaultToolkit().javaClass.name)
        } finally {
            edt { WaylandRendering.frameObserver = null; frame.dispose() }
        }
    }

    @Test fun gpuPixelsUpdateResizePopupsAndHideShowReleaseEverySurface() {
        assumeTrue(Toolkit.getDefaultToolkit().javaClass.name == "sun.awt.wl.WLToolkit")
        assertEquals("COMPONENT", System.getProperty("compose.layers.type"))
        WaylandRendering.prepare()
        val lines = CopyOnWriteArrayList<String>()
        val log = DebugLog(true) { lines += it }
        val colors = CopyOnWriteArrayList<Int>()
        val APIs = CopyOnWriteArrayList<GraphicsApi>()
        val background = mutableStateOf(Color.Red)
        val menu = mutableStateOf(false)
        val popupAlpha = mutableStateOf(1f)
        lateinit var frame: JFrame
        var constructed = false
        val before = edt { WaylandRendering.statistics() }
        try {
            edt {
                WaylandRendering.frameObserver = java.util.function.BiConsumer { layer, surface ->
                    APIs += layer.renderApi
                    Bitmap().use { bitmap ->
                        check(bitmap.allocN32Pixels(1, 1, false))
                        check(surface.readPixels(bitmap, surface.width / 2, surface.height / 2))
                        colors += bitmap.getColor(0, 0)
                    }
                }
                val panel = waylandComposePanel(log)
                val host = PopupLayerHost(panel, log)
                panel.windowContainer = host
                frame = JFrame("Native EGL rendering regression")
                constructed = true
                frame.add(host)
                frame.setSize(500, 400)
                panel.setContent {
                    Box(Modifier.fillMaxSize().background(background.value)) {
                        if (menu.value) Popup(onDismissRequest = { menu.value = false }, properties = PopupProperties(focusable = true)) {
                            Box(Modifier.size(100.dp).background(Color.Blue.copy(alpha = popupAlpha.value)))
                        }
                    }
                }
                frame.isVisible = true
            }
            await("red pixels rendered on the GPU") { 0xffff0000.toInt() in colors }
            screenshot("red", 0xffff0000.toInt())
            assertTrue(APIs.all { it == GraphicsApi.OPENGL }, APIs.toString())
            val first = edt { WaylandRendering.statistics() }
            assertEquals(before.created() + 1, first.created())
            edt { background.value = Color(0xff4080c0) }
            await("sRGB midtones on the GPU") { 0xff4080c0.toInt() in colors }
            edt { background.value = Color.Green }
            await("green pixels after recomposition") { 0xff00ff00.toInt() in colors }
            edt { frame.setSize(600, 450) }
            val resizedFrames = edt { WaylandRendering.statistics().frames() }
            await("a frame after resizing") { edt { WaylandRendering.statistics().frames() } > resizedFrames }
            assertEquals(first.created(), edt { WaylandRendering.statistics().created() }, "Resize reuses the EGL context")
            edt { menu.value = true }
            await("a separately accelerated popup") { edt { WaylandRendering.statistics().active() } >= before.active() + 2 }
            await("blue popup pixels") { 0xff0000ff.toInt() in colors }
            screenshot("popup", 0xff0000ff.toInt())
            edt { popupAlpha.value = .5f }
            await("premultiplied alpha in the popup buffer") { 0x800000ff.toInt() in colors }
            screenshot("popup-alpha", 0)
            edt { menu.value = false }
            await("the popup surface closing") { edt { WaylandRendering.statistics().active() } == before.active() + 1 }
            repeat(3) {
                edt { menu.value = true }
                await("reopened popup") { edt { WaylandRendering.statistics().active() } >= before.active() + 2 }
                edt { menu.value = false }
                await("reopened popup closing") { edt { WaylandRendering.statistics().active() } == before.active() + 1 }
            }
            assertEquals(1, WaylandEgl.connectionCount(), "All layers share JBR's one Wayland connection registry")
            edt { frame.isVisible = false }
            await("hidden surfaces closing") { edt { WaylandRendering.statistics().active() } == before.active() }
            val old = edt { WaylandRendering.statistics().created() }
            edt { frame.isVisible = true }
            await("surface recreation after showing") { edt { WaylandRendering.statistics().created() } > old }
            assertTrue(lines.any { "native Wayland EGL" in it }, lines.joinToString("\n"))
        } finally {
            println("Native Wayland rendering logs: ${lines.joinToString("; ")}")
            println("Native Wayland resource state: ${edt { WaylandRendering.statistics() }}")
            edt {
                WaylandRendering.frameObserver = null
                if (constructed) frame.dispose()
            }
        }
        await("all GPU resources closing") { edt { WaylandRendering.statistics().active() } == before.active() }
        val after = edt { WaylandRendering.statistics() }
        assertEquals(after.created() - before.created(), after.destroyed() - before.destroyed())
    }

    private fun screenshot(stage: String, expected: Int) {
        val root = System.getProperty("vivenotes.test.waylandScreenshotDir") ?: return
        val folder = File(root, stage).apply { mkdirs() }
        val process = ProcessBuilder("weston-screenshooter").directory(folder).redirectErrorStream(true).start()
        assertTrue(process.waitFor(10, TimeUnit.SECONDS), "Wayland screenshot timed out")
        assertEquals(0, process.exitValue(), process.inputStream.bufferedReader().readText())
        val images = folder.listFiles().orEmpty().filter { it.extension == "png" }.map(ImageIO::read)
        assertTrue(images.isNotEmpty(), "No compositor screenshot in $folder")
        assertTrue(images.any { image ->
            var matches = 0
            for (y in 0 until image.height) for (x in 0 until image.width) {
                val color = image.getRGB(x, y)
                if (stage == "popup-alpha") {
                    val red = color ushr 16 and 255
                    val green = color ushr 8 and 255
                    val blue = color and 255
                    if (red < 5 && green in 31..249 && blue in 31..249) matches++
                } else if (color == expected) matches++
            }
            matches > 1000
        }, "The compositor did not present $stage pixels")
    }

    private fun await(description: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + 15_000_000_000L
        while (!condition()) {
            check(System.nanoTime() < deadline) { "Timed out waiting for $description" }
            Thread.sleep(20)
        }
    }

    private fun <T> edt(block: () -> T): T {
        if (SwingUtilities.isEventDispatchThread()) return block()
        val task = FutureTask(block)
        SwingUtilities.invokeAndWait(task)
        return task.get()
    }
}
