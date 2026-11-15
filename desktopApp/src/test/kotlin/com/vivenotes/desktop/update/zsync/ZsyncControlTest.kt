package com.vivenotes.desktop.update.zsync

import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Control files as real zsyncmake writes them, and the ones a client must refuse. */
class ZsyncControlTest {
    @get:Rule val temp = TemporaryFolder()

    @Test
    fun zsyncmakesControlFilesAreRead() {
        val sample = ZsyncControl.parse(ZsyncFixtures.resource("sample.bin.zsync"))
        assertEquals("sample.bin", sample.fileName)
        assertEquals(300_017L, sample.length)
        assertEquals(2048, sample.blockSize)
        assertEquals(listOf(1, 3, 6), listOf(sample.sequenceMatches, sample.weakBytes, sample.strongBytes))
        assertEquals(147, sample.blocks)
        assertEquals(1_009, sample.blockLength(146), "the short last block")
        assertEquals(listOf("https://example.com/sample.bin"), sample.urls)
        assertEquals(sha1(ZsyncFixtures.sample()), sample.sha1)

        val large = ZsyncControl.parse(ZsyncFixtures.resource("large.bin.zsync"))
        assertEquals(listOf(2, 4, 4), listOf(large.sequenceMatches, large.weakBytes, large.strongBytes),
            "zsyncmake asks large files for two matching blocks in a row")
        assertEquals(397, large.blocks)
        assertEquals(sha1(ZsyncFixtures.large()), large.sha1)
    }

    /** The test writer is only trusted because it matches the real tool exactly. */
    @Test
    fun theTestWriterAgreesWithZsyncmakeByteForByte() {
        for ((name, data, block) in listOf(Triple("sample.bin", ZsyncFixtures.sample(), 2048),
            Triple("large.bin", ZsyncFixtures.large(), 65536))) {
            val real = ZsyncFixtures.resource("$name.zsync")
            val control = ZsyncControl.parse(real)
            val written = ZsyncFixtures.zsyncmake(data, block, control.sequenceMatches, control.weakBytes,
                control.strongBytes, fileName = name)
            assertContentEquals(ZsyncFixtures.withoutMTime(real), written, name)
        }
    }

    /** With zsyncmake on PATH (CI's Attach workflow installs it), more sizes and hash lengths are compared. */
    @Test
    fun theTestWriterAgreesWithTheInstalledZsyncmake() {
        val tool = System.getenv("PATH").orEmpty().split(File.pathSeparator).map { File(it, "zsyncmake") }
            .firstOrNull(File::canExecute)
        assumeTrue("zsyncmake is not installed", tool != null)
        for ((size, block) in listOf(1 to 2048, 2048 to 2048, 2049 to 2048, 40_000 to 512, 5_000_000 to 4096,
            3_000_000 to (1 shl 20))) {
            val data = ZsyncFixtures.xorshift(size.toLong(), size)
            val source = File(temp.root, "data-$size.bin").apply { writeBytes(data) }
            val output = File(temp.root, "data-$size.bin.zsync")
            val process = ProcessBuilder(tool!!.path, "-b", "$block", "-u", "https://example.com/${source.name}",
                "-f", source.name, "-o", output.path, source.path).redirectErrorStream(true).start()
            assertTrue(process.waitFor(60, TimeUnit.SECONDS))
            assertEquals(0, process.exitValue(), process.inputStream.bufferedReader().readText())
            val real = output.readBytes()
            val control = ZsyncControl.parse(real)
            val version = String(real, Charsets.ISO_8859_1).substringAfter("zsync: ").substringBefore('\n')
            assertContentEquals(ZsyncFixtures.withoutMTime(real), ZsyncFixtures.zsyncmake(data, block,
                control.sequenceMatches, control.weakBytes, control.strongBytes, source.name, version = version),
                "$size bytes in blocks of $block")
        }
    }

    @Test
    fun controlFilesTheClientCannotUseAreRefused() {
        val good = String(ZsyncFixtures.resource("sample.bin.zsync"), Charsets.ISO_8859_1)
        fun parse(text: String) = ZsyncControl.parse(text.toByteArray(Charsets.ISO_8859_1))
        val refused = mapOf(
            "no header end" to good.substringBefore("\n\n"),
            "not zsync" to good.replace("zsync: 0.6.3\n", ""),
            "a newer format" to good.replace("zsync: 0.6.3\n", "zsync: 0.6.3\nMin-Version: 0.7.0\n"),
            "compressed" to good.replace("URL: ", "Z-URL: "),
            "unknown header" to good.replace("URL: ", "X-Mirror: x\nURL: "),
            "block size not a power of two" to good.replace("Blocksize: 2048", "Blocksize: 2000"),
            "strong checksum too short" to good.replace("Hash-Lengths: 1,3,6", "Hash-Lengths: 1,3,2"),
            "three in a row" to good.replace("Hash-Lengths: 1,3,6", "Hash-Lengths: 3,3,6"),
            "no SHA-1" to good.replace(Regex("SHA-1: [0-9a-f]+\n"), ""),
            "table too short" to good.dropLast(1),
            "table too long" to good + "x",
            "length that disagrees with the table" to good.replace("Length: 300017", "Length: 400017"),
        )
        for ((reason, text) in refused) assertFailsWith<ZsyncUnavailable>(reason) { parse(text) }
        // A header the file marks as safe to ignore is ignored.
        val safe = parse(good.replace("URL: ", "Safe: X-Mirror\nX-Mirror: x\nURL: "))
        assertEquals(147, safe.blocks)
    }

    private fun sha1(data: ByteArray) = ZsyncFixtures.hex(java.security.MessageDigest.getInstance("SHA-1").digest(data))
}
