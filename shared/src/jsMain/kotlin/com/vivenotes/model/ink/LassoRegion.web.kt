package com.vivenotes.model.ink

internal actual fun inkLassoCloses(path: List<InkSample>): Boolean = path.size >= 4 &&
    path.all { it.x.isFinite() && it.y.isFinite() } &&
    kotlin.math.hypot(path.first().x - path.last().x, path.first().y - path.last().y) <= 4f
