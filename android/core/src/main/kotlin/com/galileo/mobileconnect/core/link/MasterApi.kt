package com.galileo.mobileconnect.core.link

import com.galileo.mobileconnect.api.client.CatalogueApi
import com.galileo.mobileconnect.api.client.ImagesApi
import com.galileo.mobileconnect.api.client.OperationsApi
import com.galileo.mobileconnect.api.client.PairingApi
import com.galileo.mobileconnect.api.client.SessionApi
import com.galileo.mobileconnect.api.infrastructure.ClientError
import com.galileo.mobileconnect.api.infrastructure.ClientException
import com.galileo.mobileconnect.api.infrastructure.ServerError
import com.galileo.mobileconnect.api.infrastructure.ServerException
import com.galileo.mobileconnect.api.model.ClientInfo
import com.galileo.mobileconnect.api.model.HandshakeRequest
import com.galileo.mobileconnect.api.model.HandshakeResult
import com.galileo.mobileconnect.api.model.OperationRequest
import com.galileo.mobileconnect.api.model.PairingRequest
import com.galileo.mobileconnect.core.api.ArgumentInfo
import com.galileo.mobileconnect.core.api.Capabilities
import com.galileo.mobileconnect.core.api.CatalogueFilter
import com.galileo.mobileconnect.core.api.HandshakeOutcome
import com.galileo.mobileconnect.core.api.Identity
import com.galileo.mobileconnect.core.api.LeaseAnswer
import com.galileo.mobileconnect.core.api.LeaseRefusal
import com.galileo.mobileconnect.core.api.OpResult
import com.galileo.mobileconnect.core.api.OperationInfo
import com.galileo.mobileconnect.core.api.PierInfo
import com.galileo.mobileconnect.core.state.AlertEvent
import com.galileo.mobileconnect.core.state.bool
import com.galileo.mobileconnect.core.state.get
import com.galileo.mobileconnect.core.state.string
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Call
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.SSLContext

/** The bearer token, shared by every request the client makes. Held in memory only; the store persists it. */
class TokenHolder(initial: String? = null) {
    private val ref = AtomicReference(initial)
    var current: String?
        get() = ref.get()
        set(value) = ref.set(value)
}

/** The one place the app's identity and token are added to a request (MAPP-CTL-080, S-API "Identity"). */
class IdentityInterceptor(private val identity: ClientIdentity, private val holder: TokenHolder) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val builder = chain.request().newBuilder().header("User-Agent", identity.userAgent)
        holder.current?.let { builder.header("Authorization", "Bearer $it") }
        return chain.proceed(builder.build())
    }
}

/**
 * The generated client's JSON settings write an unset optional field as `null` (`encodeDefaults`), where the iOS client
 * leaves it out. The Master reads absence, so the two clients must say the same thing: top-level nulls are dropped from
 * a JSON request body. Only the top level: a `null` inside `args` is the caller's own value.
 */
object DropNullFields : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val body = request.body
        if (body == null || body.contentType()?.subtype != "json") return chain.proceed(request)
        val parsed = runCatching { Json.parseToJsonElement(okio.Buffer().also { body.writeTo(it) }.readUtf8()) }.getOrNull()
        if (parsed !is JsonObject || parsed.values.none { it is JsonNull }) return chain.proceed(request)
        val cleaned = JsonObject(parsed.filterValues { it !is JsonNull }).toString()
        return chain.proceed(request.newBuilder().method(request.method, cleaned.toRequestBody(body.contentType())).build())
    }
}

object MasterHttp {
    /** An OkHttp client that trusts the Master's certificate only as [policy] says (docs/android/SDD.md 2.3). */
    fun pinnedClient(policy: PinPolicy, identity: ClientIdentity, holder: TokenHolder): Pair<OkHttpClient, PinningTrustManager> {
        val trust = PinningTrustManager(policy)
        val context = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), null) }
        val client = OkHttpClient.Builder()
            .sslSocketFactory(context.socketFactory, trust)
            .hostnameVerifier(PinnedHostnameVerifier)
            .addInterceptor(IdentityInterceptor(identity, holder))
            .addInterceptor(DropNullFields)
            .pingInterval(java.time.Duration.ofSeconds(20)) // the Master's WebSocket heartbeat is 20 s (docs/SDD.md 2.3)
            .build()
        return client to trust
    }
}

/**
 * S-API's facade over the generated client: the calls the link layer needs, with every failure turned into a
 * [LinkError]. Nothing generated is edited by hand. [identityChanged] says whether the TLS layer refused a certificate,
 * which is what tells a pin mismatch from a dropped connection.
 */
class MasterApi(
    internal val baseUrl: String,
    internal val client: Call.Factory,
    private val identity: ClientIdentity,
    val holder: TokenHolder = TokenHolder(),
    private val identityChanged: () -> Boolean = { false },
) {
    private val pairing = PairingApi(baseUrl, client)
    private val session = SessionApi(baseUrl, client)
    private val catalogue = CatalogueApi(baseUrl, client)
    private val operations = OperationsApi(baseUrl, client)
    private val images = ImagesApi(baseUrl, client)
    internal val skyApi = com.galileo.mobileconnect.api.client.SkyApi(baseUrl, client)

    data class PairingStart(
        val requestId: String,
        val code: String,
        val expiresIn: Double,
        val fingerprint: String,
        val masterProtocol: Int,
    )

    suspend fun requestPairing(): PairingStart = calling {
        val started = pairing.requestPairing(
            PairingRequest(plugin = "mobileconnect", name = identity.deviceName.take(80), kind = "app", platform = identity.platform),
        )
        PairingStart(started.requestId, started.code, started.expiresIn, started.fingerprint, started.protocol)
    }.mapStatus(
        429 to "Too many pairing requests from this device. Wait a minute and try again.",
        503 to "Too many pairing requests are waiting on the Master. Deal with those first, then try again.",
        400 to "The Master refused the pairing request.",
        what = "the pairing request",
    )

    /** `pending`, `approved`, `rejected`, `expired` or `collected`, and the token, once, with `approved`. */
    suspend fun pollPairing(requestId: String): Pair<String, String?> = calling {
        val state = pairing.pollPairing(requestId)
        state.state.value to state.token
    }.mapStatus(what = "pairing")

    suspend fun handshake(): HandshakeOutcome = calling {
        session.handshake(
            HandshakeRequest(
                protocol = LinkProtocol.VERSION,
                schema = 0,
                plugin = ClientInfo(appVersion = identity.appVersion, platform = identity.platform, clientClass = identity.clientClass),
            ),
        ).let {
            HandshakeOutcome(
                verdict = when (it.verdict) {
                    HandshakeResult.Verdict.OK -> HandshakeOutcome.Verdict.Ok
                    HandshakeResult.Verdict.RESTRICTED -> HandshakeOutcome.Verdict.Restricted
                    HandshakeResult.Verdict.REFUSED -> HandshakeOutcome.Verdict.Refused
                },
                message = it.message, allowed = it.allowed.toSet(),
                masterProtocol = it.node.protocol, galileoVersion = it.node.galileo,
            )
        }
    }.mapStatus(400 to "The Master did not understand this app's version details.", what = "the version handshake")

    suspend fun whoAmI(): Identity = calling {
        session.whoami().let { Identity(it.peerId, it.name, it.permissions.toSet()) }
    }.mapStatus(what = "the identity request")

    /** Null when the Master does not offer the route yet: an old node simply lacks it. */
    suspend fun capabilities(): Capabilities? = calling {
        try {
            catalogue.getCapabilities().let {
                Capabilities(it.areas, it.skyStream?.available, it.skyStream?.reason, it.skyStream?.ratePerS)
            }
        } catch (e: ClientException) {
            if (e.statusCode == 403 || e.statusCode == 404) null else throw e
        }
    }.mapStatus(what = "the capabilities request")

    /** The Piers, and the Master's reason when there are none. */
    suspend fun piers(): Pair<List<PierInfo>, String?> = calling {
        catalogue.listPiers().let { list ->
            list.piers.map {
                PierInfo(
                    id = it.id, name = it.name, node = it.node, observatory = it.observatory,
                    access = PierInfo.Access.of(it.access.value) ?: PierInfo.Access.None,
                    reachable = it.reachable, reason = it.reason,
                )
            } to list.reason
        }
    }.mapStatus(what = "the Pier list")

    suspend fun catalogue(pier: String?): List<OperationInfo> = calling {
        catalogue.getCatalogue(pier).operations.mapNotNull(::operationInfo)
    }.mapStatus(what = "the catalogue")

    suspend fun alerts(): List<AlertEvent> = calling {
        images.listAlerts().alerts.map {
            AlertEvent(it.id, it.severity.value, it.time.toInstant(), it.pier, it.message, it.important ?: false)
        }
    }.mapStatus(what = "the alert list")

    /** Asks for a snapshot on this client's stream: the stream has no connection hook on the Master. */
    suspend fun requestSnapshot() {
        calling { catalogue.requestSnapshot() }.mapStatus(what = "the snapshot request")
    }

    // -- operations -----------------------------------------------------------------------------------------------

    /**
     * Asks the Master for an operation. Never throws for the Master's answers: each is a typed [OpResult]. A request
     * that may or may not have arrived is [OpResult.ConnectionLost], never done and never retried here (MAPP-CTL-070).
     */
    suspend fun invoke(
        pier: String,
        name: String,
        args: Map<String, JsonElement>,
        idempotencyKey: String,
        confirmToken: String? = null,
        emergencyConfirmed: Boolean = false,
    ): OpResult = try {
        val ok = operations.invokeOperation(
            pier, name, OperationRequest(args = args, confirmed = if (emergencyConfirmed) true else null),
            idempotencyKey, confirmToken,
        )
        OpResult.Ok(ok.result?.takeIf { it !is JsonNull }, ok.correlationId)
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: ClientException) {
        failure(e.statusCode, (e.response as? ClientError<*>)?.body as? String)
    } catch (e: ServerException) {
        failure(e.statusCode, (e.response as? ServerError<*>)?.body as? String)
    } catch (e: IOException) {
        OpResult.ConnectionLost
    } catch (e: Exception) {
        OpResult.Unavailable("The Master gave an answer this app can't read.")
    }

    private fun failure(status: Int, body: String?): OpResult {
        if (status == 401) return OpResult.Refused(LinkError.Unpaired.message, "unpaired", null, false)
        val json = body?.let { runCatching { Json.parseToJsonElement(it) }.getOrNull() }
        val code = json["error"].string ?: return OpResult.Unavailable("The Master gave an unexpected answer ($status).")
        val reason = json["reason"].string ?: "The Master gave no reason."
        val detail = json["detail"]
        return when (code) {
            "refused" -> OpResult.Refused(reason, detail["kind"].string, detail["holder"].string, detail["can_take"].bool ?: false)
            "invalid" -> OpResult.Invalid(detail["path"].string, reason)
            "unavailable" -> OpResult.Unavailable(reason)
            "busy" -> OpResult.Busy(reason)
            "unreachable" -> OpResult.Unreachable(reason)
            "confirmation_required" ->
                json["confirm_token"].string?.let { OpResult.ConfirmationRequired(it, json["reason"].string) }
                    ?: OpResult.Unavailable("The Master asked for a confirmation but gave no way to confirm.")
            else -> OpResult.Unavailable("The Master gave an unexpected answer ($status).")
        }
    }

    /** Takes the lease, or takes it over when [takeOverToken] is the token the refusal offered. */
    suspend fun acquireLease(pier: String, takeOverToken: String? = null): LeaseOutcome = try {
        operations.acquireLease(pier, takeOverToken).let { LeaseOutcome.Granted(LeaseAnswer(it.heldByMe, it.holder)) }
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: ClientException) {
        LeaseOutcome.Refused(leaseRefusal(e.statusCode, (e.response as? ClientError<*>)?.body as? String))
    } catch (e: ServerException) {
        LeaseOutcome.Refused(leaseRefusal(e.statusCode, (e.response as? ServerError<*>)?.body as? String))
    } catch (e: IOException) {
        LeaseOutcome.Refused(LeaseRefusal("The Master can't be reached."))
    } catch (e: Exception) {
        LeaseOutcome.Refused(LeaseRefusal("The Master gave an answer this app can't read."))
    }

    private fun leaseRefusal(status: Int, body: String?): LeaseRefusal {
        if (status == 401) return LeaseRefusal(LinkError.Unpaired.message)
        val json = body?.let { runCatching { Json.parseToJsonElement(it) }.getOrNull() }
        val detail = json["detail"]
        return LeaseRefusal(
            reason = json["reason"].string ?: "The Master refused control.",
            holder = detail["holder"].string,
            takeOverToken = if (detail["can_take"].bool == true) json["confirm_token"].string else null,
        )
    }

    suspend fun releaseLease(pier: String): Boolean = try {
        operations.releaseLease(pier)
        true
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        false
    }

    // -- error mapping --------------------------------------------------------------------------------------------

    private class Attempt<T>(val value: T?, val failure: Throwable?, val api: MasterApi) {
        @Suppress("UNCHECKED_CAST")
        fun mapStatus(vararg messages: Pair<Int, String>, what: String): T {
            if (failure == null) return value as T
            val status = when (failure) {
                is ClientException -> failure.statusCode
                is ServerException -> failure.statusCode
                else -> null
            }
            throw when {
                failure is LinkError -> failure
                status == 401 -> LinkError.Unpaired
                status != null -> LinkError.Unexpected(
                    messages.firstOrNull { it.first == status }?.second
                        ?: "The Master gave an unexpected answer ($status) to $what.",
                )
                else -> LinkError.Unexpected("The Master gave an answer this app can't read.")
            }
        }
    }

    private suspend fun <T> calling(block: suspend () -> T): Attempt<T> = try {
        Attempt(block(), null, this)
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: IOException) {
        throw if (identityChanged()) LinkError.IdentityChanged else LinkError.Unreachable(e.message ?: "")
    } catch (e: Exception) {
        Attempt(null, e, this)
    }
}

/** The Master's answer to a request for the control lease. */
sealed interface LeaseOutcome {
    data class Granted(val answer: LeaseAnswer) : LeaseOutcome
    data class Refused(val refusal: LeaseRefusal) : LeaseOutcome
}

internal fun operationInfo(entry: com.galileo.mobileconnect.api.model.Operation): OperationInfo? {
    val cls = CatalogueFilter.accept(entry.name, entry.propertyClass) ?: return null
    return OperationInfo(
        name = entry.name, area = entry.area, operationClass = cls, needsLease = entry.lease,
        arguments = entry.args.mapNotNull { arg ->
            val kind = ArgumentInfo.Kind.of(arg.type.value) ?: return@mapNotNull null
            ArgumentInfo(
                name = arg.name, kind = kind, required = arg.required, unit = arg.unit, description = arg.description,
                defaultValue = arg.default?.takeIf { it !is JsonNull }, minimum = arg.min, maximum = arg.max,
                choices = arg.choices ?: emptyList(),
            )
        },
        description = entry.description, available = entry.available ?: true,
        unavailableBecause = entry.unavailableBecause, longRunning = entry.longRunning ?: false,
    )
}
