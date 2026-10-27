package com.vivenotes.ui.canvas

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.*
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vivenotes.model.PageStyle
import com.vivenotes.model.ink.Ruler
import com.vivenotes.model.ink.RulerPoint
import com.vivenotes.workspace.RulerKind
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

@Composable
internal fun RulerLayer(ruler: Ruler, canvasSize: DpSize, color: Color, zoom: Float, onChange: (Ruler) -> Unit) {
    val density = LocalDensity.current.density
    val measurer = rememberTextMeasurer()
    Canvas(Modifier.testTag("draw-ruler-layer").documentExtent(canvasSize)) {
        val center = Offset(ruler.centerX * density, ruler.centerY * density)
        val reach = ruler.reach * density
        val body = color.copy(alpha = .12f)
        val mark = color.copy(alpha = .8f)
        val edge = 1.2.dp.toPx() / zoom
        fun label(text: String, at: Offset) {
            val measured = measurer.measure(text, TextStyle(color = mark, fontSize = 12.sp))
            drawText(measured, topLeft = at - Offset(measured.size.width / 2f, measured.size.height / 2f))
        }
        rotate(ruler.angleRadians * 180f / PI.toFloat(), center) {
            translate(center.x, center.y) {
                if (ruler.kind == RulerKind.Straight) {
                    val half = Ruler.BAND_DP / 2f * density
                    drawRect(body, Offset(-reach, -half), Size(2 * reach, 2 * half))
                    for (side in listOf(-1f, 1f)) {
                        drawLine(mark, Offset(-reach, side * half), Offset(reach, side * half), edge)
                    }
                    val step = PageStyle.DP_PER_INCH / 2.54f / 10f * density
                    for (i in 0..(reach / step).toInt()) {
                        val major = i % 10 == 0
                        val length = Ruler.BAND_DP * (if (major) .175f else if (i % 10 == 5) .157f else .126f) * density
                        for (x in listOf(i * step, -i * step).distinct()) {
                            drawLine(mark, Offset(x, -half), Offset(x, -half + length), edge * .65f)
                            drawLine(mark, Offset(x, half), Offset(x, half - length), edge * .65f)
                            if (major) {
                                label("${i / 10}", Offset(x + 7.dp.toPx(), -half + length + 10.dp.toPx()))
                                label("${i / 10}", Offset(x + 7.dp.toPx(), half - length - 10.dp.toPx()))
                            }
                        }
                    }
                } else {
                    val topLeft = Offset(-reach, -reach)
                    val dimensions = Size(reach * 2, reach * 2)
                    drawArc(body, 180f, 180f, true, topLeft, dimensions)
                    drawArc(mark, 180f, 180f, true, topLeft, dimensions, style = Stroke(edge))
                    for (degrees in 0..180 step 5) {
                        val angle = degrees * PI.toFloat() / 180f
                        val direction = Offset(-cos(angle), -sin(angle))
                        val major = degrees % 30 == 0
                        val inner = if (major) .86f else if (degrees % 10 == 0) .90f else .94f
                        drawLine(mark, direction * reach, direction * reach * inner, edge * .65f)
                        if (major && degrees != 0 && degrees != 180) label("$degrees", direction * reach * .78f)
                    }
                    drawLine(mark, Offset(-reach * .05f, 0f), Offset(reach * .05f, 0f), edge)
                    drawLine(mark, Offset(0f, -reach * .05f), Offset(0f, reach * .05f), edge)
                }
                val dial = ruler.dialCenter().let { Offset(it.x * density, it.y * density) }
                drawCircle(color.copy(alpha = .15f), Ruler.DIAL_RADIUS_DP * density, dial)
                drawCircle(mark, Ruler.DIAL_RADIUS_DP * density, dial, style = Stroke(edge))
                rotate(-ruler.angleRadians * 180f / PI.toFloat(), dial) { label("${ruler.degrees()}°", dial) }
            }
        }
    }
}

internal suspend fun PointerInputScope.detectRulerGestures(
    rulerAt: () -> Ruler?, toPage: (Offset) -> Offset, onChange: (Ruler) -> Unit,
) {
        awaitEachGesture {
            val down = awaitFirstDown(false, PointerEventPass.Initial)
            if (down.type == PointerType.Mouse && !currentEvent.buttons.isPrimaryPressed) return@awaitEachGesture
            val start = rulerAt() ?: return@awaitEachGesture
            fun point(p: Offset) = toPage(p).let { RulerPoint(it.x, it.y) }
            // Mouse controls the dial. Touch moves the translucent body; a pen always writes.
            val dial = start.grabsDial(point(down.position))
            if (down.type != PointerType.Touch && !(down.type == PointerType.Mouse && dial)) return@awaitEachGesture
            if (!start.grabs(point(down.position))) return@awaitEachGesture
            down.consume()
            var travelled = false
            var anchor = down.position
            var hand = setOf(down.id)
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val handEvent = PointerEvent(event.changes.filter { it.type == down.type })
                val pressed = handEvent.changes.filter { it.pressed }
                handEvent.changes.forEach { it.consume() }
                if (pressed.isEmpty()) break
                val centroid = pressed.fold(Offset.Zero) { total, p -> total + p.position } / pressed.size.toFloat()
                val ids = pressed.mapTo(mutableSetOf()) { it.id }
                if (ids != hand) { hand = ids; anchor = centroid; travelled = true } else {
                    var next = rulerAt() ?: start
                    if (pressed.size >= 2) next = next.turnedBy(handEvent.calculateRotation() * PI.toFloat() / 180f)
                    if (centroid != anchor) {
                        val delta = toPage(centroid) - toPage(anchor)
                        next = next.movedBy(delta.x, delta.y)
                        anchor = centroid
                    }
                    onChange(next)
                }
                if ((centroid - down.position).getDistance() > viewConfiguration.touchSlop) travelled = true
            }
            if (dial && !travelled) onChange((rulerAt() ?: start).turnedToNextEighth())
        }
}
