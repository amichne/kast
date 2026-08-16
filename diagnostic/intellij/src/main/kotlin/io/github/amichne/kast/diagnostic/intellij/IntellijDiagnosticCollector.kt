package io.github.amichne.kast.diagnostic.intellij

import io.github.amichne.kast.diagnostic.contract.DiagnosticBatch
import io.github.amichne.kast.diagnostic.contract.DiagnosticCompilation
import io.github.amichne.kast.diagnostic.contract.DiagnosticCompilerRejection
import io.github.amichne.kast.diagnostic.contract.DiagnosticFact
import io.github.amichne.kast.diagnostic.contract.DiagnosticLimitation
import io.github.amichne.kast.diagnostic.contract.DiagnosticLimitationReason
import io.github.amichne.kast.diagnostic.contract.DiagnosticScope
import io.github.amichne.kast.diagnostic.contract.DiagnosticSourceFile
import io.github.amichne.kast.kernel.Refinement

internal enum class IntellijDiagnosticCollectionAdmission {
    ACCEPTED,
    REJECTED,
}

private enum class IntellijDiagnosticCollectorState {
    COLLECTING,
    FINISHED,
}

/** Request-local owner of exact diagnostic facts and per-file coverage. */
internal class IntellijDiagnosticCollector(
    private val scope: DiagnosticScope,
) {
    private val facts = mutableListOf<DiagnosticFact>()
    private val analyzedFiles = linkedSetOf<DiagnosticSourceFile>()
    private val limitations = linkedSetOf<DiagnosticLimitation>()
    private var state = IntellijDiagnosticCollectorState.COLLECTING

    /**
     * Proof transition: `(IntellijDiagnosticCollector, DiagnosticFact) ->
     * IntellijDiagnosticCollectionAdmission`.
     *
     * Accepted proves that the detached fact belongs to the identical exact scope while the
     * collector is open. Rejected is the closed collector-contract failure. Raw K2 values must be
     * refined into [DiagnosticFact] before this boundary.
     */
    fun accept(fact: DiagnosticFact): IntellijDiagnosticCollectionAdmission = when {
        state != IntellijDiagnosticCollectorState.COLLECTING ->
            IntellijDiagnosticCollectionAdmission.REJECTED
        fact.scope !== scope -> IntellijDiagnosticCollectionAdmission.REJECTED
        fact.location.file !in scope.files -> IntellijDiagnosticCollectionAdmission.REJECTED
        else -> {
            facts += fact
            IntellijDiagnosticCollectionAdmission.ACCEPTED
        }
    }

    /**
     * Proof transition: `(IntellijDiagnosticCollector, DiagnosticSourceFile) ->
     * IntellijDiagnosticCollectionAdmission`.
     *
     * Accepted proves that the exact scope file completed analysis without a limitation and was
     * recorded once. Rejected is the closed collector-contract failure. Live compiler completion
     * state remains inside the request-local adapter.
     */
    fun recordAnalyzed(file: DiagnosticSourceFile): IntellijDiagnosticCollectionAdmission = when {
        state != IntellijDiagnosticCollectorState.COLLECTING ->
            IntellijDiagnosticCollectionAdmission.REJECTED
        file !in scope.files -> IntellijDiagnosticCollectionAdmission.REJECTED
        file in analyzedFiles -> IntellijDiagnosticCollectionAdmission.REJECTED
        limitations.any { limitation -> limitation.file == file } ->
            IntellijDiagnosticCollectionAdmission.REJECTED
        else -> {
            analyzedFiles += file
            IntellijDiagnosticCollectionAdmission.ACCEPTED
        }
    }

    /**
     * Proof transition: `(IntellijDiagnosticCollector, DiagnosticSourceFile,
     * DiagnosticLimitationReason) -> IntellijDiagnosticCollectionAdmission`.
     *
     * Accepted proves that one exact scope file remains explicitly incomplete and cannot be
     * counted as analyzed. Rejected is the closed collector-contract failure. Raw provider state
     * is retained only as the finite [DiagnosticLimitationReason].
     */
    fun recordLimitation(
        file: DiagnosticSourceFile,
        reason: DiagnosticLimitationReason,
    ): IntellijDiagnosticCollectionAdmission = when {
        state != IntellijDiagnosticCollectorState.COLLECTING ->
            IntellijDiagnosticCollectionAdmission.REJECTED
        file !in scope.files -> IntellijDiagnosticCollectionAdmission.REJECTED
        file in analyzedFiles -> IntellijDiagnosticCollectionAdmission.REJECTED
        else -> {
            limitations += DiagnosticLimitation(file, reason)
            IntellijDiagnosticCollectionAdmission.ACCEPTED
        }
    }

    /**
     * Proof transition: `IntellijDiagnosticCollector -> DiagnosticCompilation`.
     *
     * Complete proves every exact file analyzed without limitation. Qualified proves total file
     * accounting with at least one explicit limitation. Unaccounted files, invalid facts, or
     * repeated finalization become [DiagnosticCompilerRejection.COMPILER_CONTRACT_VIOLATION].
     * Live compiler values never enter the returned detached output.
     */
    fun finish(): DiagnosticCompilation {
        if (state != IntellijDiagnosticCollectorState.COLLECTING) {
            return contractRejected()
        }
        state = IntellijDiagnosticCollectorState.FINISHED
        val accountedFiles = analyzedFiles + limitations.map(DiagnosticLimitation::file)
        if (accountedFiles != scope.files.toSet()) {
            return contractRejected()
        }
        val batch = when (val admission = DiagnosticBatch.create(scope, facts)) {
            is Refinement.Refined -> admission.value
            is Refinement.Rejected -> return contractRejected()
        }
        if (limitations.isEmpty()) {
            return DiagnosticCompilation.complete(batch)
        }
        return when (
            val qualified = DiagnosticCompilation.qualified(batch, analyzedFiles, limitations)
        ) {
            is Refinement.Refined -> qualified.value
            is Refinement.Rejected -> contractRejected()
        }
    }
}

private fun contractRejected(): DiagnosticCompilation.Rejected = DiagnosticCompilation.Rejected(
    DiagnosticCompilerRejection.COMPILER_CONTRACT_VIOLATION,
)
