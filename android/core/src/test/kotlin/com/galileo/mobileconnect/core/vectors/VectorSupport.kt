package com.galileo.mobileconnect.core.vectors

import com.galileo.mobileconnect.core.link.LinkClock
import com.galileo.mobileconnect.core.link.StreamTransport
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import java.io.File
import java.io.IOException
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume

/** A clock only the test moves. */
class TestClock(start: Instant) : LinkClock {
    private class Sleeper(val deadline: Instant, val continuation: CancellableContinuation<Unit>)

    private val lock = Any()
    private var current = start
    private val sleepers = mutableListOf<Sleeper>()

    override fun now(): Instant = synchronized(lock) { current }

    override suspend fun sleep(seconds: Double) = suspendCancellableCoroutine { continuation ->
        val sleeper = synchronized(lock) {
            Sleeper(current.plusMillis((seconds * 1000).toLong()), continuation).also { sleepers.add(it) }
        }
        continuation.invokeOnCancellation { synchronized(lock) { sleepers.remove(sleeper) } }
    }

    fun advance(seconds: Double) {
        val due = synchronized(lock) {
            current = current.plusMillis((seconds * 1000).toLong())
            sleepers.filter { it.deadline <= current }.also { sleepers.removeAll(it) }
        }
        due.forEach { it.continuation.resume(Unit) }
    }
}

/** What the client asked a vector's Master for. */
class SeenRequest(
    val id: Int,
    val method: String,
    val path: String,
    val headers: Map<String, String>,
    val body: JsonElement?,
)

/**
 * A Master whose every answer comes from the vector: a request stays in flight until the `receive` step. It is an
 * OkHttp application interceptor, so the real generated client and the real [com.galileo.mobileconnect.core.link.MasterApi]
 * run against it with no network (the Kotlin counterpart of the iOS `VectorTransport`).
 */
class VectorMaster : Interceptor {
    private val lock = Any()
    private val unclaimed = ArrayDeque<SeenRequest>()
    private val inFlight = HashMap<Int, CompletableFuture<Response>>()
    private var lastClaimed: Int? = null
    private val counter = AtomicInteger()

    val total: Int get() = counter.get()

    /** How many requests went out with each `Idempotency-Key`: the proof that nothing was re-sent. */
    val keys: Map<String, Int> get() = synchronized(lock) { HashMap(keyCounts) }
    private val keyCounts = HashMap<String, Int>()

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val body = request.body?.let { b ->
            val buffer = Buffer().also { b.writeTo(it) }
            runCatching { Json.parseToJsonElement(buffer.readUtf8()) }.getOrNull()
        }
        val id = counter.incrementAndGet()
        val future = CompletableFuture<Response>()
        val path = request.url.encodedPath + (request.url.encodedQuery?.let { "?$it" } ?: "")
        synchronized(lock) {
            request.header("Idempotency-Key")?.let { keyCounts[it] = (keyCounts[it] ?: 0) + 1 }
            inFlight[id] = future
            unclaimed.addLast(
                SeenRequest(id, request.method, path, request.headers.toMultimap().mapValues { it.value.first() }, body),
            )
        }
        return try {
            val answered = future.get()
            answered.newBuilder().request(request).build()
        } catch (e: ExecutionException) {
            throw (e.cause as? IOException) ?: IOException(e.cause)
        }
    }

    fun claim(): SeenRequest? = synchronized(lock) {
        unclaimed.removeFirstOrNull()?.also { lastClaimed = it.id }
    }

    /** Answers the request the last `send` step observed. An earlier one may stay unanswered on purpose. */
    fun answer(status: Int, body: JsonElement) {
        val future = synchronized(lock) {
            val id = lastClaimed ?: throw VectorFailure("a receive step with no request in flight")
            lastClaimed = null
            inFlight.remove(id) ?: throw VectorFailure("a receive step with no request in flight")
        }
        future.complete(
            Response.Builder()
                .request(okhttp3.Request.Builder().url("https://placeholder.invalid/").build())
                .protocol(Protocol.HTTP_1_1)
                .code(status)
                .message("vector")
                .body(Json.encodeToString(JsonElement.serializer(), body).toResponseBody("application/json".toMediaType()))
                .build(),
        )
    }

    fun failAll() {
        val waiting = synchronized(lock) { inFlight.values.toList().also { inFlight.clear() } }
        waiting.forEach { it.completeExceptionally(IOException("connection lost")) }
    }
}

/** The vector's event stream: frames arrive when a `stream` step delivers them, and `drop` ends the connection. */
class VectorStream : StreamTransport {
    private val lock = Any()
    private var channel: Channel<String>? = null
    private val seen = java.util.concurrent.CopyOnWriteArrayList<Pair<String?, Int?>>()

    /** Every `(epoch, last)` the client connected with, in order. */
    val connects: List<Pair<String?, Int?>> get() = seen
    val open: Boolean get() = synchronized(lock) { channel != null }

    override fun connect(epoch: String?, last: Int?): Flow<String> = flow {
        val mine = Channel<String>(Channel.UNLIMITED)
        synchronized(lock) {
            channel = mine
            seen.add(epoch to last)
        }
        for (frame in mine) emit(frame)
    }

    fun deliver(frame: JsonElement) {
        val target = synchronized(lock) { channel } ?: throw VectorFailure("a stream step with no connection")
        target.trySend(Json.encodeToString(JsonElement.serializer(), frame))
    }

    fun drop() {
        synchronized(lock) {
            channel?.close(IOException("connection lost"))
            channel = null
        }
    }
}

class VectorFailure(message: String) : Exception(message)

/** The vectors directory, found by walking up from the test's working directory. */
object VectorFiles {
    val directory: File by lazy {
        generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
            .map { File(it, "api/vectors") }
            .first { it.isDirectory }
    }

    fun all(): List<File> = directory.listFiles { f -> f.extension == "json" }!!.sortedBy { it.name }
}

/** Waits for an asynchronous condition. The client is concurrent, so vectors poll what they expect. */
suspend fun eventually(timeoutMillis: Long = 3000, condition: () -> Boolean): Boolean {
    val deadline = System.nanoTime() + timeoutMillis * 1_000_000
    while (System.nanoTime() < deadline) {
        if (condition()) return true
        delay(5)
    }
    return condition()
}

/** `expected` is a subset of `actual`. */
fun contains(actual: JsonElement, expected: JsonElement): Boolean {
    if (expected is JsonObject) {
        if (expected.isEmpty()) return actual is JsonObject
        val have = actual as? JsonObject ?: return false
        return expected.all { (key, value) -> have[key]?.let { contains(it, value) } ?: false }
    }
    return actual == expected
}

val JsonNullElement: JsonElement = JsonNull
