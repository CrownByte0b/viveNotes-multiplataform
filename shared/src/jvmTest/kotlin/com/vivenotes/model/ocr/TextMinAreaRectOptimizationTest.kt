package com.vivenotes.model.ocr

import com.vivenotes.model.ocr.TextDetection.Point
import com.vivenotes.model.ocr.TextDetection.Rect
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TextMinAreaRectOptimizationTest {
    @Test
    fun largeHullSupportsPreserveEveryOriginalFloatCornerAndEarliestAreaTie() {
        val random = Random(9301)
        repeat(180) { fixture ->
            val size = listOf(128, 192, 256, 384)[fixture % 4]
            val phase = random.nextDouble() * Math.PI
            val offset = if (fixture % 3 == 0) 1_000_000f else -17.25f
            val hull = List(size) { index ->
                val angle = index * 2.0 * Math.PI / size + phase
                Point(offset + (900.0 * cos(angle)).toFloat(), offset + (93.0 * sin(angle)).toFloat())
            }
            val reordered = when (fixture % 3) {
                0 -> hull
                1 -> hull.reversed()
                else -> hull.drop(size / 3) + hull.take(size / 3)
            }
            assertTrue(HullSupportTree.supports(reordered))
            assertEquals(exhaustive(reordered), TextDetection.minAreaRect(reordered), "fixture $fixture")
        }
        val collinearEdges = buildList {
            repeat(32) { add(Point(it.toFloat(), -0f)) }
            repeat(32) { add(Point(32f, it.toFloat())) }
            repeat(32) { add(Point(32f - it, 32f)) }
            repeat(32) { add(Point(0f, 32f - it)) }
        }
        assertEquals(exhaustive(collinearEdges), TextDetection.minAreaRect(collinearEdges))
    }

    @Test
    fun conservativeSupportsAlsoKeepUnorderedPointsAndSignedZeroExtremes() {
        val random = Random(9123)
        repeat(100) {
            val points = List(160) { Point(random.nextInt(-960, 961).toFloat(), random.nextInt(-960, 961).toFloat()) }
            assertEquals(exhaustive(points), TextDetection.minAreaRect(points))
        }
        val zeros = List(160) { index ->
            Point(if (index % 2 == 0) -0f else 0f, (index % 7).toFloat())
        }
        assertEquals(exhaustive(zeros), TextDetection.minAreaRect(zeros))
    }

    @Test
    fun supportBoundsKeepRawFloatExtremaAtFiniteGateAndUnderflowBoundaries() {
        val limit = Float.MAX_VALUE / 8f
        val coordinates = listOf(-limit, limit, -0f, 0f, -Float.MIN_VALUE,
            Float.MIN_VALUE, -16_777_216f, 16_777_216f)
        val points = List(160) { index ->
            Point(coordinates[index % coordinates.size], coordinates[(index * 3 + index / 8) % coordinates.size])
        }
        val directions = listOf(Point(1f, 0f), Point(-1f, 0f), Point(0f, 1f), Point(0f, -1f),
            Point(.6f, .8f), Point(-.6f, .8f), Point(.6f, -.8f), Point(-.6f, -.8f),
            Point(-0f, -0f), Point(Float.MIN_VALUE, -Float.MIN_VALUE))
        for (shift in listOf(0, 13, 67)) {
            val ordered = points.drop(shift) + points.take(shift)
            assertTrue(HullSupportTree.supports(ordered))
            val tree = HullSupportTree(ordered)
            for (direction in directions + directions.reversed()) {
                var minAlong = Float.MAX_VALUE
                var maxAlong = -Float.MAX_VALUE
                var minAcross = Float.MAX_VALUE
                var maxAcross = -Float.MAX_VALUE
                for (point in ordered) {
                    val along = point.x * direction.x + point.y * direction.y
                    val across = -point.x * direction.y + point.y * direction.x
                    minAlong = min(minAlong, along)
                    maxAlong = max(maxAlong, along)
                    minAcross = min(minAcross, across)
                    maxAcross = max(maxAcross, across)
                }
                tree.project(direction.x, direction.y)
                val context = "shift=$shift direction=$direction"
                assertEquals(minAlong.toRawBits(), tree.minAlong.toRawBits(), context)
                assertEquals(maxAlong.toRawBits(), tree.maxAlong.toRawBits(), context)
                assertEquals(minAcross.toRawBits(), tree.minAcross.toRawBits(), context)
                assertEquals(maxAcross.toRawBits(), tree.maxAcross.toRawBits(), context)
            }
        }
    }

    @Test
    fun equalAreaCandidatesKeepFirstNondegenerateEdgeOrientation() {
        val square = buildList {
            repeat(32) { add(Point(it.toFloat(), 0f)) }
            repeat(32) { add(Point(32f, it.toFloat())) }
            repeat(32) { add(Point(32f - it, 32f)) }
            repeat(32) { add(Point(0f, 32f - it)) }
        }
        for (ordered in listOf(square, square.reversed(), square.drop(40) + square.take(40))) {
            // The duplicate first point must be skipped without changing the earliest area tie.
            val withDuplicate = listOf(ordered.first()) + ordered
            assertEquals(exhaustive(withDuplicate), TextDetection.minAreaRect(withDuplicate))
        }
        assertEquals(Point(0f, 0f), TextDetection.minAreaRect(square).corners.first())
        assertEquals(Point(32f, 0f), TextDetection.minAreaRect(square.reversed()).corners.first())
    }

    @Test
    fun nonFiniteOverflowAndDegenerateInputsRetainExhaustiveBehavior() {
        for (special in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.MAX_VALUE)) {
            val points = List(160) { index -> Point(if (index == 0) special else index.toFloat(), index % 13f) }
            assertEquals(exhaustive(points), TextDetection.minAreaRect(points))
        }
        for (points in listOf(listOf(Point(-0f, 2f)), listOf(Point(-0f, 2f), Point(10f, 3f)),
            List(160) { Point(1f, 1f) })) {
            assertEquals(exhaustive(points), TextDetection.minAreaRect(points))
        }
        assertFailsWith<NoSuchElementException> { TextDetection.minAreaRect(emptyList()) }
    }

    @Test
    fun supportReuseSkipsMostVertexProjectionsOnLargeConvexBoundaries() {
        val points = List(512) { index ->
            val angle = index * 2.0 * Math.PI / 512
            Point((480 + 440 * cos(angle)).toFloat(), (480 + 140 * sin(angle)).toFloat())
        }
        var projected = 0
        val tree = HullSupportTree(points) { projected += it }
        for (index in points.indices) {
            val from = points[index]
            val to = points[(index + 1) % points.size]
            val length = from.distanceTo(to)
            val ux = (to.x - from.x) / length
            val uy = (to.y - from.y) / length
            tree.project(ux, uy)
            val along = points.map { it.x * ux + it.y * uy }
            val across = points.map { -it.x * uy + it.y * ux }
            assertEquals(along.min().toRawBits(), tree.minAlong.toRawBits())
            assertEquals(along.max().toRawBits(), tree.maxAlong.toRawBits())
            assertEquals(across.min().toRawBits(), tree.minAcross.toRawBits())
            assertEquals(across.max().toRawBits(), tree.maxAcross.toRawBits())
        }
        assertTrue(projected < points.size * points.size / 2,
            "$projected point projections should be below the exhaustive ${points.size * points.size}")
    }

    /** Frozen original candidate order/arithmetic, intentionally independent of support acceleration. */
    private fun exhaustive(hull: List<Point>): Rect {
        if (hull.size < 3) {
            val left = hull.minOf { it.x }
            val right = hull.maxOf { it.x }
            val top = hull.minOf { it.y }
            val bottom = hull.maxOf { it.y }
            return Rect(listOf(Point(left, top), Point(right, top), Point(right, bottom), Point(left, bottom)),
                min(right - left, bottom - top), max(right - left, bottom - top))
        }
        var best: Rect? = null
        var bestArea = Float.MAX_VALUE
        for (index in hull.indices) {
            val start = hull[index]
            val end = hull[(index + 1) % hull.size]
            val length = start.distanceTo(end)
            if (length < 1e-6f) continue
            val ux = (end.x - start.x) / length
            val uy = (end.y - start.y) / length
            var minAlong = Float.MAX_VALUE
            var maxAlong = -Float.MAX_VALUE
            var minAcross = Float.MAX_VALUE
            var maxAcross = -Float.MAX_VALUE
            for (point in hull) {
                val along = point.x * ux + point.y * uy
                val across = -point.x * uy + point.y * ux
                minAlong = min(minAlong, along)
                maxAlong = max(maxAlong, along)
                minAcross = min(minAcross, across)
                maxAcross = max(maxAcross, across)
            }
            val spanAlong = maxAlong - minAlong
            val spanAcross = maxAcross - minAcross
            val area = spanAlong * spanAcross
            if (area >= bestArea) continue
            bestArea = area
            fun unrotate(along: Float, across: Float) = Point(along * ux - across * uy, along * uy + across * ux)
            best = Rect(listOf(unrotate(minAlong, minAcross), unrotate(maxAlong, minAcross),
                unrotate(maxAlong, maxAcross), unrotate(minAlong, maxAcross)),
                min(spanAlong, spanAcross), max(spanAlong, spanAcross))
        }
        return best ?: Rect(hull.take(4), 0f, 0f)
    }
}
