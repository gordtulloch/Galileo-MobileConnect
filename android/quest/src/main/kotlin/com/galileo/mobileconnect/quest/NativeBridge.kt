package com.galileo.mobileconnect.quest

import android.graphics.Bitmap
import android.view.Surface
import com.galileo.mobileconnect.core.sky.Vec3

/**
 * The calls into quest.cpp (docs/quest/SDD.md Q-XR). Everything is a push of data or a read of a snapshot: the native layer never
 * calls back, so nothing here can be re-entered from the render thread.
 */
object NativeBridge {
    init {
        // NativeActivity dlopens the library for android_main, which does not make its JNI functions findable from Kotlin.
        System.loadLibrary("galileo_quest")
    }

    /** Positions are J2000 unit vectors; [data] is x, y, z, magnitude per star. */
    @JvmStatic external fun nativeSetStars(data: FloatArray, count: Int)

    /** Constellation figures: pairs of J2000 unit vectors (x, y, z per vertex), drawn as lines. */
    @JvmStatic external fun nativeSetConLines(data: FloatArray, vertexCount: Int)

    /** Deep-sky objects and bodies: x, y, z (J2000), size in degrees, kind (0 round, 1 galaxy, 2 cluster, 3 globular, 4 planetary, 5 nebula, 6 body). */
    @JvmStatic external fun nativeSetMarkers(data: FloatArray, count: Int)

    /** Shapes already in the world frame, true sky directions: x, y, z, r, g, b, a per vertex. */
    @JvmStatic external fun nativeSetOverlay(lines: FloatArray, lineVertices: Int, triangles: FloatArray, triangleVertices: Int)

    /** Labels: [entries] is 9 floats each: world direction x, y, z, atlas u0, v0, u1, v1, width and height in degrees. A null [atlas] keeps the last one. */
    @JvmStatic external fun nativeSetLabels(atlas: Bitmap?, entries: FloatArray, count: Int)

    /** [rotation]: J2000 to world, column-major 3x3. [flags]: 1 night, 2 ground, 4 constellations. */
    @JvmStatic external fun nativeSetSky(
        rotation: FloatArray, zoomCentre: FloatArray, zoom: Float, limitingMagnitude: Float, brightness: Float, flags: Int, ground: FloatArray,
    )

    @JvmStatic external fun nativeCreateQuad(id: Int, pixelWidth: Int, pixelHeight: Int)
    @JvmStatic external fun nativeDestroyQuad(id: Int)

    /** The surface a pane draws into, or null until the render thread has made it. */
    @JvmStatic external fun nativeTakeSurface(id: Int): Surface?

    /** [pose]: centre, right and up (3 floats each), metres, world axes. */
    @JvmStatic external fun nativeSetQuad(id: Int, visible: Boolean, pose: FloatArray, widthMetres: Float, heightMetres: Float)

    /** Fills [out] (at least [INPUT_SIZE]) with the latest snapshot. */
    @JvmStatic external fun nativeGetInput(out: FloatArray): Boolean

    /** Pointer lengths in metres; negative hides a pointer. */
    @JvmStatic external fun nativeSetLaser(right: Float, left: Float)
    @JvmStatic external fun nativeSetPassthrough(on: Boolean)

    const val INPUT_SIZE = 40
    const val BUTTON_A = 1
    const val BUTTON_B = 2
    const val BUTTON_X = 4
    const val BUTTON_Y = 8
    const val BUTTON_MENU = 16
}

/** One hand's slice of the input snapshot. */
class HandInput {
    var tracked = false
    var origin = Vec3(0f, 0f, 0f)
    var direction = Vec3(0f, 0f, -1f)
    var trigger = 0f
    var squeeze = 0f
    var stickX = 0f
    var stickY = 0f
    var stickClick = false
}

class InputSnapshot {
    var focused = false
    var head = Vec3(0f, 0f, 0f)
    var forward = Vec3(0f, 0f, -1f)
    var up = Vec3(0f, 1f, 0f)
    val right = HandInput()
    val left = HandInput()
    var buttons = 0

    fun pressed(mask: Int) = buttons and mask != 0

    fun read(raw: FloatArray) {
        fun v(i: Int) = Vec3(raw[i], raw[i + 1], raw[i + 2])
        focused = raw[0] > 0.5f
        head = v(2)
        forward = v(5)
        up = v(8)
        fun hand(h: HandInput, o: Int) {
            h.tracked = raw[o] > 0.5f
            h.origin = v(o + 1)
            h.direction = v(o + 4)
            h.trigger = raw[o + 7]
            h.squeeze = raw[o + 8]
            h.stickX = raw[o + 9]
            h.stickY = raw[o + 10]
            h.stickClick = raw[o + 11] > 0.5f
        }
        hand(right, 11)
        hand(left, 23)
        buttons = raw[35].toInt()
    }
}
