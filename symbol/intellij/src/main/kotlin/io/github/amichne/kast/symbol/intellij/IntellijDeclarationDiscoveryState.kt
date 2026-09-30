package io.github.amichne.kast.symbol.intellij

import io.github.amichne.kast.kernel.ReadLimitParameter
import io.github.amichne.kast.kernel.ReadLimits
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.symbol.contract.SemanticFilePartition
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryActiveInput
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryBatch
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryBlockCause
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryByteCount
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryCandidate
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryElapsedNanoseconds
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryInputRevision
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryMeasurements
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryOutcome
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryProgress
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryQualification
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryQualifications
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryRemainder
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryRequest
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryTimings
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryWorkCount
import io.github.amichne.kast.symbol.contract.candidateOrder
import io.github.amichne.kast.symbol.contract.detachedIdentityBytes
import io.github.amichne.kast.symbol.contract.fingerprintFields
import io.github.amichne.kast.workspace.intellij.read.IntellijReadObservation

/** One bounded native attempt; its only cross-request product is the detached discovery remainder. */
internal class IntellijDeclarationDiscoveryState(
    val request: SymbolDiscoveryRequest,
    val limits: ReadLimits,
    val allowance: IntellijDeclarationDiscoveryAllowance,
    private val environment: () -> IntellijDiscoveryEnvironmentState,
    private val cancellationCheck: () -> Unit,
    val observation: IntellijReadObservation,
) {
    val candidates = mutableListOf<SymbolDiscoveryCandidate>()
    val qualifications = request.remainder?.permanentQualifications.orEmpty().toMutableSet()
    var block: SymbolDiscoveryBlockCause? = null
    var inventoryFiles = 0L
    var reacquiredFiles = 0L
    var examinedLeaves = 0L
    var inventoryNanos = 0L
    var scanNanos = 0L
    var projectionNanos = 0L
    var bytes = 0L
    val frontier =
        java.util.TreeMap<String, SemanticFilePartition>().apply {
            request.remainder?.frontier.orEmpty().forEach { put(it.orderingPath, it) }
        }
    var frontierBytes = frontier.values.sumOf { it.detachedBytes() }
    var active = request.remainder?.active ?: SymbolDiscoveryActiveInput.Unopened
    private var revision = request.remainder?.inputRevision?.value ?: 0L
    var discoveredFiles = request.remainder?.discoveredFiles?.value ?: 0L
    var completedFiles = request.remainder?.completedFiles?.value ?: 0L

    fun hasInput(): Boolean = frontier.isNotEmpty() || active != SymbolDiscoveryActiveInput.Unopened

    fun observe(): Boolean {
        cancellationCheck()
        when (environment()) {
            IntellijDiscoveryEnvironmentState.DUMB -> {
                qualifications += SymbolDiscoveryQualification.DUMB_MODE_TRANSITION
                block = SymbolDiscoveryBlockCause.PROVIDER_UNAVAILABLE
                return false
            }
            IntellijDiscoveryEnvironmentState.DISPOSED -> {
                qualifications += SymbolDiscoveryQualification.PROVIDER_FAILURE
                block = SymbolDiscoveryBlockCause.PROVIDER_UNAVAILABLE
                return false
            }
            IntellijDiscoveryEnvironmentState.READY -> Unit
        }
        if (allowance.expired()) {
            qualifications += SymbolDiscoveryQualification.TIME_LIMIT_REACHED
            return false
        }
        return true
    }

    fun consume(): Boolean {
        if (allowance.consume()) return true
        qualifications += SymbolDiscoveryQualification.WORK_LIMIT_REACHED
        return false
    }

    fun resultFits(): Boolean {
        if (candidates.size < request.budget.resources.resultLimit.value) return true
        qualifications += SymbolDiscoveryQualification.RESULT_LIMIT_REACHED
        return false
    }

    fun finishFile() {
        active = SymbolDiscoveryActiveInput.Unopened
        completedFiles++
        advance()
    }

    fun advance(): Boolean {
        if (revision == Long.MAX_VALUE) {
            block = SymbolDiscoveryBlockCause.INPUT_REVISION_EXHAUSTED
            return false
        }
        revision++
        return true
    }

    fun retentionFits(pendingBytes: Long = frontierBytes): Boolean {
        val bindingBytes =
            DISCOVERY_BINDING_BYTES +
                request.scope.scope.detachedIdentityBytes() +
                TEXT_UNIT_BYTES *
                    (request.scope.lease.workspaceRoot.value.length +
                        request.scope.lease.identity.revisionKey.value.length) +
                request.constraints.fingerprintFields().sumOf { FIELD_BINDING_BYTES + TEXT_UNIT_BYTES * it.length }
        val activeBytes =
            when (val input = active) {
                SymbolDiscoveryActiveInput.Unopened -> 0L
                is SymbolDiscoveryActiveInput.Scanning ->
                    PARTITION_BINDING_BYTES + TEXT_UNIT_BYTES * input.file.stableValue.length
            }
        if (bindingBytes + activeBytes + pendingBytes <= limits[ReadLimitParameter.QUERY_CHECKPOINT_BYTES].value)
            return true
        qualifications += SymbolDiscoveryQualification.BYTE_LIMIT_REACHED
        block = SymbolDiscoveryBlockCause.RETENTION_LIMIT
        return false
    }

    private fun detachedRemainder(): SymbolDiscoveryRemainder =
        SymbolDiscoveryRemainder.fromPendingInput(
                request,
                frontier.values.toList(),
                active,
                SymbolDiscoveryInputRevision.parse(revision).discoveryRefined(),
                SymbolDiscoveryWorkCount.parse(discoveredFiles).discoveryRefined(),
                SymbolDiscoveryWorkCount.parse(completedFiles).discoveryRefined(),
                qualifications - recoverableDiscoveryQualifications,
            )
            .discoveryRefined()

    private fun progress(): SymbolDiscoveryProgress {
        block?.let {
            return SymbolDiscoveryProgress.Blocked(it)
        }
        if (!hasInput()) return SymbolDiscoveryProgress.Exhausted
        if (request.remainder == null && revision == 0L)
            return SymbolDiscoveryProgress.Blocked(SymbolDiscoveryBlockCause.INSUFFICIENT_EXECUTION_GRANT)
        val remainder = detachedRemainder()
        if (request.remainder?.let { !remainder.advancesFrom(it) } == true)
            return SymbolDiscoveryProgress.Blocked(SymbolDiscoveryBlockCause.INSUFFICIENT_EXECUTION_GRANT)
        return SymbolDiscoveryProgress.Resumable(remainder)
    }

    fun produce(started: Long): IntellijNativeDiscoveryExecution {
        if (allowance.expired()) qualifications += SymbolDiscoveryQualification.TIME_LIMIT_REACHED
        val progress = progress()
        val batch =
            SymbolDiscoveryBatch.create(
                    request,
                    candidates.sortedWith(request.candidateOrder()),
                    SymbolDiscoveryByteCount.parse(bytes).discoveryRefined(),
                    SymbolDiscoveryWorkCount.parse(allowance.consumedWork).discoveryRefined(),
                    timings(started),
                    SymbolDiscoveryMeasurements(
                        SymbolDiscoveryWorkCount.parse(inventoryFiles).discoveryRefined(),
                        SymbolDiscoveryWorkCount.parse(reacquiredFiles).discoveryRefined(),
                        SymbolDiscoveryWorkCount.parse(examinedLeaves).discoveryRefined(),
                    ),
                )
                .discoveryRefined()
        val outcome =
            if (qualifications.isEmpty() && progress == SymbolDiscoveryProgress.Exhausted)
                SymbolDiscoveryOutcome.Complete(batch)
            else
                SymbolDiscoveryOutcome.Qualified(
                    batch,
                    SymbolDiscoveryQualifications.from(
                            qualifications.ifEmpty { setOf(SymbolDiscoveryQualification.PROVIDER_FAILURE) }
                        )
                        .discoveryRefined(),
                    progress,
                )
        return IntellijNativeDiscoveryExecution.Produced(outcome)
    }

    private fun timings(started: Long): SymbolDiscoveryTimings =
        SymbolDiscoveryTimings(
            SymbolDiscoveryElapsedNanoseconds.parse((elapsed(started) - projectionNanos).coerceAtLeast(0))
                .discoveryRefined(),
            SymbolDiscoveryElapsedNanoseconds.parse(projectionNanos).discoveryRefined(),
            SymbolDiscoveryElapsedNanoseconds.parse(inventoryNanos).discoveryRefined(),
            SymbolDiscoveryElapsedNanoseconds.parse(scanNanos).discoveryRefined(),
        )

    fun elapsed(started: Long): Long = (allowance.now() - started).coerceAtLeast(0L)
}

internal fun SemanticFilePartition.detachedBytes(): Long =
    PARTITION_BINDING_BYTES + TEXT_UNIT_BYTES * location.stableValue.length

internal fun <Value, Failure> Refinement<Value, Failure>.discoveryRefined(): Value =
    when (this) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> error("An internal discovery proof failed: $failure")
    }

private const val DISCOVERY_BINDING_BYTES = 768L
private const val PARTITION_BINDING_BYTES = 160L
private const val FIELD_BINDING_BYTES = 48L
private const val TEXT_UNIT_BYTES = 2L
private val recoverableDiscoveryQualifications =
    setOf(
        SymbolDiscoveryQualification.RESULT_LIMIT_REACHED,
        SymbolDiscoveryQualification.BYTE_LIMIT_REACHED,
        SymbolDiscoveryQualification.WORK_LIMIT_REACHED,
        SymbolDiscoveryQualification.TIME_LIMIT_REACHED,
    )
