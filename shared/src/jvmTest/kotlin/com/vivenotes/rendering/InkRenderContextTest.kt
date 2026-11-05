package com.vivenotes.rendering

import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import kotlin.test.*

class InkRenderContextTest {
    @Test fun nestedFramesAndDrawingFailuresRestoreTheCallersCapability() {
        assertFalse(InkRenderContext.canReplay)
        InkRenderContext.openGpuFrame().use {
            assertTrue(InkRenderContext.canReplay)
            assertFailsWith<IllegalArgumentException> {
                InkRenderContext.openGpuFrame().use { throw IllegalArgumentException("draw failure") }
            }
            assertTrue(InkRenderContext.canReplay)
        }
        assertFalse(InkRenderContext.canReplay)
    }

    @Test fun otherThreadsCannotInheritOrCloseAnAcceleratedFrame() {
        val scope = InkRenderContext.openGpuFrame()
        try {
            val check = FutureTask {
                assertFalse(InkRenderContext.canReplay)
                assertFailsWith<IllegalStateException> { scope.close() }
            }
            Thread(check, "ink-frame-isolation-test").start()
            check.get(5, TimeUnit.SECONDS)
            assertTrue(InkRenderContext.canReplay)
        } finally { scope.close() }
        assertFalse(InkRenderContext.canReplay)
    }

    @Test fun outOfOrderAndRepeatedCloseCannotLeakAnAcceleratedFrame() {
        val outer = InkRenderContext.openGpuFrame()
        val inner = InkRenderContext.openGpuFrame()
        assertFailsWith<IllegalStateException> { outer.close() }
        assertTrue(InkRenderContext.canReplay)
        inner.close()
        assertTrue(InkRenderContext.canReplay)
        outer.close()
        assertFalse(InkRenderContext.canReplay)
        assertFailsWith<IllegalStateException> { inner.close() }
        assertFalse(InkRenderContext.canReplay)
    }
}
