package com.vivenotes.desktop.touch

import com.vivenotes.diagnostics.DebugLog
import java.awt.Window
import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemoryLayout
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout.ADDRESS
import java.lang.foreign.ValueLayout.JAVA_BYTE
import java.lang.foreign.ValueLayout.JAVA_INT
import java.lang.foreign.ValueLayout.JAVA_SHORT
import java.lang.invoke.MethodHandle
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Touch on X11, through XInput 2.2 on a connection of this app's own, over XCB and the Java FFM API.
 *
 * AWT has no touch events. Without a client that asks for touch, the X server turns the first finger
 * into the mouse and drops the rest; JetBrains Runtime asks, and turns one finger into wheel
 * scrolling and taps into clicks. So the runtime is told not to ask ([disableRuntimeTouch]), and this
 * selects touch on every X window of an app window instead — XInput delivers a touch to the innermost
 * window that selected it, and only one client may select touch on a window. New child windows are
 * selected as they appear.
 *
 * XCB rather than Xlib because Xlib's error handler is one per process, and it is the runtime's.
 * [onFrame] is called on this source's thread, with positions in root-window pixels.
 */
internal class X11TouchSource(
    private val onFrame: (TouchFrame) -> Unit,
    private val log: DebugLog,
) {
    @Volatile private var running = true
    private val requests = ConcurrentLinkedQueue<Pair<Int, Window>>()
    private val owners = HashMap<Int, Window>()
    private val clock = EventClock()
    private val frames = TouchFrameBuilder(onFrame)
    private var connection: MemorySegment = MemorySegment.NULL
    private var inputOpcode = -1
    private var refused = false

    fun start() {
        Thread(::run, "vivenotes-x11-touch").apply { isDaemon = true }.start()
    }

    fun stop() {
        running = false
    }

    /** Selects touch on [xWindow] and everything inside it, for [window]. Any thread. */
    fun watch(xWindow: Int, window: Window) {
        requests += xWindow to window
    }

    private fun run() {
        try {
            Arena.ofConfined().use { arena ->
                connection = Xcb.connect.invoke(MemorySegment.NULL, MemorySegment.NULL) as MemorySegment
                check(Xcb.hasError.invoke(connection) as Int == 0) { "cannot connect to the X server" }
                val extension = (Xcb.extensionData.invoke(connection, Xcb.inputId) as MemorySegment).reinterpret(12)
                check(!extension.isNull && extension.get(JAVA_BYTE, 8).toInt() != 0) { "no XInput" }
                inputOpcode = extension.get(JAVA_BYTE, 9).toInt() and 0xFF
                checkVersion(arena)
                val readable = Posix.Readable(arena, Xcb.fileDescriptor.invoke(connection) as Int)
                log.event("touch") { "x11 touch listening" }
                while (running) {
                    val before = owners.size
                    while (true) select(requests.poll() ?: break, arena)
                    if (owners.size != before) log.event("touch") { "x11 touch selected on ${owners.size} windows" }
                    Xcb.flush.invoke(connection)
                    readable.await(POLL_TIMEOUT_MILLIS)
                    while (true) {
                        val event = Xcb.pollForEvent.invoke(connection) as MemorySegment
                        if (event.isNull) break
                        try {
                            handle(event.reinterpret(EVENT_BYTES), arena)
                        } finally {
                            Posix.free.invoke(event)
                        }
                    }
                    // What arrived together is a frame.
                    frames.flush()
                    check(Xcb.hasError.invoke(connection) as Int == 0) { "X connection lost" }
                }
            }
        } catch (failure: Throwable) {
            log.event("touch") { "x11 touch unavailable: $failure" }
        }
    }

    private fun checkVersion(arena: Arena) {
        val cookie = Xcb.queryVersion.invoke(arena, connection, MAJOR.toShort(), MINOR.toShort()) as MemorySegment
        val reply = Xcb.queryVersionReply.invoke(connection, cookie, MemorySegment.NULL) as MemorySegment
        check(!reply.isNull) { "XInput version query failed" }
        try {
            val version = reply.reinterpret(12)
            val major = version.get(JAVA_SHORT, 8).toInt()
            val minor = version.get(JAVA_SHORT, 10).toInt()
            check(major > MAJOR || (major == MAJOR && minor >= MINOR)) { "XInput $major.$minor has no touch" }
        } finally {
            Posix.free.invoke(reply)
        }
    }

    private fun select(request: Pair<Int, Window>, arena: Arena) {
        val (xWindow, window) = request
        if (owners[xWindow] === window) return
        owners[xWindow] = window
        // Touch from every master device, on this window.
        val mask = arena.allocate(8)
        mask.set(JAVA_SHORT, 0, ALL_MASTER_DEVICES)
        mask.set(JAVA_SHORT, 2, 1)
        mask.set(JAVA_INT, 4, TOUCH_EVENTS)
        val cookie = Xcb.selectEvents.invoke(arena, connection, xWindow, 1.toShort(), mask) as MemorySegment
        val error = Xcb.requestCheck.invoke(connection, cookie) as MemorySegment
        if (!error.isNull) {
            val code = error.reinterpret(2).get(JAVA_BYTE, 1).toInt()
            Posix.free.invoke(error)
            // BadAccess: another client already has touch here. Its windows keep the mouse emulation.
            if (!refused) log.event("touch") { "x11 touch refused on window $xWindow: error $code" }
            refused = true
            return
        }
        // Children made later, such as a canvas the window adds, are selected when they appear.
        val structure = arena.allocate(4).apply { set(JAVA_INT, 0, SUBSTRUCTURE_NOTIFY) }
        Xcb.changeAttributes.invoke(arena, connection, xWindow, EVENT_MASK_ATTRIBUTE, structure)
        val treeCookie = Xcb.queryTree.invoke(arena, connection, xWindow) as MemorySegment
        val tree = Xcb.queryTreeReply.invoke(connection, treeCookie, MemorySegment.NULL) as MemorySegment
        if (tree.isNull) return
        try {
            val count = Xcb.treeChildrenLength.invoke(tree) as Int
            val children = (Xcb.treeChildren.invoke(tree) as MemorySegment).reinterpret(count * 4L)
            for (index in 0 until count) requests += children.getAtIndex(JAVA_INT, index.toLong()) to window
        } finally {
            Posix.free.invoke(tree)
        }
    }

    private fun handle(event: MemorySegment, arena: Arena) {
        when (event.get(JAVA_BYTE, 0).toInt() and 0x7F) {
            GENERIC_EVENT -> {
                if ((event.get(JAVA_BYTE, 1).toInt() and 0xFF) != inputOpcode) return
                val phase = when (event.get(JAVA_SHORT, 8).toInt()) {
                    TOUCH_BEGIN -> TouchPhase.Down
                    TOUCH_UPDATE -> TouchPhase.Move
                    TOUCH_END -> TouchPhase.Up
                    else -> return
                }
                val window = owners[event.get(JAVA_INT, 24)] ?: return
                val id = event.get(JAVA_INT, 16).toLong() and 0xFFFFFFFFL
                val rootX = event.get(JAVA_INT, 36) / 65536.0
                val rootY = event.get(JAVA_INT, 40) / 65536.0
                frames.add(TouchChange(id, phase, window, rootX, rootY), clock.millis(event.get(JAVA_INT, 12)))
            }
            CREATE_NOTIFY -> {
                val owner = owners[event.get(JAVA_INT, 4)] ?: return
                select(event.get(JAVA_INT, 8) to owner, arena)
            }
            DESTROY_NOTIFY -> owners.remove(event.get(JAVA_INT, 8))
        }
    }

    /** libxcb and its XInput extension. */
    private object Xcb {
        private val linker = Linker.nativeLinker()
        private val core = SymbolLookup.libraryLookup("libxcb.so.1", Arena.global())
        private val input = SymbolLookup.libraryLookup("libxcb-xinput.so.0", Arena.global())
        private val cookie = MemoryLayout.structLayout(JAVA_INT.withName("sequence"))

        private fun function(lib: SymbolLookup, name: String, descriptor: FunctionDescriptor): MethodHandle =
            linker.downcallHandle(lib.find(name).orElseThrow { UnsatisfiedLinkError(name) }, descriptor)

        val inputId: MemorySegment = input.find("xcb_input_id").orElseThrow { UnsatisfiedLinkError("xcb_input_id") }
        val connect = function(core, "xcb_connect", FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS))
        val hasError = function(core, "xcb_connection_has_error", FunctionDescriptor.of(JAVA_INT, ADDRESS))
        val extensionData = function(core, "xcb_get_extension_data", FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS))
        val fileDescriptor = function(core, "xcb_get_file_descriptor", FunctionDescriptor.of(JAVA_INT, ADDRESS))
        val flush = function(core, "xcb_flush", FunctionDescriptor.of(JAVA_INT, ADDRESS))
        val pollForEvent = function(core, "xcb_poll_for_event", FunctionDescriptor.of(ADDRESS, ADDRESS))
        val requestCheck = function(core, "xcb_request_check", FunctionDescriptor.of(ADDRESS, ADDRESS, cookie))
        val changeAttributes = function(core, "xcb_change_window_attributes",
            FunctionDescriptor.of(cookie, ADDRESS, JAVA_INT, JAVA_INT, ADDRESS))
        val queryTree = function(core, "xcb_query_tree", FunctionDescriptor.of(cookie, ADDRESS, JAVA_INT))
        val queryTreeReply = function(core, "xcb_query_tree_reply", FunctionDescriptor.of(ADDRESS, ADDRESS, cookie, ADDRESS))
        val treeChildren = function(core, "xcb_query_tree_children", FunctionDescriptor.of(ADDRESS, ADDRESS))
        val treeChildrenLength = function(core, "xcb_query_tree_children_length", FunctionDescriptor.of(JAVA_INT, ADDRESS))
        val queryVersion = function(input, "xcb_input_xi_query_version",
            FunctionDescriptor.of(cookie, ADDRESS, JAVA_SHORT, JAVA_SHORT))
        val queryVersionReply = function(input, "xcb_input_xi_query_version_reply",
            FunctionDescriptor.of(ADDRESS, ADDRESS, cookie, ADDRESS))
        val selectEvents = function(input, "xcb_input_xi_select_events_checked",
            FunctionDescriptor.of(cookie, ADDRESS, JAVA_INT, JAVA_SHORT, ADDRESS))
    }

    companion object {
        private const val MAJOR = 2
        private const val MINOR = 2
        private const val ALL_MASTER_DEVICES: Short = 1
        private const val TOUCH_BEGIN = 18
        private const val TOUCH_UPDATE = 19
        private const val TOUCH_END = 20
        private const val TOUCH_EVENTS = (1 shl TOUCH_BEGIN) or (1 shl TOUCH_UPDATE) or (1 shl TOUCH_END)
        private const val GENERIC_EVENT = 35
        private const val CREATE_NOTIFY = 16
        private const val DESTROY_NOTIFY = 17
        private const val EVENT_MASK_ATTRIBUTE = 1 shl 11
        private const val SUBSTRUCTURE_NOTIFY = 1 shl 19
        private const val EVENT_BYTES = 64L
        private const val POLL_TIMEOUT_MILLIS = 100

        /**
         * Stops JetBrains Runtime selecting touch on its X windows, which it would do for every window
         * it creates. It decides once, at toolkit start, whether XInput is there; this answers no
         * before any window exists. Other runtimes never select touch, and lack the field.
         */
        fun disableRuntimeTouch(log: DebugLog) {
            try {
                val toolkit = Class.forName("sun.awt.X11.XToolkit")
                val field = toolkit.getDeclaredField("hasXInputExtension").apply { isAccessible = true }
                field.setBoolean(null, false)
                log.event("touch") { "runtime touch handling disabled" }
            } catch (_: ReflectiveOperationException) {
                // Not JetBrains Runtime: nothing selects touch to begin with.
            } catch (failure: RuntimeException) {
                log.event("touch") { "runtime touch handling left on: $failure" }
            }
        }
    }
}
