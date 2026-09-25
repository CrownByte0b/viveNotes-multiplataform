package com.vivenotes.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class WaylandMainTest {
    @Test
    fun defaultsToNativeWaylandOnLinuxWaylandSession() {
        assertEquals(
            WindowBackend.NATIVE_WAYLAND,
            selectWindowBackend(emptyArray(), "Linux", "wayland", "wayland-1"),
        )
        assertEquals(
            WindowBackend.NATIVE_WAYLAND,
            selectWindowBackend(emptyArray(), "Linux", null, "wayland-1"),
        )
    }

    @Test
    fun x11FlagUsesStandardWindowOnWayland() {
        assertEquals(
            WindowBackend.STANDARD,
            selectWindowBackend(arrayOf("--x11"), "Linux", "wayland", "wayland-1"),
        )
    }

    @Test
    fun usesStandardWindowOutsideLinuxWayland() {
        assertEquals(WindowBackend.STANDARD, selectWindowBackend(emptyArray(), "Linux", "x11", null))
        assertEquals(WindowBackend.STANDARD, selectWindowBackend(emptyArray(), "Windows 11", null, null))
    }

    @Test
    fun rejectsUnknownOption() {
        assertFailsWith<IllegalArgumentException> {
            selectWindowBackend(arrayOf("--unknown"), "Linux", "wayland", "wayland-1")
        }
    }

    @Test
    fun acceptsNativeWaylandToolkit() {
        requireNativeWaylandToolkit("sun.awt.wl.WLToolkit")
    }

    @Test
    fun rejectsSilentXWaylandFallback() {
        val failure = assertFailsWith<IllegalStateException> {
            requireNativeWaylandToolkit("sun.awt.X11.XToolkit")
        }
        assertEquals(
            "Native Wayland needs JetBrains Runtime with WLToolkit; current toolkit is sun.awt.X11.XToolkit",
            failure.message,
        )
    }
}
