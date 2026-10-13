package io.github.amichne.kast.topology.contract.historical

import io.github.amichne.kast.kernel.Refinement
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Collections

enum class HistoricalUsageFailure {
    EMPTY_SOURCE_DOMAIN,
    EMPTY_TARGET_DOMAIN,
    SOURCE_OUTSIDE_CAPTURE,
    SNAPSHOT_MISMATCH,
    REFERENCES_NOT_CAPTURED,
    CURSOR_MISMATCH,
    SOURCE_DOMAIN_LIMIT,
    TARGET_DOMAIN_LIMIT,
    TARGET_SELECTION_MISMATCH,
}

sealed interface HistoricalUsageCoverage {
    data object Complete : HistoricalUsageCoverage

    class Qualified internal constructor(values: List<HistoricalCoverage>) : HistoricalUsageCoverage {
        val qualifications: List<HistoricalCoverage> = Collections.unmodifiableList(values.toList())
    }
}

/** A cursor fixes the artifact, query, and canonical occurrence order. It grants no live authority. */
data class HistoricalUsageCursor
private constructor(
    val snapshot: HistoricalDigest,
    val query: HistoricalDigest,
    val after: HistoricalRowOrdinal,
) {
    companion object {
        fun fromStored(
            snapshot: HistoricalDigest,
            query: HistoricalDigest,
            after: HistoricalRowOrdinal,
        ): HistoricalUsageCursor = HistoricalUsageCursor(snapshot, query, after)
    }
}

sealed interface HistoricalUsagePosition {
    data object First : HistoricalUsagePosition

    data class After(val cursor: HistoricalUsageCursor) : HistoricalUsagePosition
}

/** Pure request admission fixes scope, coverage, budgets, and cursor before exact storage lookups. */
class HistoricalUsagePlan
internal constructor(
    val snapshot: HistoricalDigest,
    internal val revision: HistoricalRevision,
    sources: Set<HistoricalModuleId>,
    targets: Set<HistoricalDeclarationId>,
    internal val pageSize: HistoricalPageSize,
    internal val position: HistoricalUsagePosition,
    internal val coverage: HistoricalUsageCoverage,
    internal val identity: HistoricalDigest,
) {
    val sourceModules: Set<HistoricalModuleId> = Collections.unmodifiableSet(sources.toSortedSet())
    val targetDeclarations: Set<HistoricalDeclarationId> = Collections.unmodifiableSet(targets.toSortedSet())

    fun complete(
        selections: Set<HistoricalDeclarationReference>
    ): Refinement<HistoricalUsageQuery, HistoricalUsageFailure> = HistoricalUsageQuery.complete(this, selections)
}

class HistoricalUsageQuery
private constructor(
    val snapshot: HistoricalDigest,
    val revision: HistoricalRevision,
    sources: Set<HistoricalModuleId>,
    selections: Set<HistoricalDeclarationReference>,
    val pageSize: HistoricalPageSize,
    val position: HistoricalUsagePosition,
    val coverage: HistoricalUsageCoverage,
    val identity: HistoricalDigest,
) {
    val sourceModules: Set<HistoricalModuleId> = Collections.unmodifiableSet(sources.toSortedSet())
    val targets: Set<HistoricalDeclarationReference> =
        Collections.unmodifiableSet(selections.sortedBy { it.declaration }.toCollection(linkedSetOf()))
    val family: HistoricalFactFamily = HistoricalFactFamily.REFERENCES

    fun cursorAfter(ordinal: HistoricalRowOrdinal): HistoricalUsageCursor =
        HistoricalUsageCursor.fromStored(snapshot, identity, ordinal)

    companion object {
        const val MAXIMUM_SOURCE_MODULES = 128
        const val MAXIMUM_TARGET_DECLARATIONS = 128

        fun admit(
            inventory: HistoricalSnapshotInventory,
            sources: Set<HistoricalModuleId>,
            targets: Set<HistoricalDeclarationReference>,
            pageSize: HistoricalPageSize,
            position: HistoricalUsagePosition = HistoricalUsagePosition.First,
        ): Refinement<HistoricalUsageQuery, HistoricalUsageFailure> {
            if (targets.any { it.snapshot != inventory.identity })
                return Refinement.Rejected(HistoricalUsageFailure.SNAPSHOT_MISMATCH)
            return when (
                val planned = prepare(inventory, sources, targets.map { it.declaration }.toSet(), pageSize, position)
            ) {
                is Refinement.Refined -> planned.value.complete(targets)
                is Refinement.Rejected -> planned
            }
        }

        fun prepare(
            inventory: HistoricalSnapshotInventory,
            sources: Set<HistoricalModuleId>,
            targets: Set<HistoricalDeclarationId>,
            pageSize: HistoricalPageSize,
            position: HistoricalUsagePosition = HistoricalUsagePosition.First,
        ): Refinement<HistoricalUsagePlan, HistoricalUsageFailure> {
            when (val domain = admitDomain(inventory, sources, targets)) {
                is Refinement.Refined -> Unit
                is Refinement.Rejected -> return domain
            }
            val admittedCoverage =
                when (val admitted = admitCoverage(inventory, sources)) {
                    is Refinement.Refined -> admitted.value
                    is Refinement.Rejected -> return admitted
                }
            val identity = queryIdentity(inventory.identity, sources, targets, pageSize)
            when (position) {
                HistoricalUsagePosition.First -> Unit
                is HistoricalUsagePosition.After ->
                    if (position.cursor.snapshot != inventory.identity || position.cursor.query != identity) {
                        return Refinement.Rejected(HistoricalUsageFailure.CURSOR_MISMATCH)
                    }
            }
            return Refinement.Refined(
                HistoricalUsagePlan(
                    inventory.identity,
                    inventory.header.revision,
                    sources,
                    targets,
                    pageSize,
                    position,
                    admittedCoverage,
                    identity,
                )
            )
        }

        internal fun complete(
            plan: HistoricalUsagePlan,
            selections: Set<HistoricalDeclarationReference>,
        ): Refinement<HistoricalUsageQuery, HistoricalUsageFailure> {
            if (selections.any { it.snapshot != plan.snapshot })
                return Refinement.Rejected(HistoricalUsageFailure.SNAPSHOT_MISMATCH)
            if (selections.map { it.declaration }.toSet() != plan.targetDeclarations)
                return Refinement.Rejected(HistoricalUsageFailure.TARGET_SELECTION_MISMATCH)
            return Refinement.Refined(
                HistoricalUsageQuery(
                    plan.snapshot,
                    plan.revision,
                    plan.sourceModules,
                    selections,
                    plan.pageSize,
                    plan.position,
                    plan.coverage,
                    plan.identity,
                )
            )
        }

        private fun admitDomain(
            inventory: HistoricalSnapshotInventory,
            sources: Set<HistoricalModuleId>,
            targets: Set<HistoricalDeclarationId>,
        ): Refinement<Unit, HistoricalUsageFailure> {
            if (sources.isEmpty()) return Refinement.Rejected(HistoricalUsageFailure.EMPTY_SOURCE_DOMAIN)
            if (targets.isEmpty()) return Refinement.Rejected(HistoricalUsageFailure.EMPTY_TARGET_DOMAIN)
            if (sources.size > MAXIMUM_SOURCE_MODULES)
                return Refinement.Rejected(HistoricalUsageFailure.SOURCE_DOMAIN_LIMIT)
            if (targets.size > MAXIMUM_TARGET_DECLARATIONS)
                return Refinement.Rejected(HistoricalUsageFailure.TARGET_DOMAIN_LIMIT)
            if (!inventory.modules.map { it.id }.containsAll(sources))
                return Refinement.Rejected(HistoricalUsageFailure.SOURCE_OUTSIDE_CAPTURE)
            return Refinement.Refined(Unit)
        }

        private fun admitCoverage(
            inventory: HistoricalSnapshotInventory,
            sources: Set<HistoricalModuleId>,
        ): Refinement<HistoricalUsageCoverage, HistoricalUsageFailure> {
            val coverage =
                inventory.coverage.filter { it.module in sources && it.family == HistoricalFactFamily.REFERENCES }
            if (
                inventory.header.profile == HistoricalProfile.STRUCTURE_V1 ||
                    coverage.any { it.status == HistoricalCoverageStatus.NotCollected }
            ) {
                return Refinement.Rejected(HistoricalUsageFailure.REFERENCES_NOT_CAPTURED)
            }
            val qualifications = coverage.filter { it.status != HistoricalCoverageStatus.Complete }
            return Refinement.Refined(
                if (qualifications.isEmpty()) HistoricalUsageCoverage.Complete
                else HistoricalUsageCoverage.Qualified(qualifications)
            )
        }

        private fun queryIdentity(
            snapshot: HistoricalDigest,
            sources: Set<HistoricalModuleId>,
            targets: Set<HistoricalDeclarationId>,
            pageSize: HistoricalPageSize,
        ): HistoricalDigest {
            val fields =
                listOf(
                    "historical-usage-v1",
                    snapshot.value,
                    pageSize.value.toString(),
                    "canonical-occurrence-order-v1",
                ) +
                    listOf(sources.size.toString()) +
                    sources.sorted().map { it.value } +
                    listOf(targets.size.toString()) +
                    targets.sorted().flatMap {
                        listOf(
                            it.file.value,
                            it.range.startInclusive.toString(),
                            it.range.endExclusive.toString(),
                            it.compiler.value,
                        )
                    }
            val digest = MessageDigest.getInstance("SHA-256")
            fields.forEach { field ->
                val bytes = field.toByteArray(StandardCharsets.UTF_8)
                digest.update(bytes.size.toString().toByteArray(StandardCharsets.UTF_8))
                digest.update(':'.code.toByte())
                digest.update(bytes)
            }
            val text = digest.digest().joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
            return when (val admitted = HistoricalDigest.parse(text)) {
                is Refinement.Refined -> admitted.value
                is Refinement.Rejected -> error("SHA-256 has a canonical hexadecimal representation")
            }
        }
    }
}
