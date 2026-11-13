package com.galileo.mobileconnect.platform

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.galileo.mobileconnect.core.mock.ImageRenderer
import java.io.ByteArrayOutputStream
import kotlin.math.min
import kotlin.math.pow

/** What the demonstration Master does to its one picture: apply the stretch, and cut tiles (S-MOCK, S-IMG). */
class AndroidImageRenderer : ImageRenderer {
    private fun encode(bitmap: Bitmap): ByteArray =
        ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 88, it) }.toByteArray()

    override fun stretched(jpeg: ByteArray, stretch: Double): ByteArray {
        val source = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size) ?: return jpeg
        val power = 2.0.pow((50 - stretch) / 25)
        val table = IntArray(256) { (255 * (it / 255.0).pow(power)).toInt().coerceIn(0, 255) }
        val pixels = IntArray(source.width * source.height)
        source.getPixels(pixels, 0, source.width, 0, 0, source.width, source.height)
        for (i in pixels.indices) {
            val p = pixels[i]
            pixels[i] = (p and 0xFF000000.toInt()) or (table[(p shr 16) and 0xFF] shl 16) or
                (table[(p shr 8) and 0xFF] shl 8) or table[p and 0xFF]
        }
        return encode(Bitmap.createBitmap(pixels, source.width, source.height, Bitmap.Config.ARGB_8888))
    }

    override fun tile(jpeg: ByteArray, level: Int, x: Int, y: Int): ByteArray? {
        val source = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size) ?: return null
        val scale = 1.0 / (1 shl maxOf(0, level))
        val width = (source.width * scale).toInt()
        val height = (source.height * scale).toInt()
        val left = x * 256
        val top = y * 256
        if (left >= width || top >= height || width < 1 || height < 1) return null
        val scaled = Bitmap.createScaledBitmap(source, width, height, true)
        return encode(Bitmap.createBitmap(scaled, left, top, min(256, width - left), min(256, height - top)))
    }
}
