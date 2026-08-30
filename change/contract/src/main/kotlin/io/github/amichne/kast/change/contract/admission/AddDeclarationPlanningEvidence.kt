package io.github.amichne.kast.change.contract

import io.github.amichne.kast.diagnostic.contract.DiagnosticCheckResult
import io.github.amichne.kast.diagnostic.contract.DiagnosticFact
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.RelationEndpoint
import io.github.amichne.kast.relation.contract.RelationFact
import io.github.amichne.kast.relation.contract.RelationMeaning
import io.github.amichne.kast.relation.contract.RelationReadPosition
import io.github.amichne.kast.relation.contract.RelationReadResult
import io.github.amichne.kast.symbol.contract.ExactDeclarationQualifiedIdentity
import io.github.amichne.kast.traversal.contract.TraversalPosition
import io.github.amichne.kast.traversal.contract.TraversalResult
import java.nio.charset.StandardCharsets
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

@JvmInline
value class ChangePlanningEvidenceFingerprint internal constructor(
    val value: String,
)

typealias AddDeclarationEvidenceFingerprint = ChangePlanningEvidenceFingerprint

/**
 * Complete, normalized detached evidence for one exact editable target.
 *
 * The retained variants carry their exact coverage proofs. No Boolean completion flag or
 * reconstructed absence can enter a plan.
 */
class CompleteChangePlanningEvidence private constructor(
    val relations: List<RelationReadResult.Complete>,
    val traversals: List<TraversalResult.Complete>,
    val diagnostics: List<DiagnosticCheckResult.Complete>,
    val fingerprint: ChangePlanningEvidenceFingerprint,
) {
    companion object {
        /**
         * Proof transition: `(EditableMutationTarget, AddDeclarationPlanningEvidenceInput) ->
         * Refinement<CompleteChangePlanningEvidence, ChangePlanningFailure>`.
         *
         * Establishes non-empty, complete relation, traversal, and diagnostic evidence for the
         * exact target selector, file, root, and generation, normalized independently of input
         * enumeration order. [ChangePlanningFailure] is the closed expected failure.
         * Raw compiler/platform evidence may enter only through the detached read-operation
         * boundaries; no raw extraction is permitted by planning.
         */
        internal fun admit(
            target: EditableMutationTarget,
            evidence: AddDeclarationPlanningEvidenceInput,
        ): Refinement<CompleteChangePlanningEvidence, ChangePlanningFailure> {
            if (evidence.relations.isEmpty()) {
                return Refinement.Rejected(
                    ChangePlanningFailure.RELATION_EVIDENCE_REQUIRED,
                )
            }
            val relations = evidence.relations.map { result ->
                result as? RelationReadResult.Complete ?: return Refinement.Rejected(
                    ChangePlanningFailure.RELATION_EVIDENCE_INCOMPLETE,
                )
            }
            if (evidence.traversals.isEmpty()) {
                return Refinement.Rejected(
                    ChangePlanningFailure.TRAVERSAL_EVIDENCE_REQUIRED,
                )
            }
            val traversals = evidence.traversals.map { result ->
                result as? TraversalResult.Complete ?: return Refinement.Rejected(
                    ChangePlanningFailure.TRAVERSAL_EVIDENCE_INCOMPLETE,
                )
            }
            if (evidence.diagnostics.isEmpty()) {
                return Refinement.Rejected(
                    ChangePlanningFailure.DIAGNOSTIC_EVIDENCE_REQUIRED,
                )
            }
            val diagnostics = evidence.diagnostics.map { result ->
                result as? DiagnosticCheckResult.Complete ?: return Refinement.Rejected(
                    ChangePlanningFailure.DIAGNOSTIC_EVIDENCE_INCOMPLETE,
                )
            }
            if (
                relations.any { it.batch.request.subject.lease != target.lease } ||
                traversals.any { it.page.plan.start.lease != target.lease } ||
                diagnostics.any { it.batch.scope.lease != target.lease }
            ) {
                return Refinement.Rejected(
                    ChangePlanningFailure.EVIDENCE_LEASE_MISMATCH,
                )
            }
            if (
                relations.any {
                    it.batch.request.subject.fingerprint.value != target.selector.fingerprint.value
                } ||
                traversals.any {
                    it.page.plan.start.fingerprint != target.selector.fingerprint
                } ||
                diagnostics.any { result ->
                    result.batch.scope.files.none { file -> file.value == target.file.path.value }
                }
            ) {
                return Refinement.Rejected(
                    ChangePlanningFailure.EVIDENCE_TARGET_MISMATCH,
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
                CompleteChangePlanningEvidence(
                    normalizedRelations,
                    normalizedTraversals,
                    normalizedDiagnostics,
                    ChangePlanningEvidenceFingerprint(
                        sha256Hex(canonical.toByteArray(StandardCharsets.UTF_8)),
                    ),
                ),
            )
        }

        internal fun admit(
            request: AddDeclarationPlanRequest,
        ): Refinement<CompleteChangePlanningEvidence, ChangePlanningFailure> =
            admit(request.target, request.evidence)
    }
}

typealias CompleteAddDeclarationPlanningEvidence = CompleteChangePlanningEvidence

@Serializable
enum class AddDeclarationRelationMeaning {
    REFERENCES,
    CALLERS,
    CALLEES,
    IMPLEMENTATIONS,
    INHERITORS,
    OVERRIDES,
    TYPE_USES,
}

@Serializable
@JvmInline
value class ChangePlanningEvidenceProjection private constructor(
    val value: String,
) {
    companion object {
        internal fun fromProven(value: String): ChangePlanningEvidenceProjection =
            ChangePlanningEvidenceProjection(value)
    }
}

@Serializable
@JvmInline
value class StableRelationEvidenceDigest private constructor(
    val value: String,
) {
    companion object {
        internal fun fromProven(value: String): StableRelationEvidenceDigest =
            StableRelationEvidenceDigest(value)
    }
}

@Serializable
data class DurableAddDeclarationRelationEvidence internal constructor(
    val meaning: AddDeclarationRelationMeaning,
    val projection: ChangePlanningEvidenceProjection,
    val stableDigest: StableRelationEvidenceDigest,
)

/** Canonical, restart-safe projection of every proof admitted by AddDeclaration planning. */
@Serializable
data class DurableAddDeclarationPlanningEvidence internal constructor(
    val relations: List<DurableAddDeclarationRelationEvidence>,
    val traversals: List<ChangePlanningEvidenceProjection>,
    val diagnostics: List<ChangePlanningEvidenceProjection>,
    val fingerprint: ChangePlanningEvidenceFingerprintDocument,
    @Transient
    internal val relationDigestSemantics: StableRelationEvidenceSemantics =
        StableRelationEvidenceSemantics.SEMANTIC_V2,
) {
    companion object {
        internal fun from(evidence: CompleteChangePlanningEvidence):
            DurableAddDeclarationPlanningEvidence = DurableAddDeclarationPlanningEvidence(
            relations = evidence.relations.map { result ->
                DurableAddDeclarationRelationEvidence(
                    result.batch.request.meaning.durable(),
                    ChangePlanningEvidenceProjection.fromProven(relationProjection(result)),
                    result.stableDigest(),
                )
            },
            traversals = evidence.traversals.map { result ->
                ChangePlanningEvidenceProjection.fromProven(traversalProjection(result))
            },
            diagnostics = evidence.diagnostics.map { result ->
                ChangePlanningEvidenceProjection.fromProven(diagnosticProjection(result))
            },
            fingerprint = ChangePlanningEvidenceFingerprintDocument(evidence.fingerprint.value),
        )

        internal fun restore(
            relations: List<DurableAddDeclarationRelationEvidence>,
            traversals: List<ChangePlanningEvidenceProjection>,
            diagnostics: List<ChangePlanningEvidenceProjection>,
            fingerprint: ChangePlanningEvidenceFingerprintDocument,
            relationDigestSemantics: StableRelationEvidenceSemantics =
                StableRelationEvidenceSemantics.SEMANTIC_V2,
        ): Refinement<DurableAddDeclarationPlanningEvidence, DurablePlanningEvidenceFailure> {
            if (relations.isEmpty() || traversals.isEmpty() || diagnostics.isEmpty()) {
                return Refinement.Rejected(DurablePlanningEvidenceFailure.INCOMPLETE)
            }
            val relationProjections = relations.map { it.projection.value }
            val traversalProjections = traversals.map { it.value }
            val diagnosticProjections = diagnostics.map { it.value }
            if (
                relationProjections.any(String::isBlank) ||
                traversalProjections.any(String::isBlank) ||
                diagnosticProjections.any(String::isBlank) ||
                relations.any { !SHA_256.matches(it.stableDigest.value) } ||
                !SHA_256.matches(fingerprint.value)
            ) {
                return Refinement.Rejected(DurablePlanningEvidenceFailure.MALFORMED)
            }
            if (
                relationProjections != relationProjections.sorted() ||
                traversalProjections != traversalProjections.sorted() ||
                diagnosticProjections != diagnosticProjections.sorted()
            ) {
                return Refinement.Rejected(DurablePlanningEvidenceFailure.NOT_CANONICAL)
            }
            val canonical = buildString {
                relationProjections.forEach { appendPlanningField(it) }
                traversalProjections.forEach { appendPlanningField(it) }
                diagnosticProjections.forEach { appendPlanningField(it) }
            }
            if (sha256Hex(canonical.toByteArray(StandardCharsets.UTF_8)) != fingerprint.value) {
                return Refinement.Rejected(DurablePlanningEvidenceFailure.FINGERPRINT_MISMATCH)
            }
            return Refinement.Refined(
                DurableAddDeclarationPlanningEvidence(
                    relations,
                    traversals,
                    diagnostics,
                    fingerprint,
                    relationDigestSemantics,
                ),
            )
        }
    }
}

@Serializable
@JvmInline
value class ChangePlanningEvidenceFingerprintDocument internal constructor(val value: String)

enum class DurablePlanningEvidenceFailure {
    INCOMPLETE,
    MALFORMED,
    NOT_CANONICAL,
    FINGERPRINT_MISMATCH,
}

internal enum class StableRelationEvidenceSemantics {
    GENERATION_BOUND_V1,
    SEMANTIC_V2,
}

fun DurableAddDeclarationPlanningEvidence.matches(
    expected: DurableAddDeclarationRelationEvidence,
    result: RelationReadResult.Complete,
): Boolean = expected.meaning == result.batch.request.meaning.durable() &&
    expected.stableDigest == when (relationDigestSemantics) {
        StableRelationEvidenceSemantics.GENERATION_BOUND_V1 -> result.generationBoundDigest()
        StableRelationEvidenceSemantics.SEMANTIC_V2 -> result.stableDigest()
    }

private fun RelationReadResult.Complete.generationBoundDigest(): StableRelationEvidenceDigest {
    val canonical = buildString {
        appendPlanningField(batch.request.meaning.canonicalKey())
        batch.facts.map(RelationFact::canonicalProjection)
            .sorted()
            .forEach(::appendPlanningField)
    }
    return StableRelationEvidenceDigest.fromProven(
        sha256Hex(canonical.toByteArray(StandardCharsets.UTF_8)),
    )
}

private fun RelationReadResult.Complete.stableDigest(): StableRelationEvidenceDigest {
    val canonical = buildString {
        appendPlanningField(batch.request.meaning.canonicalKey())
        batch.facts.map(RelationFact::stableSemanticProjection)
            .sorted()
            .forEach(::appendPlanningField)
    }
    return StableRelationEvidenceDigest.fromProven(
        sha256Hex(canonical.toByteArray(StandardCharsets.UTF_8)),
    )
}

/** Generation-independent semantic edge identity used only for G0/G1 equivalence. */
private fun RelationFact.stableSemanticProjection(): String = buildString {
    appendPlanningField(meaning.canonicalKey())
    appendPlanningField(source.stableSemanticProjection())
    appendPlanningField(target.stableSemanticProjection())
    appendPlanningField(occurrence.file.stableValue)
    appendPlanningField(provenance.name)
    appendPlanningField(coverage.name)
}

private fun RelationEndpoint.stableSemanticProjection(): String = buildString {
    appendPlanningField(file.stableValue)
    appendPlanningField(name.value)
    when (val identity = qualifiedIdentity) {
        is ExactDeclarationQualifiedIdentity.Available -> {
            appendPlanningField("AVAILABLE")
            appendPlanningField(identity.value)
        }
        ExactDeclarationQualifiedIdentity.Unavailable -> appendPlanningField("UNAVAILABLE")
    }
    appendPlanningField(kind.name)
    appendPlanningField(compilerIdentity.value)
}

private fun RelationMeaning.durable(): AddDeclarationRelationMeaning = when (this) {
    RelationMeaning.References -> AddDeclarationRelationMeaning.REFERENCES
    RelationMeaning.Callers -> AddDeclarationRelationMeaning.CALLERS
    RelationMeaning.Callees -> AddDeclarationRelationMeaning.CALLEES
    RelationMeaning.Implementations -> AddDeclarationRelationMeaning.IMPLEMENTATIONS
    RelationMeaning.Inheritors -> AddDeclarationRelationMeaning.INHERITORS
    RelationMeaning.Overrides -> AddDeclarationRelationMeaning.OVERRIDES
    RelationMeaning.TypeUses -> AddDeclarationRelationMeaning.TYPE_USES
}

fun AddDeclarationRelationMeaning.domain(): RelationMeaning = when (this) {
    AddDeclarationRelationMeaning.REFERENCES -> RelationMeaning.References
    AddDeclarationRelationMeaning.CALLERS -> RelationMeaning.Callers
    AddDeclarationRelationMeaning.CALLEES -> RelationMeaning.Callees
    AddDeclarationRelationMeaning.IMPLEMENTATIONS -> RelationMeaning.Implementations
    AddDeclarationRelationMeaning.INHERITORS -> RelationMeaning.Inheritors
    AddDeclarationRelationMeaning.OVERRIDES -> RelationMeaning.Overrides
    AddDeclarationRelationMeaning.TYPE_USES -> RelationMeaning.TypeUses
}

private val SHA_256 = Regex("[0-9a-f]{64}")

private fun relationProjection(result: RelationReadResult.Complete): String = buildString {
    val request = result.batch.request
    appendPlanningField(request.subject.fingerprint.value)
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
