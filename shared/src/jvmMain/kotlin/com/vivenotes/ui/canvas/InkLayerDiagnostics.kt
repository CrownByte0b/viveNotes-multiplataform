package com.vivenotes.ui.canvas

import androidx.compose.runtime.staticCompositionLocalOf

/** Opt-in application diagnostics; counters never participate in Compose invalidation. */
internal val LocalInkLayerDiagnostics = staticCompositionLocalOf<InkLayerDiagnostics?> { null }

internal data class InkLayerCounts(
    val scenes: Long,
    val draws: Long,
    val paths: Long,
    val finishedPaths: Long,
    val rasters: Long,
    val finishedStrokeDraws: Long,
    val shapeAdvances: Long,
    val observations: Long,
) {
    operator fun minus(before: InkLayerCounts) = InkLayerCounts(scenes - before.scenes,
        draws - before.draws, paths - before.paths, finishedPaths - before.finishedPaths, rasters - before.rasters,
        finishedStrokeDraws - before.finishedStrokeDraws, shapeAdvances - before.shapeAdvances,
        observations - before.observations)
}

internal class InkLayerDiagnostics {
    var baseSceneBuilds: Long = 0L
        private set
    var retainedPixelBytes: Long = 0L
        private set
    var peakRetainedPixelBytes: Long = 0L
        private set
    private var sceneBuilds = 0L
    private var draws = 0L
    private var paths = 0L
    private var finishedPaths = 0L
    private var rasters = 0L
    private var finishedStrokeDraws = 0L
    private var shapeAdvances = 0L
    private var observations = 0L
    private val pendingInputNanos = mutableListOf<Long>()
    val inputToDrawNanos = mutableListOf<Long>()

    fun sceneBuilt(base: Boolean = true) { sceneBuilds++; if (base) baseSceneBuilds++ }
    fun shapeAdvanced() { shapeAdvances++ }
    fun rasterReleased() { retainedPixelBytes = 0L }

    fun inputObserved(sampleCount: Int = 1) {
        observations += sampleCount
        pendingInputNanos += System.nanoTime()
    }

    fun drawn(pathBuildCount: Long, rasterBuildCount: Long, finishedDrawn: Int,
        finishedPathBuilds: Long, rasterPixelBytes: Long = 0L) {
        draws++
        paths = pathBuildCount
        finishedPaths += finishedPathBuilds
        rasters = rasterBuildCount
        finishedStrokeDraws += finishedDrawn
        retainedPixelBytes = rasterPixelBytes
        peakRetainedPixelBytes = maxOf(peakRetainedPixelBytes, rasterPixelBytes)
        if (pendingInputNanos.isNotEmpty()) {
            val now = System.nanoTime()
            pendingInputNanos.forEach { inputToDrawNanos += now - it }
            pendingInputNanos.clear()
        }
    }

    fun counts() = InkLayerCounts(sceneBuilds, draws, paths, finishedPaths, rasters,
        finishedStrokeDraws, shapeAdvances, observations)
}
