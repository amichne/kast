package io.github.amichne.kast.change.verify

import io.github.amichne.kast.change.contract.matches
import io.github.amichne.kast.diagnostic.contract.DiagnosticCheckResult
import io.github.amichne.kast.diagnostic.contract.DiagnosticSeverity
import io.github.amichne.kast.relation.contract.RelationReadResult

/** Shared pure obligations for both published and original-owner live semantic verification. */
internal fun addDeclarationSemanticEvidenceFailures(
    expected: ExpectedAddDeclarationSemanticEvidence,
    relations: List<RelationReadResult>,
    diagnostics: List<DiagnosticCheckResult>,
): Set<AddDeclarationProofFailure> = relationFailures(expected, relations) + diagnosticFailures(expected, diagnostics)

private fun relationFailures(
    expected: ExpectedAddDeclarationSemanticEvidence,
    relations: List<RelationReadResult>,
): Set<AddDeclarationProofFailure> {
    val complete = relations.mapNotNull { it as? RelationReadResult.Complete }
    return when {
        relations.isEmpty() -> setOf(AddDeclarationProofFailure.RELATION_EVIDENCE_REQUIRED)
        complete.size != relations.size -> setOf(AddDeclarationProofFailure.RELATION_EVIDENCE_INCOMPLETE)
        else -> completeRelationFailures(expected, complete)
    }
}

private fun completeRelationFailures(
    expected: ExpectedAddDeclarationSemanticEvidence,
    relations: List<RelationReadResult.Complete>,
): Set<AddDeclarationProofFailure> {
    val failures = linkedSetOf<AddDeclarationProofFailure>()
    if (relations.any { it.batch.request.subject.lease != expected.authority }) {
        failures += AddDeclarationProofFailure.RELATION_LEASE_MISMATCH
    }
    if (relations.any { !expected.matchesTarget(it.batch.request.subject) }) {
        failures += AddDeclarationProofFailure.RELATION_TARGET_MISMATCH
    }
    val planned = expected.planned.relations
    if (
        planned.size != relations.size ||
            planned.any { prior -> relations.count { observed -> expected.planned.matches(prior, observed) } != 1 } ||
            relations.any { observed -> planned.count { prior -> expected.planned.matches(prior, observed) } != 1 }
    ) {
        failures += AddDeclarationProofFailure.RELATION_DELTA_REJECTED
    }
    return failures
}

private fun diagnosticFailures(
    expected: ExpectedAddDeclarationSemanticEvidence,
    diagnostics: List<DiagnosticCheckResult>,
): Set<AddDeclarationProofFailure> {
    val complete = diagnostics.mapNotNull { it as? DiagnosticCheckResult.Complete }
    return when {
        diagnostics.isEmpty() -> setOf(AddDeclarationProofFailure.DIAGNOSTIC_EVIDENCE_REQUIRED)
        complete.size != diagnostics.size -> setOf(AddDeclarationProofFailure.DIAGNOSTIC_EVIDENCE_INCOMPLETE)
        else -> completeDiagnosticFailures(expected, complete)
    }
}

private fun completeDiagnosticFailures(
    expected: ExpectedAddDeclarationSemanticEvidence,
    diagnostics: List<DiagnosticCheckResult.Complete>,
): Set<AddDeclarationProofFailure> {
    val failures = linkedSetOf<AddDeclarationProofFailure>()
    if (diagnostics.any { it.batch.scope.lease != expected.authority }) {
        failures += AddDeclarationProofFailure.DIAGNOSTIC_LEASE_MISMATCH
    }
    if (
        diagnostics.size != expected.diagnosticScopes.size ||
            diagnostics.withIndex().any { (index, result) ->
                result.batch.scope.files.mapTo(linkedSetOf()) { it.value } != expected.diagnosticScopes[index]
            }
    ) {
        failures += AddDeclarationProofFailure.DIAGNOSTIC_SCOPE_MISMATCH
    }
    if (diagnostics.any { result -> result.batch.facts.any { it.severity == DiagnosticSeverity.ERROR } }) {
        failures += AddDeclarationProofFailure.COMPILER_DIAGNOSTICS_REJECTED
    }
    return failures
}
