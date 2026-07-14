package io.github.amichne.kast.api.contract.result

import io.github.amichne.kast.api.continuation.ContinuationOwnedState
import io.github.amichne.kast.api.contract.query.WorkspaceFilesPublicContinuationIdentity
import kotlinx.serialization.Serializable

@Serializable
data class WorkspaceFilesPublicContinuationState(
    val identity: WorkspaceFilesPublicContinuationIdentity,
    val compositionStampDigest: CompositionStampDigest,
    val lastRelativePath: LastRelativePath,
    val cumulativeReturnedCount: CumulativeReturnedCount,
) : ContinuationOwnedState() {
    fun toProjection(): WorkspaceFilesPublicContinuationProjection =
        WorkspaceFilesPublicContinuationProjection(
            identity = identity,
            compositionStampDigest = compositionStampDigest,
            lastRelativePath = lastRelativePath,
            cumulativeReturnedCount = cumulativeReturnedCount,
        )

    @Serializable
    @JvmInline
    value class CompositionStampDigest private constructor(val value: String) {
        init {
            require(value.length == SHA_256_HEX_LENGTH && value.all { it in '0'..'9' || it in 'a'..'f' }) {
                "Workspace-file composition stamp digest must be lowercase SHA-256 hex"
            }
        }

        companion object {
            fun parse(raw: String): CompositionStampDigest = CompositionStampDigest(raw)

            private const val SHA_256_HEX_LENGTH = 64
        }
    }

    @Serializable
    @JvmInline
    value class LastRelativePath private constructor(val value: String) {
        init {
            require(value.isNotBlank()) { "Workspace-file continuation path must not be blank" }
            require(value.none(Char::isISOControl)) {
                "Workspace-file continuation path must not contain control characters"
            }
            require('\\' !in value) { "Workspace-file continuation path must use forward slashes" }
            require(!value.startsWith('/')) { "Workspace-file continuation path must be relative" }
            require(!windowsDrivePrefix.containsMatchIn(value)) {
                "Workspace-file continuation path must not use a Windows drive prefix"
            }
            require(value.split('/').all { it.isNotEmpty() && it != "." && it != ".." }) {
                "Workspace-file continuation path must be normalized and contained"
            }
        }

        companion object {
            private val windowsDrivePrefix = Regex("^[A-Za-z]:")

            fun parse(raw: String): LastRelativePath {
                val normalized = raw.replace('\\', '/')
                return LastRelativePath(normalized)
            }
        }
    }

    @Serializable
    @JvmInline
    value class CumulativeReturnedCount private constructor(val value: Int) {
        init {
            require(value >= 0) { "Workspace-file cumulative returned count must not be negative" }
        }

        companion object {
            fun of(value: Int): CumulativeReturnedCount = CumulativeReturnedCount(value)
        }
    }
}
