package com.vivenotes.workspace

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KeyBindingsTest {
    private val ctrlZ = KeyChord(ShortcutKey.Z, ctrl = true)
    private val ctrlR = KeyChord(ShortcutKey.R, ctrl = true)
    private val ctrlShiftZ = KeyChord(ShortcutKey.Z, ctrl = true, shift = true)
    private val ctrlK = KeyChord(ShortcutKey.K, ctrl = true)
    private val ctrlC = KeyChord(ShortcutKey.C, ctrl = true)

    /** One chord, one command: the table's defaults must never have to choose between two. */
    @Test
    fun defaultChordsAreDistinctAndUsable() {
        val all = ShortcutAction.entries.flatMap { it.defaults }
        assertEquals(all.size, all.toSet().size, "a default chord runs two commands")
        assertTrue(all.all { it.usable })
        assertTrue(ShortcutAction.entries.all { it.defaults.isNotEmpty() })
    }

    /** Android's table, plus the desktop's own redo and the clipboard, select-all and Select tool keys. */
    @Test
    fun defaultsMatchAndroidAndTheDesktop() {
        val shown = ShortcutAction.entries.associateWith { KeyBindings.Default.primary(it)?.label }
        assertEquals("Ctrl+N", shown[ShortcutAction.NewPage])
        assertEquals("Ctrl+Z", shown[ShortcutAction.Undo])
        assertEquals("Ctrl+R", shown[ShortcutAction.Redo])
        assertEquals("Ctrl+=", shown[ShortcutAction.ZoomIn])
        assertEquals("Ctrl+−", shown[ShortcutAction.ZoomOut])
        assertEquals("Ctrl+0", shown[ShortcutAction.ActualSize])
        assertEquals("Ctrl+B", shown[ShortcutAction.Bold])
        assertEquals("Tab", shown[ShortcutAction.Indent])
        assertEquals("Shift+Tab", shown[ShortcutAction.Outdent])
        assertEquals("Ctrl+Shift+V", shown[ShortcutAction.PastePlainText])
        assertEquals("Esc", shown[ShortcutAction.SelectTool])
        assertEquals("Delete", shown[ShortcutAction.DeleteSelection])
        assertEquals(ShortcutAction.Redo, KeyBindings.Default.actionFor(ctrlShiftZ))
        assertEquals(ShortcutAction.ZoomIn,
            KeyBindings.Default.actionFor(KeyChord(ShortcutKey.NumPadAdd, ctrl = true)))
        assertEquals(listOf("Pages", "Edit", "View", "Tools", "Formatting", "Paragraph"),
            ShortcutAction.groups.map { it.first })
    }

    @Test
    fun everyChordSurvivesItsStoredForm() {
        for (key in ShortcutKey.entries) {
            for (modifiers in 0 until 16) {
                val chord = KeyChord(key, ctrl = modifiers and 1 != 0, shift = modifiers and 2 != 0,
                    alt = modifiers and 4 != 0, meta = modifiers and 8 != 0)
                assertEquals(chord, KeyChord.decode(chord.encode()))
            }
        }
        assertEquals("Ctrl+Shift+Alt+Super+Equals",
            KeyChord(ShortcutKey.Equals, ctrl = true, shift = true, alt = true, meta = true).encode())
        listOf("", "Ctrl+", "Ctrl+Nope", "Hyper+A", "Ctrl+Ctrl+A", "ctrl+A", "=").forEach {
            assertNull(KeyChord.decode(it), "decoded \"$it\"")
        }
    }

    @Test
    fun onlyFunctionKeysTabEscAndDeleteStandAlone() {
        assertFalse(KeyChord(ShortcutKey.K).usable)
        assertFalse(KeyChord(ShortcutKey.K, shift = true).usable, "Shift+K types a capital")
        assertTrue(KeyChord(ShortcutKey.Delete).usable)
        assertTrue(KeyChord(ShortcutKey.K, alt = true).usable)
        assertTrue(KeyChord(ShortcutKey.K, meta = true).usable)
        assertTrue(KeyChord(ShortcutKey.F5).usable)
        assertTrue(KeyChord(ShortcutKey.F12, shift = true).usable)
        assertTrue(KeyChord(ShortcutKey.Tab).usable)
    }

    @Test
    fun rebindingTakesTheChordFromItsOwner() {
        val bindings = KeyBindings.Default.rebind(ShortcutAction.Undo, ctrlR)
        assertEquals(listOf(ctrlR), bindings.chords(ShortcutAction.Undo))
        // Redo keeps Android's Ctrl+Shift+Z, which is now what it shows.
        assertEquals(ctrlShiftZ, bindings.primary(ShortcutAction.Redo))
        assertTrue(bindings.isCustomized(ShortcutAction.Redo))
        // Ctrl+Z ran Undo alone, and now runs nothing.
        assertNull(bindings.actionFor(ctrlZ))
        assertEquals(ShortcutAction.Redo, KeyBindings.Default.conflict(ShortcutAction.Undo, ctrlR))
        assertNull(KeyBindings.Default.conflict(ShortcutAction.Redo, ctrlR))
        assertNull(KeyBindings.Default.conflict(ShortcutAction.Undo, ctrlK))
    }

    @Test
    fun aDisabledShortcutRunsNothingUntilReset() {
        val disabled = KeyBindings.Default.rebind(ShortcutAction.ZoomIn, null)
        assertNull(disabled.primary(ShortcutAction.ZoomIn))
        assertNull(disabled.actionFor(KeyChord(ShortcutKey.NumPadAdd, ctrl = true)))
        assertEquals(KeyBindings.Default, disabled.reset(ShortcutAction.ZoomIn))
    }

    @Test
    fun resetTakesTheDefaultsBackFromWhoeverHasThem() {
        val moved = KeyBindings.Default.rebind(ShortcutAction.Bold, ctrlZ)
        assertNull(moved.primary(ShortcutAction.Undo))
        val undoBack = moved.reset(ShortcutAction.Undo)
        assertEquals(ctrlZ, undoBack.primary(ShortcutAction.Undo))
        assertNull(undoBack.primary(ShortcutAction.Bold), "Bold loses the chord Undo took back")
        assertEquals(KeyBindings.Default, undoBack.reset(ShortcutAction.Bold))
        assertEquals(KeyBindings.Default, moved.resetAll())
        assertFalse(KeyBindings.Default.isCustomized)
    }

    @Test
    fun givingAShortcutItsOwnDefaultsIsNoChange() {
        val bindings = KeyBindings.Default.rebind(ShortcutAction.NewPage, KeyChord(ShortcutKey.N, ctrl = true))
        assertEquals(KeyBindings.Default, bindings)
        assertTrue(bindings.changes.isEmpty())
    }

    @Test
    fun storedChangesAreCleanedAsTheyAreRead() {
        val bindings = KeyBindings.of(mapOf(
            ShortcutAction.Undo to listOf(ctrlK),
            // Claimed twice: the later action in the table keeps it.
            ShortcutAction.Bold to listOf(ctrlK),
            ShortcutAction.Italic to listOf(KeyChord(ShortcutKey.I)),
            ShortcutAction.NewPage to listOf(KeyChord(ShortcutKey.N, ctrl = true)),
        ))
        assertEquals(ctrlK, bindings.primary(ShortcutAction.Bold))
        assertNull(bindings.primary(ShortcutAction.Undo))
        assertEquals(KeyChord(ShortcutKey.I, ctrl = true), bindings.primary(ShortcutAction.Italic),
            "a bare letter is not a shortcut, so that change is ignored")
        assertFalse(bindings.isCustomized(ShortcutAction.NewPage))
    }

    @Test
    fun eachDispatchPointRunsOnlyItsOwnCommands() {
        val bindings = KeyBindings.Default
        val esc = KeyChord(ShortcutKey.Escape)
        assertEquals(ShortcutDecision.Run(ShortcutAction.SelectTool), bindings.decide(esc, ShortcutScope.Anywhere))
        assertEquals(ShortcutDecision.Pass, bindings.decide(esc, ShortcutScope.Workspace))
        assertEquals(ShortcutDecision.Run(ShortcutAction.Undo), bindings.decide(ctrlZ, ShortcutScope.Workspace))
        assertEquals(ShortcutDecision.Run(ShortcutAction.DeleteSelection),
            bindings.decide(KeyChord(ShortcutKey.Delete), ShortcutScope.Workspace))
        assertEquals(ShortcutDecision.Pass,
            bindings.decide(KeyChord(ShortcutKey.Delete), ShortcutScope.TextBox))
        assertEquals(ShortcutDecision.Pass, bindings.decide(ctrlZ, ShortcutScope.Anywhere))
        // In a text box Ctrl+Z is the text's own undo, not the canvas's.
        assertEquals(ShortcutDecision.Pass, bindings.decide(ctrlZ, ShortcutScope.TextBox))
        assertEquals(ShortcutDecision.Run(ShortcutAction.Copy), bindings.decide(ctrlC, ShortcutScope.TextBox))
        assertEquals(ShortcutDecision.Pass, bindings.decide(ctrlC, ShortcutScope.Workspace))
        assertEquals(ShortcutDecision.Pass, bindings.decide(ctrlK, ShortcutScope.TextBox))
    }

    /** Regression guard: a moved text box command must not fall back to the text field's own copy. */
    @Test
    fun aTextBoxDefaultThatRunsNothingIsUsedUp() {
        val moved = KeyBindings.Default.rebind(ShortcutAction.Copy, KeyChord(ShortcutKey.C, ctrl = true, shift = true))
        assertEquals(ShortcutDecision.Swallow, moved.decide(ctrlC, ShortcutScope.TextBox))
        assertEquals(ShortcutDecision.Run(ShortcutAction.Copy),
            moved.decide(KeyChord(ShortcutKey.C, ctrl = true, shift = true), ShortcutScope.TextBox))
        assertEquals(ShortcutDecision.Pass, moved.decide(ctrlC, ShortcutScope.Workspace))
    }

    @Test
    fun aWorkspaceCommandOnATextBoxDefaultRunsInTheTextBox() {
        val zoomOnCopy = KeyBindings.Default.rebind(ShortcutAction.ZoomIn, ctrlC)
        assertEquals(ShortcutDecision.Run(ShortcutAction.ZoomIn), zoomOnCopy.decide(ctrlC, ShortcutScope.TextBox))
        assertEquals(ShortcutDecision.Run(ShortcutAction.ZoomIn), zoomOnCopy.decide(ctrlC, ShortcutScope.Workspace))
        // Elsewhere a workspace command waits for the text field to leave the key.
        val zoomOnK = KeyBindings.Default.rebind(ShortcutAction.ZoomIn, ctrlK)
        assertEquals(ShortcutDecision.Pass, zoomOnK.decide(ctrlK, ShortcutScope.TextBox))
    }

    @Test
    fun heldKeysRepeatOnlyCommandsThatShould() {
        val bindings = KeyBindings.Default
        val newPage = KeyChord(ShortcutKey.N, ctrl = true)
        val zoomIn = KeyChord(ShortcutKey.Equals, ctrl = true)
        assertEquals(ShortcutDecision.Swallow, bindings.decide(newPage, ShortcutScope.Workspace, repeat = true))
        assertEquals(ShortcutDecision.Run(ShortcutAction.NewPage), bindings.decide(newPage, ShortcutScope.Workspace))
        assertEquals(ShortcutDecision.Run(ShortcutAction.ZoomIn),
            bindings.decide(zoomIn, ShortcutScope.Workspace, repeat = true))
    }
}
