package com.vivenotes.ink

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush as ComposeBrush
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.skiaPaint
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.ink.brush.Brush
import androidx.ink.brush.BrushBehavior
import androidx.ink.brush.BrushCoat
import androidx.ink.brush.BrushFamily
import androidx.ink.brush.BrushPaint
import androidx.ink.brush.BrushTip
import androidx.ink.brush.InputToolType
import androidx.ink.brush.behavior.SourceNode
import androidx.ink.brush.behavior.TargetNode
import androidx.ink.geometry.ImmutableAffineTransform
import androidx.ink.strokes.InProgressStroke
import androidx.ink.strokes.MutableStrokeInputBatch
import com.vivenotes.byteink.compose.InkRenderer
import com.vivenotes.byteink.kit.ViveBrushes
import org.jetbrains.skia.ColorFilter
import org.jetbrains.skia.ColorMatrix
import org.jetbrains.skia.FilterTileMode
import org.jetbrains.skia.ImageFilter
import kotlin.math.PI
import kotlin.math.sin

/**
 * The laser pointer (Android `ui/editor/LaserPointer.kt`): a lit dot under the pen, and the trace it
 * draws, which stays for a second and then goes.
 *
 * It leaves nothing on the page. Nothing here reaches a stroke row, a history step or the database:
 * it is for pointing at what is already written, while presenting or explaining, and once it has
 * been seen it is gone. That is also why it has no settings.
 *
 * **Every point of the trace lives [HOLD_MILLIS], then thins away over [VANISH_MILLIS].** By the
 * point, not by the gesture: lifting the pen does not take the trace with it, and neither does the
 * next press — circling something twice leaves both circles up until each has had its second. So
 * there can be several traces at once, each one a gesture, and only the newest is still being drawn.
 *
 * **A trace is Ink's, smoothed the way a pen is.** Each one is an [InProgressStroke] whose brush
 * wears stabilization level 1 — the same sliding window a pen at level 1 draws through — so it is
 * modelled by the library's own stroke modeller rather than by a filter written here. The vanishing
 * is part of the brush too: a behaviour shrinks each point to nothing as it ages, so the trace is a
 * function of time that Ink re-evaluates every frame.
 *
 * **It looks like a lightsaber blade.** A white core on a red edge — the brush's two coats — over
 * two blurred copies of the stroke: a tight bright-red glow and a wide deep-crimson one. Both glows
 * waver slightly, the way a blade hums. See [drawTraces].
 *
 * **The dot is drawn here, at the raw pen position.** That is where the trace's head is. It goes out
 * over [FADE_MILLIS] once the pen lifts, leaving the trace behind it.
 *
 * **Held in page units, drawn at a fixed size on screen.** The marks are where the pen pointed *on
 * the page*, so a scroll while a trace is up carries it along with the writing it was pointing at.
 * Its width is window chrome, though: each trace's brush is sized for the zoom its press landed at,
 * and the dot is the same size at every zoom.
 *
 * With animations off ([animationsEnabled] false when the press lands) there is no trace, no fade and
 * no waver: the dot is under the pen while it touches, steady, and gone the moment it lifts.
 */
internal class LaserPointer(private val animationsEnabled: () -> Boolean = { true }) {

    /** Bumped on every change to what is drawn, for the layer's draw scope to read. */
    var renderRevision by mutableIntStateOf(0)
        private set

    /**
     * Bumped each time the picture starts changing with time alone, which is what the layer's frame
     * clock is keyed on. A counter rather than a flag: a flag that went false and back to true
     * between two reads would look unchanged, and the clock would never be restarted.
     */
    var wakeups by mutableIntStateOf(0)
        private set

    /**
     * Whether the picture still changes with no input arriving — a trace still up, or a lifted dot
     * still fading. A pen resting still with its trace spent needs no frames.
     */
    val animating: Boolean get() = traces.any { it.marks.isNotEmpty() } || liftedAt >= 0L

    /** Where the dot is, in page units, or null when there is none. */
    var dot: Offset? = null
        private set

    /** Every trace still showing, oldest first. The newest is [live] while the pen is down. */
    private val traces = ArrayList<Trace>()
    private var live: Trace? = null

    /** Strokes whose traces have gone, kept to be started again rather than reallocated. */
    private val spare = ArrayDeque<InProgressStroke>()

    /** When the pen lifted, in milliseconds on [now]'s clock, or -1 while it is down or the dot is out. */
    private var liftedAt = -1L

    /** Whether this gesture gets a trace and a fade. Asked on each press. */
    private var motion = true

    /** Inputs gathered from one pointer event, handed to [live]'s stroke together. */
    private val pending = MutableStrokeInputBatch()
    private val noPrediction = MutableStrokeInputBatch()

    /**
     * A press at [point], in page units, at [time]. [traceWidth] is the trace's width at the pen and
     * [epsilon] the finest detail worth meshing, both in page units for the zoom the press lands at.
     * The traces already up are left to run out their second.
     */
    fun press(point: Offset, time: Long, traceWidth: Float, epsilon: Float) {
        endGesture()
        // Per gesture, so a change to the setting takes effect on the next press.
        motion = animationsEnabled()
        if (motion) startTrace(brushFor(traceWidth, epsilon), time)
        mark(point, time)
        flush()
    }

    /** The samples of one pointer event, oldest first, each with its time: the batch and the last. */
    fun follow(samples: List<Pair<Offset, Long>>) {
        samples.forEach { (point, time) -> mark(point, time) }
        flush()
    }

    /** The pen lifted at [time]: the dot fades, the trace stays up to run out its time. */
    fun lift(time: Long) {
        if (!motion) {
            endGesture()
            dot = null
            invalidateDraw()
            return
        }
        endGesture()
        val wasAnimating = animating
        liftedAt = time
        if (!wasAnimating) wakeups++
        invalidateDraw()
    }

    /** The gesture in progress is taken back whole — its trace and its dot — the others are left. */
    fun cancelGesture() {
        val trace = live
        endGesture()
        if (trace != null) {
            traces.remove(trace)
            retire(trace)
        }
        dot = null
        liftedAt = -1L
        invalidateDraw()
    }

    /**
     * Ages the picture to [now]: drops the marks that have run out their lifetime and the traces left
     * with none, puts the dot out once its fade has run, and restarts the live stroke when it has
     * grown long. Called once a frame for as long as [animating] holds.
     */
    fun tick(now: Long) {
        traces.forEach { it.dropMarksBefore(now - TRAIL_MILLIS) }
        traces.removeAll { trace ->
            (trace !== live && trace.marks.isEmpty()).also { gone -> if (gone) retire(trace) }
        }
        if (liftedAt >= 0L && now - liftedAt >= FADE_MILLIS) {
            liftedAt = -1L
            dot = null
        }
        live?.takeIf { now - it.startedAt > RESEED_MILLIS }?.reseed(now)
        invalidateDraw()
    }

    /** Puts the pointer away: no dot, no traces. */
    fun clear() {
        endGesture()
        traces.forEach(::retire)
        traces.clear()
        dot = null
        liftedAt = -1L
        invalidateDraw()
    }

    /** Releases the strokes' native memory; the pointer is not used again. */
    fun close() {
        clear()
        spare.forEach { it.clear() }
        spare.clear()
    }

    /**
     * Draws the traces and the dot as they stand at [now], into a scope whose units are page units
     * times [density] — the page's own canvas, already scaled by [zoom] on the way to the screen.
     */
    fun draw(scope: DrawScope, density: Float, zoom: Float, now: Long, paint: LaserPaint, renderer: InkRenderer) {
        if (traces.any { it.marks.isNotEmpty() }) drawTraces(scope, density, zoom, now, paint, renderer)

        val spot = dot ?: return
        val fade = if (liftedAt < 0L) 1f else (1f - (now - liftedAt).toFloat() / FADE_MILLIS).coerceIn(0f, 1f)
        if (fade <= 0f) return
        val hum = if (motion) hum(now) else 1f
        // Chrome, not page: the same size on the screen at every zoom.
        val toScreen = 1f / zoom
        with(scope) {
            translate(spot.x * density, spot.y * density) {
                scale(toScreen, pivot = Offset.Zero) {
                    drawCircle(paint.glow, radius = paint.glowRadius, center = Offset.Zero, alpha = fade * hum)
                    drawCircle(Color(LASER_RED), radius = paint.coreRadius, center = Offset.Zero, alpha = fade)
                    drawCircle(Color(LASER_CENTRE), radius = paint.centreRadius, center = Offset.Zero, alpha = fade)
                }
            }
        }
    }

    /**
     * The traces as lightsaber blades: two blurred glows, then the blade itself on top.
     *
     * The blade is opaque, laid into one layer that is then put down translucent, so the writing
     * under it still reads and two traces crossing do not darken where they cross. Each glow is one
     * layer for the same reason: every trace is blurred together, and the blur is tinted and faded
     * as a whole.
     */
    private fun drawTraces(scope: DrawScope, density: Float, zoom: Float, now: Long, paint: LaserPaint, renderer: InkRenderer) {
        // The layer only has to hold what is still visible. A moving average stays inside the hull
        // of the points it averages, so the live marks plus the trace's width bound it.
        var left = Float.POSITIVE_INFINITY
        var top = Float.POSITIVE_INFINITY
        var right = Float.NEGATIVE_INFINITY
        var bottom = Float.NEGATIVE_INFINITY
        traces.forEach { trace ->
            trace.marks.forEach { mark ->
                left = minOf(left, mark.x * density)
                top = minOf(top, mark.y * density)
                right = maxOf(right, mark.x * density)
                bottom = maxOf(bottom, mark.y * density)
            }
        }
        val width = paint.traceWidth / zoom
        val blade = Rect(left - width, top - width, right + 1f + width, bottom + 1f + width)

        // Once a frame, before any of the three passes reads it.
        traces.forEach { trace ->
            if (trace.marks.isNotEmpty()) trace.stroke.updateShape(now - trace.startedAt)
        }

        val canvas = scope.drawContext.canvas
        val strokeToCanvas = ImmutableAffineTransform(density, 0f, 0f, 0f, density, 0f)
        val reach = paint.glowReach / zoom
        val glowBounds = Rect(blade.left - reach, blade.top - reach, blade.right + reach, blade.bottom + reach)
        val hum = hum(now)
        drawGlow(canvas, paint.outerGlow(zoom), OUTER_GLOW_ALPHA * hum, glowBounds, strokeToCanvas, renderer)
        drawGlow(canvas, paint.innerGlow(zoom), INNER_GLOW_ALPHA * hum, glowBounds, strokeToCanvas, renderer)

        paint.layer.alpha = TRACE_ALPHA
        paint.layer.skiaPaint.imageFilter = null
        canvas.saveLayer(blade, paint.layer)
        try {
            drawStrokes(canvas, strokeToCanvas, renderer)
        } finally {
            canvas.restore()
        }
    }

    private fun drawStrokes(canvas: Canvas, strokeToCanvas: ImmutableAffineTransform, renderer: InkRenderer) {
        traces.forEach { trace ->
            if (trace.marks.isNotEmpty()) renderer.render(canvas, trace.stroke, strokeToCanvas)
        }
    }

    /** One glow: the traces drawn into a layer whose filter blurs and tints them, put down at [alpha]. */
    private fun drawGlow(
        canvas: Canvas,
        glow: Paint,
        alpha: Float,
        bounds: Rect,
        strokeToCanvas: ImmutableAffineTransform,
        renderer: InkRenderer,
    ) {
        glow.alpha = alpha.coerceIn(0f, 1f)
        canvas.saveLayer(bounds, glow)
        try {
            drawStrokes(canvas, strokeToCanvas, renderer)
        } finally {
            canvas.restore()
        }
    }

    private fun mark(point: Offset, time: Long) {
        val wasAnimating = animating
        dot = point
        live?.add(point, time, pending)
        if (!wasAnimating && animating) wakeups++
        invalidateDraw()
    }

    /** Hands the inputs gathered from one event to the live stroke. */
    private fun flush() {
        val trace = live
        if (trace != null && !pending.isEmpty()) trace.stroke.enqueueInputs(pending, noPrediction)
        pending.clear()
    }

    private fun startTrace(brush: Brush, at: Long) {
        val stroke = spare.removeFirstOrNull() ?: InProgressStroke()
        stroke.start(brush)
        live = Trace(stroke, brush, at).also { traces += it }
    }

    /** The gesture in progress stops taking input; its trace stays up to run out its time. */
    private fun endGesture() {
        pending.clear()
        live?.stroke?.finishInput()
        live = null
    }

    private fun retire(trace: Trace) {
        trace.stroke.clear()
        if (spare.size < SPARE_STROKES) spare.addLast(trace.stroke)
    }

    private fun invalidateDraw() {
        renderRevision = if (renderRevision == Int.MAX_VALUE) 0 else renderRevision + 1
    }

    /** The trace's brush for this press: [traceWidth] wide, both in page units. */
    private fun brushFor(traceWidth: Float, epsilon: Float): Brush = Brush.createWithColorIntArgb(
        family = traceFamily,
        colorIntArgb = LASER_RED,
        size = traceWidth,
        epsilon = epsilon,
    )

    /**
     * One gesture's trace: its stroke, and the inputs still alive in it.
     *
     * The marks are kept beside the stroke because the stroke cannot be asked for them cheaply: they
     * bound the layer the trace is drawn into, they say when the trace has gone, and they are what a
     * reseed starts the stroke again from.
     */
    private class Trace(val stroke: InProgressStroke, val brush: Brush, var startedAt: Long) {
        val marks = ArrayDeque<Mark>()

        /** The last input handed to the stroke, which the next one may not repeat or precede. */
        private var lastX = Float.NaN
        private var lastY = Float.NaN
        private var lastTime = startedAt

        fun add(point: Offset, time: Long, into: MutableStrokeInputBatch) {
            // Ink takes inputs in order and refuses an exact repeat. Stamps measured back from two
            // different events can cross by a millisecond or two, so they are held to the last one.
            val at = maxOf(time, lastTime)
            if (at == lastTime && point.x == lastX && point.y == lastY) return
            dropMarksBefore(at - TRAIL_MILLIS)
            marks.addLast(Mark(point.x, point.y, at))
            into.add(InputToolType.UNKNOWN, point.x, point.y, at - startedAt)
            lastX = point.x
            lastY = point.y
            lastTime = at
        }

        fun dropMarksBefore(time: Long) {
            while (marks.isNotEmpty() && marks.first().time < time) marks.removeFirst()
        }

        /**
         * Starts the stroke again from the marks still alive.
         *
         * A time behaviour changes every input on every frame, so Ink re-extrudes the whole stroke
         * each time it is drawn. Held for a minute of presenting, that is every input of the minute,
         * almost all of it long gone. Restarting from the live marks keeps the work to what can be
         * seen; the seam falls at the trace's oldest end, where it has already thinned to nothing.
         */
        fun reseed(now: Long) {
            startedAt = marks.firstOrNull()?.time ?: now
            stroke.start(brush)
            lastX = Float.NaN
            lastY = Float.NaN
            lastTime = startedAt
            val alive = marks.toList()
            marks.clear()
            val batch = MutableStrokeInputBatch()
            alive.forEach { add(Offset(it.x, it.y), it.time, batch) }
            if (!batch.isEmpty()) stroke.enqueueInputs(batch, MutableStrokeInputBatch())
        }
    }

    private class Mark(val x: Float, val y: Float, val time: Long)

    companion object {
        /** How long a point of the trace stays at full width after the pen passed it. */
        const val HOLD_MILLIS = 1_000L

        /** How long it then takes to thin away to nothing. */
        const val VANISH_MILLIS = 300L

        /** A point's whole lifetime: held, then vanishing. */
        const val TRAIL_MILLIS = HOLD_MILLIS + VANISH_MILLIS

        /** How long the dot takes to go out once the pen lifts. */
        const val FADE_MILLIS = 300L

        /** How old the live stroke may grow before it is restarted from its marks — see reseed. */
        private const val RESEED_MILLIS = 3_000L

        /** Strokes kept for reuse. A few traces up at once is the most anyone points. */
        private const val SPARE_STROKES = 4

        /** The trace is laid down slightly translucent, so writing under it still reads. */
        private const val TRACE_ALPHA = 0.85f

        /** How strongly the wide crimson glow and the tight red one are put down. */
        private const val OUTER_GLOW_ALPHA = 0.8f
        private const val INNER_GLOW_ALPHA = 0.9f

        /** How far the hum dims the glow at its lowest. */
        private const val HUM_DEPTH = 0.12f

        /**
         * The blade's hum: a slight, uneven waver in the glow, between `1 - HUM_DEPTH` and 1.
         *
         * Two slow sines, a few times a second, that never fall into step, so it reads as a hum
         * rather than a blink. Wrapped to a minute first, since milliseconds outgrow the precision a
         * sine needs within a day.
         */
        private fun hum(now: Long): Float {
            val seconds = (now % 60_000L) / 1_000.0
            val wave = 0.5 + 0.3 * sin(2.0 * PI * 4.3 * seconds) + 0.2 * sin(2.0 * PI * 7.1 * seconds)
            return (1.0 - HUM_DEPTH * wave).toFloat()
        }

        /** How wide the white-hot core is, as a share of the blade. */
        private const val CORE_SHARE = 0.5f

        /** The blade's core: white, with the faintest warmth so it reads as heat and not paper. */
        private const val LASER_CORE = 0xFFFFF4F4.toInt()

        /** Pen stabilization level 1 — the library's own default window. */
        private const val TRACE_STABILIZATION = 1

        /** Red A400 — the most saturated red in the Material palette. */
        internal const val LASER_RED = 0xFFFF1744.toInt()

        /** The outer glow's deep crimson, the dark red a blade's light fades into. */
        internal const val LASER_CRIMSON = 0xFF8E0010.toInt()

        /** The hot centre of the spot, nearly white the way the blade's core is. */
        internal const val LASER_CENTRE = 0xFFFFF4F4.toInt()

        /**
         * Two round coats, a red edge and the white core on top of it. Each is held at full size
         * for [HOLD_MILLIS] since each input, then shrunk to nothing by [TRAIL_MILLIS]; below the
         * range the source clamps, which is what holds it.
         *
         * Built once: a family is a native allocation, and nothing in it varies between presses.
         */
        private val traceFamily: BrushFamily by lazy {
            val vanish = BrushBehavior(
                terminalNode = TargetNode(
                    target = TargetNode.Target.SIZE_MULTIPLIER,
                    targetModifierRangeStart = 1f,
                    targetModifierRangeEnd = 0f,
                    input = SourceNode(
                        source = SourceNode.Source.TIME_SINCE_INPUT_IN_SECONDS,
                        sourceValueRangeStart = HOLD_MILLIS / 1_000f,
                        sourceValueRangeEnd = TRAIL_MILLIS / 1_000f,
                    ),
                ),
                developerComment = "Laser trace: held for a second, then thins away.",
            )
            BrushFamily(
                coats = listOf(
                    BrushCoat(tip = BrushTip(behaviors = listOf(vanish)), paint = BrushPaint()),
                    BrushCoat(
                        tip = BrushTip(
                            scaleX = CORE_SHARE,
                            scaleY = CORE_SHARE,
                            behaviors = listOf(vanish),
                        ),
                        paint = BrushPaint(
                            colorFunctions = listOf(
                                BrushPaint.ColorFunction.ReplaceColor.withColorIntArgb(LASER_CORE),
                            ),
                        ),
                    ),
                ),
                inputModel = ViveBrushes.inputModelFor(TRACE_STABILIZATION),
                developerComment = "ViveNotes laser pointer trace. Never stored.",
            )
        }
    }
}

/**
 * What the laser's dot and its trace's glows are drawn with, sized for one display density.
 *
 * Built once and reused every frame: the layer redraws for as long as a trace is up. The glow
 * filters depend on the zoom, since a blur's radius is given in the units of the canvas it is drawn
 * on, which the page's zoom scales; they are rebuilt only when the zoom changes.
 *
 * Fixed reds rather than theme colours. A laser pointer is red the world over, and it has to stand
 * out from ink of every colour on paper of either shade — the app's accent is what selection is
 * drawn in, and a dot in that colour would read as part of it.
 */
internal class LaserPaint(density: Float) : AutoCloseable {
    val coreRadius = CORE_RADIUS_DP * density
    val centreRadius = CENTRE_RADIUS_DP * density
    val glowRadius = GLOW_RADIUS_DP * density

    /** The trace's width, in screen pixels. */
    val traceWidth = TRACE_WIDTH_DP * density

    /** How far past the blade the glows spread, which the layers they are blurred in must hold. */
    val glowReach = OUTER_BLUR_DP * density * 2f

    private val outerRadius = OUTER_BLUR_DP * density
    private val innerRadius = INNER_BLUR_DP * density

    /** The dot's glow: bright red at the spot, fading through crimson, like the blade. */
    val glow: ComposeBrush = ComposeBrush.radialGradient(
        0f to Color(GLOW_INNER),
        0.35f to Color(GLOW_MIDDLE),
        0.7f to Color(GLOW_DARK),
        1f to Color(GLOW_OUTER),
        center = Offset.Zero,
        radius = glowRadius,
    )

    /** The translucent layer the blade is laid into. */
    val layer = Paint()

    private var filtersZoom = Float.NaN
    private val outer = Paint()
    private val inner = Paint()
    private val filters = mutableListOf<AutoCloseable>()

    /** The wide, deep-crimson glow, under everything else. Thickened so it reads on dark paper. */
    fun outerGlow(zoom: Float): Paint { filtersFor(zoom); return outer }

    /** The tight, bright-red glow hugging the blade. Thickened so it stays dense right at the edge. */
    fun innerGlow(zoom: Float): Paint { filtersFor(zoom); return inner }

    private fun filtersFor(zoom: Float) {
        if (zoom == filtersZoom) return
        filtersZoom = zoom
        filters.forEach { it.close() }
        filters.clear()
        outer.skiaPaint.imageFilter = glowFilter(outerRadius / zoom, LaserPointer.LASER_CRIMSON, 1.8f)
        inner.skiaPaint.imageFilter = glowFilter(innerRadius / zoom, LaserPointer.LASER_RED, 1.5f)
    }

    /**
     * A filter that blurs its layer by [radius] and recolours it to [color], keeping only its shape,
     * with that shape's coverage scaled by [thicken].
     *
     * Recoloured after the blur rather than before, so the glow is one even colour all the way out
     * instead of whitening where it passes over the blade's core. [radius] is Android's
     * `RenderEffect` blur radius, which Skia takes as a standard deviation the way Android converts it.
     */
    private fun glowFilter(radius: Float, color: Int, thicken: Float): ImageFilter {
        val sigma = if (radius > 0f) BLUR_SIGMA_SCALE * radius + 0.5f else 0f
        val blur = ImageFilter.makeBlur(sigma, sigma, FilterTileMode.DECAL, null, null)
        val tint = ColorFilter.makeMatrix(
            ColorMatrix(
                0f, 0f, 0f, 0f, ((color shr 16) and 0xFF) / 255f,
                0f, 0f, 0f, 0f, ((color shr 8) and 0xFF) / 255f,
                0f, 0f, 0f, 0f, (color and 0xFF) / 255f,
                0f, 0f, 0f, thicken, 0f,
            ),
        )
        return ImageFilter.makeColorFilter(tint, blur, null).also { filters += listOf(blur, tint, it) }
    }

    override fun close() {
        outer.skiaPaint.imageFilter = null
        inner.skiaPaint.imageFilter = null
        filters.forEach { it.close() }
        filters.clear()
        filtersZoom = Float.NaN
    }

    private companion object {
        const val CORE_RADIUS_DP = 4.5f
        const val CENTRE_RADIUS_DP = 2.5f
        const val GLOW_RADIUS_DP = 16f
        const val TRACE_WIDTH_DP = 6f

        /** Blur radii of the two glows. */
        const val INNER_BLUR_DP = 4f
        const val OUTER_BLUR_DP = 12f

        /** Skia's (and Android's) radius-to-sigma: a blur's radius is about 1.7 standard deviations. */
        const val BLUR_SIGMA_SCALE = 0.57735f

        const val GLOW_INNER = 0xCCFF1744.toInt()
        const val GLOW_MIDDLE = 0x80FF1744.toInt()
        const val GLOW_DARK = 0x408E0010
        const val GLOW_OUTER = 0x008E0010
    }
}
