package com.vivenotes.desktop.update.zsync

import java.io.File
import java.io.RandomAccessFile

/** Where each block of a zsync target comes from: an offset in the seed, or one of these. */
internal object BlockSource {
    const val MISSING = -1L

    /** A block of zeros, which needs neither the seed nor the network. */
    const val ZERO = -2L
}

/**
 * Finds the blocks of [control]'s target that a local [seed] already holds, as zsync's client does:
 * a rolling checksum slides over the seed one byte at a time, and where it matches a block's (and,
 * for [ZsyncControl.sequenceMatches] 2, the next block's too) the MD4 confirms it. Returns each
 * block's offset in the seed, or a [BlockSource] value. Reading past the seed's end gives zeros, as
 * zsync pads it, so a target ending like the seed keeps its short last block.
 *
 * [checkActive] runs between reads and may throw to stop the scan.
 */
internal fun scanSeed(control: ZsyncControl, seed: File, checkActive: () -> Unit = {}): LongArray {
    val sources = LongArray(control.blocks) { BlockSource.MISSING }
    markZeroBlocks(control, sources)
    val seedLength = seed.length()
    if (seedLength == 0L || control.blocks == 0) return sources
    RandomAccessFile(seed, "r").use { file ->
        Scanner(control, file, seedLength, sources, checkActive).run()
        extendRuns(control, file, seedLength, sources)
    }
    return sources
}

/** The number of target bytes [sources] can provide without downloading them. */
internal fun reusedBytes(control: ZsyncControl, sources: LongArray): Long =
    sources.indices.sumOf { if (sources[it] == BlockSource.MISSING) 0L else control.blockLength(it).toLong() }

private fun markZeroBlocks(control: ZsyncControl, sources: LongArray) {
    val zeros = ByteArray(control.blockSize)
    val digest = Md4().apply { update(zeros) }.digest()
    // A block of zeros sums to zero in both halves of the rolling checksum.
    for (block in sources.indices) {
        if (control.weak(block) == 0 && control.strongMatches(block, digest)) sources[block] = BlockSource.ZERO
    }
}

/** The blocks' rolling checksums, as zsync looks them up: sorted, behind a bit filter that rules most out. */
private class WeakIndex(control: ZsyncControl, pairs: Boolean) {
    val keys: LongArray
    val blocks: IntArray
    private val filter: LongArray
    private val shift: Int

    init {
        val count = if (pairs) control.blocks - 1 else control.blocks
        val raw = LongArray(count) { block ->
            val first = control.weak(block).toLong() and 0xFFFFFFFFL
            if (pairs) (first shl 32) or (control.weak(block + 1).toLong() and 0xFFFFFFFFL) else first
        }
        val order = (0 until count).sortedBy { raw[it] }
        keys = LongArray(count) { raw[order[it]] }
        blocks = IntArray(count) { order[it] }
        // About 64 bits per block, from 64 Ki up to 64 Mi bits: few false hits, and the filter stays small.
        val bits = Integer.highestOneBit((count.toLong() * 64).coerceIn(1L shl 16, 1L shl 26).toInt())
        filter = LongArray(bits / 64)
        shift = 64 - Integer.numberOfTrailingZeros(bits)
        for (key in keys) {
            val bit = slot(key)
            filter[bit ushr 6] = filter[bit ushr 6] or (1L shl (bit and 63))
        }
    }

    private fun slot(key: Long): Int = ((key * -0x61C8864680B583EBL) ushr shift).toInt()

    fun mayContain(key: Long): Boolean {
        val bit = slot(key)
        return filter[bit ushr 6] and (1L shl (bit and 63)) != 0L
    }

    /** The first position of [key] in [keys], or -1. */
    fun first(key: Long): Int {
        var low = 0
        var high = keys.size - 1
        var found = -1
        while (low <= high) {
            val middle = (low + high) ushr 1
            when {
                keys[middle] < key -> low = middle + 1
                keys[middle] > key -> high = middle - 1
                else -> { found = middle; high = middle - 1 }
            }
        }
        return found
    }
}

private class Scanner(
    private val control: ZsyncControl,
    private val file: RandomAccessFile,
    private val seedLength: Long,
    private val sources: LongArray,
    private val checkActive: () -> Unit,
) {
    private val size = control.blockSize
    private val shift = Integer.numberOfTrailingZeros(size)
    private val pairs = control.sequenceMatches == 2 && control.blocks >= 2
    /** The bytes one lookup covers. */
    private val span = if (pairs) 2 * size else size
    private val mask = control.weakMask
    private val index = WeakIndex(control, pairs)
    private val buffer = ByteArray(maxOf(BUFFER_BYTES, 4 * span))
    /** The seed offset of [buffer]'s first byte, and how many bytes from there it holds. */
    private var start = 0L
    private var filled = 0
    private val md4 = Md4()
    private val first = ByteArray(16)
    private val second = ByteArray(16)

    fun run() {
        var x = 0L
        // The rolling checksums of the block at x and, for pairs, of the block after it.
        var a0 = 0
        var b0 = 0
        var a1 = 0
        var b1 = 0
        var fresh = true
        while (x < seedLength) {
            // Every lookup and roll reads up to span bytes past x.
            if (x + span + 1 > start + filled) refill(x)
            if (fresh) {
                val at = (x - start).toInt()
                rolling(at).let { a0 = it ushr 16; b0 = it and 0xFFFF }
                if (pairs) rolling(at + size).let { a1 = it ushr 16; b1 = it and 0xFFFF }
                fresh = false
            }
            val limit = start + filled - span - 1
            while (x <= limit && x < seedLength) {
                val p = (x - start).toInt()
                val weak0 = ((a0 shl 16) or b0) and mask
                val key = if (pairs) (weak0.toLong() shl 32) or ((((a1 shl 16) or b1) and mask).toLong() and 0xFFFFFFFFL)
                    else weak0.toLong() and 0xFFFFFFFFL
                if (index.mayContain(key) && matchAt(key, x, p)) {
                    // A run of unchanged blocks most likely carries on right after this one.
                    x += size
                    if (pairs) { a0 = a1; b0 = b1 }
                    if (x + span + 1 > start + filled) { fresh = true; break }
                    val at = (x - start).toInt()
                    if (pairs) rolling(at + size).let { a1 = it ushr 16; b1 = it and 0xFFFF }
                    else rolling(at).let { a0 = it ushr 16; b0 = it and 0xFFFF }
                    continue
                }
                val out0 = buffer[p].toInt() and 0xFF
                val in0 = buffer[p + size].toInt() and 0xFF
                a0 = (a0 - out0 + in0) and 0xFFFF
                b0 = (b0 + a0 - (out0 shl shift)) and 0xFFFF
                if (pairs) {
                    val in1 = buffer[p + span].toInt() and 0xFF
                    a1 = (a1 - in0 + in1) and 0xFFFF
                    b1 = (b1 + a1 - (in0 shl shift)) and 0xFFFF
                }
                x++
            }
        }
    }

    /** Confirms the blocks [key] names against the seed at [x] ([p] in the buffer); true when any matched. */
    private fun matchAt(key: Long, x: Long, p: Int): Boolean {
        var position = index.first(key)
        if (position < 0) return false
        var firstReady = false
        var secondReady = false
        var matched = false
        while (position < index.keys.size && index.keys[position] == key) {
            val block = index.blocks[position++]
            if (sources[block] != BlockSource.MISSING && (!pairs || sources[block + 1] != BlockSource.MISSING)) continue
            if (!firstReady) { digest(p, first); firstReady = true }
            if (!control.strongMatches(block, first)) continue
            if (pairs) {
                if (!secondReady) { digest(p + size, second); secondReady = true }
                if (!control.strongMatches(block + 1, second)) continue
                if (sources[block + 1] == BlockSource.MISSING) sources[block + 1] = x + size
            }
            if (sources[block] == BlockSource.MISSING) sources[block] = x
            matched = true
        }
        return matched
    }

    private fun digest(at: Int, output: ByteArray) {
        md4.update(buffer, at, size)
        md4.digest(output)
    }

    private fun rolling(at: Int): Int = rollingChecksum(buffer, at, size)

    /** Moves [x] to the buffer's start and fills the rest, with zeros past the seed's end. */
    private fun refill(x: Long) {
        checkActive()
        val keep = (start + filled - x).toInt().coerceAtLeast(0)
        if (keep > 0) System.arraycopy(buffer, (x - start).toInt(), buffer, 0, keep)
        start = x
        filled = keep
        file.seek(start + filled)
        while (filled < buffer.size) {
            val position = start + filled
            if (position >= seedLength) {
                // Enough zeros for every window that starts inside the seed.
                val zeros = minOf(buffer.size - filled, (seedLength + span + 1 - position).toInt())
                if (zeros <= 0) break
                buffer.fill(0, filled, filled + zeros)
                filled += zeros
                break
            }
            val count = file.read(buffer, filled, minOf(buffer.size - filled.toLong(), seedLength - position).toInt())
            if (count < 0) throw java.io.EOFException("The seed ended early")
            filled += count
        }
    }

    private companion object {
        const val BUFFER_BYTES = 4 * 1024 * 1024
    }
}

/**
 * Blocks next to found ones, at the next offsets in the seed: the scan skips ahead after a match and,
 * for pairs, misses a block whose neighbour changed. One checksum each settles them.
 */
private fun extendRuns(control: ZsyncControl, file: RandomAccessFile, seedLength: Long, sources: LongArray) {
    val size = control.blockSize
    val buffer = ByteArray(size)
    val digest = ByteArray(16)
    val md4 = Md4()
    val pending = ArrayDeque<Int>()
    for (block in sources.indices) if (sources[block] >= 0) pending += block
    while (pending.isNotEmpty()) {
        val block = pending.removeFirst()
        val offset = sources[block]
        for ((neighbour, at) in listOf(block - 1 to offset - size, block + 1 to offset + size)) {
            if (neighbour !in sources.indices || sources[neighbour] != BlockSource.MISSING || at < 0 || at >= seedLength) continue
            buffer.fill(0)
            file.seek(at)
            var read = 0
            val wanted = minOf(size.toLong(), seedLength - at).toInt()
            while (read < wanted) {
                val count = file.read(buffer, read, wanted - read)
                if (count < 0) break
                read += count
            }
            if (rollingChecksum(buffer, 0, size) and control.weakMask != control.weak(neighbour)) continue
            md4.update(buffer, 0, size)
            if (!control.strongMatches(neighbour, md4.digest(digest))) continue
            sources[neighbour] = at
            pending += neighbour
        }
    }
}
