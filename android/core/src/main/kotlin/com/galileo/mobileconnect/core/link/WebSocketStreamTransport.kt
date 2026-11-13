package com.galileo.mobileconnect.core.link

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString

/**
 * The real stream: a WebSocket on the same client, and so the same pin, identity and token, as [MasterApi]
 * (docs/android/SDD.md 2.3). [identityChanged] says whether the TLS layer refused a certificate, which is what tells a
 * pin mismatch from an ordinary drop.
 */
class WebSocketStreamTransport(
    private val client: OkHttpClient,
    private val address: MasterAddress,
    private val identityChanged: () -> Boolean = { false },
) : StreamTransport {

    override fun connect(epoch: String?, last: Int?): Flow<String> = callbackFlow {
        // OkHttp reads wss:// as https:// for the upgrade request.
        val request = Request.Builder().url(StreamUrl.of(address, epoch, last)).build()
        val socket = client.newWebSocket(
            request,
            object : WebSocketListener() {
                override fun onMessage(webSocket: WebSocket, text: String) {
                    trySend(text)
                }

                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    trySend(bytes.utf8())
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(1000, null)
                    close()
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    close()
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    close(
                        when {
                            identityChanged() -> LinkError.IdentityChanged
                            response?.code == 401 -> LinkError.Unpaired
                            else -> t
                        },
                    )
                }
            },
        )
        awaitClose { socket.cancel() }
    }.buffer(Channel.UNLIMITED)
}
