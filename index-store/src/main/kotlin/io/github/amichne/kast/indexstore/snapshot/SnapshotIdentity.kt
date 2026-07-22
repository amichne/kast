package io.github.amichne.kast.indexstore.snapshot

import kotlinx.serialization.Serializable
import java.security.MessageDigest

@Serializable
@JvmInline
value class GitObjectId private constructor(val value: String) {
    companion object {
        fun parse(value: String): GitObjectId {
            require(value.length == 40 || value.length == 64) { "Git object ID must contain 40 or 64 hexadecimal characters" }
            require(value.all { it in '0'..'9' || it in 'a'..'f' }) { "Git object ID must be lowercase hexadecimal" }
            return GitObjectId(value)
        }
    }
}

@Serializable
@JvmInline
value class BuildClasspathFingerprint private constructor(val value: String) {
    companion object {
        fun parse(value: String): BuildClasspathFingerprint {
            require(value.length == 64 && value.all { it in '0'..'9' || it in 'a'..'f' }) {
                "Build/classpath fingerprint must be a lowercase SHA-256 digest"
            }
            return BuildClasspathFingerprint(value)
        }
    }
}

@Serializable
@JvmInline
value class ProducerVersion private constructor(val value: String) {
    companion object {
        fun parse(value: String): ProducerVersion {
            require(value.isNotBlank() && value.trim() == value && !value.any { Character.isISOControl(it.code) }) {
                "Producer version must be one non-blank canonical value"
            }
            return ProducerVersion(value)
        }
    }
}

@Serializable
data class SnapshotCompatibility(
    val buildClasspathFingerprint: BuildClasspathFingerprint,
    val indexSchema: Int,
    val producerVersion: ProducerVersion,
) {
    init {
        require(indexSchema > 0) { "Index schema must be positive" }
    }
}

@Serializable
data class SnapshotKey(
    val treeOid: GitObjectId,
    val buildClasspathFingerprint: BuildClasspathFingerprint,
    val indexSchema: Int,
    val producerVersion: ProducerVersion,
) {
    val compatibility: SnapshotCompatibility = SnapshotCompatibility(
        buildClasspathFingerprint,
        indexSchema,
        producerVersion,
    )

    val directoryName: String
        get() = sha256("${treeOid.value}\n${buildClasspathFingerprint.value}\n$indexSchema\n${producerVersion.value}")
}

@Serializable
data class ExtractionShardKey(
    val compatibility: SnapshotCompatibility,
    val blobOid: GitObjectId,
) {
    val directoryName: String
        get() = sha256(
            "${compatibility.buildClasspathFingerprint.value}\n${compatibility.indexSchema}\n" +
                "${compatibility.producerVersion.value}\n${blobOid.value}",
        )
}

private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray())
    .joinToString("") { byte -> "%02x".format(byte) }
