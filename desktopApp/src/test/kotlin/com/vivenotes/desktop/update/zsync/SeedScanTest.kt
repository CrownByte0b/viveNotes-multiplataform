package com.vivenotes.desktop.update.zsync

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Which blocks of a new file an old copy provides, the way an update meets it: the old copy with
 * bytes inserted, removed, changed, cut short or added to. Every rebuild must equal the new file.
 */
class SeedScanTest {
    private val sample = ZsyncFixtures.sample()
    private val sampleControl = ZsyncControl.parse(ZsyncFixtures.resource("sample.bin.zsync"))

    @Test
    fun anIdenticalCopyProvidesEveryBlock() {
        for ((control, data) in listOf(sampleControl to sample,
            ZsyncControl.parse(ZsyncFixtures.resource("large.bin.zsync")) to ZsyncFixtures.large())) {
            val (rebuilt, fetched) = ZsyncFixtures.rebuild(control, data, data)
            assertContentEquals(data, rebuilt)
            assertEquals(0, fetched, control.fileName)
        }
    }

    @Test
    fun bytesInsertedOrRemovedOnlyCostTheBlocksAroundThem() {
        val inserted = sample.copyOfRange(0, 250_003) + ZsyncFixtures.xorshift(77, 999) + sample.copyOfRange(250_003, sample.size)
        val removed = sample.copyOfRange(0, 60_001) + sample.copyOfRange(61_234, sample.size)
        for ((name, seed) in listOf("inserted" to inserted, "removed" to removed)) {
            val (rebuilt, fetched) = ZsyncFixtures.rebuild(sampleControl, seed, sample)
            assertContentEquals(sample, rebuilt, name)
            assertTrue(fetched in 1..3 * 2048L, "$name: fetched $fetched bytes")
        }
    }

    @Test
    fun scatteredChangesCostOnlyTheirOwnBlocks() {
        val seed = sample.copyOf()
        val changed = listOf(5L, 70_000, 99_999, 200_000, 300_016)
        for (at in changed) seed[at.toInt()] = (seed[at.toInt()] + 1).toByte()
        val (rebuilt, fetched) = ZsyncFixtures.rebuild(sampleControl, seed, sample)
        assertContentEquals(sample, rebuilt)
        // Each changed byte spoils its own block; the last one is the short last block.
        assertEquals(4 * 2048L + 1_009, fetched)
    }

    @Test
    fun aCopyCutShortOrGrownLongerStillProvidesWhatItShares() {
        val (fromShort, fetchedShort) = ZsyncFixtures.rebuild(sampleControl, sample.copyOf(200_000), sample)
        assertContentEquals(sample, fromShort)
        assertTrue(fetchedShort in 100_017L..100_017L + 2048, "fetched $fetchedShort")

        // The short last block matches when the copy ends where the new file does, and costs one block otherwise.
        val (fromLonger, fetchedLonger) = ZsyncFixtures.rebuild(sampleControl, sample + ByteArray(5_000) { 7 }, sample)
        assertContentEquals(sample, fromLonger)
        assertEquals(1_009L, fetchedLonger)
    }

    @Test
    fun zeroBlocksNeedNoCopyAndRepeatedBlocksAreAllFilled() {
        val (rebuilt, fetched) = ZsyncFixtures.rebuild(sampleControl, ByteArray(0), sample)
        assertContentEquals(sample, rebuilt)
        val zeroBlocks = (0 until sampleControl.blocks).count { block ->
            val start = sampleControl.blockStart(block).toInt()
            (start until start + sampleControl.blockLength(block)).all { sample[it] == 0.toByte() }
        }
        assertTrue(zeroBlocks >= 9)
        assertEquals(sample.size - zeroBlocks * 2048L, fetched, "everything but the zero blocks comes from the network")

        // The repeated phrase repeats every 16 bytes, so one copy of a block in the seed fills all its repeats.
        val phrase = sample.copyOfRange(120_000, 120_000 + 4096)
        val (fromPhrase, phraseFetched) = ZsyncFixtures.rebuild(sampleControl, phrase, sample)
        assertContentEquals(sample, fromPhrase)
        assertTrue(phraseFetched < sample.size - zeroBlocks * 2048L - 40_000, "fetched $phraseFetched")
    }

    /**
     * zsync's pairs mode, for large files, only accepts two blocks that match in a row. A changed
     * block and inserted bytes cost one block each; a lone unchanged block between two changes cannot
     * be confirmed and is downloaded too, as zsync's own client does.
     */
    @Test
    fun pairsModeCostsOnlyTheChangedBlocks() {
        val large = ZsyncFixtures.large()
        val control = ZsyncControl.parse(ZsyncFixtures.resource("large.bin.zsync"))
        fun changed(vararg blocks: Int) = large.copyOf().also { seed ->
            for (block in blocks) seed[block * 65536 + 5] = (seed[block * 65536 + 5] + 1).toByte()
        }
        fun shifted(seed: ByteArray) = seed.copyOfRange(0, 3_000_001) + byteArrayOf(1, 2, 3) + seed.copyOfRange(3_000_001, seed.size)

        val (rebuilt, fetched) = ZsyncFixtures.rebuild(control, shifted(changed(10)), large)
        assertContentEquals(large, rebuilt)
        assertEquals(2 * 65536L, fetched, "block 10 and the block holding the inserted bytes")

        val (alone, aloneFetched) = ZsyncFixtures.rebuild(control, changed(10, 12), large)
        assertContentEquals(large, alone)
        assertEquals(3 * 65536L, aloneFetched, "blocks 10 and 12, and 11 between them")
    }

    /** Every hash length zsync allows, in both modes; hash collisions would show as a wrong rebuild. */
    @Test
    fun everyHashLengthRebuildsTheFile() {
        val seed = sample.copyOfRange(0, 150_000) + ZsyncFixtures.xorshift(5, 3_333) + sample.copyOfRange(150_000, sample.size)
        for (sequence in 1..2) for (weak in 1..4) for (strong in listOf(3, 4, 8, 16)) {
            val control = ZsyncControl.parse(ZsyncFixtures.zsyncmake(sample, 1024, sequence, weak, strong))
            val (rebuilt, fetched) = ZsyncFixtures.rebuild(control, seed, sample)
            assertContentEquals(sample, rebuilt, "$sequence,$weak,$strong")
            assertTrue(fetched <= 4 * 1024L, "$sequence,$weak,$strong fetched $fetched")
        }
    }

    @Test
    fun missingBlocksBecomeFewRangesWithSmallGapsMerged() {
        val control = ZsyncControl.parse(ZsyncFixtures.zsyncmake(ByteArray(100 * 1024) { (it / 7).toByte() }, 1024, 1, 4, 8))
        val sources = LongArray(control.blocks) { it.toLong() * 1024 }
        for (block in listOf(3, 4, 5, 9, 60, 99)) sources[block] = BlockSource.MISSING
        assertEquals(listOf(ByteRange(3 * 1024L, 6 * 1024L), ByteRange(9 * 1024L, 10 * 1024L),
            ByteRange(60 * 1024L, 61 * 1024L), ByteRange(99 * 1024L, 100 * 1024L)), missingRanges(control, sources, mergeGap = 0))
        assertEquals(listOf(ByteRange(3 * 1024L, 10 * 1024L), ByteRange(60 * 1024L, 61 * 1024L), ByteRange(99 * 1024L, 100 * 1024L)),
            missingRanges(control, sources, mergeGap = 3 * 1024))
        assertEquals(control.length - 6 * 1024, reusedBytes(control, sources))
    }
}
