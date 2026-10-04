package com.vivenotes.desktop.touch

import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout.ADDRESS
import java.lang.foreign.ValueLayout.JAVA_INT
import java.lang.foreign.ValueLayout.JAVA_LONG
import java.lang.foreign.ValueLayout.JAVA_SHORT
import java.lang.invoke.MethodHandle

/** The C library calls the touch sources share. */
internal object Posix {
    private val linker = Linker.nativeLinker()
    private val poll: MethodHandle = linker.downcallHandle(linker.defaultLookup().find("poll").orElseThrow(),
        FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG, JAVA_INT))
    val free: MethodHandle = linker.downcallHandle(linker.defaultLookup().find("free").orElseThrow(),
        FunctionDescriptor.ofVoid(ADDRESS))

    /** A `struct pollfd` for one descriptor, waiting to read. */
    class Readable(arena: Arena, fd: Int) {
        private val pollFd: MemorySegment = arena.allocate(8).apply { set(JAVA_INT, 0, fd) }

        /** Whether [fd] has data within [timeoutMillis]; false on timeout or interruption. */
        fun await(timeoutMillis: Int): Boolean {
            pollFd.set(JAVA_SHORT, 4, POLLIN)
            pollFd.set(JAVA_SHORT, 6, 0)
            val ready = poll.invoke(pollFd, 1L, timeoutMillis) as Int
            return ready > 0 && (pollFd.get(JAVA_SHORT, 6).toInt() and POLLIN.toInt()) != 0
        }
    }

    private const val POLLIN: Short = 1
}

/** A pointer C returned, as null or not: its address, whatever size the segment has been given. */
internal val MemorySegment.isNull: Boolean get() = address() == 0L
