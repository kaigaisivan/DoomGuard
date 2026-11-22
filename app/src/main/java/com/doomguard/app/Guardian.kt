package com.doomguard.app

import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

sealed class PinResult {
    object Ok : PinResult()
    object NotSet : PinResult()
    data class Wrong(val triesLeft: Int) : PinResult()
    data class Locked(val untilMs: Long) : PinResult()
}

/**
 * The guardian PIN. It is stored only as a salted PBKDF2 hash, never in plain text.
 * Entering it opens a short "unlock window" in which loosening changes are allowed.
 * The window lives in memory only, so closing the app (or the process dying) locks it again.
 */
object Guardian {
    private const val ITERATIONS = 60_000
    private const val MAX_TRIES = 5
    private const val LOCKOUT_MS = 15 * 60_000L
    private const val UNLOCK_MS = 5 * 60_000L

    @Volatile
    private var unlockedUntil = 0L

    fun isSet(): Boolean = Store.guardianHash.isNotEmpty()
    fun isUnlocked(): Boolean = System.currentTimeMillis() < unlockedUntil

    /** True when a PIN exists and the unlock window is closed: loosening changes must be refused. */
    fun locked(): Boolean = isSet() && !isUnlocked()

    fun lockNow() { unlockedUntil = 0L }

    fun validFormat(pin: String): Boolean = pin.length in 4..8 && pin.all { it.isDigit() }

    private fun hash(pin: String, salt: ByteArray): String {
        val spec = PBEKeySpec(pin.toCharArray(), salt, ITERATIONS, 256)
        val bytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    /** Sets or replaces the PIN. The caller must already have checked the old PIN when replacing. */
    fun setPin(pin: String): Boolean {
        if (!validFormat(pin)) return false
        val replacing = isSet()
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        Store.guardianSalt = Base64.encodeToString(salt, Base64.NO_WRAP)
        Store.guardianHash = hash(pin, salt)
        Store.guardianFails = 0
        Store.guardianLockUntil = 0L
        unlockedUntil = System.currentTimeMillis() + UNLOCK_MS
        Store.logEvent("pin", if (replacing) "Guardian PIN changed" else "Guardian PIN set")
        return true
    }

    fun clear() {
        Store.guardianHash = ""
        Store.guardianSalt = ""
        Store.guardianFails = 0
        Store.guardianLockUntil = 0L
        unlockedUntil = 0L
        Store.logEvent("pin", "Guardian PIN removed")
    }

    /** Checks the PIN. With [unlock] true a correct PIN also opens the 5-minute unlock window. */
    fun verify(pin: String, unlock: Boolean = true): PinResult {
        if (!isSet()) return PinResult.NotSet
        val now = System.currentTimeMillis()
        val lockUntil = Store.guardianLockUntil
        if (now < lockUntil) return PinResult.Locked(lockUntil)
        if (pin.isEmpty()) return PinResult.Wrong(MAX_TRIES - Store.guardianFails)
        val salt = try { Base64.decode(Store.guardianSalt, Base64.NO_WRAP) } catch (e: Exception) { return PinResult.NotSet }
        val ok = MessageDigest.isEqual(hash(pin, salt).toByteArray(), Store.guardianHash.toByteArray())
        if (ok) {
            Store.guardianFails = 0
            if (unlock) unlockedUntil = now + UNLOCK_MS
            return PinResult.Ok
        }
        val fails = Store.guardianFails + 1
        if (fails >= MAX_TRIES) {
            Store.guardianFails = 0
            Store.guardianLockUntil = now + LOCKOUT_MS
            Store.logEvent("tamper", "$MAX_TRIES wrong guardian PIN attempts in a row. PIN entry locked for 15 minutes")
            return PinResult.Locked(now + LOCKOUT_MS)
        }
        Store.guardianFails = fails
        return PinResult.Wrong(MAX_TRIES - fails)
    }
}
