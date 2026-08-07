package io.github.amichne.kast.indexstore.snapshot

import io.github.amichne.kast.api.contract.NonNegativeInt
import io.github.amichne.kast.api.contract.NormalizedPath
import io.github.amichne.kast.indexstore.api.reference.SourceIndexGeneration
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.security.MessageDigest

@JvmInline
value class SnapshotRetentionEpochMillis private constructor(val value: Long) {
    init {
        require(value >= 0) { "Snapshot retention time must be non-negative" }
    }

    companion object {
        /**
         * Proof transition: `Long -> SnapshotRetentionEpochMillis`.
         *
         * Establishes a non-negative retention-clock value. Raw extraction is
         * permitted only at clock and duration-comparison boundaries.
         */
        fun fromClock(value: Long): SnapshotRetentionEpochMillis = SnapshotRetentionEpochMillis(value)
    }
}

@JvmInline
value class SnapshotDiskBudgetBytes private constructor(val value: Long) {
    init {
        require(value >= 0) { "Snapshot disk budget must be non-negative" }
    }

    companion object {
        /**
         * Proof transition: `Long -> SnapshotDiskBudgetBytes`.
         *
         * Establishes a non-negative byte budget. Raw extraction is permitted
         * only at filesystem-size comparison boundaries.
         */
        fun fromConfiguration(value: Long): SnapshotDiskBudgetBytes = SnapshotDiskBudgetBytes(value)
    }
}

@JvmInline
value class SnapshotStorageBytes private constructor(val value: Long) {
    companion object {
        /**
         * Proof transition: `Long -> SnapshotStorageBytes`.
         *
         * Establishes a non-negative filesystem measurement. Raw extraction is
         * permitted only at filesystem-size and budget-comparison boundaries.
         */
        fun fromFilesystem(value: Long): SnapshotStorageBytes {
            require(value >= 0) { "Snapshot storage size must be non-negative" }
            return SnapshotStorageBytes(value)
        }
    }
}

data class SnapshotRetentionPins(
    val activeWorktreeTargets: Set<SnapshotKey> = emptySet(),
    val mergeBaseLeases: Map<SnapshotKey, SnapshotRetentionEpochMillis> = emptyMap(),
    val now: SnapshotRetentionEpochMillis = SnapshotRetentionEpochMillis.fromClock(System.currentTimeMillis()),
    val diskBudget: SnapshotDiskBudgetBytes = SnapshotDiskBudgetBytes.fromConfiguration(DEFAULT_DISK_BUDGET_BYTES),
)

class SnapshotExportTarget private constructor(
    private val directory: NormalizedPath,
    val database: NormalizedPath,
) : AutoCloseable {
    override fun close() {
        val directoryPath = directory.toJavaPath()
        check(directoryPath.toFile().deleteRecursively()) {
            "Snapshot export target cleanup failed: $directory"
        }
    }

    companion object {
        /**
         * Capability transition: `NormalizedPath -> SnapshotExportTarget`.
         *
         * Allocates one private temporary directory beneath the repository and
         * derives an absent canonical `source-index.db` target for SQLite
         * `VACUUM INTO`. The returned capability owns cleanup; raw paths are
         * extracted only at filesystem and SQLite boundaries.
         */
        fun allocate(repositoryDirectory: NormalizedPath): SnapshotExportTarget {
            val parent = repositoryDirectory.toJavaPath()
            Files.createDirectories(parent)
            val directory = Files.createTempDirectory(parent, ".kast-snapshot-export-")
            return SnapshotExportTarget(
                directory = NormalizedPath.ofAbsolute(directory),
                database = NormalizedPath.ofAbsolute(directory.resolve("source-index.db")),
            )
        }
    }
}

data class PublicationEvidence(
    val generationBefore: SourceIndexGeneration,
    val generationAfter: SourceIndexGeneration,
    val moduleProgressCount: NonNegativeInt,
    val incompleteModuleCount: NonNegativeInt,
    val pendingCount: NonNegativeInt,
    val treeOid: GitObjectId,
    val indexSchema: SourceIndexSchemaVersion,
    val producerVersion: ProducerVersion,
) {
    /**
     * Proof transition: `(PublicationEvidence, SnapshotKey) -> SnapshotPublicationEvidenceResolution`.
     *
     * A proven result carries evidence that the source index stayed at one
     * generation, contains module progress, is complete, has no pending
     * updates, and matches the requested tree, schema, and producer. Rejection
     * is finite [SnapshotPublicationEvidenceFailure] data.
     */
    fun prove(key: SnapshotKey): SnapshotPublicationEvidenceResolution {
        if (generationBefore != generationAfter) {
            return rejected(SnapshotPublicationEvidenceFailure.GenerationMoved(generationBefore, generationAfter))
        }
        if (moduleProgressCount.value == 0) {
            return rejected(SnapshotPublicationEvidenceFailure.ModuleProgressAbsent)
        }
        if (incompleteModuleCount.value != 0) {
            return rejected(SnapshotPublicationEvidenceFailure.ModulesIncomplete(incompleteModuleCount))
        }
        if (pendingCount.value != 0) {
            return rejected(SnapshotPublicationEvidenceFailure.PendingUpdates(pendingCount))
        }
        if (treeOid != key.treeOid) {
            return rejected(SnapshotPublicationEvidenceFailure.TreeMismatch(key.treeOid, treeOid))
        }
        if (indexSchema != key.indexSchema) {
            return rejected(SnapshotPublicationEvidenceFailure.SchemaMismatch(key.indexSchema, indexSchema))
        }
        if (producerVersion != key.producerVersion) {
            return rejected(
                SnapshotPublicationEvidenceFailure.ProducerMismatch(key.producerVersion, producerVersion),
            )
        }
        return SnapshotPublicationEvidenceResolution.Proven(this)
    }

    private fun rejected(failure: SnapshotPublicationEvidenceFailure) =
        SnapshotPublicationEvidenceResolution.Rejected(failure)
}

sealed interface SnapshotPublicationEvidenceFailure {
    data class GenerationMoved(
        val before: SourceIndexGeneration,
        val after: SourceIndexGeneration,
    ) : SnapshotPublicationEvidenceFailure

    data object ModuleProgressAbsent : SnapshotPublicationEvidenceFailure

    data class ModulesIncomplete(val count: NonNegativeInt) : SnapshotPublicationEvidenceFailure

    data class PendingUpdates(val count: NonNegativeInt) : SnapshotPublicationEvidenceFailure

    data class TreeMismatch(val expected: GitObjectId, val actual: GitObjectId) : SnapshotPublicationEvidenceFailure

    data class SchemaMismatch(
        val expected: SourceIndexSchemaVersion,
        val actual: SourceIndexSchemaVersion,
    ) : SnapshotPublicationEvidenceFailure

    data class ProducerMismatch(
        val expected: ProducerVersion,
        val actual: ProducerVersion,
    ) : SnapshotPublicationEvidenceFailure
}

sealed interface SnapshotPublicationEvidenceResolution {
    data class Proven(val evidence: PublicationEvidence) : SnapshotPublicationEvidenceResolution

    data class Rejected(val failure: SnapshotPublicationEvidenceFailure) : SnapshotPublicationEvidenceResolution
}

sealed interface SnapshotPublicationFailure {
    data class EvidenceRejected(val failure: SnapshotPublicationEvidenceFailure) : SnapshotPublicationFailure

    data class SourceDatabaseUnavailable(val path: NormalizedPath) : SnapshotPublicationFailure

    data class ExistingSnapshotRejected(
        val failure: RepositorySnapshotDatabaseFailure,
    ) : SnapshotPublicationFailure

    data class MetadataRejected(
        val failure: RepositorySnapshotMetadataFailure,
    ) : SnapshotPublicationFailure
}

sealed interface SnapshotPublicationResult {
    data class Published(val manifest: SnapshotManifest) : SnapshotPublicationResult

    data class Reused(val manifest: SnapshotManifest) : SnapshotPublicationResult

    data class Rejected(val failure: SnapshotPublicationFailure) : SnapshotPublicationResult
}

sealed interface LatestGoodSnapshot {
    data object Unavailable : LatestGoodSnapshot

    data class Available(val manifest: SnapshotManifest) : LatestGoodSnapshot

    data class Rejected(val failure: RepositorySnapshotMetadataFailure) : LatestGoodSnapshot
}

class RepositoryContentShard internal constructor(
    val key: ExtractionShardKey,
    val path: NormalizedPath,
)

class RepositoryContentShardPayload private constructor(
    val key: ExtractionShardKey,
    content: ByteArray,
) {
    internal val content: ByteArray = content.copyOf()

    companion object {
        /**
         * Proof transition:
         * `(ExtractionShardKey, ByteArray) -> RepositoryContentShardPayloadResolution`.
         *
         * A proven payload has the exact Git blob identity carried by the shard
         * key; callers cannot publish unverified bytes under that identity.
         * Rejection is finite [RepositoryContentShardPayloadFailure] data. Raw
         * bytes are accepted and extracted only at Git and filesystem
         * boundaries.
         */
        fun prove(
            key: ExtractionShardKey,
            content: ByteArray,
        ): RepositoryContentShardPayloadResolution {
            val algorithm = when (key.blobOid.value.length) {
                40 -> "SHA-1"
                64 -> "SHA-256"
                else -> error("GitObjectId construction admitted an unsupported digest width")
            }
            val digest = MessageDigest.getInstance(algorithm).apply {
                update("blob ${content.size}\u0000".toByteArray(StandardCharsets.UTF_8))
                update(content)
            }.digest().joinToString("") { byte -> "%02x".format(byte) }
            val actual = GitObjectId.fromCanonical(digest)
            return if (actual == key.blobOid) {
                RepositoryContentShardPayloadResolution.Proven(
                    RepositoryContentShardPayload(key, content),
                )
            } else {
                RepositoryContentShardPayloadResolution.Rejected(
                    RepositoryContentShardPayloadFailure.HashMismatch(key.blobOid, actual),
                )
            }
        }
    }
}

sealed interface RepositoryContentShardPayloadFailure {
    data class HashMismatch(
        val expected: GitObjectId,
        val actual: GitObjectId,
    ) : RepositoryContentShardPayloadFailure
}

sealed interface RepositoryContentShardPayloadResolution {
    data class Proven(val payload: RepositoryContentShardPayload) : RepositoryContentShardPayloadResolution

    data class Rejected(
        val failure: RepositoryContentShardPayloadFailure,
    ) : RepositoryContentShardPayloadResolution
}

sealed interface RepositoryContentShardFailure {
    data class StorageInvalid(val path: NormalizedPath) : RepositoryContentShardFailure

    data class PayloadRejected(
        val failure: RepositoryContentShardPayloadFailure,
    ) : RepositoryContentShardFailure
}

sealed interface RepositoryContentShardPublicationResult {
    data class Published(val shard: RepositoryContentShard) : RepositoryContentShardPublicationResult

    data class Reused(val shard: RepositoryContentShard) : RepositoryContentShardPublicationResult

    data class Rejected(val failure: RepositoryContentShardFailure) : RepositoryContentShardPublicationResult
}

sealed interface RepositoryContentShardResolution {
    data class Available(val shard: RepositoryContentShard) : RepositoryContentShardResolution

    data object Unavailable : RepositoryContentShardResolution

    data class Rejected(val failure: RepositoryContentShardFailure) : RepositoryContentShardResolution
}

sealed interface RepositorySnapshotMetadataFailure {
    data class LatestGoodPointerInvalid(val path: NormalizedPath) : RepositorySnapshotMetadataFailure

    data class LatestGoodPointerMalformed(val path: NormalizedPath) : RepositorySnapshotMetadataFailure

    data class MainHistoryInvalid(val path: NormalizedPath) : RepositorySnapshotMetadataFailure

    data class MainHistoryMalformed(val path: NormalizedPath) : RepositorySnapshotMetadataFailure

    data class SnapshotAuthorityInvalid(val path: NormalizedPath) : RepositorySnapshotMetadataFailure

    data class SnapshotEntryInvalid(val path: NormalizedPath) : RepositorySnapshotMetadataFailure

    data class SnapshotManifestInvalid(val path: NormalizedPath) : RepositorySnapshotMetadataFailure

    data class SnapshotManifestMalformed(val path: NormalizedPath) : RepositorySnapshotMetadataFailure

    data class SnapshotDirectoryMismatch(
        val path: NormalizedPath,
        val expected: SnapshotDirectoryName,
    ) : RepositorySnapshotMetadataFailure

    data class SnapshotDatabaseRejected(
        val failure: RepositorySnapshotDatabaseFailure,
    ) : RepositorySnapshotMetadataFailure
}

sealed interface RepositorySnapshotInventoryResolution {
    data class Resolved(val manifests: List<SnapshotManifest>) : RepositorySnapshotInventoryResolution

    data class Rejected(val failure: RepositorySnapshotMetadataFailure) : RepositorySnapshotInventoryResolution
}

sealed interface RepositorySnapshotGarbageCollectionFailure {
    data class OverlayAuthorityRejected(
        val failure: RepositoryOverlayRetentionFailure,
    ) : RepositorySnapshotGarbageCollectionFailure

    data class MetadataRejected(
        val failure: RepositorySnapshotMetadataFailure,
    ) : RepositorySnapshotGarbageCollectionFailure
}

sealed interface RepositorySnapshotGarbageCollectionResult {
    data object Completed : RepositorySnapshotGarbageCollectionResult

    data class PinnedDataExceedsBudget(
        val retained: SnapshotStorageBytes,
        val budget: SnapshotDiskBudgetBytes,
    ) : RepositorySnapshotGarbageCollectionResult

    data class Rejected(
        val failure: RepositorySnapshotGarbageCollectionFailure,
    ) : RepositorySnapshotGarbageCollectionResult
}

private const val DEFAULT_DISK_BUDGET_BYTES = 10L * 1024 * 1024 * 1024
