package com.vivenotes.model.ink

import com.vivenotes.byteink.kit.InkPoint
import com.vivenotes.byteink.kit.closesIntoALoop

internal actual fun inkLassoCloses(path: List<InkSample>): Boolean =
    path.all { it.x.isFinite() && it.y.isFinite() } && path.map { InkPoint(it.x, it.y) }.closesIntoALoop(4f)
