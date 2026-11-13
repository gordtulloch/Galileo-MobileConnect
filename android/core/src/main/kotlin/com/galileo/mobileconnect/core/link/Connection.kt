package com.galileo.mobileconnect.core.link

import okhttp3.OkHttpClient

/** The real HTTPS and WebSocket clients for one Master, sharing one pinned OkHttp client. */
class MasterConnection(
    val api: MasterApi,
    val stream: StreamTransport,
    /** The pin's verdict on the last handshake, when this connection is a real one. */
    val trust: PinningTrustManager? = null,
) {
    companion object {
        fun live(address: MasterAddress, policy: PinPolicy, identity: ClientIdentity, token: String?): MasterConnection {
            val holder = TokenHolder(token)
            val (client, trust) = MasterHttp.pinnedClient(policy, identity, holder)
            val api = MasterApi(address.baseUrl, client, identity, holder, identityChanged = { trust.identityChanged })
            return MasterConnection(api, WebSocketStreamTransport(client, address) { trust.identityChanged }, trust)
        }
    }
}
