package com.galileo.mobileconnect.core.link

/** What is kept about one paired Master. Lives only in a [SecureStore] (MAPP-CON-040). */
data class MasterCredentials(
    val name: String,
    val address: MasterAddress,
    val token: String,
    /** The pin: lower-case hex SHA-256 of the Master's certificate. */
    val fingerprint: String,
) {
    val id: String get() = fingerprint

    /** The token must never reach a log, a crash report or the screen. */
    override fun toString() =
        "MasterCredentials(name=$name, address=$address, token=<redacted>, fingerprint=$fingerprint)"
}

/** Secrets are held by the platform, not the core (ADR-APP-007, S-SEC). :app implements this over the Android Keystore. */
interface SecureStore {
    fun all(): List<MasterCredentials>
    fun save(credentials: MasterCredentials)
    fun delete(id: String)
}

class InMemorySecureStore : SecureStore {
    private val items = LinkedHashMap<String, MasterCredentials>()

    @Synchronized
    override fun all() = items.values.sortedBy { it.name }

    @Synchronized
    override fun save(credentials: MasterCredentials) {
        items[credentials.id] = credentials
    }

    @Synchronized
    override fun delete(id: String) {
        items.remove(id)
    }
}
