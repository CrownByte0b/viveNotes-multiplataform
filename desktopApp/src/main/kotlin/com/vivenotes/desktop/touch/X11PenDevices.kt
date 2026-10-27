package com.vivenotes.desktop.touch

import androidx.compose.ui.input.pointer.PointerType
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** XInput 2 wire values, shared by discovery and the pressure event decoder. */
internal class X11PenDevice(val id: Int, val type: PointerType, val pressureAxis: Int?,
    val minimum: Double, val maximum: Double, var pressure: Double) {
    fun observe(event: ByteBuffer): Float {
        val axis = pressureAxis ?: return Float.NaN
        val buttons = event.getShort(52).toInt() and 0xffff
        val words = event.getShort(54).toInt() and 0xffff
        val mask = 84 + buttons * 4
        var values = mask + words * 4
        for (bit in 0 until words * 32) {
            if (event.get(mask + bit / 8).toInt() and (1 shl (bit % 8)) == 0) continue
            if (bit == axis) pressure = event.fixed32(values)
            values += 8
        }
        return if (maximum > minimum) ((pressure - minimum) / (maximum - minimum)).toFloat().coerceIn(0f, 1f) else Float.NaN
    }
}

/** Slave pen devices only: selecting a master pointer would take mouse buttons away from AWT. */
internal fun x11PenDevices(bytes: ByteArray, atomName: (Int) -> String): List<X11PenDevice> {
    val data = ByteBuffer.wrap(bytes).order(ByteOrder.nativeOrder())
    val count = data.getShort(8).toInt() and 0xffff
    var offset = 32
    return buildList {
        repeat(count) {
            val id = data.getShort(offset).toInt() and 0xffff
            val kind = data.getShort(offset + 2).toInt() and 0xffff
            val classes = data.getShort(offset + 6).toInt() and 0xffff
            val length = data.getShort(offset + 8).toInt() and 0xffff
            val name = bytes.decodeToString(offset + 12, offset + 12 + length).lowercase()
            offset += 12 + (length + 3) / 4 * 4
            var absolute = false
            var touch = false
            var pressure: Int? = null
            var min = 0.0
            var max = 0.0
            var value = 0.0
            repeat(classes) {
                val type = data.getShort(offset).toInt() and 0xffff
                val size = (data.getShort(offset + 2).toInt() and 0xffff) * 4
                require(size >= 8) { "Invalid XInput device class" }
                if (type == 8) touch = true
                if (type == 2 && size >= 44) {
                    if (data.get(offset + 40).toInt() == 1) absolute = true
                    if (atomName(data.getInt(offset + 8)) == "Abs Pressure") {
                        pressure = data.getShort(offset + 6).toInt() and 0xffff
                        min = data.fixed32(offset + 12)
                        max = data.fixed32(offset + 20)
                        value = data.fixed32(offset + 28)
                    }
                }
                offset += size
            }
            if (kind in setOf(3, 5) && absolute && !touch &&
                (pressure != null || "stylus" in name || "pen" in name || "eraser" in name)) {
                add(X11PenDevice(id, if ("eraser" in name) PointerType.Eraser else PointerType.Stylus,
                    pressure, min, max, value))
            }
        }
    }
}

private fun ByteBuffer.fixed32(offset: Int): Double = getInt(offset).toDouble() +
    Integer.toUnsignedLong(getInt(offset + 4)) / 4294967296.0
