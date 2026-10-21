package com.vivenotes.performance

import com.vivenotes.byteink.kit.ViveInkCodec
import com.vivenotes.byteink.kit.hasGeometry
import com.vivenotes.ink.desktopGeometry
import com.vivenotes.ink.toByteInk
import com.vivenotes.ink.withDesktopEdit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class InkPerformanceCasesTest {
    @Test
    fun appendFixturesAreCanonicalDeterministicAndKeepTheirStartingSnapshots() {
        val fixture = performanceInkAppendFixture(4)
        val repeated = performanceInkAppendFixture(4)
        assertEquals(fixture.fingerprint127, repeated.fingerprint127)
        assertEquals(fixture.fingerprint128, repeated.fingerprint128)
        assertNotEquals(fixture.fingerprint127, fixture.fingerprint128)
        assertTrue(fixture.fingerprint127.matches(Regex("[0-9a-f]{64}")))
        val ordinary = fixture.before127.withDesktopEdit(fixture.append127)
        val boundary = fixture.before128.withDesktopEdit(fixture.append128)
        assertEquals(4, fixture.initial.strokes.size)
        assertEquals(130, fixture.before127.strokes.size)
        assertEquals(131, fixture.before128.strokes.size)
        assertEquals(131, ordinary.strokes.size)
        assertEquals(132, boundary.strokes.size)
        assertEquals(fixture.before128.strokes.map { it.id }, ordinary.strokes.map { it.id })
        assertEquals(fixture.before128.strokes.map { it.id } + fixture.append128.row.id,
            boundary.strokes.map { it.id })
        assertEquals(132, boundary.desktopGeometry().projections.size)
        val canonical = assertNotNull(ViveInkCodec.decode(fixture.append128.row.toByteInk()))
        val projected = boundary.desktopGeometry().projections.single { it.id == fixture.append128.row.id }
        assertTrue(projected.stroke.hasGeometry)
        assertEquals(canonical.inputs.size, projected.stroke.inputs.size)
        repeat(canonical.inputs.size) { index ->
            assertEquals(canonical.inputs[index].x, projected.stroke.inputs[index].x)
            assertEquals(canonical.inputs[index].y, projected.stroke.inputs[index].y)
        }
        assertFailsWith<IllegalArgumentException> { performanceInkAppendFixture(0) }
    }
}
