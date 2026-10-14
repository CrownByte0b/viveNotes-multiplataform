package com.vivenotes.model.ink

import com.vivenotes.workspace.RulerKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.hypot

class AndroidRulerTest {

    private fun straight(
        centerX: Float = 500f,
        centerY: Float = 400f,
        angle: Float = 0f,
        size: Float = 800f,
    ) = Ruler(centerX, centerY, angle, RulerKind.Straight, size)

    private fun protractor(
        centerX: Float = 500f,
        centerY: Float = 400f,
        angle: Float = 0f,
        size: Float = 800f,
    ) = Ruler(centerX, centerY, angle, RulerKind.Protractor, size)

    private fun assertPoint(message: String, expected: RulerPoint, actual: RulerPoint) {
        assertEquals("$message x", expected.x, actual.x, 0.01f)
        assertEquals("$message y", expected.y, actual.y, 0.01f)
    }

    @Test
    fun `local and page coordinates are inverses at any angle`() {
        val ruler = straight(angle = 0.7f)
        val point = RulerPoint(613f, 122f)

        assertPoint("round trip", point, ruler.toPage(ruler.toLocal(point)))
    }

    private fun Ruler.snapFrom(point: RulerPoint) = snap(point, sideOf(point))

    @Test
    fun `a stroke above the ruler lands on its upper edge`() {
        val ruler = straight()
        val snapped = ruler.snapFrom(RulerPoint(560f, 400f - Ruler.BAND_DP / 2f - 30f))

        assertPoint("upper edge", RulerPoint(560f, 400f - Ruler.BAND_DP / 2f), snapped)
    }

    @Test
    fun `a stroke below the ruler lands on its lower edge`() {
        val ruler = straight()
        val snapped = ruler.snapFrom(RulerPoint(560f, 400f + Ruler.BAND_DP / 2f + 30f))

        assertPoint("lower edge", RulerPoint(560f, 400f + Ruler.BAND_DP / 2f), snapped)
    }

    @Test
    fun `a stroke crossing the ruler stays on the edge it started from`() {
        val ruler = straight()
        val start = RulerPoint(400f, 400f - Ruler.BAND_DP / 2f - 10f)
        val side = ruler.sideOf(start)

        assertEquals("started above it", RulerSide.Negative, side)
        val crossed = ruler.snap(RulerPoint(600f, 400f + Ruler.BAND_DP / 2f + 10f), side)

        assertPoint("still the upper edge", RulerPoint(600f, 400f - Ruler.BAND_DP / 2f), crossed)
        assertEquals(
            "and the sample on its own would have said otherwise",
            RulerSide.Positive,
            ruler.sideOf(RulerPoint(600f, 400f + Ruler.BAND_DP / 2f + 10f)),
        )
    }

    @Test
    fun `a ruler runs out, so the ends clamp`() {
        val ruler = straight()
        val past = ruler.snapFrom(RulerPoint(5000f, 300f))

        assertEquals("clamped to the + end", 500f + 400f, past.x, 0.01f)
        assertEquals(400f - Ruler.BAND_DP / 2f, past.y, 0.01f)
    }

    @Test
    fun `a rotated ruler snaps across its own axis, not the page's`() {
        val ruler = straight(angle = (PI / 2).toFloat())

        val snapped = ruler.snapFrom(RulerPoint(500f + 40f, 400f + 200f))

        assertEquals("stayed at its distance along", 400f + 200f, snapped.y, 0.01f)
        assertEquals("pulled onto the near edge", 500f + Ruler.BAND_DP / 2f, snapped.x, 0.01f)
    }

    @Test
    fun `every snapped point on one side is collinear`() {
        val ruler = straight(angle = 0.4f)
        val edge = (0..10).map { step ->
            val wobble = if (step % 2 == 0) 22f else -14f
            val point = ruler.toPage(RulerPoint(-300f + step * 60f, Ruler.BAND_DP / 2f + 20f + wobble))
            ruler.snap(point, RulerSide.Positive)
        }

        val first = edge.first()
        val last = edge.last()
        val length = hypot(last.x - first.x, last.y - first.y)
        edge.forEach { point ->
            val cross = (last.x - first.x) * (point.y - first.y) -
                (last.y - first.y) * (point.x - first.x)
            assertEquals("$point is off the line", 0f, cross / length, 0.01f)
        }
    }

    @Test
    fun `the body is grabbed, the space past the edge is not`() {
        val ruler = straight()

        val half = Ruler.BAND_DP / 2f

        assertTrue("centre", ruler.grabs(RulerPoint(500f, 400f)))
        assertTrue("inside the band", ruler.grabs(RulerPoint(700f, 400f + half - 2f)))
        assertFalse("past the edge", ruler.grabs(RulerPoint(700f, 400f + half + 2f)))
        assertFalse("past the end", ruler.grabs(RulerPoint(500f + 420f, 400f)))
    }

    @Test
    fun `a stroke engages on the ruler and just past it, but not away from it`() {
        val ruler = straight()
        val edge = 400f + Ruler.BAND_DP / 2f

        assertTrue("on it", ruler.engages(RulerPoint(500f, 400f), Ruler.SNAP_TOLERANCE_DP))
        assertTrue("just past", ruler.engages(RulerPoint(500f, edge + 10f), Ruler.SNAP_TOLERANCE_DP))
        assertFalse(
            "well away",
            ruler.engages(RulerPoint(500f, edge + Ruler.SNAP_TOLERANCE_DP + 5f), Ruler.SNAP_TOLERANCE_DP),
        )
    }

    @Test
    fun `turning is about its own centre, and does not move it`() {
        val ruler = straight()

        val turned = ruler.turnedBy((PI / 2).toFloat())

        assertEquals("the centre is the pivot", 500f, turned.centerX, 0.01f)
        assertEquals(400f, turned.centerY, 0.01f)
        assertPoint("the + end swung round", RulerPoint(500f, 800f), turned.toPage(RulerPoint(400f, 0f)))
    }

    @Test
    fun `successive turns accumulate`() {
        val ruler = straight()

        val turned = ruler.turnedBy(0.3f).turnedBy(0.4f).turnedBy(-0.1f)

        assertEquals(0.6f, turned.angleRadians, 0.001f)
    }

    @Test
    fun `the dial reads anticlockwise from the ruler lying flat`() {
        assertEquals(0, straight().degrees())
        assertEquals("tipped down to the right", 315, straight(angle = (PI / 4).toFloat()).degrees())
        assertEquals("tipped up to the right", 45, straight(angle = (-PI / 4).toFloat()).degrees())
        assertEquals(270, straight(angle = (PI / 2).toFloat()).degrees())
        assertEquals(90, straight(angle = (-PI / 2).toFloat()).degrees())
        assertEquals(315, straight(angle = (PI / 4 + 2 * PI).toFloat()).degrees())
    }

    @Test
    fun `tapping the dial steps a clean eighth of a turn`() {
        var ruler = straight()
        listOf(45, 90, 135, 180, 225, 270, 315, 0).forEach { expected ->
            ruler = ruler.turnedToNextEighth()
            assertEquals(expected, ruler.degrees())
        }
    }

    @Test
    fun `tapping the dial from a free angle lands on the next eighth`() {
        assertEquals(45, straight(angle = -0.23f).turnedToNextEighth().degrees())
        assertEquals("57 degrees should tidy to 90", 90, straight(angle = -1.0f).turnedToNextEighth().degrees())
    }

    @Test
    fun `the dial is a target in the middle, and the rest of the body is not`() {
        val ruler = straight()

        assertTrue("dead centre", ruler.grabsDial(RulerPoint(500f, 400f)))
        assertTrue("just off centre", ruler.grabsDial(RulerPoint(500f + Ruler.DIAL_RADIUS_DP, 400f)))
        assertFalse("out along the ruler", ruler.grabsDial(RulerPoint(700f, 400f)))
        assertTrue("but that is still the body", ruler.grabs(RulerPoint(700f, 400f)))
    }

    @Test
    fun `the semicircle's dial sits inside the disc, not on its flat edge`() {
        val ruler = protractor()
        val dial = ruler.dialCenter()

        assertEquals("on the centre line", 0f, dial.x, 0.01f)
        assertTrue("up inside the drawing half", dial.y < -Ruler.DIAL_RADIUS_DP)
        assertTrue("and clear of the arc", -dial.y < ruler.reach - Ruler.DIAL_RADIUS_DP)

        val onPage = ruler.toPage(dial)
        assertTrue("the dial is a target there", ruler.grabsDial(onPage))
        assertFalse("and the flat edge's centre is not", ruler.grabsDial(RulerPoint(500f, 400f)))
        assertTrue("but it is still somewhere you can take hold", ruler.grabs(onPage))
    }

    @Test
    fun `the semicircle's dial follows a rotated protractor`() {
        val ruler = protractor(angle = (PI / 2).toFloat())

        assertTrue(ruler.grabsDial(ruler.toPage(ruler.dialCenter())))
    }

    @Test
    fun `the dial follows a rotated ruler`() {
        val ruler = straight(angle = 1.1f)

        assertTrue(ruler.grabsDial(RulerPoint(500f, 400f)))
    }

    @Test
    fun `the semicircle snaps onto its arc at the radius`() {
        val ruler = protractor()
        val snapped = ruler.snapFrom(RulerPoint(600f, 300f))

        assertEquals("on the arc", 400f, hypot(snapped.x - 500f, snapped.y - 400f), 0.01f)
        assertTrue("stayed on the drawing side", snapped.y <= 400f)
    }

    @Test
    fun `the arc is reached along the line from the centre`() {
        val ruler = protractor()
        val snapped = ruler.snapFrom(RulerPoint(500f, 100f))

        assertPoint("straight up", RulerPoint(500f, 0f), snapped)
    }

    @Test
    fun `below the flat edge clamps to the end the stroke set out from`() {
        val ruler = protractor()

        assertPoint("+ end", RulerPoint(900f, 400f), ruler.snapFrom(RulerPoint(700f, 600f)))
        assertPoint("- end", RulerPoint(100f, 400f), ruler.snapFrom(RulerPoint(300f, 600f)))

        val fromThePlusEnd = ruler.sideOf(RulerPoint(700f, 300f))
        assertEquals(RulerSide.Positive, fromThePlusEnd)
        assertPoint(
            "stayed at the end it came from",
            RulerPoint(900f, 400f),
            ruler.snap(RulerPoint(300f, 600f), fromThePlusEnd),
        )
    }

    @Test
    fun `the exact centre is not a division by zero`() {
        val snapped = protractor().snapFrom(RulerPoint(500f, 400f))

        assertEquals(400f, hypot(snapped.x - 500f, snapped.y - 400f), 0.01f)
    }

    @Test
    fun `the semicircle is only the half it draws`() {
        val ruler = protractor()

        assertTrue("above the flat edge", ruler.grabs(RulerPoint(500f, 300f)))
        assertFalse("below it", ruler.grabs(RulerPoint(500f, 500f)))
        assertFalse("outside the arc", ruler.grabs(RulerPoint(500f, -100f)))
    }

    @Test
    fun `a rotated semicircle carries its flat edge with it`() {
        val ruler = protractor(angle = (PI / 2).toFloat())

        assertTrue("the drawing half followed", ruler.grabs(RulerPoint(700f, 400f)))
        assertFalse("the empty half followed", ruler.grabs(RulerPoint(300f, 400f)))
    }
}