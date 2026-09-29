package com.vivenotes.desktop

import kotlin.test.Test
import kotlin.test.assertEquals

class DesktopBuildInfoTest {
    @Test
    fun packagedBuildVersionIsAvailableToTheApp() {
        assertEquals("0.1.0", DesktopBuildInfo.version)
    }
}
