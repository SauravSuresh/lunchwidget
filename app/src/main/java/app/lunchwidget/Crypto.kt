package app.lunchwidget

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Seals the values that must not survive a copy of the app's data directory —
 * the API token above all, which grants full read/write on the Lunch Money
 * account. The AES key is generated inside the Android Keystore and never
 * leaves it, so `adb backup`, `run-as` on a debug build, and an offline image
 * of the flash all yield ciphertext with no key to go with it.
 *
 * ponytail: hand-rolled over androidx.security-crypto — that library is
 * deprecated and would be the app's second runtime dependency. AES/GCM through
 * the platform Keystore is the same primitive in thirty lines.
 */
object Crypto {

    private const val KEY_ALIAS = "lunchwidget.secrets.v1"
    private const val PREFIX = "enc1:"
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        )
        return gen.generateKey()
    }

    fun isSealed(stored: String): Boolean = stored.startsWith(PREFIX)

    fun seal(plain: String): String {
        if (plain.isEmpty()) return ""
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        // The Keystore picks the IV itself (randomized encryption is mandatory);
        // it rides in front of the ciphertext.
        val sealed = cipher.iv + cipher.doFinal(plain.toByteArray())
        return PREFIX + Base64.encodeToString(sealed, Base64.NO_WRAP)
    }

    /**
     * Unsealed input is handed back untouched — that's a value written before
     * this shipped, and the caller re-seals it. Ciphertext that won't open
     * (key dropped by a reinstall or a device-to-device restore) reads as
     * absent, which sends the widget to its "tap to set up" state instead of
     * crashing the refresh job.
     */
    fun open(stored: String): String {
        if (!isSealed(stored)) return stored
        return try {
            val raw = Base64.decode(stored.removePrefix(PREFIX), Base64.NO_WRAP)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, raw, 0, IV_BYTES))
            String(cipher.doFinal(raw, IV_BYTES, raw.size - IV_BYTES))
        } catch (e: Exception) {
            ""
        }
    }
}
