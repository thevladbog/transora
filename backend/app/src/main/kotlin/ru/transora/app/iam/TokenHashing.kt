package ru.transora.app.iam

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.HexFormat

object TokenHashing {
    private val secureRandom = SecureRandom()

    fun sha256(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
        return HexFormat.of().formatHex(digest)
    }

    fun newServiceTokenValue(): String {
        val bytes = ByteArray(24)
        secureRandom.nextBytes(bytes)
        return "st_" + HexFormat.of().formatHex(bytes)
    }
}
