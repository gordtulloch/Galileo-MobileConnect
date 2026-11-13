package com.galileo.mobileconnect.platform

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.galileo.mobileconnect.core.link.MasterAddress
import com.galileo.mobileconnect.core.link.MasterCredentials
import com.galileo.mobileconnect.core.link.SecureStore
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The token and the pin, held under a key that lives in the Android Keystore and never leaves it (ADR-APP-007, S-SEC,
 * MAPP-CON-040). The credentials themselves are kept as AES-GCM ciphertext in private preferences; nothing is logged,
 * backed up (`allowBackup` is off) or shown.
 */
class KeystoreSecureStore(context: Context) : SecureStore {
    private val prefs = context.getSharedPreferences("galileo.secure", Context.MODE_PRIVATE)

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private fun seal(plain: ByteArray): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val sealed = cipher.doFinal(plain)
        return Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(sealed, Base64.NO_WRAP)
    }

    private fun open(text: String): ByteArray {
        val (iv, sealed) = text.split(':', limit = 2).map { Base64.decode(it, Base64.NO_WRAP) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv)) }
        return cipher.doFinal(sealed)
    }

    @Synchronized
    override fun all(): List<MasterCredentials> = prefs.all.keys.filter { it.startsWith(PREFIX) }.mapNotNull { name ->
        runCatching {
            val json = Json.parseToJsonElement(String(open(prefs.getString(name, "")!!))) as JsonObject
            MasterCredentials(
                name = (json["name"] as JsonPrimitive).content,
                address = MasterAddress(
                    (json["host"] as JsonPrimitive).content,
                    (json["port"] as JsonPrimitive).intOrNull ?: MasterAddress.DEFAULT_PORT,
                ),
                token = (json["token"] as JsonPrimitive).content,
                fingerprint = (json["fingerprint"] as JsonPrimitive).content,
            )
        }.getOrNull() // an entry that can't be opened (the key was lost) is as good as not there
    }.sortedBy { it.name }

    @Synchronized
    override fun save(credentials: MasterCredentials) {
        val json = JsonObject(
            mapOf(
                "name" to JsonPrimitive(credentials.name),
                "host" to JsonPrimitive(credentials.address.host),
                "port" to JsonPrimitive(credentials.address.port),
                "token" to JsonPrimitive(credentials.token),
                "fingerprint" to JsonPrimitive(credentials.fingerprint),
            ),
        )
        prefs.edit().putString(PREFIX + credentials.id, seal(json.toString().toByteArray())).apply()
    }

    @Synchronized
    override fun delete(id: String) {
        prefs.edit().remove(PREFIX + id).apply()
    }

    private companion object {
        const val ALIAS = "galileo.credentials"
        const val PREFIX = "credential."
    }
}
