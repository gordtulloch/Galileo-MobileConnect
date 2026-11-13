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
import androidx.compose.runtime.Composable
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
                        setContent(content)
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
