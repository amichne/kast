package io.github.amichne.kast.indexstore.snapshot

import kotlinx.serialization.Serializable
import java.security.MessageDigest

@Serializable
@JvmInline
value class GitObjectId private constructor(val value: String) {
    init {
        require(isCanonical(value)) { "Git object ID must contain 40 or 64 lowercase hexadecimal characters" }
    }

    companion object {
        /**
         * Proof transition: `String -> GitObjectIdResolution`.
         *
         * A resolved value establishes a canonical 40- or 64-character
         * lowercase hexadecimal object identity. Rejection is finite
         * [GitObjectIdFailure] data. Raw input and extraction are permitted
         * only at Git and serialization boundaries.
         */
        fun resolve(value: String): GitObjectIdResolution = if (isCanonical(value)) {
            GitObjectIdResolution.Resolved(GitObjectId(value))
        } else {
            GitObjectIdResolution.Rejected(GitObjectIdFailure.NonCanonical(value))
        }

        /**
         * Derivation transition: `String -> GitObjectId` for repository-owned
         * constants and computed digests whose canonical form is authoritative.
         * A violation is a programming defect, not an expected parse outcome.
         */
        fun fromCanonical(value: String): GitObjectId = GitObjectId(value)

        private fun isCanonical(value: String): Boolean =
            (value.length == 40 || value.length == 64) && value.all { it in '0'..'9' || it in 'a'..'f' }
    }
}

sealed interface GitObjectIdFailure {
    data class NonCanonical(val value: String) : GitObjectIdFailure
}

sealed interface GitObjectIdResolution {
    data class Resolved(val objectId: GitObjectId) : GitObjectIdResolution

    data class Rejected(val failure: GitObjectIdFailure) : GitObjectIdResolution
}

@Serializable
@JvmInline
value class BuildClasspathFingerprint private constructor(val value: String) {
    init {
        require(isCanonical(value)) { "Build/classpath fingerprint must be a lowercase SHA-256 digest" }
    }

    companion object {
        /**
         * Derivation transition: `String -> BuildClasspathFingerprint`.
         *
         * Establishes one canonical lowercase SHA-256 identity. Raw extraction
         * is permitted only at hashing and serialization boundaries.
         */
        fun fromDigest(value: String): BuildClasspathFingerprint {
            return BuildClasspathFingerprint(value)
        }

        private fun isCanonical(value: String): Boolean =
            value.length == 64 && value.all { it in '0'..'9' || it in 'a'..'f' }
    }
}

@Serializable
@JvmInline
value class ProducerVersion private constructor(val value: String) {
    init {
        require(isCanonical(value)) { "Producer version must be one non-blank canonical value" }
    }

    companion object {
        /**
         * Derivation transition: `String -> ProducerVersion`.
         *
         * Establishes one trimmed, non-blank, control-free producer identity.
         * Raw extraction is permitted only at version and serialization
         * boundaries.
         */
        fun fromVersion(value: String): ProducerVersion {
            return ProducerVersion(value)
        }

        private fun isCanonical(value: String): Boolean =
            value.isNotBlank() && value.trim() == value && !value.any { Character.isISOControl(it.code) }
    }
}

@JvmInline
value class SnapshotDirectoryName private constructor(val value: String) {
    companion object {
        /**
         * Proof transition: `SnapshotKey -> SnapshotDirectoryName`.
         *
         * Derives the collision-resistant canonical directory identity from
         * every component of the snapshot key. The raw digest is extracted
         * only at the repository filesystem boundary.
         */
        internal fun derive(key: SnapshotKey): SnapshotDirectoryName = SnapshotDirectoryName(
            sha256(
                "${key.treeOid.value}\n${key.buildClasspathFingerprint.value}\n" +
                    "${key.indexSchema.value}\n${key.producerVersion.value}",
            ),
        )
    }
}

@JvmInline
value class ExtractionShardDirectoryName private constructor(val value: String) {
    companion object {
        /**
         * Proof transition: `ExtractionShardKey -> ExtractionShardDirectoryName`.
         *
         * Derives the collision-resistant canonical directory identity from
         * the shard compatibility and Git blob identity. The raw digest is
         * extracted only at the repository filesystem boundary.
         */
        internal fun derive(key: ExtractionShardKey): ExtractionShardDirectoryName = ExtractionShardDirectoryName(
            sha256(
                "${key.compatibility.buildClasspathFingerprint.value}\n${key.compatibility.indexSchema.value}\n" +
                    "${key.compatibility.producerVersion.value}\n${key.blobOid.value}",
            ),
        )
    }
}

@Serializable
data class SnapshotCompatibility(
    val buildClasspathFingerprint: BuildClasspathFingerprint,
    val indexSchema: SourceIndexSchemaVersion,
    val producerVersion: ProducerVersion,
)

@Serializable
data class SnapshotKey(
    val treeOid: GitObjectId,
    val buildClasspathFingerprint: BuildClasspathFingerprint,
    val indexSchema: SourceIndexSchemaVersion,
    val producerVersion: ProducerVersion,
) {
    val compatibility: SnapshotCompatibility = SnapshotCompatibility(
        buildClasspathFingerprint,
        indexSchema,
        producerVersion,
    )

    val directoryName: SnapshotDirectoryName
        get() = SnapshotDirectoryName.derive(this)
}

@Serializable
data class ExtractionShardKey(
    val compatibility: SnapshotCompatibility,
    val blobOid: GitObjectId,
) {
    val directoryName: ExtractionShardDirectoryName
        get() = ExtractionShardDirectoryName.derive(this)
}

private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray())
    .joinToString("") { byte -> "%02x".format(byte) }
