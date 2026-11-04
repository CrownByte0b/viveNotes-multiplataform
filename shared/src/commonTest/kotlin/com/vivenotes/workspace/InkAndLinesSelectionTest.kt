package com.vivenotes.workspace

import com.vivenotes.model.Block
import com.vivenotes.model.Outline
import com.vivenotes.model.PageDoc
import com.vivenotes.model.ink.InkPage
import com.vivenotes.model.ink.InkSelection
import com.vivenotes.model.ink.InkSelectionBounds
import com.vivenotes.model.ink.InkSelectionChange
import com.vivenotes.model.ink.ShapeKind
import com.vivenotes.model.ink.seedSegments
import com.vivenotes.model.newTable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What the ink Math toolkit acts on: Android's `InkAndLinesTest` against the desktop's selection
 * state. The gate widened from ink alone to ink plus the shapes that are marks (a fraction bar drawn
 * with the Line tool), so these pin the edge of that widening: a rectangle let through would be
 * drawn into the recognition image and read as part of an equation.
 */
class InkAndLinesSelectionTest {
    private class HeldInk(override val page: InkPage, override val targetIds: Set<String>) : InkSelection {
        override val bounds = InkSelectionBounds(0f, 0f, 100f, 100f)
        override fun move(dx: Float, dy: Float): InkSelectionChange? = null
        override fun delete(): InkSelectionChange? = null
    }

    private fun shape(id: String, kind: ShapeKind, y: Float = 0f): Outline.Shape {
        var next = 0
        return Outline.Shape(id = id, kind = kind,
            segments = seedSegments(kind, 0f, y, 80f, y + 40f) { "$id-${next++}" }).withRecomputedBounds()
    }

    private val outlines = listOf(
        shape("line", ShapeKind.Line), shape("arrow", ShapeKind.Arrow, 50f),
        shape("box", ShapeKind.Rectangle), shape("ell", ShapeKind.L), shape("unheld", ShapeKind.Line, 200f),
        newTable(2, 2).copy(id = "table"), Outline.Equation("equation", 0f, 0f, 80f, 40f, "x"),
        Outline.Image("image", 0f, 0f, 80f, attachmentId = "attachment", height = 60f),
        Outline.Text(id = "text", blocks = listOf(Block.of("note"))),
    )

    private fun selection(ink: Set<String>? = setOf("s1"), objects: Set<String> = emptySet(),
                          texts: Set<String> = emptySet()): WorkspaceState {
        val base = WorkspaceState.demo()
        val page = InkPage(base.selectedPageId, emptyList())
        return base.updatePage(base.selectedPageId) { it.copy(document = PageDoc(outlines = outlines), ink = page) }
            .copy(selectedInk = ink?.let { HeldInk(page, it) }, selectedObjectIds = objects, selectedTextOutlineIds = texts)
    }

    @Test
    fun inkAloneStillQualifies() {
        val state = selection()
        assertTrue(state.inkAndLinesSelection)
        assertTrue(state.inkOnlySelection)
        assertEquals(emptyList(), state.selectedFormulaLines)
    }

    @Test
    fun inkWithALineOrAnArrowQualifiesAndHandsOnlyThoseToRecognition() {
        assertTrue(selection(objects = setOf("line")).inkAndLinesSelection)
        assertTrue(selection(objects = setOf("arrow")).inkAndLinesSelection)
        val both = selection(objects = setOf("arrow", "line"))
        assertTrue(both.inkAndLinesSelection)
        assertFalse(both.inkOnlySelection, "grouping stays ink-only")
        assertEquals(listOf("line", "arrow"), both.selectedFormulaLines.map { it.id }, "page order, unheld lines left out")
    }

    @Test
    fun aShapeThatIsNotAMarkDoesNot() {
        assertFalse(selection(objects = setOf("box")).inkAndLinesSelection)
        assertFalse(selection(objects = setOf("ell")).inkAndLinesSelection, "the test is the kind, not whether it is open")
        assertFalse(selection(objects = setOf("line", "box")).inkAndLinesSelection, "one bad shape spoils it")
    }

    @Test
    fun linesWithoutInkAreADiagramNotAFormula() {
        assertFalse(selection(ink = null, objects = setOf("line")).inkAndLinesSelection)
        assertFalse(selection(ink = emptySet(), objects = setOf("line")).inkAndLinesSelection)
    }

    @Test
    fun everyOtherKindStillDisqualifiesTheSelection() {
        for (id in listOf("table", "equation", "image")) assertFalse(selection(objects = setOf(id)).inkAndLinesSelection, id)
        assertFalse(selection(objects = setOf("line"), texts = setOf("text")).inkAndLinesSelection, "text box")
    }

    @Test
    fun aShapeIdThePageNoLongerHasFailsRatherThanBeingSkipped() {
        assertFalse(selection(objects = setOf("line", "deleted")).inkAndLinesSelection)
    }

    @Test
    fun anEmptySelectionIsNotAFormula() {
        assertFalse(selection(ink = null).inkAndLinesSelection)
        assertFalse(selection(ink = emptySet()).inkAndLinesSelection)
    }
}
