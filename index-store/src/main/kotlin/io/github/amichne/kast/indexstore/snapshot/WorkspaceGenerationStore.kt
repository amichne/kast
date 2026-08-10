package io.github.amichne.kast.indexstore.snapshot

import io.github.amichne.kast.indexstore.api.reference.SourceIndexGeneration
import io.github.amichne.kast.api.contract.NonNegativeInt
import io.github.amichne.kast.indexstore.store.SqliteSourceIndexStore
import io.github.amichne.kast.indexstore.store.WorkspaceWriteSession
import kotlinx.serialization.Serializable

@Serializable
@JvmInline
value class PublicationEpochMillis private constructor(val value: Long) {
    companion object {
        /**
         * Proof transition: `Long -> PublicationEpochMillis`.
         *
         * Establishes a non-negative Unix epoch millisecond value. The raw
         * number may be extracted only at clock, SQLite, or serialization
         * boundaries.
         */
        fun fromClock(value: Long): PublicationEpochMillis {
            require(value >= 0) { "Publication time must not be negative" }
            return PublicationEpochMillis(value)
        }
    }
}

/**
 * Construction transition: `String -> PublishedWorkspaceIdentity`.
 *
 * Establishes one non-blank verified workspace-state identity. Raw extraction
 * is permitted only at identity capture, SQLite, and serialization boundaries.
 */
@Serializable
@JvmInline
value class PublishedWorkspaceIdentity(val value: String) {
    init {
        require(value.isNotBlank()) { "Published workspace identity must not be blank" }
    }
}

/**
 * Construction transition: `Long -> WorkspaceSemanticGeneration`.
 *
 * Establishes a positive publication revision. Raw extraction is permitted
 * only at SQLite, status serialization, and checked successor derivation.
 */
@Serializable
@JvmInline
value class WorkspaceSemanticGeneration(val value: Long) {
    init {
        require(value > 0) { "Workspace semantic generation must be positive" }
    }

    fun next(): WorkspaceSemanticGeneration = WorkspaceSemanticGeneration(Math.addExact(value, 1))
}

/**
 * Construction transition: `Int -> SourceIndexSchemaVersion`.
 *
 * Establishes a positive generated source-index schema identity. Raw
 * extraction is permitted only at schema, SQLite, and serialization
 * boundaries.
 */
@Serializable
@JvmInline
value class SourceIndexSchemaVersion(val value: Int) {
    init {
        require(value > 0) { "Source index schema version must be positive" }
    }
}

@Serializable
data class PublishedWorkspaceGenerationManifest(
    val generation: WorkspaceSemanticGeneration,
    val identity: PublishedWorkspaceIdentity,
    val sourceIndexGeneration: SourceIndexGeneration,
    val sourceRevision: EvidenceRevision = EvidenceRevision.fromSourceIndexGeneration(sourceIndexGeneration),
    val referenceRevision: EvidenceRevision = EvidenceRevision.fromSourceIndexGeneration(sourceIndexGeneration),
    val graphPublication: GraphEvidencePublication = GraphEvidencePublication.Ready(
        EvidenceRevision.fromSourceIndexGeneration(sourceIndexGeneration),
    ),
    val sourceIndexSchemaVersion: SourceIndexSchemaVersion,
    val publishedAt: PublicationEpochMillis,
    val repositoryOverlay: RepositoryOverlayPublication,
) {
    companion object {
        /**
         * Proof transition:
         * `SerializedWorkspacePublication -> WorkspacePublicationRecordResolution`.
         *
         * A resolved manifest carries a positive revision, non-blank identity,
         * non-negative source-index generation and publication time, positive
         * schema version, and closed overlay state. Rejection is finite
         * [WorkspacePublicationRecordFailure] data. Raw primitives are accepted
         * only from the SQLite publication-row boundary.
         */
        internal fun resolve(
            record: SerializedWorkspacePublication,
        ): WorkspacePublicationRecordResolution {
            if (record.generation <= 0) {
                return rejected(WorkspacePublicationRecordFailure.InvalidGeneration(record.generation))
            }
            if (record.identity.isBlank()) {
                return rejected(WorkspacePublicationRecordFailure.BlankIdentity(record.identity))
            }
            if (record.sourceIndexGeneration < 0) {
                return rejected(
                    WorkspacePublicationRecordFailure.NegativeSourceIndexGeneration(record.sourceIndexGeneration),
                )
            }
            if (record.sourceIndexSchemaVersion <= 0) {
                return rejected(
                    WorkspacePublicationRecordFailure.InvalidSchemaVersion(record.sourceIndexSchemaVersion),
                )
            }
            val sourceRevision = when (val resolution = EvidenceRevision.fromPersisted(record.sourceRevision)) {
                is EvidenceRevisionResolution.Resolved -> resolution.revision
                is EvidenceRevisionResolution.Rejected ->
                    return rejected(WorkspacePublicationRecordFailure.NegativeEvidenceRevision)
            }
            val referenceRevision = when (val resolution = EvidenceRevision.fromPersisted(record.referenceRevision)) {
                is EvidenceRevisionResolution.Resolved -> resolution.revision
                is EvidenceRevisionResolution.Rejected ->
                    return rejected(WorkspacePublicationRecordFailure.NegativeEvidenceRevision)
            }
            val graphPublication = when {
                record.graphRevision != null && record.graphBlocker == null -> when (
                    val resolution = EvidenceRevision.fromPersisted(record.graphRevision)
                ) {
                    is EvidenceRevisionResolution.Resolved -> GraphEvidencePublication.Ready(resolution.revision)
                    is EvidenceRevisionResolution.Rejected ->
                        return rejected(WorkspacePublicationRecordFailure.InvalidGraphPublication)
                }
                record.graphRevision == null && record.graphBlocker == GraphEvidenceBlocker.INDEXING_FAILED.name ->
                    GraphEvidencePublication.Blocked(GraphEvidenceBlocker.INDEXING_FAILED)
                else -> return rejected(WorkspacePublicationRecordFailure.InvalidGraphPublication)
            }
            if (record.publishedAtEpochMillis < 0) {
                return rejected(
                    WorkspacePublicationRecordFailure.NegativePublicationTime(record.publishedAtEpochMillis),
                )
            }
            val overlay = when (
                val resolution = RepositoryOverlayPublication.fromSerializedFileName(record.repositoryOverlayFile)
            ) {
                is RepositoryOverlayPublicationResolution.Resolved -> resolution.publication
                is RepositoryOverlayPublicationResolution.Rejected -> return rejected(
                    WorkspacePublicationRecordFailure.InvalidRepositoryOverlay(resolution.failure),
                )
            }
            return WorkspacePublicationRecordResolution.Resolved(
                PublishedWorkspaceGenerationManifest(
                    generation = WorkspaceSemanticGeneration(record.generation),
                    identity = PublishedWorkspaceIdentity(record.identity),
                    sourceIndexGeneration = SourceIndexGeneration(record.sourceIndexGeneration),
                    sourceRevision = sourceRevision,
                    referenceRevision = referenceRevision,
                    graphPublication = graphPublication,
                    sourceIndexSchemaVersion = SourceIndexSchemaVersion(record.sourceIndexSchemaVersion),
                    publishedAt = PublicationEpochMillis.fromClock(record.publishedAtEpochMillis),
                    repositoryOverlay = overlay,
                ),
            )
        }

        private fun rejected(failure: WorkspacePublicationRecordFailure) =
            WorkspacePublicationRecordResolution.Rejected(failure)
    }
}

internal data class SerializedWorkspacePublication(
    val generation: Long,
    val identity: String,
    val sourceIndexGeneration: Long,
    val sourceRevision: Long,
    val referenceRevision: Long,
    val graphRevision: Long?,
    val graphBlocker: String?,
    val sourceIndexSchemaVersion: Int,
    val publishedAtEpochMillis: Long,
    val repositoryOverlayFile: String?,
)

sealed interface WorkspacePublicationRecordFailure {
    data class InvalidGeneration(val value: Long) : WorkspacePublicationRecordFailure

    data class BlankIdentity(val value: String) : WorkspacePublicationRecordFailure

    data class NegativeSourceIndexGeneration(val value: Long) : WorkspacePublicationRecordFailure

    data object NegativeEvidenceRevision : WorkspacePublicationRecordFailure

    data object InvalidGraphPublication : WorkspacePublicationRecordFailure

    data class InvalidSchemaVersion(val value: Int) : WorkspacePublicationRecordFailure

    data class NegativePublicationTime(val value: Long) : WorkspacePublicationRecordFailure

    data class InvalidRepositoryOverlay(
        val failure: RepositoryOverlayPublicationFailure,
    ) : WorkspacePublicationRecordFailure
}

internal sealed interface WorkspacePublicationRecordResolution {
    data class Resolved(
        val manifest: PublishedWorkspaceGenerationManifest,
    ) : WorkspacePublicationRecordResolution

    data class Rejected(
        val failure: WorkspacePublicationRecordFailure,
    ) : WorkspacePublicationRecordResolution
}

class InvalidWorkspacePublicationRecordException(
    val failure: WorkspacePublicationRecordFailure,
) : IllegalStateException(failure.toString())

internal data class WorkspacePublicationReadiness(
    val sourceIndexGeneration: SourceIndexGeneration,
)

sealed interface WorkspacePublicationReadinessFailure {
    data object ModuleProgressAbsent : WorkspacePublicationReadinessFailure

    data class ModulesIncomplete(val count: NonNegativeInt) : WorkspacePublicationReadinessFailure

    data class PendingUpdates(val count: NonNegativeInt) : WorkspacePublicationReadinessFailure
}

internal sealed interface WorkspacePublicationReadinessResolution {
    data class Ready(val proof: WorkspacePublicationReadiness) : WorkspacePublicationReadinessResolution

    data class Rejected(
        val failure: WorkspacePublicationReadinessFailure,
    ) : WorkspacePublicationReadinessResolution
}

class WorkspacePublicationRejectedException(
    val failure: WorkspacePublicationReadinessFailure,
) : IllegalStateException(failure.toString())

internal data class WorkspacePublicationCommitProof(
    val manifest: PublishedWorkspaceGenerationManifest,
)

sealed interface WorkspacePublicationCommitFailure {
    data class SchemaMismatch(
        val expected: SourceIndexSchemaVersion,
        val actual: SourceIndexSchemaVersion,
    ) : WorkspacePublicationCommitFailure

    data class SourceIndexGenerationMoved(
        val expected: SourceIndexGeneration,
        val actual: SourceIndexGeneration,
    ) : WorkspacePublicationCommitFailure

    data object EvidenceRevisionMismatch : WorkspacePublicationCommitFailure

    data class RevisionMoved(
        val expected: WorkspaceSemanticGeneration,
        val actual: WorkspaceSemanticGeneration,
    ) : WorkspacePublicationCommitFailure
}

internal sealed interface WorkspacePublicationCommitResolution {
    data class Proven(val proof: WorkspacePublicationCommitProof) : WorkspacePublicationCommitResolution

    data class Rejected(val failure: WorkspacePublicationCommitFailure) : WorkspacePublicationCommitResolution
}

class WorkspacePublicationCommitRejectedException(
    val failure: WorkspacePublicationCommitFailure,
) : IllegalStateException(failure.toString())

data class WorkspaceGenerationCommit(
    val manifest: PublishedWorkspaceGenerationManifest,
)

sealed interface PublishedWorkspaceGenerationState {
    data object Unpublished : PublishedWorkspaceGenerationState

    data class Published(
        val manifest: PublishedWorkspaceGenerationManifest,
    ) : PublishedWorkspaceGenerationState
}

internal class WorkspaceGenerationOwner

/** Opaque capability for an active, unprepared workspace publication transaction. */
class OpenWorkspaceGeneration internal constructor(
    internal val write: WorkspaceWriteSession,
    internal val owner: WorkspaceGenerationOwner,
)

/**
 * Proof transition: `OpenWorkspaceGeneration -> PreparedWorkspaceGeneration`.
 *
 * Carries the manifest proven complete against the still-open SQLite
 * transaction. Only [WorkspaceGenerationStore.prepare] can derive this
 * commit capability; raw SQLite state is not exposed.
 */
class PreparedWorkspaceGeneration internal constructor(
    internal val write: WorkspaceWriteSession,
    internal val owner: WorkspaceGenerationOwner,
    val manifest: PublishedWorkspaceGenerationManifest,
)

/**
 * Owns the one long-lived SQLite transaction that turns a complete workspace reconciliation
 * into the next published revision. No filesystem generation or pointer is created.
 */
class WorkspaceGenerationStore(
    private val store: SqliteSourceIndexStore,
    private val publicationClock: () -> PublicationEpochMillis = {
        PublicationEpochMillis.fromClock(System.currentTimeMillis())
    },
) {
    private val owner = WorkspaceGenerationOwner()

    fun current(): PublishedWorkspaceGenerationState = store.readWorkspacePublication()

    fun begin(): OpenWorkspaceGeneration = OpenWorkspaceGeneration(
        write = store.beginWorkspaceWrite(),
        owner = owner,
    )

    /**
     * Proof transition:
     * `(OpenWorkspaceGeneration, PublishedWorkspaceIdentity) -> PreparedWorkspaceGeneration`.
     *
     * Establishes that all module progress is complete, no pending update
     * remains, and the returned manifest is bound to the active source-index
     * generation. Raw clock time is refined to [PublicationEpochMillis] at
     * this boundary.
     */
    fun prepare(
        candidate: OpenWorkspaceGeneration,
        identity: PublishedWorkspaceIdentity,
        graphBlocker: GraphEvidenceBlocker? = null,
    ): PreparedWorkspaceGeneration {
        requireOwned(candidate.owner)
        val manifest = store.prepareWorkspacePublication(
            session = candidate.write,
            identity = identity,
            publishedAt = publicationClock(),
            graphBlocker = graphBlocker,
        )
        return PreparedWorkspaceGeneration(candidate.write, candidate.owner, manifest)
    }

    fun commit(candidate: PreparedWorkspaceGeneration): WorkspaceGenerationCommit {
        requireOwned(candidate.owner)
        val committed = store.commitWorkspacePublication(candidate.write, candidate.manifest)
        return WorkspaceGenerationCommit(committed)
    }

    fun discard(candidate: OpenWorkspaceGeneration) {
        requireOwned(candidate.owner)
        store.discardWorkspaceWrite(candidate.write)
    }

    fun discard(candidate: PreparedWorkspaceGeneration) {
        requireOwned(candidate.owner)
        store.discardWorkspaceWrite(candidate.write)
    }

    private fun requireOwned(candidateOwner: WorkspaceGenerationOwner) {
        require(candidateOwner === owner) { "Workspace publication belongs to another store" }
    }
}
