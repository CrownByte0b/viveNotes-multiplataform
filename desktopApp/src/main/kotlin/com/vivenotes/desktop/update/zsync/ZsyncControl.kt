package com.vivenotes.desktop.update.zsync

import java.io.IOException

/**
 * Why a zsync update cannot go ahead: an unusable control file, a server that will not serve parts
 * of a file, or a result that did not verify. The update then downloads the whole installer instead.
 */
internal class ZsyncUnavailable(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * A zsync control file, as `zsyncmake` 0.6 writes it: `Key: value` lines, an empty line, then for
 * every block of the target the trailing [weakBytes] of its rolling checksum (big-endian `a`, `b`)
 * and the leading [strongBytes] of its MD4. The last block's checksums cover it padded with zeros.
 */
internal class ZsyncControl private constructor(
    val fileName: String,
    val length: Long,
    val blockSize: Int,
    /** How many consecutive blocks must match before a match counts: 1, or 2 for large files. */
    val sequenceMatches: Int,
    val weakBytes: Int,
    val strongBytes: Int,
    /** The whole target's SHA-1, lowercase hex. */
    val sha1: String,
    val urls: List<String>,
    private val table: ByteArray,
) {
    val blocks: Int = ((length + blockSize - 1) / blockSize).toInt()

    /** The bits of a rolling checksum `a << 16 | b` the file keeps. */
    val weakMask: Int = if (weakBytes == 4) -1 else (1 shl (8 * weakBytes)) - 1

    private val entry = weakBytes + strongBytes

    /** Block [block]'s stored rolling checksum, already within [weakMask]. */
    fun weak(block: Int): Int {
        var value = 0
        val start = block * entry
        for (i in 0 until weakBytes) value = (value shl 8) or (table[start + i].toInt() and 0xFF)
        return value
    }

    /** Whether [md4] begins with block [block]'s stored strong checksum. */
    fun strongMatches(block: Int, md4: ByteArray): Boolean {
        val start = block * entry + weakBytes
        for (i in 0 until strongBytes) if (table[start + i] != md4[i]) return false
        return true
    }

    fun blockStart(block: Int): Long = block.toLong() * blockSize

    fun blockLength(block: Int): Int = minOf(blockSize.toLong(), length - blockStart(block)).toInt()

    companion object {
        /** Larger than any control file of ours: about 13 bytes for every 4 KiB of a 2 GB installer. */
        const val MAX_BYTES = 64 * 1024 * 1024

        private const val CLIENT_VERSION = "0.6.3"

        // Headers that describe a gzip-compressed form of the target, which the releases never use.
        private val COMPRESSED = setOf("Z-URL", "Z-Filename", "Z-Map2", "Recompress")

        fun parse(bytes: ByteArray): ZsyncControl {
            val end = (0 until bytes.size - 1).firstOrNull { bytes[it] == '\n'.code.toByte() && bytes[it + 1] == '\n'.code.toByte() }
                ?: throw ZsyncUnavailable("The zsync control file has no end to its header")
            val header = String(bytes, 0, end, Charsets.ISO_8859_1).split('\n').map { it.removeSuffix("\r") }
            val fields = linkedMapOf<String, MutableList<String>>()
            for (line in header) {
                val colon = line.indexOf(": ")
                if (colon <= 0) throw ZsyncUnavailable("Unreadable zsync header line: $line")
                fields.getOrPut(line.substring(0, colon)) { mutableListOf() } += line.substring(colon + 2)
            }
            fun one(key: String): String? = fields[key]?.singleOrNull()
            val version = one("zsync") ?: throw ZsyncUnavailable("Not a zsync control file")
            if (version == "0.0.4") throw ZsyncUnavailable("zsync 0.0.4 control files are not supported")
            one("Min-Version")?.let { if (compareVersions(it, CLIENT_VERSION) > 0) throw ZsyncUnavailable("Needs zsync $it") }
            val safe = one("Safe")?.split(Regex("""\s+""")).orEmpty().toSet()
            val known = setOf("zsync", "Min-Version", "Safe", "Filename", "MTime", "Blocksize", "Length", "Hash-Lengths",
                "URL", "SHA-1")
            for (key in fields.keys) {
                if (key in COMPRESSED) throw ZsyncUnavailable("The control file describes a compressed download")
                if (key !in known && key !in safe) throw ZsyncUnavailable("Unknown zsync header $key")
            }
            val fileName = one("Filename") ?: throw ZsyncUnavailable("The control file names no file")
            val length = one("Length")?.toLongOrNull()?.takeIf { it >= 0 }
                ?: throw ZsyncUnavailable("The control file gives no length")
            val blockSize = one("Blocksize")?.toIntOrNull()?.takeIf { it in 16..(1 shl 24) && it and (it - 1) == 0 }
                ?: throw ZsyncUnavailable("The control file gives no usable block size")
            val lengths = (one("Hash-Lengths") ?: "1,4,16").split(',').map { it.trim().toIntOrNull() }
            val (sequence, weak, strong) = lengths.takeIf { it.size == 3 && it.all { n -> n != null } }?.map { it!! }
                ?: throw ZsyncUnavailable("Unreadable hash lengths")
            if (sequence !in 1..2 || weak !in 1..4 || strong !in 3..16) throw ZsyncUnavailable("Unusable hash lengths")
            val sha1 = one("SHA-1")?.lowercase()?.takeIf { Regex("[0-9a-f]{40}").matches(it) }
                ?: throw ZsyncUnavailable("The control file has no SHA-1")
            val blocks = (length + blockSize - 1) / blockSize
            val table = bytes.copyOfRange(end + 2, bytes.size)
            if (blocks > Int.MAX_VALUE / (weak + strong) || table.size.toLong() != blocks * (weak + strong)) {
                throw ZsyncUnavailable("The block checksums do not cover the file")
            }
            return ZsyncControl(fileName, length, blockSize, sequence, weak, strong, sha1, fields["URL"].orEmpty(), table)
        }

        private fun compareVersions(a: String, b: String): Int {
            val left = a.split('.').map { it.toIntOrNull() ?: 0 }
            val right = b.split('.').map { it.toIntOrNull() ?: 0 }
            for (i in 0 until maxOf(left.size, right.size)) {
                val difference = left.getOrElse(i) { 0 }.compareTo(right.getOrElse(i) { 0 })
                if (difference != 0) return difference
            }
            return 0
        }
    }
}

/** zsync's rolling checksum of `data[offset, offset + length)`: `a` is the byte sum, `b` weighs each by its distance to the end. */
internal fun rollingChecksum(data: ByteArray, offset: Int, length: Int): Int {
    var a = 0
    var b = 0
    for (i in 0 until length) {
        val c = data[offset + i].toInt() and 0xFF
        a += c
        b += (length - i) * c
    }
    return ((a and 0xFFFF) shl 16) or (b and 0xFFFF)
}
