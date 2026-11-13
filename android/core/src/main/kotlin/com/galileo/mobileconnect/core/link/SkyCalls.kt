package com.galileo.mobileconnect.core.link

import com.galileo.mobileconnect.api.infrastructure.ClientException
import com.galileo.mobileconnect.core.sky.EphemerisBody
import com.galileo.mobileconnect.core.sky.EphemerisSample
import com.galileo.mobileconnect.core.sky.IdentifiedObject
import com.galileo.mobileconnect.core.sky.PositionCheck
import com.galileo.mobileconnect.core.sky.PreviewImage
import com.galileo.mobileconnect.core.sky.SiteAnswer
import com.galileo.mobileconnect.core.sky.SkyConstellations
import com.galileo.mobileconnect.core.sky.SkyFrameDecoder
import com.galileo.mobileconnect.core.sky.SkyPage
import com.galileo.mobileconnect.core.sky.SkySite
import com.galileo.mobileconnect.core.sky.SkyTile
import com.galileo.mobileconnect.core.sky.SkyTileImage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import java.time.Instant
import java.time.ZoneOffset

/**
 * What a binary route answered. These routes bypass the generated client on purpose: it would write each body to a
 * temporary file (the sky and the pictures are held in memory only, MAPP-STR-050) and drop the headers they carry.
 */
internal class BinaryAnswer(val status: Int, val headers: Headers, val bytes: ByteArray)

internal suspend fun MasterApi.binaryGet(path: String, query: Map<String, Any?>, limit: Int): BinaryAnswer {
    val url = (baseUrl + path).toHttpUrl().newBuilder().apply {
        for ((key, value) in query) if (value != null) addQueryParameter(key, value.toString())
    }.build()
    return withContext(Dispatchers.IO) {
        client.newCall(Request.Builder().url(url).build()).execute().use { response ->
            val body = response.body
            if (body != null && body.contentLength() > limit) throw java.io.IOException("The answer is larger than this app will take.")
            BinaryAnswer(response.code, response.headers, body?.bytes() ?: ByteArray(0))
        }
    }
}

/** Runs a read, answering null for any failure but cancellation: the sky and the pictures say so and ask again later. */
private suspend fun <T> quiet(block: suspend () -> T): T? = try {
    block()
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    null
}

private fun Instant.offset() = atOffset(ZoneOffset.UTC)

suspend fun MasterApi.skyPage(ra: Double, dec: Double, radius: Double, level: Int, cursor: String?): SkyPage {
    val answer = try {
        binaryGet("/mobileconnect/v1/sky/catalog", mapOf("ra" to ra, "dec" to dec, "radius" to radius, "lod" to level, "cursor" to cursor), 16 shl 20)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        return SkyPage.BackOff // the link: the view says so, and the next look asks again
    }
    return when (answer.status) {
        200 -> try {
            SkyPage.Page(SkyFrameDecoder.decode(answer.bytes), answer.headers["X-Sky-Next"] ?: "")
        } catch (e: Exception) {
            SkyPage.Refused("The Master's sky data couldn't be read.")
        }
        429, 503 -> SkyPage.BackOff
        400 -> SkyPage.Refused("The Master refused that part of the sky.")
        401 -> SkyPage.Refused(LinkError.Unpaired.message)
        else -> SkyPage.Refused("The Master gave an unexpected answer (${answer.status}).")
    }
}

suspend fun MasterApi.ephemeris(from: Instant, to: Instant, stepSeconds: Double): List<EphemerisSample>? = quiet {
    skyApi.getEphemeris(null, from.offset(), to.offset(), stepSeconds).samples.map { sample ->
        EphemerisSample(sample.t.toInstant(), sample.bodies.map { EphemerisBody(it.name, it.raDeg, it.decDeg) })
    }
}

suspend fun MasterApi.skySite(pier: String): SiteAnswer = try {
    val site = skyApi.getSite(pier)
    SiteAnswer.Found(
        SkySite(site.name, site.latitudeDeg, site.longitudeDeg, site.horizon.map { SkySite.HorizonPoint(it.azDeg, it.altDeg) }),
    )
} catch (e: CancellationException) {
    throw e
} catch (e: ClientException) {
    if (e.statusCode == 404) SiteAnswer.NoLocation else SiteAnswer.Failed
} catch (e: Exception) {
    SiteAnswer.Failed
}

/** Null if the Master can't get it just now: ask again later. */
suspend fun MasterApi.surveyTile(tile: SkyTile): SkyTileImage? = quiet {
    val answer = binaryGet("/mobileconnect/v1/sky/survey/tile", mapOf("order" to tile.order, "npix" to tile.npix), 4 shl 20)
    if (answer.status != 200) null else SkyTileImage(answer.bytes, answer.headers["X-Image-Credit"] ?: "")
}

/** Which deep-sky objects are in which catalog, by the Master's index. Empty if this Master can't say. */
suspend fun MasterApi.skyCatalogs(): Map<String, Set<Int>> = quiet {
    skyApi.getSkyCatalogs().catalogs.associate { it.name to it.indexes.toSet() }
} ?: emptyMap()

suspend fun MasterApi.constellations(): SkyConstellations? = quiet {
    val body = skyApi.getConstellations()
    SkyConstellations(
        lines = body.lines.map { line -> line.filter { it.size >= 2 }.map { SkyConstellations.Point(it[0], it[1]) } },
        names = body.names.map { SkyConstellations.Name(it.name, it.raDeg, it.decDeg) },
    )
}

suspend fun MasterApi.checkPosition(pier: String, raDeg: Double, decDeg: Double): PositionCheck? = quiet {
    skyApi.checkPosition(pier, raDeg, decDeg).let { PositionCheck(it.altitudeDeg, it.azimuthDeg, it.aboveHorizon, it.reason) }
}

suspend fun MasterApi.identify(index: Int): IdentifiedObject? = quiet {
    skyApi.identifyObject(index).let {
        IdentifiedObject(it.index, it.kind == "deep_sky", it.name, it.type, it.raDeg, it.decDeg, it.magnitude)
    }
}

/** Which picture to show: the Master renders it (S-IMG). */
enum class PreviewSource(val wire: String) { Current("current"), Stack("stack"), Guide("guide"), Solve("solve") }

/** The Master renders the picture; the stretch (0-100) travels to it so that it agrees with the desktop (S-IMG). */
suspend fun MasterApi.preview(
    pier: String, width: Int, quality: Int, stretch: Int, source: PreviewSource = PreviewSource.Current,
): PreviewImage? {
    val answer = binaryGet(
        "/mobileconnect/v1/preview",
        mapOf("pier" to pier, "source" to source.wire, "w" to width, "q" to quality, "stretch" to stretch),
        64 shl 20,
    )
    return when (answer.status) {
        200 -> PreviewImage(answer.bytes, answer.headers["X-Frame-Width"]?.toIntOrNull(), answer.headers["X-Frame-Height"]?.toIntOrNull())
        404 -> null
        401 -> throw LinkError.Unpaired
        else -> throw LinkError.Unexpected("The Master gave an unexpected answer (${answer.status}).")
    }
}

suspend fun MasterApi.tile(
    pier: String, level: Int, x: Int, y: Int, stretch: Int, source: PreviewSource = PreviewSource.Current,
): ByteArray? {
    val answer = binaryGet(
        "/mobileconnect/v1/tile",
        mapOf("pier" to pier, "source" to source.wire.takeIf { it != "guide" }, "z" to level, "x" to x, "y" to y, "stretch" to stretch),
        4 shl 20,
    )
    return when (answer.status) {
        200 -> answer.bytes
        404 -> null
        401 -> throw LinkError.Unpaired
        else -> throw LinkError.Unexpected("The Master gave an unexpected answer (${answer.status}).")
    }
}

/** One read-class operation's value, for things the screens ask about rather than do. Not in the ledger. */
suspend fun MasterApi.read(pier: String, name: String, args: Map<String, kotlinx.serialization.json.JsonElement>, key: String) =
    (invoke(pier, name, args, key) as? com.galileo.mobileconnect.core.api.OpResult.Ok)?.value
