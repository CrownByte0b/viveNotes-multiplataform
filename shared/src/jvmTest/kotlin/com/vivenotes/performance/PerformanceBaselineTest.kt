package com.vivenotes.performance

import com.vivenotes.data.PageLoad
import com.vivenotes.model.JsonDocumentCodec
import com.vivenotes.model.search.FuzzyMatcher
import com.vivenotes.pdf.groupContent
import com.vivenotes.ui.canvas.previewProjection
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class PerformanceBaselineTest {
    @Test
    fun nearestRankPercentilesHandleEndpointsAndUnsortedSamples() {
        val values = listOf(4.0, 1.0, 3.0, 2.0)
        assertEquals(1.0, percentile(values, 0.0))
        assertEquals(2.0, percentile(values, 0.5))
        assertEquals(4.0, percentile(values, 0.95))
        assertEquals(4.0, percentile(values, 1.0))
        assertFailsWith<IllegalArgumentException> { percentile(emptyList(), 0.5) }
        assertFailsWith<IllegalArgumentException> { percentile(values, 1.1) }
        assertFailsWith<IllegalArgumentException> { percentile(listOf(Double.NaN), 0.5) }
    }

    @Test
    fun workloadFixturesAreDeterministicAndResolveActualTextAndCells() {
        val doc = performanceDocument(12)
        assertEquals(JsonDocumentCodec.encodeToString(doc), JsonDocumentCodec.encodeToString(performanceDocument(12)))
        val state = performanceWorkspace(doc)
        assertEquals(12, doc.outlines.count { state.richTextFor(it.id) != null })
        for (cells in listOf(20, 100, 200)) {
            val (table, ids) = performanceTable(cells)
            assertEquals(cells, ids.size)
            assertEquals(cells, ids.toSet().size)
            val tableState = performanceWorkspace(table)
            assertTrue(ids.all { tableState.richTextFor(it)!!.text == "cell text" })
            assertEquals(table, performanceTable(cells).first)
        }
    }

    @Test
    fun groupingSearchAndPreviewFixturesRetainExpectedSemantics() {
        val atoms = performanceAtoms(12)
        val groups = groupContent(atoms, 600f, 800f)
        assertEquals(atoms.map { "group:${it.id}" }.toSet(), groups.map { it.id }.toSet())
        assertTrue(groups.all { it.atoms.size == 1 && it.bounds == it.atoms.single().bounds })
        assertEquals(0, FuzzyMatcher.match("a", "a ".repeat(12))!!.start)
        val (source, ranges) = performancePreview(12)
        val shown = previewProjection(source, ranges)
        assertEquals(source.length + 12, shown.length)
        assertEquals(12, shown.paragraphStyles.size)
        assertTrue(shown.paragraphStyles.all { it.start < it.end && it.end <= shown.length })
    }

    @Test
    fun measurementsExecuteWorkAndPreserveRawSamples() {
        val runner = BaselineMeasurements(warmupNanos = 0, minWarmupOps = 2, samples = 3, targetBatchNanos = 1)
        var calls = 0
        runner.measure("fixture", 12, fingerprint("fixture"), fixedOps = 2) { ++calls }
        assertEquals(8, calls)
        val result = runner.results.single()
        assertEquals(3, result.getValue("sampleNanosPerOperation").jsonArray.size)
        assertEquals("2", result.getValue("operationsPerSample").jsonPrimitive.content)
        assertEquals(fingerprint("fixture"), result.getValue("fixtureSha256").jsonPrimitive.content)
        assertFailsWith<IllegalArgumentException> { BaselineMeasurements(samples = 0) }
    }

    @Test
    fun storageFixtureUsesTheRealRepositoryAndPreservesUnknownInkBytes() {
        withPerformanceDatabase { _, repository, page, advance ->
            val doc = performanceDocument(2)
            runBlocking {
                repository.saveDoc(page, doc)
                assertEquals(doc, assertIs<PageLoad.Loaded>(repository.loadDoc(page)).doc)
                val points = byteArrayOf(1, 2, 3)
                repository.addStroke(performanceInkRow("test-stroke", page, 0, points))
                advance()
                repository.saveDoc(page, performanceDocument(3))
                assertTrue(repository.inkFor(page).single().points.contentEquals(points))
                assertTrue(repository.revisionHistory(page).isNotEmpty())
            }
        }
    }
}
