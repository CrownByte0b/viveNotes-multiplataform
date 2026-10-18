package com.vivenotes.desktop.camera

import com.vivenotes.data.CameraException
import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemoryLayout
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout.ADDRESS
import java.lang.foreign.ValueLayout.JAVA_BYTE
import java.lang.foreign.ValueLayout.JAVA_INT
import java.lang.foreign.ValueLayout.JAVA_LONG
import java.lang.foreign.ValueLayout.JAVA_SHORT
import java.lang.invoke.MethodHandle

/**
 * Video4Linux2, the kernel interface every Linux webcam driver speaks, as much of it as taking a photo
 * needs: what a device is, which formats and sizes it sends, and a stream of its frames through
 * memory the driver maps in.
 *
 * Struct offsets are the 64-bit kernel ABI's, which x86-64 and arm64 share — [supported] says
 * whether this machine is one of them. Each ioctl number carries its struct's size, so a wrong
 * layout is refused by the kernel rather than read past.
 */
internal object V4l2 {

    /** Whether this machine's ABI is the one the layouts here describe. */
    val supported: Boolean
        get() = System.getProperty("os.name").startsWith("Linux", ignoreCase = true) &&
            System.getProperty("os.arch") in setOf("amd64", "x86_64", "aarch64")

    // <asm-generic/ioctl.h>: number, type, size and direction packed into one word.
    private const val IOC_WRITE = 1L
    private const val IOC_READ = 2L

    internal fun ioc(direction: Long, number: Int, size: Int): Long =
        (direction shl 30) or (size.toLong() shl 16) or ('V'.code.toLong() shl 8) or number.toLong()

    // Struct sizes, as the 64-bit ABI lays them out.
    const val CAPABILITY_SIZE = 104
    const val FMTDESC_SIZE = 64
    const val FORMAT_SIZE = 208
    const val REQUESTBUFFERS_SIZE = 20
    const val BUFFER_SIZE = 88
    const val FRMSIZEENUM_SIZE = 44

    val VIDIOC_QUERYCAP = ioc(IOC_READ, 0, CAPABILITY_SIZE)
    val VIDIOC_ENUM_FMT = ioc(IOC_READ or IOC_WRITE, 2, FMTDESC_SIZE)
    val VIDIOC_S_FMT = ioc(IOC_READ or IOC_WRITE, 5, FORMAT_SIZE)
    val VIDIOC_REQBUFS = ioc(IOC_READ or IOC_WRITE, 8, REQUESTBUFFERS_SIZE)
    val VIDIOC_QUERYBUF = ioc(IOC_READ or IOC_WRITE, 9, BUFFER_SIZE)
    val VIDIOC_QBUF = ioc(IOC_READ or IOC_WRITE, 15, BUFFER_SIZE)
    val VIDIOC_DQBUF = ioc(IOC_READ or IOC_WRITE, 17, BUFFER_SIZE)
    val VIDIOC_STREAMON = ioc(IOC_WRITE, 18, Int.SIZE_BYTES)
    val VIDIOC_STREAMOFF = ioc(IOC_WRITE, 19, Int.SIZE_BYTES)
    val VIDIOC_ENUM_FRAMESIZES = ioc(IOC_READ or IOC_WRITE, 74, FRMSIZEENUM_SIZE)

    const val BUF_TYPE_VIDEO_CAPTURE = 1
    const val MEMORY_MMAP = 1
    const val FIELD_ANY = 0
    const val CAP_VIDEO_CAPTURE = 0x00000001
    const val CAP_STREAMING = 0x04000000
    const val CAP_DEVICE_CAPS = 0x80000000.toInt()
    const val FRMSIZE_TYPE_DISCRETE = 1
    const val BUF_FLAG_ERROR = 0x00000040

    /** `v4l2_fourcc`: four characters, the first in the lowest byte. */
    fun fourcc(code: String): Int = code.foldIndexed(0) { index, value, c -> value or (c.code shl (8 * index)) }

    val PIX_FMT_YUYV = fourcc("YUYV")
    val PIX_FMT_MJPEG = fourcc("MJPG")
    val PIX_FMT_JPEG = fourcc("JPEG")
}

/** The formats the app reads: JPEG frames, which Skia decodes, and raw YUYV, converted here. */
internal enum class PixelFormat(val fourcc: Int) {
    Mjpeg(V4l2.PIX_FMT_MJPEG), Jpeg(V4l2.PIX_FMT_JPEG), Yuyv(V4l2.PIX_FMT_YUYV);

    val compressed: Boolean get() = this != Yuyv

    companion object {
        fun of(fourcc: Int): PixelFormat? = entries.firstOrNull { it.fourcc == fourcc }
    }
}

internal data class FrameSize(val width: Int, val height: Int) {
    val area: Long get() = width.toLong() * height
}

/** A format a camera offers, at the sizes it offers it in; none when it takes any size up to [largest]. */
internal data class CameraMode(val format: PixelFormat, val sizes: List<FrameSize>, val largest: FrameSize? = null)

/** One frame as the driver delivered it, copied out of the mapped buffer. */
internal class RawFrame(
    val format: PixelFormat,
    val width: Int,
    val height: Int,
    val bytesPerLine: Int,
    val data: ByteArray,
)

/** What `VIDIOC_QUERYCAP` and `VIDIOC_ENUM_FMT` say about a device node that can be a camera. */
internal data class V4l2Description(val path: String, val name: String, val busInfo: String, val formats: Set<PixelFormat>)

/**
 * Which format and size to stream: JPEG frames at up to 1080p when the camera offers them — the
 * camera compresses, so the cable carries the full picture at full rate — and otherwise raw YUYV at
 * up to 720p, which a USB 2 camera can send at a usable rate. The largest size under the cap; the
 * smallest there is when every size is over it.
 */
internal fun chooseMode(modes: List<CameraMode>): Pair<PixelFormat, FrameSize>? {
    fun pick(mode: CameraMode, cap: FrameSize): FrameSize? {
        val fits = mode.sizes.filter { it.width <= cap.width && it.height <= cap.height }
        return fits.maxByOrNull { it.area }
            ?: mode.sizes.minByOrNull { it.area }
            ?: mode.largest?.let { FrameSize(minOf(it.width, cap.width), minOf(it.height, cap.height)) }
    }
    for ((formats, cap) in listOf(
        setOf(PixelFormat.Mjpeg, PixelFormat.Jpeg) to FrameSize(1920, 1080),
        setOf(PixelFormat.Yuyv) to FrameSize(1280, 720),
    )) {
        val mode = modes.firstOrNull { it.format in formats } ?: continue
        pick(mode, cap)?.let { return mode.format to it }
    }
    return null
}

/** The C library, with `errno` captured as each call returns. */
internal object Libc {
    private val linker = Linker.nativeLinker()
    private val stateLayout = Linker.Option.captureStateLayout()
    private val errnoOffset = stateLayout.byteOffset(MemoryLayout.PathElement.groupElement("errno"))
    private val captureErrno = Linker.Option.captureCallState("errno")

    private fun function(name: String, descriptor: FunctionDescriptor, vararg options: Linker.Option): MethodHandle =
        linker.downcallHandle(linker.defaultLookup().find(name).orElseThrow(), descriptor, *options)

    // int open(const char *path, int flags, ...), the mode passed as its one variadic argument.
    private val open = function("open", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT),
        captureErrno, Linker.Option.firstVariadicArg(2))
    // int ioctl(int fd, unsigned long request, ...), the struct passed as its one variadic argument.
    private val ioctl = function("ioctl", FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_LONG, ADDRESS),
        captureErrno, Linker.Option.firstVariadicArg(2))
    private val close = function("close", FunctionDescriptor.of(JAVA_INT, JAVA_INT))
    private val mmap = function("mmap",
        FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_LONG, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_LONG), captureErrno)
    private val munmap = function("munmap", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG))
    private val poll = function("poll", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG, JAVA_INT), captureErrno)

    const val O_RDWR = 2
    const val O_NONBLOCK = 0x800
    const val O_CLOEXEC = 0x80000
    const val PROT_READ = 1
    const val PROT_WRITE = 2
    const val MAP_SHARED = 1
    const val POLLIN: Short = 1
    const val POLLERR = 0x8
    const val POLLHUP = 0x10
    const val POLLNVAL = 0x20
    const val EINTR = 4
    const val EAGAIN = 11
    const val EBUSY = 16
    const val ENODEV = 19
    const val EINVAL = 22

    /** Where a call's errno lands; one per thread of calls, allocated from that caller's arena. */
    fun errnoState(arena: Arena): MemorySegment = arena.allocate(stateLayout)

    fun errno(state: MemorySegment): Int = state.get(JAVA_INT, errnoOffset)

    fun open(state: MemorySegment, path: MemorySegment, flags: Int): Int =
        open.invoke(state, path, flags, 0) as Int

    /** `ioctl`, retried while a signal interrupts it. */
    fun ioctl(state: MemorySegment, fd: Int, request: Long, argument: MemorySegment): Int {
        while (true) {
            val result = ioctl.invoke(state, fd, request, argument) as Int
            if (result != -1 || errno(state) != EINTR) return result
        }
    }

    fun close(fd: Int): Int = close.invoke(fd) as Int

    fun mmap(state: MemorySegment, length: Long, fd: Int, offset: Long): MemorySegment =
        mmap.invoke(state, MemorySegment.NULL, length, PROT_READ or PROT_WRITE, MAP_SHARED, fd, offset) as MemorySegment

    fun munmap(address: MemorySegment, length: Long): Int = munmap.invoke(address, length) as Int

    /** `poll` on one descriptor for reading: its `revents`, 0 on timeout, -1 on error. */
    fun pollIn(state: MemorySegment, pollFd: MemorySegment, fd: Int, timeoutMillis: Int): Int {
        pollFd.set(JAVA_INT, 0, fd)
        pollFd.set(JAVA_SHORT, 4, POLLIN)
        pollFd.set(JAVA_SHORT, 6, 0)
        val ready = poll.invoke(state, pollFd, 1L, timeoutMillis) as Int
        return if (ready <= 0) ready else pollFd.get(JAVA_SHORT, 6).toInt() and 0xFFFF
    }
}

/** The NUL-terminated text in [length] bytes at [offset]. */
private fun MemorySegment.cString(offset: Long, length: Int): String {
    val bytes = asSlice(offset, length.toLong()).toArray(JAVA_BYTE)
    val end = bytes.indexOf(0).let { if (it < 0) bytes.size else it }
    return bytes.decodeToString(0, end).trim()
}

/** One open device node, closed by [close] with everything mapped from it. */
internal class V4l2Device private constructor(
    val path: String,
    private val fd: Int,
    private val arena: Arena,
) : AutoCloseable {
    private val state = Libc.errnoState(arena)

    /**
     * One struct's worth of memory every call reuses: the calls come one at a time from the one
     * thread streaming, and a frame loop allocating per call would grow the arena until close.
     */
    private val scratch = arena.allocate(SCRATCH_BYTES, 8)

    /**
     * `ioctl` on a zeroed struct of [size] bytes, filled by [prepare]; the errno when it fails. The
     * struct is [scratch]: read what is needed from it before the next call.
     */
    private fun call(request: Long, size: Int, prepare: (MemorySegment) -> Unit = {}): Pair<MemorySegment, Int> {
        val struct = scratch.asSlice(0, size.toLong()).fill(0)
        prepare(struct)
        val result = Libc.ioctl(state, fd, request, struct)
        return struct to if (result == -1) Libc.errno(state) else 0
    }

    /** Name, bus and the formats the app reads; null when this node is not a camera's picture. */
    fun describe(): V4l2Description? {
        val (cap, error) = call(V4l2.VIDIOC_QUERYCAP, V4l2.CAPABILITY_SIZE)
        if (error != 0) return null
        val capabilities = cap.get(JAVA_INT, 84)
        val caps = if (capabilities and V4l2.CAP_DEVICE_CAPS != 0) cap.get(JAVA_INT, 88) else capabilities
        if (caps and V4l2.CAP_VIDEO_CAPTURE == 0 || caps and V4l2.CAP_STREAMING == 0) return null
        // Read before enumerating formats, which reuses the struct.
        val name = cap.cString(16, 32)
        val busInfo = cap.cString(48, 32)
        val formats = formats()
        if (formats.isEmpty()) return null
        return V4l2Description(path, name, busInfo, formats)
    }

    /** `VIDIOC_ENUM_FMT` until the driver says there are no more. */
    private fun formats(): Set<PixelFormat> = buildSet {
        for (index in 0 until MAX_ENUMERATION) {
            val (desc, error) = call(V4l2.VIDIOC_ENUM_FMT, V4l2.FMTDESC_SIZE) {
                it.set(JAVA_INT, 0, index)
                it.set(JAVA_INT, 4, V4l2.BUF_TYPE_VIDEO_CAPTURE)
            }
            if (error != 0) break
            PixelFormat.of(desc.get(JAVA_INT, 44))?.let(::add)
        }
    }

    /** `VIDIOC_ENUM_FRAMESIZES` for [format]. */
    fun mode(format: PixelFormat): CameraMode {
        val sizes = mutableListOf<FrameSize>()
        var largest: FrameSize? = null
        for (index in 0 until MAX_ENUMERATION) {
            val (size, error) = call(V4l2.VIDIOC_ENUM_FRAMESIZES, V4l2.FRMSIZEENUM_SIZE) {
                it.set(JAVA_INT, 0, index)
                it.set(JAVA_INT, 4, format.fourcc)
            }
            if (error != 0) break
            if (size.get(JAVA_INT, 8) == V4l2.FRMSIZE_TYPE_DISCRETE) {
                sizes += FrameSize(size.get(JAVA_INT, 12), size.get(JAVA_INT, 16))
            } else {
                // Stepwise or continuous: one entry, whose maximum is what matters here.
                largest = FrameSize(size.get(JAVA_INT, 16), size.get(JAVA_INT, 28))
                break
            }
        }
        return CameraMode(format, sizes, largest)
    }

    /** Asks for [format] at [size]; the format the driver settled on, which may differ in size. */
    fun setFormat(format: PixelFormat, size: FrameSize): StreamFormat {
        val (fmt, error) = call(V4l2.VIDIOC_S_FMT, V4l2.FORMAT_SIZE) {
            it.set(JAVA_INT, 0, V4l2.BUF_TYPE_VIDEO_CAPTURE)
            it.set(JAVA_INT, 8, size.width)
            it.set(JAVA_INT, 12, size.height)
            it.set(JAVA_INT, 16, format.fourcc)
            it.set(JAVA_INT, 20, V4l2.FIELD_ANY)
        }
        if (error != 0) throw failure(error)
        val settled = PixelFormat.of(fmt.get(JAVA_INT, 16)) ?: throw CameraException(CameraException.Reason.Failed)
        val width = fmt.get(JAVA_INT, 8)
        val height = fmt.get(JAVA_INT, 12)
        val bytesPerLine = fmt.get(JAVA_INT, 24).takeIf { it > 0 } ?: (width * 2)
        if (width <= 0 || height <= 0) throw CameraException(CameraException.Reason.Failed)
        return StreamFormat(settled, width, height, bytesPerLine)
    }

    private val mapped = mutableListOf<Pair<MemorySegment, Long>>()
    private var streaming = false

    /** Maps the driver's buffers, hands them all to it and starts the picture. */
    fun startStreaming(buffers: Int = BUFFERS) {
        val (request, error) = call(V4l2.VIDIOC_REQBUFS, V4l2.REQUESTBUFFERS_SIZE) {
            it.set(JAVA_INT, 0, buffers)
            it.set(JAVA_INT, 4, V4l2.BUF_TYPE_VIDEO_CAPTURE)
            it.set(JAVA_INT, 8, V4l2.MEMORY_MMAP)
        }
        if (error != 0) throw failure(error)
        val count = request.get(JAVA_INT, 0)
        if (count < 2) throw CameraException(CameraException.Reason.Failed)
        for (index in 0 until count) {
            val (buffer, queryError) = call(V4l2.VIDIOC_QUERYBUF, V4l2.BUFFER_SIZE) { it.prepareBuffer(index) }
            if (queryError != 0) throw failure(queryError)
            val length = buffer.get(JAVA_INT, 72).toLong() and 0xFFFFFFFFL
            val offset = buffer.get(JAVA_INT, 64).toLong() and 0xFFFFFFFFL
            val memory = Libc.mmap(state, length, fd, offset)
            if (memory.address() == -1L || memory.address() == 0L) throw failure(Libc.errno(state))
            mapped += memory.reinterpret(length) to length
            val (_, queueError) = call(V4l2.VIDIOC_QBUF, V4l2.BUFFER_SIZE) { it.prepareBuffer(index) }
            if (queueError != 0) throw failure(queueError)
        }
        val type = arena.allocate(JAVA_INT).also { it.set(JAVA_INT, 0, V4l2.BUF_TYPE_VIDEO_CAPTURE) }
        if (Libc.ioctl(state, fd, V4l2.VIDIOC_STREAMON, type) == -1) throw failure(Libc.errno(state))
        streaming = true
    }

    private val pollFd = arena.allocate(8, 4)

    /**
     * The newest frame that arrives within [timeoutMillis], every buffer handed straight back to the
     * driver, or null when none came. Older frames already waiting are skipped: a preview should
     * show now, not catch up.
     */
    fun next(format: StreamFormat, timeoutMillis: Int): RawFrame? {
        val events = Libc.pollIn(state, pollFd, fd, timeoutMillis)
        if (events < 0) {
            if (Libc.errno(state) == Libc.EINTR) return null
            throw CameraException(CameraException.Reason.Stopped)
        }
        if (events == 0) return null
        if (events and (Libc.POLLERR or Libc.POLLHUP or Libc.POLLNVAL) != 0) {
            throw CameraException(CameraException.Reason.Stopped)
        }
        var newest: RawFrame? = null
        // Each buffer comes back at most once per frame, so a few passes empty the queue.
        repeat(mapped.size * 2) {
            val (buffer, error) = call(V4l2.VIDIOC_DQBUF, V4l2.BUFFER_SIZE) { it.prepareBuffer(0) }
            when (error) {
                0 -> Unit
                Libc.EAGAIN -> return newest
                // The queue is in error, or the device gone: in either case no more frames come.
                else -> throw CameraException(CameraException.Reason.Stopped)
            }
            val index = buffer.get(JAVA_INT, 0)
            val used = buffer.get(JAVA_INT, 8).toLong() and 0xFFFFFFFFL
            val flags = buffer.get(JAVA_INT, 12)
            val (memory, length) = mapped.getOrNull(index) ?: throw CameraException(CameraException.Reason.Stopped)
            if (flags and V4l2.BUF_FLAG_ERROR == 0 && used > 0) {
                val bytes = memory.asSlice(0, minOf(used, length)).toArray(JAVA_BYTE)
                newest = RawFrame(format.format, format.width, format.height, format.bytesPerLine, bytes)
            }
            val (_, queueError) = call(V4l2.VIDIOC_QBUF, V4l2.BUFFER_SIZE) { it.prepareBuffer(index) }
            if (queueError != 0) throw CameraException(CameraException.Reason.Stopped)
        }
        return newest
    }

    private fun MemorySegment.prepareBuffer(index: Int) {
        set(JAVA_INT, 0, index)
        set(JAVA_INT, 4, V4l2.BUF_TYPE_VIDEO_CAPTURE)
        set(JAVA_INT, 60, V4l2.MEMORY_MMAP)
    }

    /** Stops the picture, unmaps and frees the buffers and closes the node. Errors are past caring. */
    override fun close() {
        if (streaming) {
            val type = arena.allocate(JAVA_INT).also { it.set(JAVA_INT, 0, V4l2.BUF_TYPE_VIDEO_CAPTURE) }
            Libc.ioctl(state, fd, V4l2.VIDIOC_STREAMOFF, type)
            streaming = false
        }
        mapped.forEach { (memory, length) -> Libc.munmap(memory, length) }
        if (mapped.isNotEmpty()) {
            mapped.clear()
            call(V4l2.VIDIOC_REQBUFS, V4l2.REQUESTBUFFERS_SIZE) {
                it.set(JAVA_INT, 4, V4l2.BUF_TYPE_VIDEO_CAPTURE)
                it.set(JAVA_INT, 8, V4l2.MEMORY_MMAP)
            }
        }
        Libc.close(fd)
        arena.close()
    }

    companion object {
        private const val MAX_ENUMERATION = 64
        private const val SCRATCH_BYTES = 256L
        const val BUFFERS = 4

        /** Opens [path] without waiting on it; a failure says why, as [CameraException.Reason]. */
        fun open(path: String): V4l2Device {
            val arena = Arena.ofShared()
            try {
                val state = Libc.errnoState(arena)
                val fd = Libc.open(state, arena.allocateFrom(path), Libc.O_RDWR or Libc.O_NONBLOCK or Libc.O_CLOEXEC)
                if (fd < 0) throw failure(Libc.errno(state))
                return V4l2Device(path, fd, arena)
            } catch (e: Throwable) {
                arena.close()
                throw e
            }
        }

        /** Another program streaming from the camera is the one failure the person can fix. */
        fun failure(errno: Int): CameraException = CameraException(
            if (errno == Libc.EBUSY) CameraException.Reason.Busy else CameraException.Reason.Failed,
            RuntimeException("errno $errno"),
        )
    }
}

/** What [V4l2Device.setFormat] settled on: every frame of the stream is this. */
internal data class StreamFormat(val format: PixelFormat, val width: Int, val height: Int, val bytesPerLine: Int)
