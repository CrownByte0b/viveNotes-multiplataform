package com.vivenotes.ui.ribbon.draw

import com.vivenotes.localization.Strings
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.toArgb
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

internal val HUE_STOPS: List<Color> = List(13) { Color.hsv(it * 30f, 1f, 1f) }

@Composable
fun ColumnScope.ColorWheelContent(
    initialArgb: Int,
    onPick: (Int) -> Unit,
    onDone: () -> Unit,
) {
    val seed = remember { initialArgb.toHsv() }
    var hue by remember { mutableFloatStateOf(seed[0]) }
    var saturation by remember { mutableFloatStateOf(seed[1]) }
    var brightness by remember { mutableFloatStateOf(seed[2]) }

    val latestPick by rememberUpdatedState(onPick)
    fun commit() = latestPick(argbOf(hue, saturation, brightness))

    val current = Color.hsvSafe(hue, saturation, brightness)
    val wheelDescription = Strings.color_wheel

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Canvas(
            modifier = Modifier
                .size(WHEEL_DIAMETER)
                .testTag(InkControlsTags.Wheel)
                .semantics { contentDescription = wheelDescription }
                .pointerInput(Unit) {
                    trackPointer(
                        onSample = { position ->
                            val radius = minOf(size.width, size.height) / 2f
                            val dx = position.x - size.width / 2f
                            val dy = position.y - size.height / 2f
                            hue = (((atan2(dy, dx) * 180.0 / PI) + 360.0) % 360.0)
                                .toFloat()
                            saturation = if (radius <= 0f) {
                                0f
                            } else {
                                (hypot(dx, dy) / radius).coerceIn(0f, 1f)
                            }
                        },
                        onEnd = { commit() },
                    )
                },
        ) {
            val radius = size.minDimension / 2f
            drawCircle(brush = Brush.sweepGradient(HUE_STOPS, center), radius = radius)
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(Color.White, Color.Transparent),
                    center = center,
                    radius = radius,
                ),
                radius = radius,
            )
            if (brightness < 1f) {
                drawCircle(color = Color.Black.copy(alpha = 1f - brightness), radius = radius)
            }

            val angle = (hue * PI / 180.0)
            val thumb = Offset(
                x = center.x + (cos(angle) * saturation * radius).toFloat(),
                y = center.y + (sin(angle) * saturation * radius).toFloat(),
            )
            drawThumb(thumb, 9.dp.toPx())
        }

        Spacer(Modifier.height(14.dp))
        BrightnessBar(
            hue = hue,
            saturation = saturation,
            brightness = brightness,
            onChange = { brightness = it },
            onEnd = { commit() },
        )

        Spacer(Modifier.height(12.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(26.dp)
                        .clip(RoundedCornerShape(50))
                        .background(current)
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(50))
                        .testTag(InkControlsTags.WheelPreview),
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = hexOf(argbOf(hue, saturation, brightness)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = onDone, modifier = Modifier.testTag(InkControlsTags.WheelDone)) { Text(Strings.done) }
        }
    }
}

@Composable
private fun BrightnessBar(
    hue: Float,
    saturation: Float,
    brightness: Float,
    onChange: (Float) -> Unit,
    onEnd: () -> Unit,
) {
    val latestChange by rememberUpdatedState(onChange)
    val latestEnd by rememberUpdatedState(onEnd)
    val lit = Color.hsvSafe(hue, saturation, 1f)
    val thumbOutline = MaterialTheme.colorScheme.outlineVariant
    val brightnessDescription = Strings.brightness

    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(BAR_HEIGHT)
            .testTag(InkControlsTags.Brightness)
            .semantics { contentDescription = brightnessDescription }
            .pointerInput(Unit) {
                trackPointer(
                    onSample = { position ->
                        val width = size.width.toFloat()
                        latestChange(if (width <= 0f) 0f else (position.x / width).coerceIn(0f, 1f))
                    },
                    onEnd = { latestEnd() },
                )
            },
    ) {
        val radius = size.height / 2f
        drawRoundRect(
            brush = Brush.horizontalGradient(listOf(Color.Black, lit)),
            cornerRadius = CornerRadius(radius, radius),
        )
        drawRoundRect(
            color = thumbOutline,
            cornerRadius = CornerRadius(radius, radius),
            style = Stroke(width = 1.dp.toPx()),
        )
        val x = (brightness * size.width).coerceIn(radius, size.width - radius)
        drawThumb(Offset(x, radius), radius - 3.dp.toPx())
    }
}

private fun DrawScope.drawThumb(center: Offset, radius: Float) {
    drawCircle(
        color = Color.Black.copy(alpha = 0.55f),
        radius = radius + 1.5.dp.toPx(),
        center = center,
        style = Stroke(width = 1.5.dp.toPx()),
    )
    drawCircle(
        color = Color.White,
        radius = radius,
        center = center,
        style = Stroke(width = 3.dp.toPx()),
    )
}

private suspend fun PointerInputScope.trackPointer(
    onSample: (Offset) -> Unit,
    onEnd: () -> Unit,
) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        down.consume()
        onSample(down.position)
        while (true) {
            val event = awaitPointerEvent()
            val change = event.changes.firstOrNull { it.id == down.id } ?: break
            change.consume()
            if (!change.pressed) break
            onSample(change.position)
        }
        onEnd()
    }
}

internal fun Int.toHsv(): FloatArray {
    val c = Color(this)
    val hi = maxOf(c.red, c.green, c.blue)
    val lo = minOf(c.red, c.green, c.blue)
    val d = hi - lo
    val h = when {
        d == 0f -> 0f
        hi == c.red -> ((c.green - c.blue) / d) % 6f
        hi == c.green -> (c.blue - c.red) / d + 2f
        else -> (c.red - c.green) / d + 4f
    } * 60f
    return floatArrayOf((h + 360f) % 360f, if (hi == 0f) 0f else d / hi, hi)
}

private fun argbOf(hue: Float, saturation: Float, brightness: Float): Int =
    Color.hsvSafe(hue, saturation, brightness).toArgb()

private fun hexOf(argb: Int): String = "#" + (argb and 0xffffff).toString(16).padStart(6, '0').uppercase()

private fun Color.Companion.hsvSafe(hue: Float, saturation: Float, value: Float): Color =
    hsv(hue.coerceIn(0f, 360f), saturation.coerceIn(0f, 1f), value.coerceIn(0f, 1f))

private val WHEEL_DIAMETER = 206.dp
private val BAR_HEIGHT = 26.dp