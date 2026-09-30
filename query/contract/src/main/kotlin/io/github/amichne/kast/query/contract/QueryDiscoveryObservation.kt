package io.github.amichne.kast.query.contract

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryBlockCause
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryConstraints
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryElapsedNanoseconds
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryMeasurements
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryOutcome
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryProgress
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryProviderOrder
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryQualification
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryRequest
import io.github.amichne.kast.symbol.contract.SymbolDiscoverySourceOffset
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryTarget
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryTimings
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryWorkCount
import io.github.amichne.kast.symbol.contract.SymbolSearchScope
import io.github.amichne.kast.symbol.contract.fingerprintFields
import io.github.amichne.kast.workspace.contract.SemanticReadAuthority

/** Supported declaration discovery providers establish this language universe before enumeration. */
enum class QueryDeclarationLanguage {
    KOTLIN
}

/** An observation exposes progress without exposing the retained inventory as transport authority. */
sealed interface QueryDiscoveryProgress {
    data object Exhausted : QueryDiscoveryProgress

    data class Resumable(
        val providerOrder: SymbolDiscoveryProviderOrder,
        val completedFiles: SymbolDiscoveryWorkCount,
        val nextOffset: SymbolDiscoverySourceOffset,
        val discoveredFiles: SymbolDiscoveryWorkCount,
        val pendingPartitions: SymbolDiscoveryWorkCount,
        val retainedBytes: Long,
    ) : QueryDiscoveryProgress

    data class Blocked(val cause: SymbolDiscoveryBlockCause) : QueryDiscoveryProgress
}

@ConsistentCopyVisibility
data class QueryDiscoveryObservation
private constructor(
    val authority: SemanticReadAuthority,
    val scope: SymbolSearchScope,
    val target: SymbolDiscoveryTarget,
    val constraints: SymbolDiscoveryConstraints,
    val declarationLanguage: QueryDeclarationLanguage,
    val progress: QueryDiscoveryProgress,
    val observedStops: Set<SymbolDiscoveryQualification>,
    val examinedWorkUnits: SymbolDiscoveryWorkCount,
    val timings: SymbolDiscoveryTimings,
    val measurements: SymbolDiscoveryMeasurements,
) {
    /** Instrumentation counts as retained state even though it does not consume semantic row capacity. */
    val retainedBytes: Long
        get() {
            val targetBytes =
                when (val selected = target) {
                    is SymbolDiscoveryTarget.Name -> 2L * selected.pattern.value.length
                    is SymbolDiscoveryTarget.All -> 0L
                    is SymbolDiscoveryTarget.Location -> 2L * selected.file.value.length
                    is SymbolDiscoveryTarget.Text -> 2L * selected.pattern.value.length
                }
            val scopeBytes =
                when (val selected = scope) {
                    is SymbolSearchScope.ExactFile -> 2L * selected.file.value.length
                    is SymbolSearchScope.Module -> 2L * selected.module.value.length
                    is SymbolSearchScope.GradleProject ->
                        2L * (selected.project.buildRoot.value.length + selected.project.projectPath.value.length)
                    is SymbolSearchScope.SourceSet ->
                        2L *
                            (selected.project.buildRoot.value.length +
                                selected.project.projectPath.value.length +
                                selected.sourceSet.value.length)
                    is SymbolSearchScope.Workspace -> 0L
                }
            return 768L +
                targetBytes +
                scopeBytes +
                2L * (authority.workspaceRoot.value.length + authority.identity.revisionKey.value.length) +
                constraints.fingerprintFields().sumOf { 48L + 2L * it.length }
        }

    /** Conservative canonical UTF-8 envelope contribution; fixed field names and numeric widths are reserved. */
    fun projectedUtf8Size(): Long = DISCOVERY_ENVELOPE_RESERVED_BYTES + retainedBytes

    fun sameProducer(other: QueryDiscoveryObservation): Boolean =
        authority == other.authority &&
            scope == other.scope &&
            target == other.target &&
            constraints == other.constraints &&
            declarationLanguage == other.declarationLanguage

    /** Sequential pages in one invocation have disjoint charged work, including failed candidate attempts. */
    fun followedBy(other: QueryDiscoveryObservation): QueryDiscoveryObservation {
        require(sameProducer(other))
        fun count(left: SymbolDiscoveryWorkCount, right: SymbolDiscoveryWorkCount): SymbolDiscoveryWorkCount =
            SymbolDiscoveryWorkCount.parse(add(left.value, right.value)).refined()
        fun duration(
            left: SymbolDiscoveryElapsedNanoseconds,
            right: SymbolDiscoveryElapsedNanoseconds,
        ): SymbolDiscoveryElapsedNanoseconds =
            SymbolDiscoveryElapsedNanoseconds.parse(add(left.value, right.value)).refined()
        return copy(
            progress = other.progress,
            observedStops = java.util.Collections.unmodifiableSet(observedStops + other.observedStops),
            examinedWorkUnits = count(examinedWorkUnits, other.examinedWorkUnits),
            timings =
                SymbolDiscoveryTimings(
                    duration(timings.nativeQuery, other.timings.nativeQuery),
                    duration(timings.projection, other.timings.projection),
                    duration(timings.inventory, other.timings.inventory),
                    duration(timings.declarationScan, other.timings.declarationScan),
                ),
            measurements =
                SymbolDiscoveryMeasurements(
                    count(measurements.inventoryFiles, other.measurements.inventoryFiles),
                    count(measurements.reacquiredFiles, other.measurements.reacquiredFiles),
                    count(measurements.examinedLeaves, other.measurements.examinedLeaves),
                ),
        )
    }

    companion object {
        fun from(request: SymbolDiscoveryRequest, outcome: SymbolDiscoveryOutcome): QueryDiscoveryObservation {
            val batch =
                when (outcome) {
                    is SymbolDiscoveryOutcome.Complete -> outcome.batch
                    is SymbolDiscoveryOutcome.Qualified -> outcome.batch
                }
            val progress =
                when (val native = outcome.progress) {
                    SymbolDiscoveryProgress.Exhausted -> QueryDiscoveryProgress.Exhausted
                    is SymbolDiscoveryProgress.Blocked -> QueryDiscoveryProgress.Blocked(native.cause)
                    is SymbolDiscoveryProgress.Resumable ->
                        QueryDiscoveryProgress.Resumable(
                            native.remainder.providerOrder,
                            native.remainder.completedFiles,
                            native.remainder.nextOffset,
                            native.remainder.discoveredFiles,
                            SymbolDiscoveryWorkCount.parse(native.remainder.frontier.size.toLong()).refined(),
                            native.remainder.retainedBytes,
                        )
                }
            return QueryDiscoveryObservation(
                request.scope.lease,
                request.scope.scope,
                request.target,
                request.constraints,
                QueryDeclarationLanguage.KOTLIN,
                progress,
                java.util.Collections.unmodifiableSet(
                    (outcome as? SymbolDiscoveryOutcome.Qualified)?.qualifications?.values.orEmpty().toSet()
                ),
                batch.examinedWorkUnits,
                batch.timings,
                batch.measurements,
            )
        }
    }
}

private const val DISCOVERY_ENVELOPE_RESERVED_BYTES = 4_096L

private fun add(left: Long, right: Long): Long = if (right > Long.MAX_VALUE - left) Long.MAX_VALUE else left + right

private fun <Value, Failure> Refinement<Value, Failure>.refined(): Value =
    when (this) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> error("An observation measure lost its proof: $failure")
    }
