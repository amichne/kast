package io.github.amichne.kast.diagnostic.service

import io.github.amichne.kast.diagnostic.contract.DiagnosticCheckRequest
import io.github.amichne.kast.diagnostic.contract.DiagnosticCheckResult
import io.github.amichne.kast.diagnostic.contract.DiagnosticEnumerationRequest
import io.github.amichne.kast.diagnostic.contract.DiagnosticEnumerationResult
import io.github.amichne.kast.diagnostic.contract.DiagnosticFact
import io.github.amichne.kast.diagnostic.contract.DiagnosticFactCount
import io.github.amichne.kast.diagnostic.contract.DiagnosticLimitation
import io.github.amichne.kast.diagnostic.contract.DiagnosticOperations
import io.github.amichne.kast.diagnostic.contract.DiagnosticScanCheckpoint
import io.github.amichne.kast.diagnostic.contract.DiagnosticScanInventory
import io.github.amichne.kast.diagnostic.contract.DiagnosticScanOperations
import io.github.amichne.kast.diagnostic.contract.DiagnosticScanPage
import io.github.amichne.kast.diagnostic.contract.DiagnosticScanRejection
import io.github.amichne.kast.diagnostic.contract.DiagnosticScanRequest
import io.github.amichne.kast.diagnostic.contract.DiagnosticScanResult
import io.github.amichne.kast.diagnostic.contract.DiagnosticScanStop
import io.github.amichne.kast.diagnostic.contract.DiagnosticScope
import io.github.amichne.kast.diagnostic.contract.DiagnosticScopeEnumerator
import io.github.amichne.kast.diagnostic.contract.DiagnosticScopeQuery
import io.github.amichne.kast.diagnostic.contract.DiagnosticScopeResolutionFailure
import io.github.amichne.kast.diagnostic.contract.DiagnosticSourceFile
import io.github.amichne.kast.kernel.ElapsedTimeLimitMillis
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.ResourceBudget
import io.github.amichne.kast.kernel.ResultLimit
import io.github.amichne.kast.kernel.WorkUnitLimit
import io.github.amichne.kast.workspace.contract.SemanticReadValidation
import io.github.amichne.kast.workspace.contract.SemanticReadValidationPort
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/** Advances detached stages under one request grant, retaining complete-file output between calls. */
class DiagnosticScanService(
    private val authorities: SemanticReadValidationPort,
    private val enumeration: DiagnosticScopeEnumerator,
    private val diagnostics: DiagnosticOperations,
    private val clock: () -> Long = System::nanoTime,
) : DiagnosticScanOperations {
    override suspend fun scan(request: DiagnosticScanRequest, budget: ResourceBudget): DiagnosticScanResult {
        val start = clock()
        if (authorities.validate(request.query.lease) != SemanticReadValidation.CURRENT) return stale()
        if (TimeUnit.NANOSECONDS.toMillis(clock() - start) >= budget.elapsedTimeLimit.value) return timeGrantRejected()
        val checkpoint =
            when (request) {
                is DiagnosticScanRequest.First ->
                    DetachedDiagnosticScan(
                        request.query,
                        DiagnosticScanWork.Enumerate(DiagnosticEnumerationRequest.First(request.query)),
                    )
                is DiagnosticScanRequest.Resume ->
                    request.checkpoint as? DetachedDiagnosticScan ?: return scopeRejected()
            }
        return progress(checkpoint, budget, start)
    }

    private suspend fun progress(
        initial: DetachedDiagnosticScan,
        budget: ResourceBudget,
        start: Long,
    ): DiagnosticScanResult {
        var checkpoint = initial
        val allowance = DiagnosticScanAllowance(budget)
        while (true) {
            val remaining =
                when (val grant = allowance.remaining(TimeUnit.NANOSECONDS.toMillis(clock() - start))) {
                    is Refinement.Refined -> grant.value
                    is Refinement.Rejected -> return DiagnosticScanResult.Rejected(grant.failure)
                }
            val advanced =
                validatePublication(checkpoint, advance(checkpoint, allowance, remaining, start), budget, start)
            when (advanced) {
                is DiagnosticScanResult.Advancing -> {
                    allowance.facts += advanced.page.facts
                    if (allowance.remainingWork == 0L || allowance.facts.size == budget.resultLimit.value) {
                        return advanced.copy(page = advanced.page.copy(facts = allowance.facts.toList()))
                    }
                    checkpoint = advanced.checkpoint as? DetachedDiagnosticScan ?: return scopeRejected()
                }
                is DiagnosticScanResult.Complete ->
                    return advanced.copy(page = advanced.page.copy(facts = allowance.facts + advanced.page.facts))
                is DiagnosticScanResult.Qualified ->
                    return advanced.copy(page = advanced.page.copy(facts = allowance.facts + advanced.page.facts))
                is DiagnosticScanResult.Rejected -> return advanced
            }
        }
    }

    private suspend fun validatePublication(
        checkpoint: DetachedDiagnosticScan,
        advanced: DiagnosticScanResult,
        budget: ResourceBudget,
        start: Long,
    ): DiagnosticScanResult =
        when {
            authorities.validate(checkpoint.query.lease) != SemanticReadValidation.CURRENT -> stale()
            advanced is DiagnosticScanResult.Rejected -> advanced
            TimeUnit.NANOSECONDS.toMillis(clock() - start) >= budget.elapsedTimeLimit.value -> timeGrantRejected()
            else -> advanced
        }

    private suspend fun advance(
        checkpoint: DetachedDiagnosticScan,
        allowance: DiagnosticScanAllowance,
        remaining: ResourceBudget,
        start: Long,
    ): DiagnosticScanResult =
        when (val work = checkpoint.work) {
            is DiagnosticScanWork.Enumerate -> {
                val enumerated = enumeration.enumerate(work.request, remaining)
                val consumed =
                    when (enumerated) {
                        is DiagnosticEnumerationResult.Exhausted -> enumerated.consumedWork.value
                        else -> allowance.remainingWork
                    }
                if (consumed > allowance.remainingWork) scopeRejected()
                else {
                    allowance.remainingWork -= consumed
                    enumerate(checkpoint, enumerated)
                }
            }
            is DiagnosticScanWork.Analyze -> {
                allowance.remainingWork--
                analyze(checkpoint, work, allowance.budget.copy(resultLimit = remaining.resultLimit), start)
            }
            is DiagnosticScanWork.Drain -> checkpoint.publish(work.facts, work.next, remaining.resultLimit.value)
            DiagnosticScanWork.Finished -> scopeRejected()
        }

    private fun enumerate(
        checkpoint: DetachedDiagnosticScan,
        result: DiagnosticEnumerationResult,
    ): DiagnosticScanResult {
        if (result is DiagnosticEnumerationResult.Rejected)
            return DiagnosticScanResult.Rejected(DiagnosticScanRejection.Enumeration(result.failure))
        val files = result.files
        if (!checkpoint.admitsEnumeration(files)) return scopeRejected()
        val next =
            when (result) {
                is DiagnosticEnumerationResult.Advancing -> {
                    if (result.cursor.query != checkpoint.query) return scopeRejected()
                    DiagnosticScanWork.Enumerate(DiagnosticEnumerationRequest.Resume(result.cursor))
                }
                is DiagnosticEnumerationResult.Exhausted -> DiagnosticScanWork.Finished
                is DiagnosticEnumerationResult.Rejected -> return scopeRejected()
            }
        val updated = checkpoint.copy(work = if (files.isEmpty()) next else DiagnosticScanWork.Analyze(files, next))
        if (
            updated.work == DiagnosticScanWork.Finished && updated.analyzed.isEmpty() && updated.limitations.isEmpty()
        ) {
            return DiagnosticScanResult.Rejected(DiagnosticScanRejection.Scope(DiagnosticScopeResolutionFailure.EMPTY))
        }
        val stop =
            when (result) {
                is DiagnosticEnumerationResult.Advancing -> DiagnosticScanStop.Enumeration(result.reason)
                else -> DiagnosticScanStop.AnalysisPending
            }
        return updated.result(emptyList(), stop)
    }

    private suspend fun analyze(
        checkpoint: DetachedDiagnosticScan,
        work: DiagnosticScanWork.Analyze,
        budget: ResourceBudget,
        start: Long,
    ): DiagnosticScanResult {
        if (TimeUnit.NANOSECONDS.toMillis(clock() - start) >= budget.elapsedTimeLimit.value) return increaseBudget()
        val scope =
            when (
                val admitted =
                    DiagnosticScope.fromCanonicalPaths(
                        checkpoint.query.lease,
                        listOf(Path.of(work.files.first().value)),
                    )
            ) {
                is Refinement.Refined -> admitted.value
                is Refinement.Rejected -> return scopeRejected()
            }
        val result = diagnostics.check(DiagnosticCheckRequest(scope))
        if (TimeUnit.NANOSECONDS.toMillis(clock() - start) >= budget.elapsedTimeLimit.value) return increaseBudget()
        val facts =
            when (result) {
                is DiagnosticCheckResult.Complete -> result.batch.facts
                is DiagnosticCheckResult.Qualified -> result.batch.facts
                is DiagnosticCheckResult.Rejected ->
                    return DiagnosticScanResult.Rejected(DiagnosticScanRejection.Compiler(result.reason))
            }
        val count =
            when (val admitted = checkpoint.knownDiagnosticCount.adding(facts)) {
                is Refinement.Refined -> admitted.value
                is Refinement.Rejected -> return scopeRejected()
            }
        val next = if (work.files.size == 1) work.next else work.copy(files = work.files.drop(1))
        return when (result) {
            is DiagnosticCheckResult.Rejected ->
                DiagnosticScanResult.Rejected(DiagnosticScanRejection.Compiler(result.reason))
            is DiagnosticCheckResult.Complete ->
                checkpoint
                    .copy(analyzed = checkpoint.analyzed + result.coverage.analyzedFiles, knownDiagnosticCount = count)
                    .publish(result.batch.facts, next, budget.resultLimit.value)
            is DiagnosticCheckResult.Qualified ->
                checkpoint
                    .copy(
                        analyzed = checkpoint.analyzed + result.coverage.analyzedFiles,
                        limitations = checkpoint.limitations + result.coverage.limitations,
                        knownDiagnosticCount = count,
                    )
                    .publish(result.batch.facts, next, budget.resultLimit.value)
        }
    }
}

/** Request-local accounting; detached checkpoints never retain or replenish a grant. */
private class DiagnosticScanAllowance(val budget: ResourceBudget) {
    var remainingWork = budget.workUnitLimit.value
    val facts = mutableListOf<DiagnosticFact>()

    fun remaining(elapsedMillis: Long): Refinement<ResourceBudget, DiagnosticScanRejection> {
        val resultLimit =
            when (val limit = ResultLimit.parse(budget.resultLimit.value - facts.size)) {
                is Refinement.Refined -> limit.value
                is Refinement.Rejected ->
                    return Refinement.Rejected(
                        DiagnosticScanRejection.Scope(DiagnosticScopeResolutionFailure.INVALID_SCOPE)
                    )
            }
        val workLimit =
            when (val limit = WorkUnitLimit.parse(remainingWork)) {
                is Refinement.Refined -> limit.value
                is Refinement.Rejected ->
                    return Refinement.Rejected(
                        DiagnosticScanRejection.Scope(DiagnosticScopeResolutionFailure.INVALID_SCOPE)
                    )
            }
        val timeLimit =
            when (val limit = ElapsedTimeLimitMillis.parse(budget.elapsedTimeLimit.value - elapsedMillis)) {
                is Refinement.Refined -> limit.value
                is Refinement.Rejected -> return Refinement.Rejected(DiagnosticScanRejection.ExecutionTimeGrantTooSmall)
            }
        return Refinement.Refined(ResourceBudget(resultLimit, workLimit, timeLimit))
    }
}

private sealed interface DiagnosticScanWork {
    data class Enumerate(val request: DiagnosticEnumerationRequest) : DiagnosticScanWork

    data class Analyze(val files: List<DiagnosticSourceFile>, val next: DiagnosticScanWork) : DiagnosticScanWork

    data class Drain(val facts: List<DiagnosticFact>, val next: DiagnosticScanWork) : DiagnosticScanWork

    data object Finished : DiagnosticScanWork
}

private data class DetachedDiagnosticScan(
    override val query: DiagnosticScopeQuery,
    val work: DiagnosticScanWork,
    val analyzed: List<DiagnosticSourceFile> = emptyList(),
    val limitations: Set<DiagnosticLimitation> = emptySet(),
    val knownDiagnosticCount: DiagnosticFactCount = DiagnosticFactCount.observed(emptyList()),
) : DiagnosticScanCheckpoint {
    override val retainedBytes: Long
        get() =
            RETAINED_CHECKPOINT_OVERHEAD +
                query.path.toString().length * RETAINED_CHARACTER_BYTES +
                analyzed.sumOf { RETAINED_NODE_OVERHEAD + it.value.length * RETAINED_CHARACTER_BYTES } +
                limitations.sumOf { RETAINED_LIMITATION_OVERHEAD + it.file.value.length * RETAINED_CHARACTER_BYTES } +
                work.retainedBytes()

    fun admitsEnumeration(files: List<DiagnosticSourceFile>): Boolean {
        if (files != files.distinct().sortedBy { it.value }) return false
        if (files.any { !Path.of(it.value).startsWith(query.path) }) return false
        val previous = (analyzed + limitations.map { it.file }).maxOfOrNull { it.value } ?: return true
        return files.all { it.value > previous }
    }

    fun publish(facts: List<DiagnosticFact>, next: DiagnosticScanWork, limit: Int): DiagnosticScanResult {
        val suffix = facts.drop(limit)
        val checkpoint = copy(work = if (suffix.isEmpty()) next else DiagnosticScanWork.Drain(suffix, next))
        return checkpoint.result(
            facts.take(limit),
            when {
                suffix.isNotEmpty() -> DiagnosticScanStop.OutputPending
                else -> DiagnosticScanStop.AnalysisPending
            },
        )
    }

    fun result(facts: List<DiagnosticFact>, stop: DiagnosticScanStop): DiagnosticScanResult {
        val inventory =
            when (work.terminalEnumeration()) {
                EnumerationCompletion.PENDING -> DiagnosticScanInventory.Enumerating
                EnumerationCompletion.EXHAUSTED ->
                    DiagnosticScanInventory.Exhausted(
                        (analyzed + limitations.map { it.file } + work.pendingFiles()).sortedBy { it.value }
                    )
            }
        val page =
            DiagnosticScanPage(facts.toList(), analyzed.toList(), limitations.toSet(), inventory, knownDiagnosticCount)
        return when {
            work != DiagnosticScanWork.Finished -> DiagnosticScanResult.Advancing(page, this, stop)
            limitations.isNotEmpty() -> DiagnosticScanResult.Qualified(page)
            else -> DiagnosticScanResult.Complete(page)
        }
    }
}

private enum class EnumerationCompletion {
    PENDING,
    EXHAUSTED,
}

private fun DiagnosticScanWork.terminalEnumeration(): EnumerationCompletion =
    when (this) {
        is DiagnosticScanWork.Enumerate -> EnumerationCompletion.PENDING
        is DiagnosticScanWork.Analyze -> next.terminalEnumeration()
        is DiagnosticScanWork.Drain -> next.terminalEnumeration()
        DiagnosticScanWork.Finished -> EnumerationCompletion.EXHAUSTED
    }

private fun DiagnosticScanWork.pendingFiles(): List<DiagnosticSourceFile> =
    when (this) {
        is DiagnosticScanWork.Analyze -> files + next.pendingFiles()
        is DiagnosticScanWork.Drain -> next.pendingFiles()
        else -> emptyList()
    }

private fun DiagnosticScanWork.retainedBytes(): Long =
    when (this) {
        is DiagnosticScanWork.Enumerate ->
            when (request) {
                is DiagnosticEnumerationRequest.First -> RETAINED_CHECKPOINT_OVERHEAD
                is DiagnosticEnumerationRequest.Resume -> request.cursor.retainedBytes
            }
        is DiagnosticScanWork.Analyze ->
            RETAINED_NODE_OVERHEAD +
                files.sumOf { RETAINED_NODE_OVERHEAD + it.value.length * RETAINED_CHARACTER_BYTES } +
                next.retainedBytes()
        is DiagnosticScanWork.Drain ->
            RETAINED_NODE_OVERHEAD +
                facts.sumOf {
                    RETAINED_FACT_OVERHEAD +
                        (it.message.value.length.toLong() + it.code.value.length + it.location.file.value.length) *
                            RETAINED_CHARACTER_BYTES
                } +
                next.retainedBytes()
        DiagnosticScanWork.Finished -> 0L
    }

private fun stale() = DiagnosticScanResult.Rejected(DiagnosticScanRejection.StaleBasis)

private fun scopeRejected() =
    DiagnosticScanResult.Rejected(DiagnosticScanRejection.Scope(DiagnosticScopeResolutionFailure.INVALID_SCOPE))

private fun increaseBudget() = DiagnosticScanResult.Rejected(DiagnosticScanRejection.IndivisibleUnitExceedsBudget)

private const val RETAINED_FACT_OVERHEAD = 1024L
private const val RETAINED_CHECKPOINT_OVERHEAD = 256L
private const val RETAINED_LIMITATION_OVERHEAD = 192L
private const val RETAINED_NODE_OVERHEAD = 128L
private const val RETAINED_CHARACTER_BYTES = 4L

private fun timeGrantRejected(): DiagnosticScanResult =
    DiagnosticScanResult.Rejected(DiagnosticScanRejection.ExecutionTimeGrantTooSmall)
