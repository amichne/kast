package io.github.amichne.kast.indexstore.snapshot

import io.github.amichne.kast.indexstore.api.reference.SourceIndexGeneration
import kotlinx.serialization.Serializable

internal const val WORKSPACE_DATABASE_FILE = "source-index.db"
internal const val WORKSPACE_REPOSITORY_OVERLAY_FILE = "repository-overlay.json"

@Serializable
enum class RepositoryOverlayPublication {
    ABSENT,
    ATTACHED,
    ;

    /** Raw nullable extraction is confined to SQLite and runtime-status serialization. */
    fun serializedFileName(): String? = when (this) {
        ABSENT -> null
        ATTACHED -> WORKSPACE_REPOSITORY_OVERLAY_FILE
    }

    companion object {
        /**
         * Proof transition: `String? -> RepositoryOverlayPublicationResolution`.
         *
         * Derives the closed overlay-publication state from SQLite storage.
         * Only absence and the canonical `repository-overlay.json` filename are
         * valid. Rejection is finite [RepositoryOverlayPublicationFailure]
         * data. Raw nullable data is accepted only at the SQLite boundary.
         */
        fun fromSerializedFileName(raw: String?): RepositoryOverlayPublicationResolution = when (raw) {
            null -> RepositoryOverlayPublicationResolution.Resolved(ABSENT)
            WORKSPACE_REPOSITORY_OVERLAY_FILE -> RepositoryOverlayPublicationResolution.Resolved(ATTACHED)
            else -> RepositoryOverlayPublicationResolution.Rejected(
                RepositoryOverlayPublicationFailure.UnknownFile(raw),
            )
        }
    }
}

sealed interface RepositoryOverlayPublicationFailure {
    data class UnknownFile(val value: String) : RepositoryOverlayPublicationFailure
}

sealed interface RepositoryOverlayPublicationResolution {
    data class Resolved(
        val publication: RepositoryOverlayPublication,
    ) : RepositoryOverlayPublicationResolution

    data class Rejected(
        val failure: RepositoryOverlayPublicationFailure,
    ) : RepositoryOverlayPublicationResolution
}

/**
 * Proof transition: `Long -> EvidenceRevision`.
 *
 * Establishes a non-negative revision for one evidence lane. Raw extraction is
 * permitted only at SQLite and protocol serialization boundaries.
 */
@Serializable
@JvmInline
value class EvidenceRevision private constructor(val value: Long) {
    companion object {
        /**
         * Proof transition: `SourceIndexGeneration -> EvidenceRevision`.
         *
         * Preserves the already-proven non-negative source generation as one
         * immutable evidence-lane revision. Raw extraction is permitted only
         * at SQLite and protocol serialization boundaries.
         */
        fun fromSourceIndexGeneration(generation: SourceIndexGeneration): EvidenceRevision =
            EvidenceRevision(generation.value)

        /**
         * Proof transition: `Long -> EvidenceRevisionResolution`.
         *
         * Establishes a non-negative persisted evidence-lane revision.
         * Rejection is finite [EvidenceRevisionFailure] data. Raw input is
         * accepted only at the SQLite row boundary.
         */
        internal fun fromPersisted(value: Long): EvidenceRevisionResolution = if (value < 0) {
            EvidenceRevisionResolution.Rejected(EvidenceRevisionFailure.Negative(value))
        } else {
            EvidenceRevisionResolution.Resolved(EvidenceRevision(value))
        }
    }
}

sealed interface EvidenceRevisionFailure {
    data class Negative(val value: Long) : EvidenceRevisionFailure
}

internal sealed interface EvidenceRevisionResolution {
    data class Resolved(val revision: EvidenceRevision) : EvidenceRevisionResolution

    data class Rejected(val failure: EvidenceRevisionFailure) : EvidenceRevisionResolution
}

@Serializable
sealed interface GraphEvidencePublication {
    @Serializable
    data class Ready(val revision: EvidenceRevision) : GraphEvidencePublication

    @Serializable
    data class Blocked(val blocker: GraphEvidenceBlocker) : GraphEvidencePublication
}

@Serializable
enum class GraphEvidenceBlocker {
    INDEXING_FAILED,
}
