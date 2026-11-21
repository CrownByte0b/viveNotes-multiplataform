package com.vivenotes.desktop.touch

import java.lang.foreign.Arena
import java.lang.foreign.MemoryLayout
import java.lang.foreign.MemorySegment
import java.lang.foreign.StructLayout
import java.lang.foreign.ValueLayout.ADDRESS
import java.lang.foreign.ValueLayout.JAVA_INT

/**
 * libwayland's `wl_message` and `wl_interface`, for protocols libwayland-client does not carry:
 * what wayland-scanner would generate from a protocol's XML. A message's types list the interface of
 * each object or new-id argument, null for the others; libwayland checks an event's objects against
 * them.
 */
internal object WaylandInterfaces {
    val message: StructLayout = MemoryLayout.structLayout(
        ADDRESS.withName("name"), ADDRESS.withName("signature"), ADDRESS.withName("types"))
    val iface: StructLayout = MemoryLayout.structLayout(
        ADDRESS.withName("name"), JAVA_INT.withName("version"), JAVA_INT.withName("method_count"),
        ADDRESS.withName("methods"), JAVA_INT.withName("event_count"), MemoryLayout.paddingLayout(4),
        ADDRESS.withName("events"))

    class Message(val name: String, val signature: String, val types: List<MemorySegment?> = emptyList())

    /** What an interface struct says, read back. */
    data class Description(val name: String, val version: Int, val requests: List<String>, val events: List<String>)

    fun define(arena: Arena, name: String, version: Int, requests: List<Message>, events: List<Message>): MemorySegment {
        val struct = arena.allocate(iface)
        struct.set(ADDRESS, offset(iface, "name"), arena.allocateFrom(name))
        struct.set(JAVA_INT, offset(iface, "version"), version)
        struct.set(JAVA_INT, offset(iface, "method_count"), requests.size)
        struct.set(ADDRESS, offset(iface, "methods"), messages(arena, requests))
        struct.set(JAVA_INT, offset(iface, "event_count"), events.size)
        struct.set(ADDRESS, offset(iface, "events"), messages(arena, events))
        return struct
    }

    /**
     * An interface struct read back: each message as `name(signature)`, followed by its arguments'
     * interfaces when it has object or new-id arguments (`-` for the others). Wayland's own structs
     * read the same way, which is how a test checks these layouts against libwayland's.
     */
    fun describe(struct: MemorySegment): Description {
        val at = struct.reinterpret(iface.byteSize())
        fun list(countAt: String, arrayAt: String): List<String> {
            val count = at.get(JAVA_INT, offset(iface, countAt))
            val array = at.get(ADDRESS, offset(iface, arrayAt)).reinterpret(message.byteSize() * count)
            return (0 until count).map { index ->
                val entry = array.asSlice(index * message.byteSize(), message.byteSize())
                val signature = string(entry.get(ADDRESS, offset(message, "signature")))
                val arguments = signature.count { it.isLetter() }
                val types = entry.get(ADDRESS, offset(message, "types")).reinterpret(ADDRESS.byteSize() * arguments)
                val named = (0 until arguments).map { argument ->
                    val type = types.getAtIndex(ADDRESS, argument.toLong())
                    if (type.address() == 0L) "-" else string(type.reinterpret(iface.byteSize()).get(ADDRESS, offset(iface, "name")))
                }
                val name = string(entry.get(ADDRESS, offset(message, "name")))
                if (named.all { it == "-" }) "$name($signature)" else "$name($signature ${named.joinToString(",")})"
            }
        }
        return Description(string(at.get(ADDRESS, offset(iface, "name"))), at.get(JAVA_INT, offset(iface, "version")),
            list("method_count", "methods"), list("event_count", "events"))
    }

    private fun messages(arena: Arena, messages: List<Message>): MemorySegment {
        if (messages.isEmpty()) return MemorySegment.NULL
        val array = arena.allocate(message, messages.size.toLong())
        messages.forEachIndexed { index, entry ->
            val arguments = entry.signature.count { it.isLetter() }
            require(entry.types.isEmpty() || entry.types.size == arguments) { "${entry.name}: a type for each argument" }
            // Never a null array: libwayland indexes it for an event's object arguments.
            val types = arena.allocate(ADDRESS, maxOf(arguments, 1).toLong())
            entry.types.forEachIndexed { argument, type -> types.setAtIndex(ADDRESS, argument.toLong(), type ?: MemorySegment.NULL) }
            val slot = array.asSlice(index * message.byteSize(), message.byteSize())
            slot.set(ADDRESS, offset(message, "name"), arena.allocateFrom(entry.name))
            slot.set(ADDRESS, offset(message, "signature"), arena.allocateFrom(entry.signature))
            slot.set(ADDRESS, offset(message, "types"), types)
        }
        return array
    }

    private fun string(address: MemorySegment): String = address.reinterpret(Long.MAX_VALUE).getString(0)

    private fun offset(layout: StructLayout, field: String): Long = layout.byteOffset(MemoryLayout.PathElement.groupElement(field))
}

/**
 * `pointer-gestures-unstable-v1`, at version 1: a pinch from the manager, for a `wl_pointer`. Swipes
 * are left to the compositor, which uses them for its own gestures; their interface is not defined,
 * and the request that would make one is never sent.
 */
internal class PointerGestureInterfaces(arena: Arena, pointer: MemorySegment, surface: MemorySegment) {
    val pinch: MemorySegment = WaylandInterfaces.define(arena, "zwp_pointer_gesture_pinch_v1", VERSION,
        requests = listOf(WaylandInterfaces.Message("destroy", "")),
        events = listOf(
            WaylandInterfaces.Message("begin", "uuou", listOf(null, null, surface, null)),
            WaylandInterfaces.Message("update", "uffff"),
            WaylandInterfaces.Message("end", "uui"),
        ))

    val gestures: MemorySegment = WaylandInterfaces.define(arena, "zwp_pointer_gestures_v1", VERSION,
        requests = listOf(
            WaylandInterfaces.Message("get_swipe_gesture", "no", listOf(null, pointer)),
            WaylandInterfaces.Message("get_pinch_gesture", "no", listOf(pinch, pointer)),
        ),
        events = emptyList())

    companion object {
        const val VERSION = 1
        const val GET_PINCH_GESTURE = 1
        const val PINCH_DESTROY = 0
    }
}
