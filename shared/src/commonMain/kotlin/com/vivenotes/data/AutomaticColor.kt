package com.vivenotes.data

fun automaticColorOr(stored: Int, followsTheme: Boolean?, canvasInk: Int): Int = when {
    followsTheme == true -> canvasInk
    followsTheme == false -> stored
    stored == 0xffffffff.toInt() || stored == 0xff000000.toInt() -> canvasInk
    else -> stored
}
