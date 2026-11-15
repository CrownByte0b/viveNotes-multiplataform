package com.vivenotes.desktop.update.zsync

/**
 * RFC 1320 MD4, the strong block checksum zsync control files carry. The JDK ships none it exposes.
 * MD4 is broken as a security hash; here it only tells blocks apart, and the assembled file is
 * accepted on its SHA-1 and the release's SHA-256.
 */
internal class Md4 {
    private val state = IntArray(4)
    private val words = IntArray(16)
    private val buffer = ByteArray(64)
    private val padding = ByteArray(72)
    private var count = 0L

    init {
        reset()
    }

    fun reset() {
        state[0] = 0x67452301
        state[1] = 0xEFCDAB89.toInt()
        state[2] = 0x98BADCFE.toInt()
        state[3] = 0x10325476
        count = 0
    }

    fun update(input: ByteArray, offset: Int = 0, length: Int = input.size - offset) {
        var position = offset
        var remaining = length
        var index = (count and 63).toInt()
        count += length
        if (index > 0) {
            val take = minOf(64 - index, remaining)
            System.arraycopy(input, position, buffer, index, take)
            index += take
            position += take
            remaining -= take
            if (index < 64) return
            transform(buffer, 0)
        }
        while (remaining >= 64) {
            transform(input, position)
            position += 64
            remaining -= 64
        }
        if (remaining > 0) System.arraycopy(input, position, buffer, 0, remaining)
    }

    /** The digest of everything given since the last reset, into [output]; then starts over. */
    fun digest(output: ByteArray = ByteArray(16)): ByteArray {
        val bits = count shl 3
        val index = (count and 63).toInt()
        val pad = if (index < 56) 56 - index else 120 - index
        padding.fill(0)
        padding[0] = 0x80.toByte()
        for (i in 0 until 8) padding[pad + i] = (bits ushr (8 * i)).toByte()
        update(padding, 0, pad + 8)
        for (i in 0 until 4) for (j in 0 until 4) output[i * 4 + j] = (state[i] ushr (8 * j)).toByte()
        reset()
        return output
    }

    private fun transform(block: ByteArray, offset: Int) {
        for (i in 0 until 16) {
            val at = offset + 4 * i
            words[i] = (block[at].toInt() and 0xFF) or ((block[at + 1].toInt() and 0xFF) shl 8) or
                ((block[at + 2].toInt() and 0xFF) shl 16) or ((block[at + 3].toInt() and 0xFF) shl 24)
        }
        var a = state[0]
        var b = state[1]
        var c = state[2]
        var d = state[3]
        for (i in 0 until 4) {
            a = (a + ((b and c) or (b.inv() and d)) + words[4 * i]).rotateLeft(3)
            d = (d + ((a and b) or (a.inv() and c)) + words[4 * i + 1]).rotateLeft(7)
            c = (c + ((d and a) or (d.inv() and b)) + words[4 * i + 2]).rotateLeft(11)
            b = (b + ((c and d) or (c.inv() and a)) + words[4 * i + 3]).rotateLeft(19)
        }
        for (i in 0 until 4) {
            a = (a + ((b and c) or (b and d) or (c and d)) + words[i] + ROUND_2).rotateLeft(3)
            d = (d + ((a and b) or (a and c) or (b and c)) + words[i + 4] + ROUND_2).rotateLeft(5)
            c = (c + ((d and a) or (d and b) or (a and b)) + words[i + 8] + ROUND_2).rotateLeft(9)
            b = (b + ((c and d) or (c and a) or (d and a)) + words[i + 12] + ROUND_2).rotateLeft(13)
        }
        for (i in ROUND_3_ORDER) {
            a = (a + (b xor c xor d) + words[i] + ROUND_3).rotateLeft(3)
            d = (d + (a xor b xor c) + words[i + 8] + ROUND_3).rotateLeft(9)
            c = (c + (d xor a xor b) + words[i + 4] + ROUND_3).rotateLeft(11)
            b = (b + (c xor d xor a) + words[i + 12] + ROUND_3).rotateLeft(15)
        }
        state[0] += a
        state[1] += b
        state[2] += c
        state[3] += d
    }

    private companion object {
        const val ROUND_2 = 0x5A827999
        const val ROUND_3 = 0x6ED9EBA1
        val ROUND_3_ORDER = intArrayOf(0, 2, 1, 3)
    }
}
