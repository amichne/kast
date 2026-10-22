package io.github.amichne.kast.change.contract

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.BoundedRelationBudget
import io.github.amichne.kast.relation.contract.RelationBudget
import io.github.amichne.kast.relation.contract.RelationMeaning
import io.github.amichne.kast.relation.contract.RelationReadPosition
import io.github.amichne.kast.relation.contract.RelationSearchBoundary
import io.github.amichne.kast.symbol.contract.CanonicalWorkspaceFilePath
import io.github.amichne.kast.traversal.contract.TraversalBudget
import io.github.amichne.kast.traversal.contract.TraversalPosition
import java.nio.file.Path

data class LivePlannedRelationRead
internal constructor(
    val meaning: RelationMeaning,
    val boundedBudget: BoundedRelationBudget,
    val boundary: RelationSearchBoundary = RelationSearchBoundary.RETAINED_SUBJECT,
) {
    val budget: RelationBudget
        get() = boundedBudget.value
}

data class LivePlannedTraversal
internal constructor(
    val meaning: RelationMeaning,
    val budget: TraversalBudget.Bounded,
    val boundedOneHop: BoundedRelationBudget,
    val expansion: RelationSearchBoundary = RelationSearchBoundary.RETAINED_SUBJECT,
)

class LivePlannedDiagnosticScope private constructor(files: List<CanonicalWorkspaceFilePath>) {
    val files: List<CanonicalWorkspaceFilePath> = files.toList()

    internal companion object {
        fun restore(
            files: List<CanonicalWorkspaceFilePath>
        ): Refinement<LivePlannedDiagnosticScope, LiveAddDeclarationPlanDecodeFailure> {
            if (files.isEmpty() || files.distinct().size != files.size || files != files.sortedBy { it.value }) {
                return Refinement.Rejected(LiveAddDeclarationPlanDecodeFailure.EVIDENCE_INCOMPLETE)
            }
            if (files.any { !it.value.endsWith(".kt") && !it.value.endsWith(".kts") }) {
                return Refinement.Rejected(LiveAddDeclarationPlanDecodeFailure.EVIDENCE_INCOMPLETE)
            }
            return Refinement.Refined(LivePlannedDiagnosticScope(files))
        }
    }
}

/** Exact original complete read requests, detached from the planning read authority for later replay. */
class LiveAddDeclarationVerificationScope
private constructor(
    relations: List<LivePlannedRelationRead>,
    traversals: List<LivePlannedTraversal>,
    diagnostics: List<LivePlannedDiagnosticScope>,
) {
    val relations: List<LivePlannedRelationRead> = relations.toList()
    val traversals: List<LivePlannedTraversal> = traversals.toList()
    val diagnostics: List<LivePlannedDiagnosticScope> = diagnostics.toList()

    internal companion object {
        fun capture(
            evidence: CompleteChangePlanningEvidence
        ): Refinement<LiveAddDeclarationVerificationScope, ChangePlanningFailure> {
            if (evidence.relations.any { it.batch.request.position != RelationReadPosition.Start }) {
                return Refinement.Rejected(ChangePlanningFailure.RELATION_EVIDENCE_INCOMPLETE)
            }
            if (evidence.traversals.any { it.page.plan.position != TraversalPosition.Start }) {
                return Refinement.Rejected(ChangePlanningFailure.TRAVERSAL_EVIDENCE_INCOMPLETE)
            }
            val diagnostics =
                evidence.diagnostics.map { result ->
                    val files =
                        result.batch.scope.files.map { file ->
                            when (
                                val parsed =
                                    CanonicalWorkspaceFilePath.fromCanonicalPath(
                                        result.batch.scope.lease.workspaceRoot,
                                        Path.of(file.value),
                                    )
                            ) {
                                is Refinement.Refined -> parsed.value
                                is Refinement.Rejected ->
                                    return Refinement.Rejected(ChangePlanningFailure.DIAGNOSTIC_EVIDENCE_INCOMPLETE)
                            }
                        }
                    when (val restored = LivePlannedDiagnosticScope.restore(files)) {
                        is Refinement.Refined -> restored.value
                        is Refinement.Rejected ->
                            return Refinement.Rejected(ChangePlanningFailure.DIAGNOSTIC_EVIDENCE_INCOMPLETE)
                    }
                }
            return Refinement.Refined(
                LiveAddDeclarationVerificationScope(
                    evidence.relations.map { relation ->
                        when (val captured = captureLiveRelation(relation)) {
                            is Refinement.Refined -> captured.value
                            is Refinement.Rejected -> return captured
                        }
                    },
                    evidence.traversals.map { traversal ->
                        when (val captured = captureLiveTraversal(traversal)) {
                            is Refinement.Refined -> captured.value
                            is Refinement.Rejected -> return captured
                        }
                    },
                    diagnostics,
                )
            )
        }

        fun restore(
            relations: List<LivePlannedRelationRead>,
            traversals: List<LivePlannedTraversal>,
            diagnostics: List<LivePlannedDiagnosticScope>,
            evidence: DurableAddDeclarationPlanningEvidence,
        ): Refinement<LiveAddDeclarationVerificationScope, LiveAddDeclarationPlanDecodeFailure> {
            val invalid = Refinement.Rejected(LiveAddDeclarationPlanDecodeFailure.EVIDENCE_INCOMPLETE)
            if (relations.isEmpty() || traversals.isEmpty() || diagnostics.isEmpty()) return invalid
            if (
                relations.size != evidence.relations.size ||
                    traversals.size != evidence.traversals.size ||
                    diagnostics.size != evidence.diagnostics.size
            )
                return invalid
            if (relations.map { it.meaning } != evidence.relations.map { it.meaning.domain() }) return invalid
            if (traversals.any { !it.budget.containsOneHop() }) return invalid
            return Refinement.Refined(LiveAddDeclarationVerificationScope(relations, traversals, diagnostics))
        }
    }
}

private fun TraversalBudget.Bounded.containsOneHop(): Boolean =
    oneHop.outputResults.value <= records.value &&
        oneHop.returnedBytes.value <= returnedBytes.value &&
        oneHop.resources.fitsWithin(resources)
