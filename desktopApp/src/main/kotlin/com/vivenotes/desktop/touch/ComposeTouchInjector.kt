package com.vivenotes.desktop.touch

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.scene.ComposeScene
import androidx.compose.ui.scene.ComposeScenePointer
import com.vivenotes.diagnostics.DebugLog
import java.awt.Component
import java.awt.Window
import java.awt.event.MouseEvent
import java.lang.reflect.Method
import javax.swing.SwingUtilities

/**
 * Hands touch frames to Compose as touch pointers — what Compose Desktop's own AWT input cannot do,
 * because AWT has no multi-touch events.
 *
 * Each contact goes to the Compose scene drawn where it lands, found the way a mouse event finds
 * its component: Swing's hit test. That is the window's own scene, or a popup layer above it; a
 * contact outside an open popup is passed to the popup's background as a mouse press and release,
 * which is how such a popup learns it should close. Positions are mapped to the scene by the same
 * function Compose maps a mouse event with, so a finger lands where the cursor would.
 *
 * While a finger is down the mouse is kept out ([TouchAwareEventQueue]), and a gesture starts by
 * telling the scene the cursor has left it.
 *
 * Confined to the Swing event thread, as Compose is.
 */
internal class ComposeTouchInjector(
    private val log: DebugLog = DebugLog(),
    private val mouse: TouchAwareEventQueue = TouchAwareEventQueue.install(),
) {
    private val tracker = TouchPointerTracker(::targetAt)

    fun accept(frame: TouchFrame) {
        for (dispatch in tracker.accept(frame)) {
            try {
                dispatch.target.deliver(dispatch, mouse)
            } catch (failure: Exception) {
                log.event("touch") { "a touch event could not be delivered: $failure" }
            }
        }
        mouse.touching = tracker.active
    }

    private fun targetAt(window: Window, x: Double, y: Double): TouchTarget? {
        val hit = SwingUtilities.getDeepestComponentAt(window, x.toInt(), y.toInt()) ?: return null
        var component: Component? = hit
        while (component != null && component !== window) {
            SceneTarget.of(component)?.let { return it }
            if (component.mouseListeners.any { it.javaClass.name == POPUP_BACKGROUND }) return PopupBackground(component)
            component = component.parent
        }
        return null
    }

    /** Where a touch is delivered. */
    private sealed interface TouchTarget {
        fun deliver(dispatch: TouchDispatch<TouchTarget>, mouse: TouchAwareEventQueue)
    }

    /** A Compose scene, reached through the mediator that feeds it the component's mouse events. */
    @OptIn(InternalComposeUiApi::class, ExperimentalComposeUiApi::class)
    private class SceneTarget(private val mediator: Any, private val component: Component) : TouchTarget {
        override fun equals(other: Any?) = other is SceneTarget && other.mediator === mediator
        override fun hashCode() = System.identityHashCode(mediator)

        override fun deliver(dispatch: TouchDispatch<TouchTarget>, mouse: TouchAwareEventQueue) {
            val scene = getScene.invoke(null, mediator) as ComposeScene
            when (dispatch) {
                is TouchDispatch.Cancel -> scene.cancelPointerInput()
                is TouchDispatch.Event -> {
                    val mapping = mapping()
                    if (dispatch.type == TouchEventType.Press) {
                        if (!component.hasFocusWithin()) {
                            // A tap gives the window's content the keyboard, as a click does.
                            component.requestFocusInWindow()
                        }
                        sendMouseAway(scene, mouse, mapping)
                    }
                    scene.sendPointerEvent(
                        eventType = when (dispatch.type) {
                            TouchEventType.Press -> PointerEventType.Press
                            TouchEventType.Move -> PointerEventType.Move
                            TouchEventType.Release -> PointerEventType.Release
                        },
                        pointers = dispatch.pointers.map { pointer ->
                            ComposeScenePointer(
                                id = PointerId(pointer.pointerId),
                                position = mapping(pointer.window, pointer.x, pointer.y),
                                pressed = pointer.pressed,
                                type = PointerType.Touch,
                            )
                        },
                        timeMillis = dispatch.timeMillis,
                    )
                }
            }
        }

        /**
         * The cursor leaves the scene when a finger lands, as hover ends under a finger on Android.
         * Compose otherwise sends a press beside the resting cursor's stale change, which `clickable`
         * and `awaitFirstDown` do not count as a press. It comes back when the mouse moves.
         */
        private fun sendMouseAway(scene: ComposeScene, mouse: TouchAwareEventQueue, mapping: (Window, Double, Double) -> Offset) {
            val cursor = mouse.mouse ?: return
            if (SwingUtilities.getWindowAncestor(component) !== cursor.window && component !== cursor.window) return
            scene.sendPointerEvent(
                eventType = PointerEventType.Exit,
                pointers = listOf(ComposeScenePointer(
                    id = PointerId(MOUSE_POINTER),
                    position = mapping(cursor.window, cursor.x, cursor.y),
                    pressed = false,
                    type = PointerType.Mouse,
                )),
            )
            mouse.forgetMouse()
        }

        /** Window coordinates to the scene's, through Compose's own mouse mapping. */
        private fun mapping(): (Window, Double, Double) -> Offset {
            val origin = scenePosition(0, 0)
            val far = scenePosition(MAPPING_SPAN, MAPPING_SPAN)
            val scaleX = (far.x - origin.x) / MAPPING_SPAN
            val scaleY = (far.y - origin.y) / MAPPING_SPAN
            return { window, x, y ->
                val at = SwingUtilities.convertPoint(window, 0, 0, component)
                Offset(origin.x + (x + at.x).toFloat() * scaleX, origin.y + (y + at.y).toFloat() * scaleY)
            }
        }

        private fun scenePosition(x: Int, y: Int): Offset {
            val probe = MouseEvent(component, MouseEvent.MOUSE_MOVED, 0L, 0, x, y, 0, false)
            val packed = getPosition.invoke(mediator, probe) as Long
            return Offset(Float.fromBits((packed ushr 32).toInt()), Float.fromBits(packed.toInt()))
        }

        private fun Component.hasFocusWithin(): Boolean {
            val owner = java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager().focusOwner ?: return false
            return owner === this || SwingUtilities.isDescendingFrom(owner, this)
        }

        companion object {
            private const val MEDIATOR = "androidx.compose.ui.scene.ComposeSceneMediator"
            private const val MAPPING_SPAN = 1000
            /** The id Compose Desktop gives the mouse's pointer. */
            private const val MOUSE_POINTER = 0L
            private val mediatorClass: Class<*> = Class.forName(MEDIATOR)
            private val getScene: Method = mediatorClass.getMethod("access\$getScene", mediatorClass)
            private val getPosition: Method = mediatorClass.declaredMethods.single {
                it.name.startsWith("getPosition") && it.parameterTypes.contentEquals(arrayOf(MouseEvent::class.java))
            }.apply { isAccessible = true }

            /** The component's scene, if Compose draws it: its mouse listener belongs to a mediator. */
            fun of(component: Component): SceneTarget? {
                val listener = component.mouseListeners.firstOrNull { it.javaClass.name == "$MEDIATOR\$mouseListener\$1" }
                    ?: return null
                val outer = listener.javaClass.getDeclaredField("this\$0").apply { isAccessible = true }
                return SceneTarget(outer.get(listener), component)
            }
        }
    }

    /** Outside an open popup: its background closes it on a press, as for a mouse. */
    private class PopupBackground(private val component: Component) : TouchTarget {
        override fun equals(other: Any?) = other is PopupBackground && other.component === component
        override fun hashCode() = System.identityHashCode(component)

        override fun deliver(dispatch: TouchDispatch<TouchTarget>, mouse: TouchAwareEventQueue) {
            if (dispatch !is TouchDispatch.Event) return
            val id = when (dispatch.type) {
                TouchEventType.Press -> MouseEvent.MOUSE_PRESSED
                TouchEventType.Release -> MouseEvent.MOUSE_RELEASED
                TouchEventType.Move -> return
            }
            val pointer = dispatch.pointers.first()
            val at = SwingUtilities.convertPoint(pointer.window, pointer.x.toInt(), pointer.y.toInt(), component)
            component.dispatchEvent(MouseEvent(component, id, dispatch.timeMillis,
                if (id == MouseEvent.MOUSE_PRESSED) MouseEvent.BUTTON1_DOWN_MASK else 0,
                at.x, at.y, 1, false, MouseEvent.BUTTON1))
        }
    }

    private companion object {
        const val POPUP_BACKGROUND = "androidx.compose.ui.scene.SwingComposeSceneLayer\$backgroundMouseListener\$1"
    }
}
