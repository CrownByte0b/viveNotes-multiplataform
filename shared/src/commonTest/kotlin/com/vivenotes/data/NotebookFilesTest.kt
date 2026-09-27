package com.vivenotes.data

import kotlin.test.Test
import kotlin.test.assertEquals

/** Android's `viveFileName`: the name a notebook is offered under in the save dialog. */
class NotebookFilesTest {

    @Test
    fun aPlainNameKeepsItsSpacesAndGainsTheExtension() {
        assertEquals("Computer architecture.vive", viveFileName("Computer architecture"))
    }

    @Test
    fun charactersNoPlatformAllowsInAFileNameAreReplaced() {
        assertEquals("a_b_c_d_e_f_g_h_i_j.vive", viveFileName("a\\b/c:d*e?f\"g<h>i|j"))
        assertEquals("tab_new_line.vive", viveFileName("tab\tnew\nline"))
    }

    /**
     * Spaces are trimmed first and dots after, as Android does: a trailing dot is not a legal
     * Windows file name. A space the dots leave behind stays — Android's rule, kept exactly.
     */
    @Test
    fun surroundingSpacesThenDotsAreTrimmed() {
        assertEquals("Notes.vive", viveFileName("  Notes  "))
        assertEquals("Notes.vive", viveFileName("...Notes..."))
        assertEquals("Computer architecture .vive", viveFileName("Computer architecture ."))
    }

    @Test
    fun aLongNameIsCutToAHundredCharacters() {
        assertEquals("x".repeat(100) + ".vive", viveFileName("x".repeat(300)))
    }

    @Test
    fun aNameWithNothingLeftBecomesNotebook() {
        assertEquals("Notebook.vive", viveFileName(""))
        assertEquals("Notebook.vive", viveFileName(" . "))
    }
}
