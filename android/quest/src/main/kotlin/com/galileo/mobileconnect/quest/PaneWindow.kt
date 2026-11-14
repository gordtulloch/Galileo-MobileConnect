package com.galileo.mobileconnect.quest

import android.app.Activity
import android.app.Presentation
import android.content.Context
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.os.Bundle
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.Surface
import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner

/** Compose needs a lifecycle, saved state and a view-model store on the window's root; a NativeActivity has none of them. */
private class WindowOwner : LifecycleOwner, SavedStateRegistryOwner, ViewModelStoreOwner {
    private val registry = LifecycleRegistry(this)
    private val saved = SavedStateRegistryController.create(this)
    override val viewModelStore = ViewModelStore()
    override val lifecycle: Lifecycle get() = registry
    override val savedStateRegistry: SavedStateRegistry get() = saved.savedStateRegistry

    init {
        saved.performAttach()
        saved.performRestore(null)
        registry.currentState = Lifecycle.State.RESUMED
    }

    fun destroy() {
        registry.currentState = Lifecycle.State.DESTROYED
        viewModelStore.clear()
    }
}

/**
 * One floating surface's content: a Compose screen drawn on a virtual display whose surface is an OpenXR swapchain, so
 * what the screen draws appears on its quad (docs/quest/SDD.md Q-PANE). Touches arrive as the pointer's hit, in pixels,
 * and go to the screen as a finger.
 */
class PaneWindow(
    private val activity: Activity,
    surface: Surface,
    val pixelWidth: Int,
    val pixelHeight: Int,
    densityDpi: Int,
    name: String,
    content: @Composable () -> Unit,
) {
    private val owner = WindowOwner()
    private val display: VirtualDisplay
    private val presentation: Presentation
    private var downTime = 0L
    private var touching = false

    /**
     * Where each controller points on this pane, drawn as a ring. The laser is part of the sky layer and the panes are
     * composited over it, so on a pane the laser is hidden: the ring is how the wearer sees where a press will land.
     */
    private var hoverRight by mutableStateOf<IntOffset?>(null)
    private var hoverLeft by mutableStateOf<IntOffset?>(null)
    var night by mutableStateOf(false)

    fun hover(right: Boolean, x: Float?, y: Float?) {
        val at = if (x == null || y == null) null else IntOffset(x.toInt(), y.toInt())
        if (right) hoverRight = at else hoverLeft = at
    }

    init {
        val manager = activity.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        display = manager.createVirtualDisplay(
            "galileo-$name", pixelWidth, pixelHeight, densityDpi, surface,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION or DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY,
        )
        presentation = object : Presentation(activity, display.display) {
            override fun onCreate(savedInstanceState: Bundle?) {
                super.onCreate(savedInstanceState)
                val decor = window!!.decorView
                decor.setViewTreeLifecycleOwner(owner)
                decor.setViewTreeSavedStateRegistryOwner(owner)
                decor.setViewTreeViewModelStoreOwner(owner)
                setContentView(
                    ComposeView(context).apply {
                        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                        setContent {
                            Box {
                                content()
                                val ink = if (night) Color(0xFFFF2A2A) else Color.White
                                for ((at, radius) in listOf(hoverLeft to 11, hoverRight to 14)) {
                                    at?.let { h ->
                                        val diameter = radius * 2
                                        Box(
                                            Modifier.offset { IntOffset(h.x - (diameter.dp.roundToPx() / 2), h.y - (diameter.dp.roundToPx() / 2)) }
                                                .size(diameter.dp)
                                                .border(3.dp, ink, CircleShape).background(ink.copy(alpha = 0.25f), CircleShape),
                                        )
                                    }
                                }
                            }
                        }
                    },
                )
            }
        }
        presentation.show()
    }

    private fun send(action: Int, x: Float, y: Float) {
        val now = SystemClock.uptimeMillis()
        if (action == MotionEvent.ACTION_DOWN) downTime = now
        val event = MotionEvent.obtain(downTime, now, action, x, y, 0)
        event.source = InputDevice.SOURCE_TOUCHSCREEN
        presentation.window?.decorView?.dispatchTouchEvent(event)
        event.recycle()
    }

    /**
     * Scrolls the content under the pointer, as a mouse wheel would. [lines] is wheel notches: positive moves the view up
     * (earlier content), negative down.
     */
    fun scroll(x: Float, y: Float, lines: Float) {
        val now = SystemClock.uptimeMillis()
        val props = arrayOf(MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_MOUSE })
        val coords = arrayOf(
            MotionEvent.PointerCoords().apply {
                this.x = x
                this.y = y
                setAxisValue(MotionEvent.AXIS_VSCROLL, lines)
                setAxisValue(MotionEvent.AXIS_HSCROLL, 0f)
            },
        )
        val event = MotionEvent.obtain(now, now, MotionEvent.ACTION_SCROLL, 1, props, coords, 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_MOUSE, 0)
        presentation.window?.decorView?.dispatchGenericMotionEvent(event)
        event.recycle()
    }

    val isTouching: Boolean get() = touching

    fun touchDown(x: Float, y: Float) {
        touching = true
        send(MotionEvent.ACTION_DOWN, x, y)
    }

    fun touchMove(x: Float, y: Float) {
        if (touching) send(MotionEvent.ACTION_MOVE, x, y)
    }

    fun touchUp(x: Float, y: Float) {
        if (touching) {
            touching = false
            send(MotionEvent.ACTION_UP, x, y)
        }
    }

    /** The finger is gone without lifting: a held button must end, not stay held (MAPP-QST-060). */
    fun touchCancel() {
        if (touching) {
            touching = false
            send(MotionEvent.ACTION_CANCEL, 0f, 0f)
        }
    }

    fun close() {
        touchCancel()
        runCatching { presentation.dismiss() }
        runCatching { display.release() }
        owner.destroy()
    }
}
