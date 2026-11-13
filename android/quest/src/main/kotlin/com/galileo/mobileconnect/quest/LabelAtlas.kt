package com.galileo.mobileconnect.quest

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface

/**
 * The names drawn on the sky (constellations, bodies, the selection, N E S W) as one bitmap, white on clear: the renderer
 * tints it, so Night stays red. Rebuilt only when the set of names changes.
 */
class LabelAtlas private constructor(val bitmap: Bitmap, private val places: Map<String, Place>) {
    class Place(val u0: Float, val v0: Float, val u1: Float, val v1: Float, val widthDegrees: Float, val heightDegrees: Float)

    fun place(text: String): Place? = places[text]

    companion object {
        private const val WIDTH = 1024
        private const val CELL_HEIGHT = 44
        private const val TEXT_SIZE = 32f
        private const val PADDING = 8
        const val HEIGHT_DEGREES = 2.4f

        fun build(strings: Collection<String>): LabelAtlas {
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textSize = TEXT_SIZE
                typeface = Typeface.DEFAULT_BOLD
            }
            val distinct = strings.distinct().sorted()
            val widths = distinct.associateWith { paint.measureText(it).toInt() + PADDING * 2 }
            // Lay the names out in rows; the bitmap is as tall as it needs to be, rounded up to a power of two.
            var x = 0
            var rows = 1
            for (s in distinct) {
                val w = widths.getValue(s)
                if (x + w > WIDTH) {
                    rows += 1
                    x = 0
                }
                x += w
            }
            var height = 64
            while (height < rows * CELL_HEIGHT) height *= 2
            val bitmap = Bitmap.createBitmap(WIDTH, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            val places = HashMap<String, Place>()
            x = 0
            var row = 0
            for (s in distinct) {
                val w = widths.getValue(s)
                if (x + w > WIDTH) {
                    row += 1
                    x = 0
                }
                val top = row * CELL_HEIGHT
                canvas.drawText(s, (x + PADDING).toFloat(), (top + CELL_HEIGHT - 12).toFloat(), paint)
                places[s] = Place(
                    x.toFloat() / WIDTH, top.toFloat() / height, (x + w).toFloat() / WIDTH, (top + CELL_HEIGHT).toFloat() / height,
                    widthDegrees = HEIGHT_DEGREES * w / CELL_HEIGHT, heightDegrees = HEIGHT_DEGREES,
                )
                x += w
            }
            return LabelAtlas(bitmap, places)
        }
    }
}
