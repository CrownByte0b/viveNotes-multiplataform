package com.vivenotes.data

import kotlin.test.Test
import kotlin.test.assertEquals

class AutomaticColorTest {
    @Test
    fun automaticFollowsEveryCanvasWhileExplicitColorsKeepTheirIntent() {
        for (canvas in listOf(0xff1b1b1b.toInt(), 0xffe6e6e6.toInt(), 0xff123456.toInt())) {
            for (stored in listOf(0xff000000.toInt(), 0xffffffff.toInt(), 0xffe53935.toInt(), 0x66ffffff)) {
                assertEquals(canvas, automaticColorOr(stored, true, canvas))
                assertEquals(stored, automaticColorOr(stored, false, canvas))
                val inferred = if (stored == 0xff000000.toInt() || stored == 0xffffffff.toInt()) canvas else stored
                assertEquals(inferred, automaticColorOr(stored, null, canvas))
            }
        }
    }
}
