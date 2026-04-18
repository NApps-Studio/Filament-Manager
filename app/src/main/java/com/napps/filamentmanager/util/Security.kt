package com.napps.filamentmanager.util

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.compose.runtime.mutableStateOf
import com.napps.filamentmanager.database.SyncRegion
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Utility for handling encryption and decryption of sensitive data like
 * printer serial numbers and MQTT access tokens.
 *
 * Uses the Android Keystore system with AES/GCM/NoPadding for secure
 * hardware-backed key storage and authenticated encryption.
 */
object CryptoManager {
    private const val ALGORITHM = "AES/GCM/NoPadding"
    private const val KEY_ALIAS = "bambu_secret_key"
    private const val KEYSTORE_NAME = "AndroidKeyStore"

    private val keyStore = java.security.KeyStore.getInstance(KEYSTORE_NAME).apply { load(null) }

    /**
     * Retrieves the secret key from the Android Keystore or generates a new one.
     */
    private fun getSecretKey(): SecretKey {
        val existingKey = keyStore.getEntry(KEY_ALIAS, null) as? java.security.KeyStore.SecretKeyEntry
        return existingKey?.secretKey ?: createKey()
    }

    /**
     * Generates a new 256-bit AES key and stores it in the hardware Keystore.
     */
    private fun createKey(): SecretKey {
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_NAME).apply {
            init(
                KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setUserAuthenticationRequired(false)
                    .build()
            )
        }.generateKey()
    }

    /**
     * Encrypts a plain-text string.
     * @return A Pair containing the Base64-encoded encrypted data and the Base64-encoded Initialization Vector (IV).
     */
    fun encrypt(data: String): Pair<String, String> {
        val cipher = Cipher.getInstance(ALGORITHM)
        cipher.init(Cipher.ENCRYPT_MODE, getSecretKey())

        val iv = Base64.getEncoder().encodeToString(cipher.iv)
        val encrypted = Base64.getEncoder().encodeToString(cipher.doFinal(data.toByteArray()))

        return encrypted to iv
    }

    /**
     * Decrypts an encrypted string using the provided IV.
     * @param encryptedData Base64-encoded encrypted string.
     * @param iv Base64-encoded Initialization Vector.
     * @return The original plain-text string.
     */
    fun decrypt(encryptedData: String, iv: String): String {
        val cipher = Cipher.getInstance(ALGORITHM)
        val ivBytes = Base64.getDecoder().decode(iv)
        val spec = GCMParameterSpec(128, ivBytes)

        cipher.init(Cipher.DECRYPT_MODE, getSecretKey(), spec)

        val encryptedBytes = Base64.getDecoder().decode(encryptedData)
        return String(cipher.doFinal(encryptedBytes))
    }
}

/**
 * Global singleton managing decrypted session state.
 * To maintain security, raw serial numbers and access tokens are only kept
 * in memory within this object and are never persisted in plain text.
 */
object SecuritySession {
    private var decryptedToken = mutableStateOf<String?>(null)
    private var decryptedUid = mutableStateOf<String?>(null)
    private var decryptedRegion = mutableStateOf(SyncRegion.EU)
    private var decryptedIsInChina = mutableStateOf(false)
    
    // Observable Map of Hashed Serial -> Decrypted Raw Serial
    private val _decryptedPrintersFlow = MutableStateFlow<Map<String, String>>(emptyMap())
    /** A flow that provides a real-time mapping of hashed serials to their decrypted versions. */
    val decryptedPrintersFlow: StateFlow<Map<String, String>> = _decryptedPrintersFlow.asStateFlow()

    /**
     * Initializes the session with the user's primary credentials.
     *
     * @param token The decrypted Bambu account access token.
     * @param uid The decrypted Bambu account user ID.
     * @param region The user's account region (e.g., EU, US).
     * @param isInChina Whether the account is located in China (determines MQTT host).
     */
    fun initialize(token: String, uid: String, region: SyncRegion, isInChina: Boolean) {
        decryptedToken.value = token
        decryptedUid.value = uid
        decryptedRegion.value = region
        decryptedIsInChina.value = isInChina
    }

    /**
     * Updates the session with a map of decrypted printer serials.
     *
     * @param printers A map where the key is the hashed serial and the value is the raw decrypted serial.
     */
    fun setDecryptedPrinters(printers: Map<String, String>) {
        _decryptedPrintersFlow.value = printers
    }

    /**
     * Adds a single decrypted printer serial to the current session.
     *
     * @param hashedSn The hashed serial number of the printer.
     * @param rawSn The raw decrypted serial number.
     */
    fun addDecryptedPrinter(hashedSn: String, rawSn: String) {
        _decryptedPrintersFlow.value = _decryptedPrintersFlow.value + (hashedSn to rawSn)
    }

    /** @return The decrypted access token, or null if not initialized. */
    fun getAccessToken(): String? = decryptedToken.value
    /** @return The decrypted user ID, or null if not initialized. */
    fun getUid(): String? = decryptedUid.value
    /** @return The user's synchronization region. */
    fun getRegion(): SyncRegion = decryptedRegion.value
    /** @return True if the user is in the China region. */
    fun getIsInChina(): Boolean = decryptedIsInChina.value
    /** @return A map of all currently decrypted printer serials. */
    fun getDecryptedSerials(): Map<String, String> = _decryptedPrintersFlow.value
    
    /**
     * Retrieves the raw serial for a specific hashed serial.
     * @param hashedSn The hashed version of the serial number.
     * @return The raw decrypted serial, or null if not found.
     */
    fun getRawSerial(hashedSn: String): String? = _decryptedPrintersFlow.value[hashedSn]

    /**
     * Clears all sensitive data from the in-memory session.
     * Should be called on logout or when the app is backgrounded for an extended period.
     */
    fun clear() {
        decryptedToken.value = null
        decryptedUid.value = null
        decryptedRegion.value = SyncRegion.EU
        decryptedIsInChina.value = false
        _decryptedPrintersFlow.value = emptyMap()
    }
}
