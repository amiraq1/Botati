package com.botati.domain.security

import java.security.MessageDigest

/** Pure JVM SHA-256 hex helper (no Android deps — unit-testable). */
object Sha256 {
    fun hex(input: String): String = hex(input.toByteArray(Charsets.UTF_8))

    fun hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }
}
