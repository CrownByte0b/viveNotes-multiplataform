package com.vivenotes.data.onenote

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class OneNoteConverterTest {
    @Test
    fun nativeResourcesMatchTheJvmPlatformAndArchitecture() {
        for (arch in listOf("amd64", "x86_64")) {
            assertEquals("/onenote/native/linux-x86_64/libvive_converter.so", OneNoteConverter.nativeResource("Linux", arch))
            assertEquals("/onenote/native/windows-x86_64/vive_converter.dll", OneNoteConverter.nativeResource("Windows 11", arch))
        }
        for (arch in listOf("arm64", "aarch64")) {
            assertEquals("/onenote/native/linux-aarch64/libvive_converter.so", OneNoteConverter.nativeResource("Linux", arch))
            assertEquals("/onenote/native/windows-aarch64/vive_converter.dll", OneNoteConverter.nativeResource("Windows 11", arch))
        }
        assertNull(OneNoteConverter.nativeResource("Mac OS X", "aarch64"))
        assertNull(OneNoteConverter.nativeResource("Linux", "x86"))
    }

    @Test
    fun conversionCountsAndOmissionsAreReadWithoutRejectingNewReportFields() {
        assertEquals(OneNoteConversion(2, 3, 4, 1, listOf("omitted an embedded file")),
            OneNoteConverter.decodeReport("""{"sections":2,"pages":3,"strokes":4,"attachments":1,
                "warnings":["omitted an embedded file"],"futureField":true}"""))
        assertEquals(OneNoteConversion(0, 0, 0, 0, emptyList()), OneNoteConverter.decodeReport("{}"))
    }

    @Test
    fun converterErrorsKeepTheirDistinctReasonsAndDetails() {
        val reasons = mapOf(
            "tableOfContents" to OneNoteConversionException.Reason.TableOfContents,
            "unreadable" to OneNoteConversionException.Reason.Unreadable,
            "noSections" to OneNoteConversionException.Reason.NoSections,
            "failed" to OneNoteConversionException.Reason.Failed,
            "futureError" to OneNoteConversionException.Reason.Failed,
        )
        for ((code, reason) in reasons) {
            val failure = assertFailsWith<OneNoteConversionException> {
                OneNoteConverter.decodeReport("""{"error":"$code","detail":"explanation"}""")
            }
            assertEquals(reason, failure.reason)
            assertEquals("explanation", failure.message)
        }
    }
}
