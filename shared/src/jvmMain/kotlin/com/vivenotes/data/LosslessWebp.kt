package com.vivenotes.data

import java.util.PriorityQueue

/**
 * Lossless WebP (VP8L, RFC 9649), which Android writes with `Bitmap.CompressFormat.WEBP_LOSSLESS` and
 * Skia's encoder, as Skiko exposes it, cannot: every quality it takes is the lossy format.
 *
 * Only what a drawn page needs: the subtract-green transform, LZ77 backward references and one set of
 * prefix codes. A page is mostly paper, and paper is one long run, which a reference to the pixel to
 * the left or above packs into a few bits a row. Any decoder reads the result, Skia's and Android's
 * among them.
 */
internal object LosslessWebp {
    /** [argb] is [width] × [height] pixels, row after row, as `0xAARRGGBB`. */
    fun encode(width: Int, height: Int, argb: IntArray): ByteArray {
        require(width in 1..MAX_SIDE && height in 1..MAX_SIDE) { "VP8L sides are 1 to $MAX_SIDE pixels" }
        require(argb.size == width * height)
        val pixels = IntArray(argb.size) { index ->
            // Subtract green: grey ink and paper leave nothing in red and blue to code.
            val pixel = argb[index]
            val green = (pixel shr 8) and 0xFF
            val red = (((pixel shr 16) and 0xFF) - green) and 0xFF
            val blue = ((pixel and 0xFF) - green) and 0xFF
            (pixel and 0xFF00FF00.toInt()) or (red shl 16) or blue
        }
        val tokens = backwardReferences(pixels, width)
        val writer = BitWriter(argb.size / 8 + 64)
        writer.write(SIGNATURE, 8)
        writer.write(width - 1, 14)
        writer.write(height - 1, 14)
        writer.write(if (argb.any { (it ushr 24) != 0xFF }) 1 else 0, 1)
        writer.write(0, 3)
        // One transform, subtract green, and then no more.
        writer.write(1, 1)
        writer.write(SUBTRACT_GREEN, 2)
        writer.write(0, 1)
        // No colour cache, and one group of prefix codes for the whole image.
        writer.write(0, 1)
        writer.write(0, 1)
        writeImage(writer, tokens, pixels)
        return riff(writer.bytes())
    }

    /**
     * What the pixels are coded as, in order: for each token a [lengths] entry of 0 and a [values]
     * entry naming the literal pixel, or the backward reference's length and its distance code.
     */
    private class Tokens(val lengths: IntArray, val values: IntArray, val count: Int)

    /**
     * Literal pixels and backward references, greedily chosen: the left and upper neighbours first,
     * since paper repeats them, then whatever an earlier match of the same two pixels offers.
     */
    private fun backwardReferences(pixels: IntArray, width: Int): Tokens {
        val size = pixels.size
        val lengths = IntArray(size)
        val values = IntArray(size)
        var count = 0
        val head = IntArray(HASH_SIZE) { -1 }
        val chain = IntArray(size)
        fun hashAt(at: Int): Int =
            ((pixels[at] * 0x1E35A7BD + pixels[at + 1] * 0x2F2E4C3B) ushr (32 - HASH_BITS))
        fun insert(at: Int) {
            if (at + 1 >= size) return
            val hash = hashAt(at)
            chain[at] = head[hash]
            head[hash] = at
        }
        fun matchLength(at: Int, distance: Int, limit: Int): Int {
            if (distance <= 0 || distance > at) return 0
            var length = 0
            while (length < limit && pixels[at + length] == pixels[at - distance + length]) length++
            return length
        }
        var at = 0
        while (at < size) {
            val limit = minOf(MAX_LENGTH, size - at)
            var bestLength = 0
            var bestDistance = 0
            for (distance in intArrayOf(1, width)) {
                val length = matchLength(at, distance, limit)
                if (length > bestLength) {
                    bestLength = length
                    bestDistance = distance
                }
            }
            if (bestLength < limit && at + 1 < size) {
                var candidate = head[hashAt(at)]
                var tries = 0
                while (candidate >= 0 && tries < MAX_CHAIN) {
                    val distance = at - candidate
                    if (distance > MAX_DISTANCE) break
                    val length = matchLength(at, distance, limit)
                    if (length > bestLength) {
                        bestLength = length
                        bestDistance = distance
                        if (length == limit) break
                    }
                    candidate = chain[candidate]
                    tries++
                }
            }
            if (bestLength >= MIN_MATCH) {
                lengths[count] = bestLength
                values[count++] = distanceCode(bestDistance, width)
                repeat(bestLength) { insert(at + it) }
                at += bestLength
            } else {
                values[count++] = at
                insert(at)
                at++
            }
        }
        return Tokens(lengths, values, count)
    }

    /** The plane codes for the pixel above and the pixel to the left; any other distance, plus 120. */
    private fun distanceCode(distance: Int, width: Int): Int = when (distance) {
        width -> 1
        1 -> 2
        else -> distance + PLANE_CODES
    }

    private fun writeImage(writer: BitWriter, tokens: Tokens, pixels: IntArray) {
        val green = IntArray(GREEN_ALPHABET)
        val red = IntArray(256)
        val blue = IntArray(256)
        val alpha = IntArray(256)
        val distance = IntArray(DISTANCE_ALPHABET)
        for (token in 0 until tokens.count) {
            if (tokens.lengths[token] == 0) {
                val pixel = pixels[tokens.values[token]]
                green[(pixel shr 8) and 0xFF]++
                red[(pixel shr 16) and 0xFF]++
                blue[pixel and 0xFF]++
                alpha[pixel ushr 24]++
            } else {
                green[256 + prefixOf(tokens.lengths[token]).code]++
                distance[prefixOf(tokens.values[token]).code]++
            }
        }
        val codes = listOf(green, red, blue, alpha, distance).map { PrefixCode.of(it) }
        codes.forEach { it.write(writer) }
        val (greenCode, redCode, blueCode, alphaCode, distanceCodes) = codes
        for (token in 0 until tokens.count) {
            if (tokens.lengths[token] == 0) {
                val pixel = pixels[tokens.values[token]]
                greenCode.put(writer, (pixel shr 8) and 0xFF)
                redCode.put(writer, (pixel shr 16) and 0xFF)
                blueCode.put(writer, pixel and 0xFF)
                alphaCode.put(writer, pixel ushr 24)
            } else {
                val length = prefixOf(tokens.lengths[token])
                greenCode.put(writer, 256 + length.code)
                writer.write(length.extra, length.extraBits)
                val far = prefixOf(tokens.values[token])
                distanceCodes.put(writer, far.code)
                writer.write(far.extra, far.extraBits)
            }
        }
    }

    private class Prefix(val code: Int, val extraBits: Int, val extra: Int)

    /** LZ77 prefix coding of a length or distance code, which is at least one. */
    private fun prefixOf(value: Int): Prefix {
        if (value <= 4) return Prefix(value - 1, 0, 0)
        val rest = value - 1
        val highest = 31 - Integer.numberOfLeadingZeros(rest)
        val second = (rest shr (highest - 1)) and 1
        val extraBits = highest - 1
        return Prefix(2 * highest + second, extraBits, rest and ((1 shl extraBits) - 1))
    }

    /**
     * One canonical prefix code: [lengths] per symbol, and [bits] — reversed for the LSB-first
     * stream — for each. An alphabet with one symbol in use codes it in no bits at all.
     */
    private class PrefixCode(private val lengths: IntArray, private val used: List<Int>) {
        private val bits = canonical(lengths)

        fun put(writer: BitWriter, symbol: Int) {
            if (used.size <= 1) return
            writer.write(bits[symbol], lengths[symbol])
        }

        fun write(writer: BitWriter) {
            if (used.size <= 2 && used.all { it < 256 }) {
                writeSimple(writer)
            } else {
                writeNormal(writer, lengths)
            }
        }

        private fun writeSimple(writer: BitWriter) {
            writer.write(1, 1)
            val symbols = used.ifEmpty { listOf(0) }
            writer.write(symbols.size - 1, 1)
            val first = symbols[0]
            if (first < 2) {
                writer.write(0, 1)
                writer.write(first, 1)
            } else {
                writer.write(1, 1)
                writer.write(first, 8)
            }
            if (symbols.size == 2) writer.write(symbols[1], 8)
        }

        companion object {
            fun of(counts: IntArray): PrefixCode {
                val used = counts.indices.filter { counts[it] > 0 }
                val lengths = when (used.size) {
                    0 -> IntArray(counts.size)
                    1 -> IntArray(counts.size).also { it[used[0]] = 1 }
                    else -> limitedLengths(counts, MAX_CODE_LENGTH)
                }
                return PrefixCode(lengths, used)
            }
        }
    }

    /** A normal code's lengths, themselves run-length coded and prefix coded, as the format reads them. */
    private fun writeNormal(writer: BitWriter, lengths: IntArray) {
        writer.write(0, 1)
        val symbols = ArrayList<Int>()
        val extras = ArrayList<Int>()
        var index = 0
        while (index < lengths.size) {
            val value = lengths[index]
            var run = 1
            while (index + run < lengths.size && lengths[index + run] == value) run++
            index += run
            if (value == 0) {
                var left = run
                while (left >= 11) {
                    val take = minOf(left, 138)
                    symbols += 18
                    extras += take - 11
                    left -= take
                }
                if (left >= 3) {
                    symbols += 17
                    extras += left - 3
                    left = 0
                }
                repeat(left) {
                    symbols += 0
                    extras += 0
                }
            } else {
                symbols += value
                extras += 0
                var left = run - 1
                while (left >= 3) {
                    val take = minOf(left, 6)
                    symbols += 16
                    extras += take - 3
                    left -= take
                }
                repeat(left) {
                    symbols += value
                    extras += 0
                }
            }
        }
        val counts = IntArray(CODE_LENGTH_CODES)
        symbols.forEach { counts[it]++ }
        val used = counts.indices.filter { counts[it] > 0 }
        val codeLengths = when (used.size) {
            1 -> IntArray(CODE_LENGTH_CODES).also { it[used[0]] = 1 }
            else -> limitedLengths(counts, MAX_CODE_LENGTH_CODE_LENGTH)
        }
        val codeBits = canonical(codeLengths)
        val written = (CODE_LENGTH_ORDER.indexOfLast { codeLengths[it] != 0 } + 1).coerceAtLeast(4)
        writer.write(written - 4, 4)
        for (order in 0 until written) writer.write(codeLengths[CODE_LENGTH_ORDER[order]], 3)
        // Every length is written, so no symbol count follows.
        writer.write(0, 1)
        symbols.forEachIndexed { at, symbol ->
            if (used.size > 1) writer.write(codeBits[symbol], codeLengths[symbol])
            when (symbol) {
                16 -> writer.write(extras[at], 2)
                17 -> writer.write(extras[at], 3)
                18 -> writer.write(extras[at], 7)
            }
        }
    }

    /**
     * Huffman lengths no longer than [maxLength], for two or more symbols in use: rare symbols are
     * counted as more common, doubling the floor, until the tree is shallow enough — libwebp's way.
     */
    private fun limitedLengths(counts: IntArray, maxLength: Int): IntArray {
        var floor = 1
        while (true) {
            val lengths = huffmanLengths(counts, floor)
            if (lengths.max() <= maxLength) return lengths
            floor *= 2
        }
    }

    private fun huffmanLengths(counts: IntArray, floor: Int): IntArray {
        class Node(val weight: Long, val symbol: Int, val left: Node?, val right: Node?, val order: Int)
        var order = 0
        val queue = PriorityQueue<Node>(compareBy<Node>({ it.weight }, { it.order }))
        counts.forEachIndexed { symbol, count ->
            if (count > 0) queue += Node(maxOf(count, floor).toLong(), symbol, null, null, order++)
        }
        while (queue.size > 1) {
            val a = queue.poll()
            val b = queue.poll()
            queue += Node(a.weight + b.weight, -1, a, b, order++)
        }
        val lengths = IntArray(counts.size)
        fun walk(node: Node, depth: Int) {
            if (node.symbol >= 0) {
                lengths[node.symbol] = depth.coerceAtLeast(1)
                return
            }
            walk(node.left!!, depth + 1)
            walk(node.right!!, depth + 1)
        }
        walk(queue.poll(), 0)
        return lengths
    }

    /** DEFLATE's canonical codes, each bit-reversed, since the stream is read least significant bit first. */
    private fun canonical(lengths: IntArray): IntArray {
        val longest = lengths.maxOrNull() ?: 0
        val perLength = IntArray(longest + 1)
        lengths.forEach { if (it > 0) perLength[it]++ }
        val next = IntArray(longest + 2)
        var code = 0
        for (length in 1..longest) {
            code = (code + perLength[length - 1]) shl 1
            next[length] = code
        }
        return IntArray(lengths.size) { symbol ->
            val length = lengths[symbol]
            if (length == 0) 0 else reverse(next[length]++, length)
        }
    }

    private fun reverse(value: Int, length: Int): Int = Integer.reverse(value) ushr (32 - length)

    private fun riff(payload: ByteArray): ByteArray {
        val padded = payload.size + (payload.size and 1)
        val out = java.io.ByteArrayOutputStream(padded + 20)
        fun int32(value: Int) {
            out.write(value and 0xFF)
            out.write((value shr 8) and 0xFF)
            out.write((value shr 16) and 0xFF)
            out.write((value shr 24) and 0xFF)
        }
        out.write("RIFF".toByteArray(Charsets.US_ASCII))
        int32(4 + 8 + padded)
        out.write("WEBP".toByteArray(Charsets.US_ASCII))
        out.write("VP8L".toByteArray(Charsets.US_ASCII))
        int32(payload.size)
        out.write(payload)
        if (payload.size and 1 == 1) out.write(0)
        return out.toByteArray()
    }

    /** Bits least significant first, as VP8L reads them. */
    private class BitWriter(capacity: Int) {
        private var buffer = ByteArray(capacity.coerceAtLeast(64))
        private var size = 0
        private var pending = 0L
        private var pendingBits = 0

        fun write(value: Int, bits: Int) {
            if (bits == 0) return
            pending = pending or ((value.toLong() and ((1L shl bits) - 1)) shl pendingBits)
            pendingBits += bits
            while (pendingBits >= 8) {
                put((pending and 0xFF).toInt())
                pending = pending ushr 8
                pendingBits -= 8
            }
        }

        private fun put(byte: Int) {
            if (size == buffer.size) buffer = buffer.copyOf(buffer.size * 2)
            buffer[size++] = byte.toByte()
        }

        fun bytes(): ByteArray {
            if (pendingBits > 0) {
                put((pending and 0xFF).toInt())
                pending = 0
                pendingBits = 0
            }
            return buffer.copyOf(size)
        }
    }

    private const val SIGNATURE = 0x2F
    private const val SUBTRACT_GREEN = 2
    private const val MAX_SIDE = 16384
    private const val GREEN_ALPHABET = 256 + 24
    private const val DISTANCE_ALPHABET = 40
    private const val CODE_LENGTH_CODES = 19
    private const val MAX_CODE_LENGTH = 15
    private const val MAX_CODE_LENGTH_CODE_LENGTH = 7
    private const val PLANE_CODES = 120
    private const val MIN_MATCH = 3
    private const val MAX_LENGTH = 4096
    private const val MAX_DISTANCE = (1 shl 20) - PLANE_CODES - 1
    private const val MAX_CHAIN = 32
    private const val HASH_BITS = 16
    private const val HASH_SIZE = 1 shl HASH_BITS
    private val CODE_LENGTH_ORDER = intArrayOf(17, 18, 0, 1, 2, 3, 4, 5, 16, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15)
}
