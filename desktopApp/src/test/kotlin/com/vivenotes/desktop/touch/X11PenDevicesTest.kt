package com.vivenotes.desktop.touch

import androidx.compose.ui.input.pointer.PointerType
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.*

class X11PenDevicesTest {
    private fun fixture(name: String = "Wacom Pen stylus", kind: Int = 3, touch: Boolean = false, absolute: Boolean = true): ByteArray {
        val label = name.encodeToByteArray()
        val start = 32 + 12 + (label.size + 3) / 4 * 4
        return ByteArray(start + 44 + if (touch) 8 else 0).also { bytes ->
            val b = ByteBuffer.wrap(bytes).order(ByteOrder.nativeOrder())
            b.putShort(8, 1)
            b.putShort(32, 12); b.putShort(34, kind.toShort()); b.putShort(38, if (touch) 2 else 1)
            b.putShort(40, label.size.toShort()); label.copyInto(bytes, 44)
            b.putShort(start, 2); b.putShort(start + 2, 11); b.putShort(start + 4, 12)
            b.putShort(start + 6, 2); b.putInt(start + 8, 99)
            b.putInt(start + 12, 100); b.putInt(start + 20, 1100); b.putInt(start + 28, 100)
            b.put(start + 40, if (absolute) 1 else 0)
            if (touch) { b.putShort(start + 44, 8); b.putShort(start + 46, 2) }
        }
    }

    @Test fun onlyAbsoluteSlavePensAreSelectedAndTheEraserTipKeepsItsIdentity() {
        fun decode(name: String, kind: Int = 3, touch: Boolean = false, absolute: Boolean = true) =
            x11PenDevices(fixture(name, kind, touch, absolute)) { "Abs Pressure" }
        assertEquals(PointerType.Stylus, decode("Wacom Pen stylus").single().type)
        assertEquals(PointerType.Eraser, decode("Wacom Pen eraser").single().type)
        assertTrue(decode("Virtual core pointer", kind = 1).isEmpty(), "never select the master mouse")
        assertTrue(decode("Touchscreen", touch = true).isEmpty())
        assertTrue(decode("Mouse", absolute = false).isEmpty())
    }

    @Test fun sparsePressureValuatorsUseTheDeviceRangeAndRetainTheLastPressure() {
        val device = x11PenDevices(fixture()) { "Abs Pressure" }.single()
        val event = ByteBuffer.allocate(108).order(ByteOrder.nativeOrder())
        event.putShort(52, 1); event.putShort(54, 1)
        event.putInt(88, (1 shl 0) or (1 shl 2))
        event.putInt(92, 123); event.putInt(100, 600)
        assertEquals(.5f, device.observe(event), .0001f)
        event.putInt(88, 0)
        assertEquals(.5f, device.observe(event), .0001f)
        event.putInt(88, 1 shl 2); event.putInt(92, 1100)
        assertEquals(1f, device.observe(event), .0001f)
    }

    @Test fun aPenWithoutAPressureAxisNeverInventsMeasuredPressure() {
        val device = x11PenDevices(fixture()) { "Abs X" }.single()
        assertTrue(device.observe(ByteBuffer.allocate(84)).isNaN())
    }
}
