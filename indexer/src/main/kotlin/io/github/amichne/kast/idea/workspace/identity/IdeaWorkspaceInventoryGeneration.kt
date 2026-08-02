package io.github.amichne.kast.idea

import java.security.MessageDigest

@JvmInline
internal value class IdeaWorkspaceInventoryGeneration private constructor(
    val value: String,
) {
    companion object {
        fun fingerprint(evidence: List<String>): IdeaWorkspaceInventoryGeneration {
            val digest = MessageDigest.getInstance("SHA-256")
            evidence.forEach { value ->
                val bytes = value.toByteArray(Charsets.UTF_8)
                digest.update(bytes.size.toString().toByteArray(Charsets.US_ASCII))
                digest.update(0)
                digest.update(bytes)
                digest.update(0)
            }
            return IdeaWorkspaceInventoryGeneration(
                digest.digest().joinToString(separator = "") { byte -> "%02x".format(byte) },
            )
        }
    }
}
