package com.vivenotes.localization

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class LocalizationTest {
    @AfterTest
    fun restoreDefaults() = Localization.reset()

    @Test
    fun englishDefaultsAreAvailableBeforePlatformInitialization() {
        assertEquals("Save", Strings.save)
        assertEquals("Version 0.1.0", Strings.version_value("0.1.0"))
        assertEquals("Coupon added 3 month(s) through tomorrow.",
            Strings.coupon_added_value_month_s_through_value(3, "tomorrow"))
    }

    @Test
    fun insertedValuesKeepUnicodeQuotesNewlinesAndPlaceholderLikeText() {
        val name = "A & B <note> \"quoted\"\n日本語 %2\$s \\folder"
        assertEquals("$name could not be renamed", Strings.value_could_not_be_renamed(name))
        assertEquals("Coupon added $name month(s) through %1\$s.",
            Strings.coupon_added_value_month_s_through_value(name, "%1\$s"))
    }

    @Test
    fun defaultsPreserveWhitespaceInSentenceFragmentsAndSampleNotes() {
        assertEquals("Now: Disabled. ", Strings.now_value(Strings.disabled))
        assertEquals("Anaphase I\n\nHomologous chromosomes separate and move to opposite poles.",
            Strings.anaphase_i_homologous_chromosomes_separate_and_move_to_opposite_poles)
    }

    @Test
    fun absentResourcesAndArgumentsFailClearly() {
        assertFailsWith<NoSuchElementException> { Localization.text("missing_resource") }
        assertFailsWith<IllegalArgumentException> {
            Localization.text("coupon_added_value_month_s_through_value", 3)
        }
    }
}
