package com.vivenotes.data

import com.vivenotes.data.db.*
import com.vivenotes.ink.InkCodec
import com.vivenotes.ink.InkFixtures
import com.vivenotes.ink.InkPageReader
import com.vivenotes.ink.desktopGeometry
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class LoadedInkCacheTest {
    @Test
    fun everyStoredStrokeFieldParticipatesInReuse() {
        val row = row()
        val original = snapshot(listOf(row))
        assertTrue(original.hasPrefix(snapshot(listOf(row.copy(points = row.points.copyOf())))))
        val changes = listOf(row.copy(id = "other"), row.copy(pageId = "other"), row.copy(seq = 9),
            row.copy(brushFamily = "marker"), row.copy(brushVersion = 2), row.copy(sizeDp = 9f),
            row.copy(colorArgb = 123), row.copy(colorFollowsTheme = true), row.copy(epsilon = 0.2f),
            row.copy(stabilization = 3), row.copy(minX = 1f), row.copy(minY = 1f), row.copy(maxX = 99f),
            row.copy(maxY = 99f), row.copy(points = byteArrayOf(0)), row.copy(enc = "future"),
            row.copy(createdAt = 2), row.copy(groupId = "group"), row.copy(deletedAt = 3))
        for ((index, changed) in changes.withIndex())
            assertFalse(snapshot(listOf(changed)).hasPrefix(original), "field mutation $index")
    }

    @Test
    fun everyOperationFieldAndTargetParticipatesEvenWithIdenticalIdsAndBlobs() {
        val e = InkEraseEntity("erase", "page", sizeDp = 5f, points = InkFixtures.twoPointStroke, enc = InkCodec.ENCODING, createdAt = 1)
        val et = listOf(InkEraseTargetEntity(e.id, "stroke"))
        val baseErase = snapshot(erases = listOf(InkEraseWithTargets(e, et)))
        for (changed in listOf(e.copy(id = "new"), e.copy(pageId = "new"), e.copy(mode = EraserMode.Object),
            e.copy(sizeDp = 8f), e.copy(points = byteArrayOf()), e.copy(enc = "future"), e.copy(createdAt = 2), e.copy(deletedAt = 3)))
            assertFalse(baseErase.sameOperations(snapshot(erases = listOf(InkEraseWithTargets(changed, et)))))
        assertFalse(baseErase.sameOperations(snapshot(erases = listOf(InkEraseWithTargets(e, emptyList())))))
        val m = InkMoveEntity("move", "page", 1f, 2f, points = byteArrayOf(1), enc = InkCodec.MOVE_ENCODING, createdAt = 3)
        val mt = listOf(InkMoveTargetEntity(m.id, "stroke"))
        val baseMove = snapshot(moves = listOf(InkMoveWithTargets(m, mt)))
        for (changed in listOf(m.copy(id = "new"), m.copy(pageId = "new"), m.copy(dxDp = 9f), m.copy(dyDp = 9f),
            m.copy(scaleX = 2f), m.copy(scaleY = 2f), m.copy(anchorX = 9f), m.copy(anchorY = 9f),
            m.copy(points = byteArrayOf(2)), m.copy(enc = "future"), m.copy(createdAt = 4), m.copy(deletedAt = 5)))
            assertFalse(baseMove.sameOperations(snapshot(moves = listOf(InkMoveWithTargets(changed, mt)))))
        assertFalse(baseMove.sameOperations(snapshot(moves = listOf(InkMoveWithTargets(m, emptyList())))))
    }

    @Test
    fun freshReadsReuseIdentityAndSafeAppendsKeepThePreparedBase() = runTest {
        val cache = LoadedInkCache(StandardTestDispatcher(testScheduler))
        var reads = 0
        suspend fun load(rows: List<InkStrokeEntity>) = cache.load { reads++; snapshot(rows) }
        val first = load(listOf(row()))
        assertSame(first, load(listOf(row())))
        val appended = load(listOf(row(), row("next", 1)))
        assertSame(first.desktopGeometry().base, appended.desktopGeometry().base)
        assertEquals(listOf("stroke", "next"), appended.strokes.map { it.id })
        assertEquals(1, cache.fullLoads); assertEquals(1, cache.prefixLoads); assertEquals(1, cache.hits)
        assertEquals(3, reads)
        val canonical = InkPageReader.read("page", listOf(row(), row("next", 1)), emptyList(), emptyList())
        assertEquals(canonical.desktopGeometry().projections.map { it.pageBounds }, appended.desktopGeometry().projections.map { it.pageBounds })
    }

    @Test
    fun anOldEraseNamingAnArrivingStrokeRequiresCanonicalReplay() = runTest {
        val cache = LoadedInkCache(StandardTestDispatcher(testScheduler))
        val erase = InkEraseWithTargets(InkEraseEntity("erase", "page", sizeDp = 30f,
            points = InkFixtures.twoPointStroke, enc = InkCodec.ENCODING, createdAt = 1),
            listOf(InkEraseTargetEntity("erase", "next")))
        cache.load { snapshot(listOf(row()), erases = listOf(erase)) }
        val rows = listOf(row(), row("next", 1))
        val page = cache.load { snapshot(rows, erases = listOf(erase)) }
        val reference = InkPageReader.read("page", rows, listOf(erase), emptyList())
        assertEquals(reference.desktopGeometry().projections.map { it.id to it.pageBounds },
            page.desktopGeometry().projections.map { it.id to it.pageBounds })
        assertEquals(2, cache.fullLoads); assertEquals(0, cache.prefixLoads)
        val move = InkMoveWithTargets(InkMoveEntity("move", "page", 4f, 2f, points = byteArrayOf(), enc = "unknown", createdAt = 1),
            listOf(InkMoveTargetEntity("move", "next")))
        assertFalse(snapshot(rows, moves = listOf(move)).canAppend(snapshot(listOf(row()), moves = listOf(move))))
    }

    @Test
    fun operationClockChangesKeepGeometryButAdvanceTheAuthoringClock() = runTest {
        val cache = LoadedInkCache(StandardTestDispatcher(testScheduler))
        val first = cache.load { snapshot() }
        val second = cache.load { snapshot(clock = 400) }
        assertSame(first.geometry, second.geometry)
        assertEquals(400, second.latestOperationAt)
    }

    @Test
    fun pageCountAndEveryWeightBudgetEvictOrRejectRetention() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val cache = LoadedInkCache(dispatcher, maxPages = 2)
        suspend fun load(page: String) = cache.load { snapshot(listOf(row().copy(pageId = page)), page = page) }
        val a = load("a"); load("b"); assertSame(a, load("a")); load("c")
        assertEquals(2, cache.retainedPages)
        load("b"); assertEquals(4, cache.fullLoads)
        for (bounded in listOf(LoadedInkCache(dispatcher, maxRows = 0), LoadedInkCache(dispatcher, maxSourceWeight = 0),
            LoadedInkCache(dispatcher, maxSamples = 0), LoadedInkCache(dispatcher, maxProjections = 0), LoadedInkCache(dispatcher, maxPages = 0))) {
            bounded.load { snapshot() }; bounded.load { snapshot() }
            assertEquals(0, bounded.retainedPages); assertEquals(2, bounded.fullLoads)
        }
        val sharedBudget = LoadedInkCache(dispatcher, maxRows = 1)
        sharedBudget.load { snapshot(page = "a", rows = listOf(row().copy(pageId = "a"))) }
        sharedBudget.load { snapshot(page = "b", rows = listOf(row().copy(pageId = "b"))) }
        assertEquals(1, sharedBudget.retainedPages)
    }

    @Test
    fun ownerChangesAndUnavailablePagesNeverReuseAnOlderOwner() = runTest {
        val cache = LoadedInkCache(StandardTestDispatcher(testScheduler))
        val first = cache.load { snapshot() }
        assertNotSame(first, cache.load { snapshot(owner = InkCacheOwner("another", "book")) })
        assertNotSame(first, cache.load { snapshot() })
        cache.load { snapshot(owner = null) }
        assertEquals(0, cache.retainedPages)
        cache.close()
        assertFailsWith<IllegalStateException> { cache.load { snapshot() } }
    }

    @Test
    fun aFortyThousandStrokeVisitKeepsItsNativeBaseAndBothPreparedScenesOnAppendAndReturn() = runTest {
        val cache = LoadedInkCache(StandardTestDispatcher(testScheduler))
        val template = row()
        val rows = (0 until 40_000).map { template.copy(id = "stroke-$it", seq = it) }
        val first = cache.load { snapshot(rows) }
        val scene = first.desktopGeometry().base.sceneData(0xff1b1b1b.toInt())
        cache.load { snapshot(emptyList(), page = "other") }
        val appended = cache.load { snapshot(rows + template.copy(id = "new", seq = 40_000)) }
        assertSame(first.desktopGeometry().base, appended.desktopGeometry().base)
        assertSame(scene, appended.desktopGeometry().base.sceneData(0xff1b1b1b.toInt()))
        assertSame(appended, cache.load { snapshot(rows + template.copy(id = "new", seq = 40_000)) })
        assertEquals(40_001, appended.desktopGeometry().projections.size)
        assertEquals(2, cache.fullLoads); assertEquals(1, cache.prefixLoads); assertEquals(1, cache.hits)
        cache.close(); assertEquals(0, cache.retainedPages)
    }

    @Test
    fun cancelledReadsAndCloseDuringReadCannotPublishOrRetain() = runTest {
        val cache = LoadedInkCache(StandardTestDispatcher(testScheduler))
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val job = launch { cache.load { entered.complete(Unit); release.await(); snapshot() } }
        entered.await(); job.cancelAndJoin()
        assertEquals(0, cache.retainedPages)
        val secondEntered = CompletableDeferred<Unit>()
        val late = async { runCatching { cache.load { secondEntered.complete(Unit); release.await(); snapshot() } } }
        secondEntered.await(); cache.close(); release.complete(Unit)
        assertIs<IllegalStateException>(late.await().exceptionOrNull())
        assertEquals(0, cache.retainedPages)
    }

    companion object {
        internal fun row(id: String = "stroke", seq: Int = 0) = InkStrokeEntity(id, "page", seq,
            "pressure-pen", 1, 8f, 0xffcc0000.toInt(), false, 0.1f, 1, 0f, 0f, 40f, 50f,
            InkFixtures.twoPointStroke, InkCodec.ENCODING, 1)
        private fun snapshot(rows: List<InkStrokeEntity> = listOf(row()), page: String = "page",
            owner: InkCacheOwner? = InkCacheOwner(null, "book"),
            erases: List<InkEraseWithTargets> = emptyList(), moves: List<InkMoveWithTargets> = emptyList(), clock: Long = 0) =
            StoredInkSnapshot(page, owner, rows, erases, moves, clock)
    }
}
