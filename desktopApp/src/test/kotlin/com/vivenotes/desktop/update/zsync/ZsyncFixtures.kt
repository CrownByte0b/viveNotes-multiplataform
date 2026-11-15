package com.vivenotes.desktop.update.zsync

import java.io.ByteArrayOutputStream
import java.security.MessageDigest

/**
 * Test data and control files. The `.zsync` files in `resources/update/zsync` are real `zsyncmake` 0.6.3 output for
 * [sample] and [large] (`make_fixtures.py` writes them again); [zsyncmake] writes the same format for
 * any data and hash lengths, and [ZsyncControlTest] checks it agrees with the real tool byte for byte.
 */
internal object ZsyncFixtures {
    /** xorshift64, each state's eight bytes little-endian: what `make_fixtures.py` writes. */
    fun xorshift(seed: Long, size: Int): ByteArray {
        var state = seed
        val out = ByteArray(size)
        var i = 0
        while (i < size) {
            state = state xor (state shl 13)
            state = state xor (state ushr 7)
            state = state xor (state shl 17)
            for (b in 0 until 8) {
                if (i == size) break
                out[i++] = (state ushr (8 * b)).toByte()
            }
        }
        return out
    }

    /** Random bytes, a run of zeros, a repeated phrase, more random bytes, and a short last block. */
    fun sample(): ByteArray {
        val phrase = "ViveNotes block ".toByteArray()
        val pattern = ByteArray(50_000) { phrase[it % phrase.size] }
        return xorshift(1, 100_000) + ByteArray(20_000) + pattern + xorshift(2, 130_017)
    }

    /** Large enough that zsyncmake asks for two blocks in a row to match. */
    fun large(): ByteArray = xorshift(3, 26_000_000)

    fun resource(name: String): ByteArray =
        checkNotNull(ZsyncFixtures::class.java.getResource("/update/zsync/$name")) { name }.readBytes()

    /** A control file as zsyncmake 0.6 writes it, without its MTime line. */
    fun zsyncmake(
        data: ByteArray,
        blockSize: Int,
        sequenceMatches: Int,
        weakBytes: Int,
        strongBytes: Int,
        fileName: String = "target.bin",
        url: String = "https://example.com/$fileName",
        version: String = "0.6.3",
    ): ByteArray {
        val out = ByteArrayOutputStream()
        val header = buildString {
            append("zsync: $version\n")
            append("Filename: $fileName\n")
            append("Blocksize: $blockSize\n")
            append("Length: ${data.size}\n")
            append("Hash-Lengths: $sequenceMatches,$weakBytes,$strongBytes\n")
            append("URL: $url\n")
            append("SHA-1: ${hex(MessageDigest.getInstance("SHA-1").digest(data))}\n")
            append("\n")
        }
        out.write(header.toByteArray(Charsets.ISO_8859_1))
        val md4 = Md4()
        var start = 0
        while (start < data.size) {
            val block = ByteArray(blockSize)
            System.arraycopy(data, start, block, 0, minOf(blockSize, data.size - start))
            val weak = rollingChecksum(block, 0, blockSize)
            val full = byteArrayOf((weak ushr 24).toByte(), (weak ushr 16).toByte(), (weak ushr 8).toByte(), weak.toByte())
            out.write(full, 4 - weakBytes, weakBytes)
            md4.update(block)
            out.write(md4.digest(), 0, strongBytes)
            start += blockSize
        }
        return out.toByteArray()
    }

    /** [control] without its MTime line, which only says when the source file last changed. */
    fun withoutMTime(control: ByteArray): ByteArray {
        val text = String(control, Charsets.ISO_8859_1)
        return text.replace(Regex("""MTime: [^\n]*\n"""), "").toByteArray(Charsets.ISO_8859_1)
    }

    /** Builds [control]'s target from [seed] alone, with [remote] for what the seed lacks; the bytes and how many came from [remote]. */
    fun rebuild(control: ZsyncControl, seed: ByteArray, remote: ByteArray): Pair<ByteArray, Long> {
        val file = kotlin.io.path.createTempFile("zsync-seed", ".bin").toFile()
        try {
            file.writeBytes(seed)
            val sources = scanSeed(control, file)
            val out = ByteArray(control.length.toInt())
            var fetched = 0L
            for (block in sources.indices) {
                val start = control.blockStart(block).toInt()
                val length = control.blockLength(block)
                when (val from = sources[block]) {
                    BlockSource.MISSING -> { System.arraycopy(remote, start, out, start, length); fetched += length }
                    BlockSource.ZERO -> Unit
                    else -> for (i in 0 until length) out[start + i] = seed.getOrElse((from + i).toInt()) { 0 }
                }
            }
            return out to fetched
        } finally {
            file.delete()
        }
    }

    fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }
}
