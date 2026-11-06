package app.sofabrowser.data

import android.content.Context
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * The private space's PIN: only a salted, slow hash of it is kept, and wrong tries make the next ones wait.
 * Written with commit, not apply: going into the space kills the process right after.
 */
object SpacePin {
    const val LENGTH = 4
    private const val PREFS = "space_pin"
    private const val FREE_TRIES = 5
    private const val FIRST_WAIT = 30_000L
    private const val LONGEST_WAIT = 3_600_000L
    private const val ROUNDS = 60_000

    internal fun hash(pin: String, salt: ByteArray, rounds: Int = ROUNDS): ByteArray =
        SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(PBEKeySpec(pin.toCharArray(), salt, rounds, 256)).encoded

    /** How long to wait after [failures] wrong tries in a row. */
    internal fun waitAfter(failures: Int): Long {
        if (failures < FREE_TRIES) return 0
        return (FIRST_WAIT shl (failures - FREE_TRIES).coerceAtMost(10)).coerceAtMost(LONGEST_WAIT)
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
    private fun bytes(hex: String) = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    fun isSet(context: Context) = prefs(context).contains("hash")

    /** Slow: not on the main thread. */
    fun set(context: Context, pin: String) {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        prefs(context).edit().clear().putString("salt", hex(salt)).putString("hash", hex(hash(pin, salt))).commit()
    }

    /** Slow: not on the main thread. Counts a wrong try. */
    fun check(context: Context, pin: String): Boolean {
        val p = prefs(context)
        val right = MessageDigest.isEqual(hash(pin, bytes(p.getString("salt", "")!!)), bytes(p.getString("hash", "")!!))
        val failures = if (right) 0 else p.getInt("failures", 0) + 1
        p.edit().putInt("failures", failures).putLong("wrong_at", System.currentTimeMillis()).commit()
        return right
    }

    /** Milliseconds until the next try is allowed. A clock set back can't make it longer than the wait itself. */
    fun waiting(context: Context): Long {
        val p = prefs(context)
        val wait = waitAfter(p.getInt("failures", 0))
        return (p.getLong("wrong_at", 0) + wait - System.currentTimeMillis()).coerceIn(0, wait)
    }

    fun clear(context: Context) = prefs(context).edit().clear().commit()
}
