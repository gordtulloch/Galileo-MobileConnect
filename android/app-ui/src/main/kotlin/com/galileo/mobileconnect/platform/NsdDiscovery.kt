package com.galileo.mobileconnect.platform

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import com.galileo.mobileconnect.core.link.DiscoveredMaster
import com.galileo.mobileconnect.core.link.DiscoveryEvent
import com.galileo.mobileconnect.core.link.LinkError
import com.galileo.mobileconnect.core.link.MasterAddress
import com.galileo.mobileconnect.core.link.MasterDiscovery
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Finds `_galileo-link._tcp` with `NsdManager` (docs/android/SDD.md 3.3). The advertisement's TXT record is only readable
 * after resolving, so the list shows the service name and the address is resolved when the user chooses one.
 */
class NsdDiscovery(private val context: Context) : MasterDiscovery {
    private val manager get() = context.getSystemService(Context.NSD_SERVICE) as NsdManager

    override fun events(): Flow<DiscoveryEvent> = callbackFlow {
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val lock = wifi?.createMulticastLock("galileo-discovery")?.apply { setReferenceCounted(false) }
        runCatching { lock?.acquire() }
        val found = LinkedHashMap<String, NsdServiceInfo>()
        fun publish() = trySend(
            DiscoveryEvent.Results(found.values.map { DiscoveredMaster(it.serviceName, it.serviceName, null, "") }.sortedBy { it.name }),
        )
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) = Unit

            override fun onServiceFound(info: NsdServiceInfo) {
                found[info.serviceName] = info
                publish()
            }

            override fun onServiceLost(info: NsdServiceInfo) {
                found.remove(info.serviceName)
                publish()
            }

            override fun onDiscoveryStopped(serviceType: String) = Unit

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                trySend(DiscoveryEvent.Failed("The network search could not start (error $errorCode)."))
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
        }
        try {
            manager.discoverServices("${MasterDiscovery.SERVICE_TYPE}.", NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (e: SecurityException) {
            trySend(DiscoveryEvent.PermissionDenied)
        } catch (e: Exception) {
            trySend(DiscoveryEvent.Failed(e.message ?: "The network search could not start."))
        }
        awaitClose {
            runCatching { manager.stopServiceDiscovery(listener) }
            runCatching { lock?.release() }
        }
    }

    @Suppress("DEPRECATION")
    override suspend fun resolve(master: DiscoveredMaster): MasterAddress = try {
        withTimeout(8_000) {
            suspendCancellableCoroutine { continuation ->
                val info = NsdServiceInfo().apply {
                    serviceName = master.id
                    serviceType = "${MasterDiscovery.SERVICE_TYPE}."
                }
                manager.resolveService(
                    info,
                    object : NsdManager.ResolveListener {
                        override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                            if (continuation.isActive) continuation.resumeWithException(LinkError.Unreachable("${master.name} did not answer."))
                        }

                        override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                            val host = serviceInfo.host?.hostAddress?.substringBefore('%') // a URL can't carry a scope id
                            if (!continuation.isActive) return
                            if (host == null) {
                                continuation.resumeWithException(LinkError.Unexpected("Could not find the address of ${master.name}."))
                            } else {
                                continuation.resume(MasterAddress(host, serviceInfo.port))
                            }
                        }
                    },
                )
            }
        }
    } catch (e: TimeoutCancellationException) {
        throw LinkError.Unreachable("${master.name} did not answer.")
    }
}
