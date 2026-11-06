package app.sofabrowser.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import app.sofabrowser.Space
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Saved sign-ins: a site's origin, a user name and its password. Each password is encrypted (AES-GCM) with a key that
 * Android's keystore keeps for Sofa and never hands out, so the database file alone doesn't give it away; the
 * encryption is bound to the origin and user name, so a password can't be moved to another site's row. User names
 * stay readable, to list them. Each side of the private space has its own file and key.
 */
class SignIns private constructor(context: Context) : SQLiteOpenHelper(context, Space.name("signins.db"), null, 1) {

    data class SignIn(val origin: String, val user: String)

    private val alias = Space.name(KEY)

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE signins (origin TEXT NOT NULL, user TEXT NOT NULL, secret BLOB NOT NULL, used INTEGER NOT NULL, PRIMARY KEY (origin, user))")
        db.execSQL("CREATE TABLE never (origin TEXT PRIMARY KEY)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    /** The user names saved for [origin], the last used first. */
    fun users(origin: String): List<String> =
        readableDatabase.rawQuery("SELECT user FROM signins WHERE origin = ? ORDER BY used DESC", arrayOf(origin)).use { c ->
            buildList { while (c.moveToNext()) add(c.getString(0)) }
        }

    /** Null when there is none, or it can't be decrypted any more (the key is gone). */
    fun password(origin: String, user: String): String? {
        val secret = readableDatabase.rawQuery("SELECT secret FROM signins WHERE origin = ? AND user = ?", arrayOf(origin, user)).use { c ->
            if (c.moveToFirst()) c.getBlob(0) else null
        } ?: return null
        return runCatching { decrypt(secret, origin, user) }.getOrNull()
    }

    fun save(origin: String, user: String, password: String) {
        writableDatabase.insertWithOnConflict("signins", null, ContentValues().apply {
            put("origin", origin); put("user", user)
            put("secret", encrypt(password, origin, user))
            put("used", System.currentTimeMillis())
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun markUsed(origin: String, user: String) {
        writableDatabase.update("signins", ContentValues().apply { put("used", System.currentTimeMillis()) },
            "origin = ? AND user = ?", arrayOf(origin, user))
    }

    fun remove(origin: String, user: String) {
        writableDatabase.delete("signins", "origin = ? AND user = ?", arrayOf(origin, user))
    }

    fun all(): List<SignIn> = readableDatabase.rawQuery("SELECT origin, user FROM signins ORDER BY origin, user", null).use { c ->
        buildList { while (c.moveToNext()) add(SignIn(c.getString(0), c.getString(1))) }
    }

    /** "Never for this site": Sofa doesn't offer to save sign-ins there. */
    fun never(origin: String): Boolean =
        readableDatabase.rawQuery("SELECT 1 FROM never WHERE origin = ?", arrayOf(origin)).use { it.moveToFirst() }

    fun setNever(origin: String, on: Boolean) {
        if (on) writableDatabase.insertWithOnConflict("never", null, ContentValues().apply { put("origin", origin) }, SQLiteDatabase.CONFLICT_IGNORE)
        else writableDatabase.delete("never", "origin = ?", arrayOf(origin))
    }

    fun neverSites(): List<String> = readableDatabase.rawQuery("SELECT origin FROM never ORDER BY origin", null).use { c ->
        buildList { while (c.moveToNext()) add(c.getString(0)) }
    }

    /** Removes every saved sign-in and "Never" site, and the key with them. */
    fun clear() {
        writableDatabase.delete("signins", null, null)
        writableDatabase.delete("never", null, null)
        deleteKey(alias)
    }

    private fun encrypt(password: String, origin: String, user: String): ByteArray {
        val cipher = Cipher.getInstance(CIPHER)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        cipher.updateAAD(bound(origin, user))
        return cipher.iv + cipher.doFinal(password.toByteArray(Charsets.UTF_8))
    }

    private fun decrypt(secret: ByteArray, origin: String, user: String): String {
        val cipher = Cipher.getInstance(CIPHER)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, secret, 0, IV_BYTES))
        cipher.updateAAD(bound(origin, user))
        return String(cipher.doFinal(secret, IV_BYTES, secret.size - IV_BYTES), Charsets.UTF_8)
    }

    private fun bound(origin: String, user: String) = "$origin\n$user".toByteArray(Charsets.UTF_8)

    private fun key(): SecretKey {
        (keyStore().getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build())
        }.generateKey()
    }

    companion object {
        private const val KEYSTORE = "AndroidKeyStore"
        private const val KEY = "signins"
        private const val CIPHER = "AES/GCM/NoPadding"
        private const val IV_BYTES = 12

        @Volatile private var instance: SignIns? = null

        fun get(context: Context): SignIns = instance ?: synchronized(this) {
            instance ?: SignIns(context.applicationContext).also { instance = it }
        }

        private fun keyStore() = KeyStore.getInstance(KEYSTORE).apply { load(null) }

        private fun deleteKey(alias: String) {
            runCatching { keyStore().deleteEntry(alias) }
        }

        /** The private space's key, when the space is erased from the normal side. */
        fun deleteSpaceKey(spacePrefix: String) = deleteKey(spacePrefix + KEY)
    }
}
