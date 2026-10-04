package com.vivenotes.desktop.touch

import com.vivenotes.diagnostics.DebugLog
import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout.ADDRESS
import java.lang.foreign.ValueLayout.JAVA_INT
import java.lang.invoke.MethodHandle
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType

/**
 * Touch on native Wayland, which JetBrains Runtime's toolkit never asks the compositor for: this binds
 * `wl_seat` and `wl_touch` itself, through libwayland-client and the Java FFM API.
 *
 * It must be the runtime's own connection, [display]: a compositor sends touches only to the client
 * that owns the surface touched. Its objects live on an event queue of their own, read and dispatched
 * by a thread of their own, so the runtime's queue and threads are left alone. libwayland lets
 * several threads read one connection: each declares it will read (`prepare_read`), then reads or
 * cancels, and the last one does the reading for all — so this thread never stops between the two,
 * because the runtime's own reads wait for it.
 *
 * [onFrame] is called on that thread.
 */
internal class WaylandTouchSource(
    private val display: MemorySegment,
    private val onFrame: (SurfaceTouchFrame) -> Unit,
    private val log: DebugLog,
) {
    @Volatile private var running = true
    private val frames = WaylandTouchFrames(onFrame)
    private val arena = Arena.ofShared()
    private val clock = EventClock()
    private val seats = mutableListOf<Seat>()

    private class Seat(val index: Int, val proxy: MemorySegment, val version: Int) {
        var touch: MemorySegment? = null
    }

    fun start() {
        Thread(::run, "vivenotes-wayland-touch").apply { isDaemon = true }.start()
    }

    fun stop() {
        running = false
    }

    private fun run() {
        try {
            val queue = Wl.createQueue.invoke(display) as MemorySegment
            check(!queue.isNull) { "no event queue" }
            val wrapper = Wl.createWrapper.invoke(display) as MemorySegment
            Wl.setQueue.invoke(wrapper, queue)
            val registry = Wl.marshalNewId.invoke(wrapper, GET_REGISTRY, Wl.registryInterface,
                Wl.getVersion.invoke(wrapper) as Int, 0, MemorySegment.NULL) as MemorySegment
            Wl.wrapperDestroy.invoke(wrapper)
            Wl.addListener.invoke(registry, listener(
                stub("global", MethodType.methodType(Void.TYPE, MemorySegment::class.java, MemorySegment::class.java,
                    Int::class.java, MemorySegment::class.java, Int::class.java),
                    FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, JAVA_INT, ADDRESS, JAVA_INT)),
                stub("globalRemove", MethodType.methodType(Void.TYPE, MemorySegment::class.java,
                    MemorySegment::class.java, Int::class.java), FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, JAVA_INT)),
            ), MemorySegment.NULL)
            // Globals, which bind the seats; then the seats' capabilities, which give touch.
            repeat(2) { check(Wl.roundtripQueue.invoke(display, queue) as Int >= 0) { "roundtrip failed" } }
            log.event("touch") { "wayland touch listening: seats=${seats.size}, touch=${seats.count { it.touch != null }}" }
            readLoop(queue)
        } catch (failure: Throwable) {
            log.event("touch") { "wayland touch unavailable: $failure" }
        }
    }

    private fun readLoop(queue: MemorySegment) {
        Arena.ofConfined().use { local ->
            val connection = Posix.Readable(local, Wl.getFd.invoke(display) as Int)
            while (running) {
                while (Wl.prepareReadQueue.invoke(display, queue) as Int != 0) {
                    check(Wl.dispatchQueuePending.invoke(display, queue) as Int >= 0) { "dispatch failed" }
                }
                // Prepared: from here this thread must read or cancel, whatever happens.
                var read = false
                try {
                    Wl.flush.invoke(display)
                    if (connection.await(POLL_TIMEOUT_MILLIS)) {
                        read = true
                        check(Wl.readEvents.invoke(display) as Int >= 0) { "connection lost" }
                    }
                } finally {
                    if (!read) Wl.cancelRead.invoke(display)
                }
                check(Wl.dispatchQueuePending.invoke(display, queue) as Int >= 0) { "dispatch failed" }
                // Mutter sends a lift with no `frame` after it. What arrived in one read belongs
                // together, so the end of a read closes a frame the compositor left open.
                frames.frame()
            }
        }
    }

    // --- listeners, called on this thread while the queue is dispatched. An exception must not
    // escape into libwayland, so each one is caught and logged.

    private fun global(data: MemorySegment, registry: MemorySegment, name: Int, iface: MemorySegment, version: Int) =
        guarded {
            if (iface.reinterpret(256).getString(0) != "wl_seat") return@guarded
            val bound = minOf(version, SEAT_VERSION)
            val seatName = Wl.seatInterface.reinterpret(8).get(ADDRESS, 0)
            val seat = Wl.marshalBind.invoke(registry, BIND, Wl.seatInterface, bound, 0,
                name, seatName, bound, MemorySegment.NULL) as MemorySegment
            val entry = Seat(seats.size, seat, bound)
            seats += entry
            Wl.addListener.invoke(seat, seatListener, MemorySegment.ofAddress(entry.index.toLong()))
        }

    @Suppress("UNUSED_PARAMETER")
    private fun globalRemove(data: MemorySegment, registry: MemorySegment, name: Int) = Unit

    private fun capabilities(data: MemorySegment, seatProxy: MemorySegment, capabilities: Int) = guarded {
        val seat = seats.getOrNull(data.address().toInt()) ?: return@guarded
        val hasTouch = capabilities and CAPABILITY_TOUCH != 0
        val current = seat.touch
        if (hasTouch && current == null) {
            val touch = Wl.marshalNewId.invoke(seat.proxy, GET_TOUCH, Wl.touchInterface, seat.version, 0,
                MemorySegment.NULL) as MemorySegment
            Wl.addListener.invoke(touch, touchListener, MemorySegment.ofAddress(seat.index.toLong()))
            seat.touch = touch
            log.event("touch") { "touch screen on seat ${seat.index}" }
        } else if (!hasTouch && current != null) {
            seat.touch = null
            frames.cancel()
            if (seat.version >= TOUCH_RELEASE_SINCE) {
                Wl.marshalDestroy.invoke(current, TOUCH_RELEASE, MemorySegment.NULL, seat.version, MARSHAL_DESTROY)
            } else {
                Wl.destroy.invoke(current)
            }
            log.event("touch") { "touch screen gone from seat ${seat.index}" }
        }
    }

    @Suppress("UNUSED_PARAMETER")
    private fun seatName(data: MemorySegment, seat: MemorySegment, name: MemorySegment) = Unit

    @Suppress("UNUSED_PARAMETER")
    private fun down(data: MemorySegment, touch: MemorySegment, serial: Int, time: Int, surface: MemorySegment,
                     id: Int, x: Int, y: Int) = guarded {
        if (surface.isNull) return@guarded
        frames.down(contact(data, id), clock.millis(time), surface.address(), x / 256.0, y / 256.0)
    }

    @Suppress("UNUSED_PARAMETER")
    private fun up(data: MemorySegment, touch: MemorySegment, serial: Int, time: Int, id: Int) = guarded {
        frames.up(contact(data, id), clock.millis(time))
    }

    @Suppress("UNUSED_PARAMETER")
    private fun motion(data: MemorySegment, touch: MemorySegment, time: Int, id: Int, x: Int, y: Int) = guarded {
        frames.motion(contact(data, id), clock.millis(time), x / 256.0, y / 256.0)
    }

    @Suppress("UNUSED_PARAMETER")
    private fun frame(data: MemorySegment, touch: MemorySegment) = guarded { frames.frame() }

    @Suppress("UNUSED_PARAMETER")
    private fun cancel(data: MemorySegment, touch: MemorySegment) = guarded { frames.cancel() }

    @Suppress("UNUSED_PARAMETER")
    private fun shape(data: MemorySegment, touch: MemorySegment, id: Int, major: Int, minor: Int) = Unit

    @Suppress("UNUSED_PARAMETER")
    private fun orientation(data: MemorySegment, touch: MemorySegment, id: Int, orientation: Int) = Unit

    /** A contact on one seat, unique across seats: the seat's index above the compositor's id. */
    private fun contact(seatData: MemorySegment, id: Int): Long = (seatData.address() shl 32) or (id.toLong() and 0xFFFFFFFFL)

    private inline fun guarded(block: () -> Unit) {
        try {
            block()
        } catch (failure: Throwable) {
            log.event("touch") { "wayland touch event failed: $failure" }
        }
    }

    private val seatListener by lazy {
        listener(
            stub("capabilities", MethodType.methodType(Void.TYPE, MemorySegment::class.java, MemorySegment::class.java,
                Int::class.java), FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, JAVA_INT)),
            stub("seatName", MethodType.methodType(Void.TYPE, MemorySegment::class.java, MemorySegment::class.java,
                MemorySegment::class.java), FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, ADDRESS)),
        )
    }

    // Every event wl_touch has, through version 9, so no version a compositor offers can reach a hole.
    private val touchListener by lazy {
        val segment = MemorySegment::class.java
        val int = Int::class.java
        listener(
            stub("down", MethodType.methodType(Void.TYPE, segment, segment, int, int, segment, int, int, int),
                FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, JAVA_INT, JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT)),
            stub("up", MethodType.methodType(Void.TYPE, segment, segment, int, int, int),
                FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT)),
            stub("motion", MethodType.methodType(Void.TYPE, segment, segment, int, int, int, int),
                FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT)),
            stub("frame", MethodType.methodType(Void.TYPE, segment, segment), FunctionDescriptor.ofVoid(ADDRESS, ADDRESS)),
            stub("cancel", MethodType.methodType(Void.TYPE, segment, segment), FunctionDescriptor.ofVoid(ADDRESS, ADDRESS)),
            stub("shape", MethodType.methodType(Void.TYPE, segment, segment, int, int, int),
                FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT)),
            stub("orientation", MethodType.methodType(Void.TYPE, segment, segment, int, int),
                FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, JAVA_INT, JAVA_INT)),
        )
    }

    private fun stub(method: String, type: MethodType, descriptor: FunctionDescriptor): MemorySegment {
        val handle = MethodHandles.lookup().findVirtual(WaylandTouchSource::class.java, method, type).bindTo(this)
        return Wl.linker.upcallStub(handle, descriptor, arena)
    }

    /** A listener struct: the function pointers in the order of the interface's events. */
    private fun listener(vararg functions: MemorySegment): MemorySegment {
        val struct = arena.allocate(ADDRESS.byteSize() * functions.size)
        functions.forEachIndexed { index, function -> struct.setAtIndex(ADDRESS, index.toLong(), function) }
        return struct
    }

    /** libwayland-client: the runtime has already loaded it, and a lookup by name finds that copy. */
    private object Wl {
        val linker: Linker = Linker.nativeLinker()
        private val lib = SymbolLookup.libraryLookup("libwayland-client.so.0", Arena.global())
        private fun symbol(name: String) = lib.find(name).orElseThrow { UnsatisfiedLinkError(name) }
        private fun function(name: String, descriptor: FunctionDescriptor, vararg options: Linker.Option): MethodHandle =
            linker.downcallHandle(symbol(name), descriptor, *options)

        val registryInterface: MemorySegment = symbol("wl_registry_interface")
        val seatInterface: MemorySegment = symbol("wl_seat_interface")
        val touchInterface: MemorySegment = symbol("wl_touch_interface")

        val createQueue = function("wl_display_create_queue", FunctionDescriptor.of(ADDRESS, ADDRESS))
        val createWrapper = function("wl_proxy_create_wrapper", FunctionDescriptor.of(ADDRESS, ADDRESS))
        val wrapperDestroy = function("wl_proxy_wrapper_destroy", FunctionDescriptor.ofVoid(ADDRESS))
        val setQueue = function("wl_proxy_set_queue", FunctionDescriptor.ofVoid(ADDRESS, ADDRESS))
        val getVersion = function("wl_proxy_get_version", FunctionDescriptor.of(JAVA_INT, ADDRESS))
        val addListener = function("wl_proxy_add_listener", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS))
        val destroy = function("wl_proxy_destroy", FunctionDescriptor.ofVoid(ADDRESS))
        val roundtripQueue = function("wl_display_roundtrip_queue", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS))
        val prepareReadQueue = function("wl_display_prepare_read_queue", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS))
        val dispatchQueuePending = function("wl_display_dispatch_queue_pending",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS))
        val readEvents = function("wl_display_read_events", FunctionDescriptor.of(JAVA_INT, ADDRESS))
        val cancelRead = function("wl_display_cancel_read", FunctionDescriptor.ofVoid(ADDRESS))
        val flush = function("wl_display_flush", FunctionDescriptor.of(JAVA_INT, ADDRESS))
        val getFd = function("wl_display_get_fd", FunctionDescriptor.of(JAVA_INT, ADDRESS))

        // wl_proxy_marshal_flags(proxy, opcode, interface, version, flags, ...), once per argument list.
        private val marshal = arrayOf(ADDRESS, JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT)
        val marshalNewId = function("wl_proxy_marshal_flags", FunctionDescriptor.of(ADDRESS, *marshal, ADDRESS),
            Linker.Option.firstVariadicArg(marshal.size))
        val marshalBind = function("wl_proxy_marshal_flags",
            FunctionDescriptor.of(ADDRESS, *marshal, JAVA_INT, ADDRESS, JAVA_INT, ADDRESS),
            Linker.Option.firstVariadicArg(marshal.size))
        val marshalDestroy = function("wl_proxy_marshal_flags", FunctionDescriptor.of(ADDRESS, *marshal),
            Linker.Option.firstVariadicArg(marshal.size))
    }

    private companion object {
        const val GET_REGISTRY = 1 // wl_display.get_registry
        const val BIND = 0 // wl_registry.bind
        const val GET_TOUCH = 2 // wl_seat.get_touch
        const val TOUCH_RELEASE = 0 // wl_touch.release
        const val TOUCH_RELEASE_SINCE = 3
        const val MARSHAL_DESTROY = 1 // WL_MARSHAL_FLAG_DESTROY
        const val CAPABILITY_TOUCH = 4
        /** wl_seat 5: touch with frames and cancel, and release for both. Later versions add nothing used here. */
        const val SEAT_VERSION = 5
        const val POLL_TIMEOUT_MILLIS = 200
    }
}

/**
 * A device's millisecond timestamps — Wayland's and X11's, which wrap and start anywhere — placed on
 * the wall clock AWT's own events use, keeping the spacing the device reported between them.
 */
internal class EventClock(private val now: () -> Long = System::currentTimeMillis) {
    private var offset: Long? = null
    private var last = Long.MIN_VALUE

    /** Never earlier than the time it returned before: Compose reads event times as monotonic. */
    fun millis(deviceTime: Int): Long {
        val device = deviceTime.toLong() and 0xFFFFFFFFL
        val current = now()
        val known = offset
        // First use, a wrap, a clock gone astray or stepping back: start again from now.
        if (known == null || kotlin.math.abs(current - (device + known)) > RESYNC_MILLIS || device + known < last) {
            offset = current - device
        }
        last = maxOf(last, device + offset!!)
        return last
    }

    private companion object {
        const val RESYNC_MILLIS = 5_000L
    }
}
