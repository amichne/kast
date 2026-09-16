package io.github.amichne.kast.diagnostic.intellij

import io.github.amichne.kast.diagnostic.contract.DiagnosticEnumerationCursor
import io.github.amichne.kast.diagnostic.contract.DiagnosticEnumerationFailure
import io.github.amichne.kast.diagnostic.contract.DiagnosticEnumerationRequest
import io.github.amichne.kast.diagnostic.contract.DiagnosticEnumerationResult
import io.github.amichne.kast.diagnostic.contract.DiagnosticEnumerationStop
import io.github.amichne.kast.diagnostic.contract.DiagnosticEnumerationWork
import io.github.amichne.kast.diagnostic.contract.DiagnosticScopeQuery
import io.github.amichne.kast.diagnostic.contract.DiagnosticScopeResolutionFailure
import io.github.amichne.kast.diagnostic.contract.DiagnosticSourceFile
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.ResourceBudget

private data class DetachedDiagnosticEnumerationCursor(
    override val query: DiagnosticScopeQuery,
    val seen: Set<DiagnosticSourceFile>,
) : DiagnosticEnumerationCursor {
    override val retainedBytes: Long = enumerationBytes(query, seen)
}

/** Callback work and elapsed accounting survive retried read actions; their candidate sets do not. */
internal class DiagnosticEnumerationAllowance(
    private val budget: ResourceBudget,
    private val elapsedMillis: () -> Long,
) {
    var consumedWork = DiagnosticEnumerationWork.NONE
        private set

    fun admit(newFiles: Int): DiagnosticEnumerationAdmission =
        when {
            elapsedMillis() >= budget.elapsedTimeLimit.value ->
                DiagnosticEnumerationAdmission.Stop(DiagnosticEnumerationStop.TIME_LIMIT)
            consumedWork.value >= budget.workUnitLimit.value ->
                DiagnosticEnumerationAdmission.Stop(DiagnosticEnumerationStop.WORK_LIMIT)
            newFiles >= budget.resultLimit.value ->
                DiagnosticEnumerationAdmission.Stop(DiagnosticEnumerationStop.FILE_LIMIT)
            else -> {
                consumedWork = consumedWork.incremented()
                DiagnosticEnumerationAdmission.Continue
            }
        }

    fun publication(): DiagnosticEnumerationAdmission =
        if (elapsedMillis() >= budget.elapsedTimeLimit.value)
            DiagnosticEnumerationAdmission.Stop(DiagnosticEnumerationStop.TIME_LIMIT)
        else DiagnosticEnumerationAdmission.Continue
}

internal sealed interface DiagnosticEnumerationAdmission {
    data object Continue : DiagnosticEnumerationAdmission

    data class Stop(val reason: DiagnosticEnumerationStop) : DiagnosticEnumerationAdmission
}

/**
 * Retains only bounded identities observed so far. Index iteration order is not retained or trusted. A replay prefix is
 * charged, and a grant unable to cross it rejects without an unchanged cursor. The inventory becomes canonically
 * ordered only after native exhaustion, before any compiler work.
 */
internal class BoundedDiagnosticEnumeration
private constructor(
    private val query: DiagnosticScopeQuery,
    previous: Set<DiagnosticSourceFile>,
    private val allowance: DiagnosticEnumerationAllowance,
    private val maximumBytes: Long,
) {
    private val initialCount = previous.size
    private val seen = previous.toMutableSet()
    private var bytes = enumerationBytes(query, previous)
    private var state: Refinement<Unit, DiagnosticEnumerationFailure> = Refinement.Refined(Unit)
    private var admission: DiagnosticEnumerationAdmission = DiagnosticEnumerationAdmission.Continue

    fun accept(observe: () -> Refinement<DiagnosticSourceFile, DiagnosticScopeResolutionFailure>): Boolean {
        if (state is Refinement.Rejected || admission is DiagnosticEnumerationAdmission.Stop) return false
        admission = allowance.admit(seen.size - initialCount)
        if (admission is DiagnosticEnumerationAdmission.Stop) return false
        val file =
            when (val observed = observe()) {
                is Refinement.Refined -> observed.value
                is Refinement.Rejected -> return reject(observed.failure)
            }
        if (file in seen) return true
        val retained = sourceIdentityBytes(file)
        if (bytes + retained > maximumBytes) {
            state = Refinement.Rejected(DiagnosticEnumerationFailure.RetentionCapacity)
            return false
        }
        seen += file
        bytes += retained
        return true
    }

    private fun reject(reason: DiagnosticScopeResolutionFailure): Boolean {
        state = Refinement.Rejected(DiagnosticEnumerationFailure.Scope(reason))
        return false
    }

    fun finish(): DiagnosticEnumerationResult {
        when (val current = state) {
            is Refinement.Rejected -> return DiagnosticEnumerationResult.Rejected(current.failure)
            is Refinement.Refined -> Unit
        }
        if (admission == DiagnosticEnumerationAdmission.Continue) admission = allowance.publication()
        return when (val stopped = admission) {
            DiagnosticEnumerationAdmission.Continue ->
                DiagnosticEnumerationResult.Exhausted(seen.sortedBy { it.value }, allowance.consumedWork)
            is DiagnosticEnumerationAdmission.Stop -> {
                if (seen.size == initialCount)
                    DiagnosticEnumerationResult.Rejected(DiagnosticEnumerationFailure.IncreaseGrant(stopped.reason))
                else
                    DiagnosticEnumerationResult.Advancing(
                        emptyList(),
                        DetachedDiagnosticEnumerationCursor(query, seen.toSet()),
                        stopped.reason,
                    )
            }
        }
    }

    companion object {
        fun create(
            request: DiagnosticEnumerationRequest,
            allowance: DiagnosticEnumerationAllowance,
            maximumBytes: Long,
        ): Refinement<BoundedDiagnosticEnumeration, DiagnosticEnumerationFailure> {
            val previous =
                when (request) {
                    is DiagnosticEnumerationRequest.First -> emptySet()
                    is DiagnosticEnumerationRequest.Resume -> {
                        val cursor =
                            request.cursor as? DetachedDiagnosticEnumerationCursor
                                ?: return Refinement.Rejected(
                                    DiagnosticEnumerationFailure.Scope(DiagnosticScopeResolutionFailure.INVALID_SCOPE)
                                )
                        cursor.seen
                    }
                }
            if (enumerationBytes(request.query, previous) > maximumBytes)
                return Refinement.Rejected(DiagnosticEnumerationFailure.RetentionCapacity)
            return Refinement.Refined(BoundedDiagnosticEnumeration(request.query, previous, allowance, maximumBytes))
        }
    }
}

private fun enumerationBytes(query: DiagnosticScopeQuery, files: Set<DiagnosticSourceFile>): Long =
    RETAINED_CURSOR_OVERHEAD +
        query.path.toString().length * RETAINED_CHARACTER_BYTES +
        files.sumOf(::sourceIdentityBytes)

private fun sourceIdentityBytes(file: DiagnosticSourceFile): Long =
    RETAINED_IDENTITY_OVERHEAD + file.value.length * RETAINED_CHARACTER_BYTES

private const val RETAINED_CURSOR_OVERHEAD = 256L
private const val RETAINED_IDENTITY_OVERHEAD = 128L
private const val RETAINED_CHARACTER_BYTES = 4L
