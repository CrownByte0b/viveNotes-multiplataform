package com.vivenotes.desktop.touch

import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout.ADDRESS
import java.lang.foreign.ValueLayout.JAVA_INT
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType
import kotlin.math.roundToInt

/**
 * ByteInk's private tablet test compositor (its `byteink_test` protocol, version 2), reached on the
 * toolkit's own display through a queue of this helper's: it sends tablet-v2 input to a surface and
 * reports what each tablet tool's cursor was asked to be. [open] is null on any other compositor.
 * Confined to one thread.
 */
internal class TabletFixture private constructor(private val display: MemorySegment) : AutoCloseable {
    /** One tool's cursor requests as the compositor recorded them; shapes are cursor-shape-v1 values. */
    data class Cursor(val seat: Int, val tool: Int, val shapes: Int, val shape: Int, val shapeSerial: Boolean,
        val surfaces: Int, val surfaceSerial: Boolean, val hidden: Int)

    private val arena = Arena.ofShared()
    private val library = SymbolLookup.libraryLookup("libwayland-client.so.0", arena)
    private val linker = Linker.nativeLinker()
    private fun function(name: String, descriptor: FunctionDescriptor) = linker.downcallHandle(library.find(name).orElseThrow(), descriptor)
    private val createQueue = function("wl_display_create_queue", FunctionDescriptor.of(ADDRESS, ADDRESS))
    private val createWrapper = function("wl_proxy_create_wrapper", FunctionDescriptor.of(ADDRESS, ADDRESS))
    private val destroyWrapper = function("wl_proxy_wrapper_destroy", FunctionDescriptor.ofVoid(ADDRESS))
    private val setQueue = function("wl_proxy_set_queue", FunctionDescriptor.ofVoid(ADDRESS, ADDRESS))
    private val marshal = function("wl_proxy_marshal_array_flags",
        FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT, ADDRESS))
    private val addListener = function("wl_proxy_add_listener", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS))
    private val roundtrip = function("wl_display_roundtrip_queue", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS))
    private val destroyProxy = function("wl_proxy_destroy", FunctionDescriptor.ofVoid(ADDRESS))
    private val destroyQueue = function("wl_event_queue_destroy", FunctionDescriptor.ofVoid(ADDRESS))
    private val control = WaylandInterfaces.define(arena, "byteink_test", 2,
        requests = listOf(
            WaylandInterfaces.Message("send", "ouiffiffu", listOf(library.find("wl_surface_interface").orElseThrow()) + List(8) { null }),
            WaylandInterfaces.Message("destroy", ""),
            WaylandInterfaces.Message("cursors", "2"),
        ),
        events = listOf(WaylandInterfaces.Message("cursor", "2uuuuuuuuiiiii")))
    private val queue = createQueue.invokeWithArguments(display) as MemorySegment
    private var registry = MemorySegment.NULL
    private var proxy = MemorySegment.NULL
    private val reported = mutableListOf<Cursor>()

    init {
        val wrapper = createWrapper.invokeWithArguments(display) as MemorySegment
        try {
            setQueue.invokeWithArguments(wrapper, queue)
            registry = request(wrapper, 1, library.find("wl_registry_interface").orElseThrow(), 1, MemorySegment.NULL)
        } finally { destroyWrapper.invokeWithArguments(wrapper) }
        val global = listener("global", FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, JAVA_INT, ADDRESS, JAVA_INT))
        val globalRemove = listener("globalRemove", FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, JAVA_INT))
        listen(registry, global, globalRemove)
        roundtrip()
    }

    @Suppress("unused") // Called from libwayland.
    private fun global(data: MemorySegment, registry: MemorySegment, name: Int, iface: MemorySegment, version: Int) {
        if (proxy.address() == 0L && version >= 2 && iface.reinterpret(256).getString(0) == "byteink_test") {
            proxy = request(registry, 0, control, 2, name, arena.allocateFrom("byteink_test"), 2, MemorySegment.NULL)
            listen(proxy, listener("cursor", FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, *Array(13) { JAVA_INT })))
        }
    }

    @Suppress("unused", "UNUSED_PARAMETER") // Called from libwayland.
    private fun globalRemove(data: MemorySegment, registry: MemorySegment, name: Int) = Unit

    @Suppress("unused", "UNUSED_PARAMETER") // Called from libwayland.
    private fun cursor(data: MemorySegment, proxy: MemorySegment, seat: Int, tool: Int, shapes: Int, shape: Int, shapeSerial: Int,
        surfaces: Int, surfaceSerial: Int, hidden: Int, hotspotX: Int, hotspotY: Int, width: Int, height: Int, scale: Int) {
        reported += Cursor(seat, tool, shapes, shape, shapeSerial != 0, surfaces, surfaceSerial != 0, hidden)
    }

    /** Tablet tool [tool] at ([x], [y]) on [surface], in its surface coordinates; phase 1 hovers, 3 leaves proximity. */
    fun send(surface: Long, tool: Int, phase: Int, x: Double, y: Double, time: Int) {
        request(proxy, 0, MemorySegment.NULL, 2, MemorySegment.ofAddress(surface), tool, phase,
            (x * 256).roundToInt(), (y * 256).roundToInt(), 16384, 0, 0, time)
        roundtrip()
    }

    fun cursors(): List<Cursor> {
        reported.clear()
        request(proxy, 2, MemorySegment.NULL, 2)
        roundtrip()
        return reported.toList()
    }

    override fun close() {
        if (proxy.address() != 0L) request(proxy, 1, MemorySegment.NULL, 2, flags = 1)
        destroyProxy.invokeWithArguments(registry)
        roundtrip()
        destroyQueue.invokeWithArguments(queue)
        arena.close()
    }

    private fun roundtrip() = check(roundtrip.invokeWithArguments(display, queue) as Int >= 0) { "the test compositor's connection failed" }

    private fun request(proxy: MemorySegment, opcode: Int, type: MemorySegment, version: Int, vararg arguments: Any, flags: Int = 0): MemorySegment =
        Arena.ofConfined().use { local ->
            val array = if (arguments.isEmpty()) MemorySegment.NULL else local.allocate(8L * arguments.size, 8)
            arguments.forEachIndexed { index, value ->
                if (value is Int) array.set(JAVA_INT, index * 8L, value) else array.set(ADDRESS, index * 8L, value as MemorySegment)
            }
            marshal.invokeWithArguments(proxy, opcode, type, version, flags, array) as MemorySegment
        }

    /** An upcall to this object's [method], whose parameters are [descriptor]'s, in order. */
    private fun listener(method: String, descriptor: FunctionDescriptor): MemorySegment {
        val parameters = descriptor.argumentLayouts().map { if (it == JAVA_INT) Integer.TYPE else MemorySegment::class.java }
        val handle = MethodHandles.lookup().findVirtual(TabletFixture::class.java, method,
            MethodType.methodType(Void.TYPE, parameters)).bindTo(this)
        return linker.upcallStub(handle, descriptor, arena)
    }

    /** A listener table: one upcall per event, in the interface's event order. */
    private fun listen(proxy: MemorySegment, vararg events: MemorySegment) {
        val table = arena.allocate(ADDRESS, events.size.toLong())
        events.forEachIndexed { index, stub -> table.setAtIndex(ADDRESS, index.toLong(), stub) }
        check(addListener.invokeWithArguments(proxy, table, MemorySegment.NULL) as Int == 0)
    }

    companion object {
        fun open(display: Long): TabletFixture? {
            val fixture = TabletFixture(MemorySegment.ofAddress(display))
            if (fixture.proxy.address() != 0L) return fixture
            fixture.close()
            return null
        }
    }
}
