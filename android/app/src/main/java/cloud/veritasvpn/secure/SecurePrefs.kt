package cloud.veritasvpn.secure

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Encrypted SharedPreferences backed by the Android Keystore.
 *
 * Opens `${legacyName}_secure` and, on first use, copies any existing plaintext
 * `${legacyName}` prefs into it, then clears/deletes the plaintext file.
 */
object SecurePrefs {
    private const val TAG = "SecurePrefs"
    private const val MIGRATION_FLAG = "__veritas_secure_migrated_v1"

    fun open(context: Context, legacyName: String): SharedPreferences {
        val appContext = context.applicationContext
        val secureName = "${legacyName}_secure"
        val encrypted = createEncrypted(appContext, secureName)
        migrateFromPlaintext(appContext, legacyName, encrypted)
        return encrypted
    }

    private fun createEncrypted(context: Context, fileName: String): SharedPreferences {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            context,
            fileName,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    private fun migrateFromPlaintext(
        context: Context,
        legacyName: String,
        encrypted: SharedPreferences
    ) {
        if (encrypted.getBoolean(MIGRATION_FLAG, false)) return

        val legacy = context.getSharedPreferences(legacyName, Context.MODE_PRIVATE)
        val legacyAll = legacy.all
        if (legacyAll.isEmpty()) {
            encrypted.edit().putBoolean(MIGRATION_FLAG, true).apply()
            return
        }

        try {
            val editor = encrypted.edit()
            for ((key, value) in legacyAll) {
                if (key == MIGRATION_FLAG) continue
                when (value) {
                    null -> editor.remove(key)
                    is Boolean -> editor.putBoolean(key, value)
                    is Float -> editor.putFloat(key, value)
                    is Int -> editor.putInt(key, value)
                    is Long -> editor.putLong(key, value)
                    is String -> editor.putString(key, value)
                    is Set<*> -> {
                        @Suppress("UNCHECKED_CAST")
                        editor.putStringSet(key, value as Set<String>)
                    }
                    else -> Log.w(TAG, "Skipping unsupported pref type for key=$key")
                }
            }
            editor.putBoolean(MIGRATION_FLAG, true)
            editor.apply()
            legacy.edit().clear().apply()
            context.deleteSharedPreferences(legacyName)
            Log.i(TAG, "Migrated plaintext prefs '$legacyName' to encrypted storage")
        } catch (e: Exception) {
            Log.e(TAG, "Failed migrating prefs '$legacyName'", e)
            // Do not set the migration flag so a later launch can retry.
        }
    }
}
