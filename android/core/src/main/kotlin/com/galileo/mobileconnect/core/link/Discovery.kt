package com.galileo.mobileconnect.core.link

import kotlinx.coroutines.flow.Flow

/** A Master seen on the network. The advertisement carries only a name, a node, a protocol and a role. */
data class DiscoveredMaster(val id: String, val name: String, val protocolVersion: Int?, val role: String)

sealed interface DiscoveryEvent {
    data class Results(val masters: List<DiscoveredMaster>) : DiscoveryEvent

    /** The platform refused the local-network permission (MAPP-PLT-070). */
    data object PermissionDenied : DiscoveryEvent
    data class Failed(val message: String) : DiscoveryEvent
}

/**
 * Finds `_galileo-link._tcp` on the local network (`galileo/connect_link/discovery.py`). The address is resolved when the
 * user chooses a Master. `:app` implements this over `NsdManager`; the core never touches the platform.
 */
interface MasterDiscovery {
    fun events(): Flow<DiscoveryEvent>

    /** Throws [LinkError] when the Master can't be resolved or doesn't answer. */
    suspend fun resolve(master: DiscoveredMaster): MasterAddress

    companion object {
        const val SERVICE_TYPE = "_galileo-link._tcp"
    }
}
