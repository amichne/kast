package io.github.amichne.kast.change.contract

import io.github.amichne.kast.diagnostic.contract.DiagnosticCheckResult
import io.github.amichne.kast.diagnostic.contract.DiagnosticFact
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.RelationMeaning
import io.github.amichne.kast.relation.contract.RelationReadPosition
import io.github.amichne.kast.relation.contract.RelationReadResult
import io.github.amichne.kast.traversal.contract.TraversalPosition
import io.github.amichne.kast.traversal.contract.TraversalResult

@JvmInline
value class AddDeclarationEvidenceFingerprint internal constructor(
    val value: String,
)

/**
 * Complete, normalized detached evidence for one exact editable target.
 *
 * The retained variants carry their exact coverage proofs. No Boolean completion flag or
 * reconstructed absence can enter a plan.
 */
class CompleteAddDeclarationPlanningEvidence private constructor(
    val relations: List<RelationReadResult.Complete>,
    val traversals: List<TraversalResult.Complete>,
    val diagnostics: List<DiagnosticCheckResult.Complete>,
    val fingerprint: AddDeclarationEvidenceFingerprint,
) {
    companion object {
        /**
         * Proof transition: `AddDeclarationPlanRequest -> Refinement<
         * CompleteAddDeclarationPlanningEvidence, AddDeclarationPlanningFailure>`.
         *
         * Establishes non-empty, complete relation, traversal, and diagnostic evidence for the
         * request's exact target selector, file, root, and generation, normalized independently of
         * input enumeration order. [AddDeclarationPlanningFailure] is the closed expected failure.
         * Raw compiler/platform evidence may enter only through the detached read-operation
         * boundaries; no raw extraction is permitted by planning.
         */
        internal fun admit(
            request: AddDeclarationPlanRequest,
        ): Refinement<CompleteAddDeclarationPlanningEvidence, AddDeclarationPlanningFailure> {
            if (request.evidence.relations.isEmpty()) {
                return Refinement.Rejected(
                    AddDeclarationPlanningFailure.RELATION_EVIDENCE_REQUIRED,
                )
            }
            val relations = request.evidence.relations.map { result ->
                result as? RelationReadResult.Complete ?: return Refinement.Rejected(
                    AddDeclarationPlanningFailure.RELATION_EVIDENCE_INCOMPLETE,
                )
            }
            if (request.evidence.traversals.isEmpty()) {
                return Refinement.Rejected(
                    AddDeclarationPlanningFailure.TRAVERSAL_EVIDENCE_REQUIRED,
                )
            }
            val traversals = request.evidence.traversals.map { result ->
                result as? TraversalResult.Complete ?: return Refinement.Rejected(
                    AddDeclarationPlanningFailure.TRAVERSAL_EVIDENCE_INCOMPLETE,
                )
            }
            if (request.evidence.diagnostics.isEmpty()) {
                return Refinement.Rejected(
                    AddDeclarationPlanningFailure.DIAGNOSTIC_EVIDENCE_REQUIRED,
                )
            }
            val diagnostics = request.evidence.diagnostics.map { result ->
                result as? DiagnosticCheckResult.Complete ?: return Refinement.Rejected(
                    AddDeclarationPlanningFailure.DIAGNOSTIC_EVIDENCE_INCOMPLETE,
                )
            }
            val target = request.target
            if (
                relations.any { it.batch.request.selector.lease != target.lease } ||
                traversals.any { it.page.plan.start.lease != target.lease } ||
                diagnostics.any { it.batch.scope.lease != target.lease }
            ) {
                return Refinement.Rejected(
                    AddDeclarationPlanningFailure.EVIDENCE_LEASE_MISMATCH,
                )
            }
            if (
                relations.any {
                    it.batch.request.selector.fingerprint != target.selector.fingerprint
                } ||
                traversals.any {
                    it.page.plan.start.fingerprint != target.selector.fingerprint
                } ||
                diagnostics.any { result ->
                    result.batch.scope.files.none { file -> file.value == target.file.path.value }
                }
            ) {
                return Refinement.Rejected(
                    AddDeclarationPlanningFailure.EVIDENCE_TARGET_MISMATCH,
                )
            }
            val normalizedRelations = relations.sortedBy(::relationProjection)
            val normalizedTraversals = traversals.sortedBy(::traversalProjection)
            val normalizedDiagnostics = diagnostics.sortedBy(::diagnosticProjection)
            val canonical = buildString {
                normalizedRelations.forEach { appendPlanningField(relationProjection(it)) }
                normalizedTraversals.forEach { appendPlanningField(traversalProjection(it)) }
                normalizedDiagnostics.forEach { appendPlanningField(diagnosticProjection(it)) }
            }
            return Refinement.Refined(
                CompleteAddDeclarationPlanningEvidence(
                    normalizedRelations,
                    normalizedTraversals,
                    normalizedDiagnostics,
                    AddDeclarationEvidenceFingerprint(sha256Hex(canonical.toByteArray())),
                ),
            )
        }
    }
}

private fun relationProjection(result: RelationReadResult.Complete): String = buildString {
    val request = result.batch.request
    appendPlanningField(request.selector.fingerprint.value)
    appendPlanningField(request.meaning.canonicalKey())
    appendPlanningField(
        when (val position = request.position) {
            RelationReadPosition.Start -> "START"
            is RelationReadPosition.Resume ->
                "RESUME:" + position.continuation.fingerprint.value
        },
    )
    appendPlanningField(request.budget.resources.resultLimit.value.toString())
    appendPlanningField(request.budget.resources.workUnitLimit.value.toString())
    appendPlanningField(request.budget.resources.elapsedTimeLimit.value.toString())
    appendPlanningField(request.budget.returnedBytes.value.toString())
    appendPlanningField(result.batch.encodedBytes.value.toString())
    appendPlanningField(result.batch.examinedWorkUnits.value.toString())
    appendPlanningField(result.coverage.exactCount.value.toString())
    result.batch.facts.forEach { fact -> appendPlanningField(fact.canonicalProjection()) }
}

private fun traversalProjection(result: TraversalResult.Complete): String = buildString {
    val plan = result.page.plan
    appendPlanningField(plan.identity.value)
    appendPlanningField(
        when (val position = plan.position) {
            TraversalPosition.Start -> "START"
            is TraversalPosition.Resume ->
                "RESUME:" + position.continuation.fingerprint.value
        },
    )
    appendPlanningField(plan.budget.records.value.toString())
    appendPlanningField(plan.budget.returnedBytes.value.toString())
    appendPlanningField(plan.budget.workUnits.value.toString())
    appendPlanningField(plan.budget.elapsedTime.value.toString())
    appendPlanningField(plan.budget.depth.value.toString())
    appendPlanningField(plan.budget.frontier.value.toString())
    appendPlanningField(result.page.encodedBytes.value.toString())
    appendPlanningField(result.page.examinedWorkUnits.value.toString())
    appendPlanningField(result.page.elapsedMillis.value.toString())
    appendPlanningField(result.page.expandedFrontier.value.toString())
    appendPlanningField(result.coverage.exactRecordCount.value.toString())
    result.page.records.forEach { record ->
        appendPlanningField(record.canonicalProjection())
    }
}

private fun diagnosticProjection(result: DiagnosticCheckResult.Complete): String = buildString {
    val scope = result.batch.scope
    appendPlanningField(scope.lease.workspaceRoot.value)
    appendPlanningField(scope.lease.generation.value.toString())
    scope.files.forEach { file -> appendPlanningField(file.value) }
    result.coverage.analyzedFiles.forEach { file -> appendPlanningField(file.value) }
    result.batch.facts
        .sortedBy(::diagnosticFactProjection)
        .forEach { fact -> appendPlanningField(diagnosticFactProjection(fact)) }
}

private fun diagnosticFactProjection(fact: DiagnosticFact): String = buildString {
    appendPlanningField(fact.location.file.value)
    appendPlanningField(fact.location.range.start.value.toString())
    appendPlanningField(fact.location.range.endExclusive.value.toString())
    appendPlanningField(fact.severity.name)
    appendPlanningField(fact.code.value)
    appendPlanningField(fact.message.value)
}

private fun RelationMeaning.canonicalKey(): String = when (this) {
    RelationMeaning.References -> "REFERENCES"
    RelationMeaning.Callers -> "CALLERS"
    RelationMeaning.Callees -> "CALLEES"
    RelationMeaning.Implementations -> "IMPLEMENTATIONS"
    RelationMeaning.Inheritors -> "INHERITORS"
    RelationMeaning.Overrides -> "OVERRIDES"
    RelationMeaning.TypeUses -> "TYPE_USES"
}

internal fun StringBuilder.appendPlanningField(value: String) {
    append(value.length)
    append(':')
    append(value)
}
