package com.galileo.mobileconnect.core.sky

/** One catalog object as the stream carries it: where, how bright, and its stable catalog index. */
data class SkyObject(
    val index: Int,
    val raDeg: Float,
    val decDeg: Float,
    val magnitude: Float,
    /** 0 star, 1 deep-sky. */
    val kind: Int,
) {
    val isDeepSky: Boolean get() = kind == 1
}

sealed class SkyFrameError(message: String) : Exception(message) {
    data object NotASkyFrame : SkyFrameError("Not a sky frame")
    data class UnsupportedEncoding(val version: Int) : SkyFrameError("Unsupported sky frame version $version")
    data object Truncated : SkyFrameError("Truncated sky frame")
}

/** `galileo.skystream`'s binary frame, isolated here so that a format change is one component (S-SKY). */
object SkyFrameDecoder {
    val supportedVersions = setOf(1)
    private val magic = byteArrayOf(0x47, 0x53, 0x4B) // "GSK"
    private const val HEADER_SIZE = 9
    private const val POSITION_QUANTUM = 1000.0
    private const val MAGNITUDE_QUANTUM = 100.0

    /**
     * Header (magic, version, kind, little-endian count), then delta-coded zigzag varints of RA, Dec, magnitude and
     * index, and one byte of object kind, for each object.
     */
    fun decode(bytes: ByteArray): List<SkyObject> {
        if (bytes.size < HEADER_SIZE || !bytes.copyOfRange(0, 3).contentEquals(magic)) throw SkyFrameError.NotASkyFrame
        val version = bytes[3].toInt() and 0xFF
        if (version !in supportedVersions || bytes[4].toInt() != 1) throw SkyFrameError.UnsupportedEncoding(version)
        fun u(i: Int) = bytes[i].toInt() and 0xFF
        val count = u(5) or (u(6) shl 8) or (u(7) shl 16) or (u(8) shl 24)
        var position = HEADER_SIZE

        fun varint(): Long {
            var shift = 0
            var result = 0L
            while (true) {
                if (position >= bytes.size || shift >= 64) throw SkyFrameError.Truncated
                val byte = bytes[position].toInt() and 0xFF
                position += 1
                result = result or ((byte and 0x7F).toLong() shl shift)
                if (byte and 0x80 == 0) break
                shift += 7
            }
            return (result ushr 1) xor -(result and 1)
        }

        val objects = ArrayList<SkyObject>(minOf(count, 100_000))
        var ra = 0L
        var dec = 0L
        var mag = 0L
        var index = 0L
        for (n in 0 until count) {
            ra += varint()
            dec += varint()
            mag += varint()
            index += varint()
            if (position >= bytes.size) throw SkyFrameError.Truncated
            val kind = bytes[position].toInt() and 0xFF
            position += 1
            objects += SkyObject(
                index = index.toInt(), raDeg = (ra / POSITION_QUANTUM).toFloat(), decDeg = (dec / POSITION_QUANTUM).toFloat(),
                magnitude = (mag / MAGNITUDE_QUANTUM).toFloat(), kind = kind,
            )
        }
        return objects
    }

    /** The inverse, for the mock Master: a client never sends one. */
    fun encode(objects: List<SkyObject>): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        fun put(value: Long) {
            var v = (value shl 1) xor (value shr 63)
            while ((v ushr 7) != 0L) {
                out.write(((v and 0x7F) or 0x80).toInt())
                v = v ushr 7
            }
            out.write(v.toInt())
        }
        out.write(magic)
        out.write(1)
        out.write(1)
        val count = objects.size
        out.write(count and 0xFF)
        out.write((count shr 8) and 0xFF)
        out.write((count shr 16) and 0xFF)
        out.write((count shr 24) and 0xFF)
        var previous = longArrayOf(0, 0, 0, 0)
        for (o in objects.sortedBy { it.raDeg }) {
            val current = longArrayOf(
                Math.round(o.raDeg.toDouble() * POSITION_QUANTUM), Math.round(o.decDeg.toDouble() * POSITION_QUANTUM),
                Math.round(o.magnitude.toDouble() * MAGNITUDE_QUANTUM), o.index.toLong(),
            )
            for (i in 0 until 4) put(current[i] - previous[i])
            out.write(o.kind)
            previous = current
        }
        return out.toByteArray()
    }
}
