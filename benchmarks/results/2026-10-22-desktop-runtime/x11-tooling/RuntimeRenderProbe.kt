package com.vivenotes.runtimeprobe

import java.awt.AWTEvent
import java.awt.Component
import java.awt.Container
import java.awt.EventQueue
import java.awt.Frame
import java.awt.Toolkit
import java.awt.event.AWTEventListener
import java.awt.event.KeyEvent
import java.awt.event.MouseEvent
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import jdk.jfr.Category
import jdk.jfr.Event
import jdk.jfr.Label
import jdk.jfr.Name
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.jetbrains.skiko.SkiaLayer
import org.jetbrains.skiko.SkikoRenderDelegate

/** Observes the owned window's existing CPU recording callbacks; never requests a frame. */
internal class RuntimeRenderProbe private constructor(private val frame: Frame) : AutoCloseable {
    private class Binding(val layer: SkiaLayer, val original: SkikoRenderDelegate, val wrapper: SkikoRenderDelegate)
    private val counters = RenderCounters()
    private val bindings = ArrayList<Binding>()
    private val closed = AtomicBoolean()
    private var listening = false
    private val listener = AWTEventListener { event ->
        val component = event.source as? Component
        if (component != null && belongsToFrame(component)) counters.input(event.id)
    }

    fun setPhase(name: String) = counters.setPhase(name)
    fun completedFrames(): Long = counters.completedFrames()

    fun summary(): JsonObject = buildJsonObject {
        put("schema", "vive-runtime-render-observation/1")
        put("recordingScope", "original SkiaLayer.renderDelegate.onRender callback; Compose CPU work/command recording, before PictureRecorder.finishRecordingAsPicture")
        put("inputScope", "owned-frame AWT dispatch observation to first later successfully returned recording callback; delivered/coalesced events only")
        put("presentationMeasured", false)
        put("inputCausalityEstablished", false)
        put("requestsFrames", false)
        put("capturesInputContent", false)
        put("phaseAttribution", "frames belong to phase at callback entry; input observations belong to phase at dispatch")
        put("processCpuScope", "process CPU and idle CPU are separate launcher observations; callback elapsed time is not thread CPU time")
        put("layers", JsonArray(bindings.mapIndexed { index, binding -> buildJsonObject {
            put("index", index)
            put("componentClass", binding.layer.javaClass.name)
            put("delegateClass", binding.original.javaClass.name)
        } }))
        put("observations", counters.summary())
    }

    private fun belongsToFrame(component: Component): Boolean {
        var current: Component? = component
        while (current != null) {
            if (current === frame) return true
            current = current.parent
        }
        return false
    }

    private fun wrap(layer: SkiaLayer) {
        val original = checkNotNull(layer.renderDelegate) { "The owned SkiaLayer has no render delegate" }
        val index = bindings.size
        val wrapper = SkikoRenderDelegate { canvas, width, height, scheduledNanos ->
            val token = counters.beginFrame()
            if (token == null) {
                original.onRender(canvas, width, height, scheduledNanos)
            } else {
                val event = RuntimeComposeRenderEvent().apply {
                    phase = token.phase
                    frameSequence = token.sequence
                    layerIndex = index
                    widthPixels = width
                    heightPixels = height
                    frameClockNanos = scheduledNanos
                    begin()
                }
                var returned = false
                try {
                    original.onRender(canvas, width, height, scheduledNanos)
                    returned = true
                } finally {
                    event.end()
                    val observed = counters.finishFrame(token, returned)
                    event.returnedNormally = returned
                    event.callbackElapsedNanos = observed.elapsedNanos
                    event.inputObservations = observed.inputs.size
                    event.commit()
                    for (input in observed.inputs) RuntimeInputToDrawEvent().apply {
                        phase = input.phase
                        inputSequence = input.sequence
                        inputEventId = input.id
                        frameSequence = token.sequence
                        dispatchToCallbackStartNanos = token.startedNanos - input.observedNanos
                        dispatchToCallbackEndNanos = observed.finishedNanos - input.observedNanos
                        commit()
                    }
                }
            }
        }
        layer.renderDelegate = wrapper
        bindings += Binding(layer, original, wrapper)
    }

    override fun close() {
        check(EventQueue.isDispatchThread()) { "Remove the render probe on the owned EDT" }
        if (!closed.compareAndSet(false, true)) return
        counters.close()
        try {
            if (listening) Toolkit.getDefaultToolkit().removeAWTEventListener(listener)
        } finally {
            listening = false
            for (binding in bindings.asReversed()) {
                // A later owner may have deliberately installed another delegate.
                if (binding.layer.renderDelegate === binding.wrapper) binding.layer.renderDelegate = binding.original
            }
        }
    }

    companion object {
        fun install(frame: Frame): RuntimeRenderProbe {
            check(EventQueue.isDispatchThread()) { "Install the render probe on the owned EDT" }
            check(frame.isShowing) { "The owned application frame must be showing" }
            val layers = ArrayList<SkiaLayer>()
            fun visit(component: Component) {
                if (component is SkiaLayer) layers += component
                if (component is Container) component.components.forEach(::visit)
            }
            visit(frame)
            check(layers.isNotEmpty()) { "No observable SkiaLayer in the owned frame; no frame result can be claimed" }
            val probe = RuntimeRenderProbe(frame)
            try {
                layers.forEach(probe::wrap)
                Toolkit.getDefaultToolkit().addAWTEventListener(probe.listener,
                    AWTEvent.MOUSE_EVENT_MASK or AWTEvent.MOUSE_MOTION_EVENT_MASK or
                        AWTEvent.MOUSE_WHEEL_EVENT_MASK or AWTEvent.KEY_EVENT_MASK)
                probe.listening = true
                return probe
            } catch (failure: Throwable) {
                probe.close()
                throw failure
            }
        }

        /** Pure deterministic contract; needs neither a window nor Skia/native rendering. */
        fun selfContract(): JsonObject {
            var now = 100L
            val counters = RenderCounters(maxPending = 2, clock = { now })
            var checks = 0
            fun verify(value: Boolean) { check(value) { "Render observation contract $checks failed" }; checks++ }
            counters.setPhase("first")
            val first = checkNotNull(counters.beginFrame())
            now = 110L
            counters.input(MouseEvent.MOUSE_DRAGGED)
            counters.setPhase("second")
            now = 120L
            verify(counters.finishFrame(first, true).inputs.isEmpty())
            verify(counters.completedFrames() == 1L)
            now = 200L
            val second = checkNotNull(counters.beginFrame())
            now = 210L
            val matched = counters.finishFrame(second, true)
            verify(matched.inputs.single().observedNanos == 110L)
            verify(matched.inputs.single().phase == "first")
            val phases = counters.summary().getValue("phases") as JsonArray
            val firstPhase = phases.map { it as JsonObject }.single { it["name"] == JsonPrimitive("first") }
            verify(firstPhase["completedCallbacks"] == JsonPrimitive(1L))
            verify(firstPhase["totalCallbackNanos"] == JsonPrimitive(20L))
            verify(firstPhase["inputToCallbackStartTotalNanos"] == JsonPrimitive(90L))
            verify(firstPhase["inputToCallbackEndTotalNanos"] == JsonPrimitive(100L))

            now = 220L
            counters.input(KeyEvent.KEY_PRESSED)
            now = 230L
            val failed = checkNotNull(counters.beginFrame())
            now = 240L
            verify(counters.finishFrame(failed, false).inputs.isEmpty())
            verify(counters.completedFrames() == 2L)
            now = 250L
            val recovered = checkNotNull(counters.beginFrame())
            now = 260L
            verify(counters.finishFrame(recovered, true).inputs.single().observedNanos == 220L)

            now = 300L
            counters.input(MouseEvent.MOUSE_MOVED)
            now = 301L
            counters.input(MouseEvent.MOUSE_MOVED)
            now = 302L
            counters.input(MouseEvent.MOUSE_MOVED)
            verify(counters.summary()["droppedPendingInputs"] == JsonPrimitive(1L))
            verify(counters.summary()["pendingInputs"] == JsonPrimitive(2))
            counters.close()
            counters.close()
            verify(counters.summary()["unmatchedInputsAtClose"] == JsonPrimitive(2L))
            verify(counters.beginFrame() == null)
            counters.input(MouseEvent.MOUSE_MOVED)
            verify(counters.summary()["pendingInputs"] == JsonPrimitive(0))
            verify(counters.completedFrames() == 3L)
            return buildJsonObject { put("schema", "vive-runtime-render-contract/1"); put("checks", checks); put("passed", true) }
        }
    }
}

private class RenderPhase(val name: String) {
    var started = 0L
    var completed = 0L
    var failed = 0L
    var totalNanos = 0L
    var maxNanos = 0L
    var inputs = 0L
    var matched = 0L
    var dropped = 0L
    var unmatchedAtClose = 0L
    var startDelayTotal = 0L
    var startDelayMax = 0L
    var endDelayTotal = 0L
    var endDelayMax = 0L
}

private data class RecordingToken(val owner: RenderPhase, val sequence: Long, val startedNanos: Long) {
    val phase: String get() = owner.name
}
private data class ObservedInput(val owner: RenderPhase, val sequence: Long, val id: Int, val observedNanos: Long) {
    val phase: String get() = owner.name
}
private data class RecordingResult(val finishedNanos: Long, val elapsedNanos: Long, val inputs: List<ObservedInput>)

private class RenderCounters(private val maxPending: Int = 512, private val clock: () -> Long = System::nanoTime) {
    private val lock = Any()
    private val phases = linkedMapOf<String, RenderPhase>()
    private var current = RenderPhase("observer_install").also { phases[it.name] = it }
    private val pending = ArrayDeque<ObservedInput>()
    private val completed = AtomicLong()
    private var frameSequence = 0L
    private var inputSequence = 0L
    private var inFlight = 0
    private var closed = false

    init { require(maxPending > 0) }
    fun completedFrames(): Long = completed.get()

    fun setPhase(name: String) = synchronized(lock) {
        if (closed) return@synchronized
        require(name.isNotBlank() && name.length <= 80)
        current = phases[name] ?: run {
            require(phases.size < 64) { "Render summaries accept at most 64 named phases" }
            RenderPhase(name).also { phases[name] = it }
        }
    }

    fun beginFrame(): RecordingToken? = synchronized(lock) {
        if (closed) null else {
            current.started++
            inFlight++
            RecordingToken(current, ++frameSequence, clock())
        }
    }

    fun input(id: Int) = synchronized(lock) {
        if (closed) return@synchronized
        current.inputs++
        if (pending.size == maxPending) pending.removeFirst().owner.dropped++
        pending.addLast(ObservedInput(current, ++inputSequence, id, clock()))
    }

    fun finishFrame(token: RecordingToken, returned: Boolean): RecordingResult = synchronized(lock) {
        val now = clock()
        val duration = (now - token.startedNanos).coerceAtLeast(0L)
        inFlight--
        token.owner.totalNanos += duration
        token.owner.maxNanos = maxOf(token.owner.maxNanos, duration)
        val observed = if (!returned || pending.isEmpty()) emptyList() else buildList {
            while (pending.isNotEmpty() && pending.first().observedNanos <= token.startedNanos) {
                val input = pending.removeFirst()
                input.owner.matched++
                val toStart = (token.startedNanos - input.observedNanos).coerceAtLeast(0L)
                val toEnd = (now - input.observedNanos).coerceAtLeast(0L)
                input.owner.startDelayTotal += toStart
                input.owner.startDelayMax = maxOf(input.owner.startDelayMax, toStart)
                input.owner.endDelayTotal += toEnd
                input.owner.endDelayMax = maxOf(input.owner.endDelayMax, toEnd)
                add(input)
            }
        }
        if (returned) { token.owner.completed++; completed.incrementAndGet() } else token.owner.failed++
        RecordingResult(now, duration, observed)
    }

    fun close() = synchronized(lock) {
        if (!closed) {
            closed = true
            pending.forEach { it.owner.unmatchedAtClose++ }
            pending.clear()
        }
    }

    fun summary(): JsonObject = synchronized(lock) {
        buildJsonObject {
            put("completedCallbacks", completed.get())
            put("startedCallbacks", frameSequence)
            put("failedCallbacks", phases.values.sumOf { it.failed })
            put("inFlightCallbacks", inFlight)
            put("inputObservations", inputSequence)
            put("pendingInputs", pending.size)
            put("droppedPendingInputs", phases.values.sumOf { it.dropped })
            put("unmatchedInputsAtClose", phases.values.sumOf { it.unmatchedAtClose })
            put("maxPendingInputs", maxPending)
            put("maxNamedPhases", 64)
            put("closed", closed)
            put("phases", JsonArray(phases.values.map { phase -> buildJsonObject {
                put("name", phase.name)
                put("startedCallbacks", phase.started)
                put("completedCallbacks", phase.completed)
                put("failedCallbacks", phase.failed)
                put("totalCallbackNanos", phase.totalNanos)
                put("maxCallbackNanos", phase.maxNanos)
                put("inputObservations", phase.inputs)
                put("inputsFollowedByCallback", phase.matched)
                put("droppedPendingInputs", phase.dropped)
                put("unmatchedInputsAtClose", phase.unmatchedAtClose)
                put("inputToCallbackStartTotalNanos", phase.startDelayTotal)
                put("inputToCallbackStartMaxNanos", phase.startDelayMax)
                put("inputToCallbackEndTotalNanos", phase.endDelayTotal)
                put("inputToCallbackEndMaxNanos", phase.endDelayMax)
            } }))
        }
    }
}

@Name("com.vivenotes.RuntimeComposeRender")
@Label("Vive Notes Compose CPU recording callback")
@Category("Vive Notes diagnostics")
class RuntimeComposeRenderEvent : Event() {
    @JvmField var phase: String = ""
    @JvmField var frameSequence: Long = 0L
    @JvmField var layerIndex: Int = 0
    @JvmField var widthPixels: Int = 0
    @JvmField var heightPixels: Int = 0
    @JvmField var frameClockNanos: Long = 0L
    @JvmField var callbackElapsedNanos: Long = 0L
    @JvmField var inputObservations: Int = 0
    @JvmField var returnedNormally: Boolean = false
}

@Name("com.vivenotes.RuntimeInputToDraw")
@Label("Vive Notes AWT dispatch to later CPU recording observation")
@Category("Vive Notes diagnostics")
class RuntimeInputToDrawEvent : Event() {
    @JvmField var phase: String = ""
    @JvmField var inputSequence: Long = 0L
    @JvmField var inputEventId: Int = 0
    @JvmField var frameSequence: Long = 0L
    @JvmField var dispatchToCallbackStartNanos: Long = 0L
    @JvmField var dispatchToCallbackEndNanos: Long = 0L
}
