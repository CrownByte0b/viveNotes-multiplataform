package com.vivenotes.runtimeprobe

import java.awt.AWTEvent
import java.awt.Component
import java.awt.Container
import java.awt.EventQueue
import java.awt.Frame
import java.awt.Toolkit
import java.awt.Window
import java.awt.event.AWTEventListener
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong
import jdk.jfr.Recording
import jdk.jfr.consumer.RecordingFile
import jdk.jfr.consumer.RecordingStream
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** JDK 25 method tracing for the existing SwingGraphics host. Never requests a repaint. */
internal class RuntimeRenderProbe private constructor(private val frame: Frame) : AutoCloseable {
    private val edt = Thread.currentThread().threadId()
    private val stream = RecordingStream()
    private val counts = ConcurrentHashMap<String, AtomicLong>()
    private val errors = CopyOnWriteArrayList<String>()
    private val loss = AtomicLong()
    private var closed = false
    private val components = descendants(frame).filter { swingLayer.isInstance(it) }.map { it.javaClass.name }
    private val listener = AWTEventListener { checkOwnership() }

    init {
        check(EventQueue.isDispatchThread())
        check(Toolkit.getDefaultToolkit().javaClass.name == "sun.awt.wl.WLToolkit")
        check(components.isNotEmpty()) { "No SwingGraphics layer in the owned frame" }
        check(descendants(frame).none { skiaLayer.isInstance(it) }) { "Mixed rendering paths" }
        checkOwnership()
        check(errors.isEmpty()) { errors.joinToString() }
        methods.forEach { counts[it] = AtomicLong() }
        stream.setMaxSize(Long.MAX_VALUE)
        stream.enable("jdk.MethodTrace").withoutThreshold().withStackTrace().with("filter", methods.joinToString(";"))
        stream.enable("jdk.DataLoss")
        stream.enable("com.vivenotes.RuntimeProbePhase").withoutThreshold()
        stream.onEvent("jdk.MethodTrace") { event ->
            val method = event.getValue<jdk.jfr.consumer.RecordedMethod>("method")
            val key = method.type.name + "::" + method.name
            if (key !in methods || event.thread.javaThreadId != edt || event.duration.isNegative) {
                errors.add("Unexpected method/thread/duration: $key")
            } else {
                counts.getValue(key).incrementAndGet()
            }
        }
        stream.onEvent("jdk.DataLoss") { loss.incrementAndGet() }
        stream.onError { errors.add(it.toString()) }
        Toolkit.getDefaultToolkit().addAWTEventListener(listener, AWTEvent.WINDOW_EVENT_MASK or AWTEvent.HIERARCHY_EVENT_MASK)
        try {
            stream.startAsync()
        } catch (failure: Throwable) {
            Toolkit.getDefaultToolkit().removeAWTEventListener(listener)
            stream.close()
            throw failure
        }
    }

    private fun checkOwnership() {
        if (Window.getWindows().any { it.isShowing && it !== frame }) {
            errors.addIfAbsent("Another visible AWT window makes method ownership ambiguous")
        }
    }

    fun setPhase(name: String) { require(name.isNotBlank()) }
    fun completedFrames(): Long = counts.getValue(methods[0]).get()

    override fun close() {
        check(EventQueue.isDispatchThread())
        if (closed) return
        try {
            checkOwnership()
            check(stream.stop()) { "JFR stream did not stop" }
            stream.dump(Path.of("native-render.jfr"))
        } finally {
            stream.close()
            Toolkit.getDefaultToolkit().removeAWTEventListener(listener)
            closed = true
        }
        check(errors.isEmpty() && loss.get() == 0L) { "Native observer failed: $errors; data-loss events=${loss.get()}" }
        check(completedFrames() > 0) { "No render methods observed" }
    }

    fun summary(): JsonObject = buildJsonObject {
        put("schema", "vive-native-wayland-method-observation/1")
        put("closed", closed)
        put("edtThreadId", edt)
        put("methodCounts", buildJsonObject { methods.forEach { put(it, counts.getValue(it).get()) } })
        put("errors", JsonArray(errors.map(::JsonPrimitive)))
        put("dataLossEvents", loss.get())
        put("components", JsonArray(components.map(::JsonPrimitive)))
        put("requestsFrames", false)
        put("presentationMeasured", false)
        put("inputTimingMeasured", false)
        put("returnOutcomeMeasured", false)
        put("scope", "Elapsed method invocations on the sole visible AWT window's EDT; no receiver identity or success flag in MethodTrace")
        put("phaseAttribution", "Offline by JFR method start timestamp, never by asynchronous delivery time")
        put("readiness", "Live JFR counts; event batching adds observer-dependent waits to scripted phases")
    }

    companion object {
        private val methods = listOf(
            "androidx.compose.ui.scene.ComposeSceneMediator::onRender",
            "org.jetbrains.skiko.swing.SkiaSwingLayer::paint",
            "org.jetbrains.skiko.swing.SwingRendererBase::redraw",
        )
        private val swingLayer = Class.forName("org.jetbrains.skiko.swing.SkiaSwingLayer")
        private val skiaLayer = Class.forName("org.jetbrains.skiko.SkiaLayer")
        private fun descendants(component: Component): List<Component> = listOf(component) +
            if (component is Container) component.components.flatMap(::descendants) else emptyList()

        fun install(frame: Frame): RuntimeRenderProbe = RuntimeRenderProbe(frame)

        fun selfContract(): JsonObject {
            val path = Path.of("native-contract.jfr")
            val expected = listOf("normal", "caught", "escaping").associateWith { 20 }
            Recording().use { recording ->
                recording.enable("jdk.MethodTrace").withoutThreshold().with("filter",
                    expected.keys.joinToString(";") { "com.vivenotes.runtimeprobe.NativeTraceControl::$it" })
                recording.enable("jdk.DataLoss")
                recording.start()
                repeat(20) { index ->
                    check(NativeTraceControl.normal(index) == index + 1)
                    check(NativeTraceControl.caught(index) == index)
                    check(runCatching { NativeTraceControl.escaping(index) }.isFailure)
                }
                recording.stop()
                recording.dump(path)
            }
            val events = RecordingFile.readAllEvents(path)
            check(events.none { it.eventType.name == "jdk.DataLoss" })
            val trace = events.filter { it.eventType.name == "jdk.MethodTrace" }
            check(trace.all { !it.duration.isNegative })
            val actual = trace.groupingBy { it.getValue<jdk.jfr.consumer.RecordedMethod>("method").name }.eachCount()
            check(actual == expected) { "MethodTrace normal/caught/escaping contract: $actual" }
            return buildJsonObject {
                put("passed", true)
                put("checks", 3)
                put("methodCounts", buildJsonObject { actual.forEach { (name, count) -> put(name, count) } })
            }
        }
    }
}

private object NativeTraceControl {
    fun normal(value: Int): Int = value + 1
    fun caught(value: Int): Int = try { throw IllegalStateException(value.toString()) } catch (_: IllegalStateException) { value }
    fun escaping(value: Int): Int = throw IllegalStateException(value.toString())
}
