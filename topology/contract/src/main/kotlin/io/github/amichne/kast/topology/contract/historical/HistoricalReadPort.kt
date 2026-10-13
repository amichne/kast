package io.github.amichne.kast.topology.contract.historical

import io.github.amichne.kast.kernel.Refinement
import java.util.Collections

enum class HistoricalReadValueFailure {
    NEGATIVE_COUNT,
    PAGE_SIZE_OUTSIDE_LIMIT,
    INVALID_METADATA,
    PROFILE_MISMATCH,
    INCOMPLETE_INVENTORY,
}

@JvmInline
value class HistoricalReadCount private constructor(val value: Long) {
    companion object {
        val Zero = HistoricalReadCount(0)

        fun parse(raw: Long): Refinement<HistoricalReadCount, HistoricalReadValueFailure> =
            if (raw >= 0) Refinement.Refined(HistoricalReadCount(raw))
            else Refinement.Rejected(HistoricalReadValueFailure.NEGATIVE_COUNT)
    }
}

@JvmInline
value class HistoricalPageSize private constructor(val value: Int) {
    companion object {
        const val MAXIMUM = 1000

        fun parse(raw: Int): Refinement<HistoricalPageSize, HistoricalReadValueFailure> =
            if (raw in 1..MAXIMUM) Refinement.Refined(HistoricalPageSize(raw))
            else Refinement.Rejected(HistoricalReadValueFailure.PAGE_SIZE_OUTSIDE_LIMIT)
    }
}

@JvmInline
value class HistoricalRowOrdinal private constructor(val value: Long) {
    companion object {
        fun parse(raw: Long): Refinement<HistoricalRowOrdinal, HistoricalReadValueFailure> =
            if (raw >= 0) Refinement.Refined(HistoricalRowOrdinal(raw))
            else Refinement.Rejected(HistoricalReadValueFailure.NEGATIVE_COUNT)
    }
}

data class HistoricalInventoryCounts(
    val files: HistoricalReadCount,
    val declarations: HistoricalReadCount,
    val occurrences: HistoricalReadCount,
)

/** Metadata carries scope and coverage without a retained declaration or occurrence graph. */
class HistoricalSnapshotInventory
private constructor(
    val identity: HistoricalDigest,
    val header: HistoricalHeader,
    modules: List<HistoricalModule>,
    coverage: List<HistoricalCoverage>,
    val counts: HistoricalInventoryCounts,
) {
    val modules: List<HistoricalModule> = Collections.unmodifiableList(modules.sortedBy { it.id })
    val coverage: List<HistoricalCoverage> =
        Collections.unmodifiableList(coverage.sortedWith(compareBy({ it.module }, { it.family })))

    companion object {
        fun from(snapshot: HistoricalSnapshot): HistoricalSnapshotInventory =
            HistoricalSnapshotInventory(
                snapshot.identity,
                snapshot.header,
                snapshot.modules,
                snapshot.coverage,
                HistoricalInventoryCounts(
                    count(snapshot.files.size),
                    count(snapshot.declarations.size),
                    count(snapshot.occurrences.size),
                ),
            )

        /** The SQLite reader calls this after artifact integrity admission. No compiler authenticity follows. */
        fun fromStored(
            identity: HistoricalDigest,
            header: HistoricalHeader,
            modules: List<HistoricalModule>,
            coverage: List<HistoricalCoverage>,
            counts: HistoricalInventoryCounts,
        ): Refinement<HistoricalSnapshotInventory, HistoricalReadValueFailure> {
            val ids = modules.map { it.id }
            val expected = ids.flatMap { id -> HistoricalFactFamily.entries.map { id to it } }.toSet()
            val actual = coverage.map { it.module to it.family }
            if (
                ids.distinct().size != ids.size || actual.distinct().size != actual.size || actual.toSet() != expected
            ) {
                return Refinement.Rejected(HistoricalReadValueFailure.INVALID_METADATA)
            }
            if (
                coverage.any {
                    it.family in setOf(HistoricalFactFamily.MODULES, HistoricalFactFamily.FILES) &&
                        it.status != HistoricalCoverageStatus.Complete
                }
            ) {
                return Refinement.Rejected(HistoricalReadValueFailure.INCOMPLETE_INVENTORY)
            }
            val references = coverage.filter { it.family == HistoricalFactFamily.REFERENCES }
            val profileMismatch =
                when (header.profile) {
                    HistoricalProfile.STRUCTURE_V1 ->
                        references.any { it.status != HistoricalCoverageStatus.NotCollected } ||
                            counts.occurrences != HistoricalReadCount.Zero
                    HistoricalProfile.REFERENCES_V1 ->
                        references.any { it.status == HistoricalCoverageStatus.NotCollected }
                }
            if (profileMismatch) return Refinement.Rejected(HistoricalReadValueFailure.PROFILE_MISMATCH)
            return Refinement.Refined(HistoricalSnapshotInventory(identity, header, modules, coverage, counts))
        }

        private fun count(value: Int): HistoricalReadCount =
            when (val result = HistoricalReadCount.parse(value.toLong())) {
                is Refinement.Refined -> result.value
                is Refinement.Rejected -> error("Collection cardinality cannot be negative")
            }
    }
}

/** Open admission work is separate from bounded row reads. Copy counts name actual byte transfers. */
data class HistoricalOpenWork(
    val rowsExamined: HistoricalReadCount,
    val artifactBytes: HistoricalReadCount,
    val copiedBytesRead: HistoricalReadCount,
    val copiedBytesWritten: HistoricalReadCount,
)

data class HistoricalPageWork(val rowsExamined: HistoricalReadCount, val rowsReturned: HistoricalReadCount)

enum class HistoricalReadFailure {
    STORAGE_UNAVAILABLE,
    CORRUPT_ARTIFACT,
    SCHEMA_UNSUPPORTED,
    PROFILE_UNSUPPORTED,
    CAPACITY_EXCEEDED,
    SNAPSHOT_MISMATCH,
    QUERY_INVENTORY_MISMATCH,
    DECLARATION_NOT_FOUND,
    CURSOR_MISMATCH,
}

sealed interface HistoricalReadResult<out Value> {
    data class Read<Value>(val value: Value) : HistoricalReadResult<Value>

    data class Rejected(val failure: HistoricalReadFailure) : HistoricalReadResult<Nothing>
}

sealed interface HistoricalRowPosition {
    data object First : HistoricalRowPosition

    data class After(
        val snapshot: HistoricalDigest,
        val family: HistoricalFactFamily,
        val ordinal: HistoricalRowOrdinal,
    ) : HistoricalRowPosition
}

data class HistoricalPageRequest(
    val size: HistoricalPageSize,
    val position: HistoricalRowPosition = HistoricalRowPosition.First,
)

sealed interface HistoricalPageEnd {
    data object Exhausted : HistoricalPageEnd

    data class More(val position: HistoricalRowPosition.After) : HistoricalPageEnd
}

data class HistoricalInventoryPage<Value>(
    val rows: List<Value>,
    val end: HistoricalPageEnd,
    val work: HistoricalPageWork,
)

data class HistoricalSelectedDeclaration(
    val reference: HistoricalDeclarationReference,
    val declaration: HistoricalDeclaration,
    val work: HistoricalPageWork,
)

/** One stored occurrence retains exact evidence and both module owners. The ordinal is artifact-local ordering. */
data class HistoricalUsageRow(
    val ordinal: HistoricalRowOrdinal,
    val sourceModule: HistoricalModuleId,
    val targetModule: HistoricalModuleId,
    val occurrence: HistoricalOccurrence,
)

sealed interface HistoricalUsageEnd {
    data object Exhausted : HistoricalUsageEnd

    data class More(val cursor: HistoricalUsageCursor) : HistoricalUsageEnd
}

data class HistoricalUsagePage(
    val query: HistoricalUsageQuery,
    val rows: List<HistoricalUsageRow>,
    val end: HistoricalUsageEnd,
    val work: HistoricalPageWork,
)

/** This capability exposes only the chosen artifact. It has no live, Git, source-read, or mutation operation. */
interface HistoricalSnapshotReader : AutoCloseable {
    val inventory: HistoricalSnapshotInventory
    val openWork: HistoricalOpenWork

    fun files(request: HistoricalPageRequest): HistoricalReadResult<HistoricalInventoryPage<HistoricalFile>>

    fun declarations(
        request: HistoricalPageRequest
    ): HistoricalReadResult<HistoricalInventoryPage<HistoricalDeclaration>>

    fun select(id: HistoricalDeclarationId): HistoricalReadResult<HistoricalSelectedDeclaration>

    fun usage(query: HistoricalUsageQuery): HistoricalReadResult<HistoricalUsagePage>

    override fun close()
}
