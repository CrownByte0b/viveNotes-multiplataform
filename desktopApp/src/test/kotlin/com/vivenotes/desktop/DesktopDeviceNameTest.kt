package com.vivenotes.desktop

import kotlin.test.Test
import kotlin.test.assertEquals

class DesktopDeviceNameTest {
    private fun deviceName(environment: Map<String, String>) = desktopDeviceName(environment::get)

    @Test
    fun computerNameThenHostNameThenTheDisplayFallbackAreUsed() {
        assertEquals("Work PC", deviceName(mapOf("COMPUTERNAME" to "Work PC", "HOSTNAME" to "host")))
        assertEquals("host", deviceName(mapOf("HOSTNAME" to "host")))
        assertEquals("Desktop", deviceName(emptyMap()))
        assertEquals("Desktop", deviceName(mapOf("COMPUTERNAME" to " ", "HOSTNAME" to "host")))
    }

    @Test
    fun namesAreTrimmedAndFitTheServerDeviceNameLimit() {
        assertEquals("Work PC", deviceName(mapOf("HOSTNAME" to "  Work PC  ")))
        assertEquals("x".repeat(128), deviceName(mapOf("HOSTNAME" to "x".repeat(140))))
    }
}
