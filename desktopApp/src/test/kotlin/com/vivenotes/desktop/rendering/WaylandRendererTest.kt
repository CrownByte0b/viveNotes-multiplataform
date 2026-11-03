package com.vivenotes.desktop.rendering

import org.junit.Assume.assumeTrue
import kotlin.test.*

class WaylandRendererTest {
    @Test fun physicalBuffersCoverFractionalAndIntegerScales() {
        assertEquals(WaylandRenderer.Pixels(300, 200), WaylandRenderer.Pixels.of(300, 200, 1.0))
        assertEquals(WaylandRenderer.Pixels(452, 302), WaylandRenderer.Pixels.of(301, 201, 1.5))
        assertEquals(WaylandRenderer.Pixels(602, 402), WaylandRenderer.Pixels.of(301, 201, 2.0))
        assertEquals(WaylandRenderer.Pixels(0, 0), WaylandRenderer.Pixels.of(0, 0, 1.5))
    }

    @Test fun invalidOrOverflowingGeometryIsRejectedBeforeNativeAllocation() {
        for (scale in listOf(0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY)) {
            assertFailsWith<IllegalArgumentException> { WaylandRenderer.Pixels.of(300, 200, scale) }
        }
        assertFailsWith<IllegalArgumentException> { WaylandRenderer.Pixels.of(-1, 200, 1.0) }
        assertFailsWith<IllegalArgumentException> { WaylandRenderer.Pixels.of(300, -1, 1.0) }
        assertFailsWith<IllegalArgumentException> { WaylandRenderer.Pixels.of(Int.MAX_VALUE, 200, 2.0) }
    }

    @Test fun explicitSoftwareAndOtherApisKeepTheirRequestedRenderer() {
        assertTrue(WaylandRendering.accelerationRequested(null))
        assertTrue(WaylandRendering.accelerationRequested("OPENGL"))
        assertTrue(WaylandRendering.accelerationRequested("opengl"))
        for (api in listOf("SOFTWARE", "SOFTWARE_FAST", "SOFTWARE_COMPAT", "VULKAN", "DIRECT3D", "METAL")) {
            assertFalse(WaylandRendering.accelerationRequested(api), api)
        }
    }

    @Test fun skikoProvidesTheExpectedRendererFactoryAndClippedDelegate() {
        WaylandRendering.checkAdapterContract()
    }

    @Test fun linuxPackageLoadsItsJniBridgeAndResolvesEglEntryPoints() {
        assumeTrue(System.getProperty("os.name").startsWith("Linux"))
        assertNotEquals(0L, WaylandEgl.getProcAddress())
    }
}
