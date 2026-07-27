package io.github.amichne.kast.api.validation

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

object FileHashing {
    fun sha256(content: String): String = sha256(content.toByteArray(StandardCharsets.UTF_8))

    fun sha256(content: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(content)
            .joinToString(separator = "") { byte -> "%02x".format(byte) }
}
