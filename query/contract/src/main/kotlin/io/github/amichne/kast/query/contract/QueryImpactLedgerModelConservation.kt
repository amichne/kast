package io.github.amichne.kast.query.contract

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.BoundaryModel
import io.github.amichne.kast.relation.contract.ExactModelCallablePosition
import io.github.amichne.kast.relation.contract.ModelCallableReference
import io.github.amichne.kast.relation.contract.ModelValuePosition
import io.github.amichne.kast.relation.contract.RepresentationEvidence
import io.github.amichne.kast.relation.contract.RepresentationHistory
import io.github.amichne.kast.relation.contract.RepresentationModelApplication
import io.github.amichne.kast.relation.contract.RepresentationRule
import io.github.amichne.kast.relation.contract.ValueRole

/** Model alternatives are conserved by the same ledger that accounts for native outgoing branches. */
internal class QueryImpactLedgerModelConservation(
    private val producers: List<QueryImpactProducer>,
    private val representations: List<RepresentationRule>,
    private val boundaries: List<BoundaryModel>,
    private val paths: List<QueryImpactPath>,
) {
    fun validate(): Refinement<Unit, QueryImpactLedgerFailure> {
        for (producer in producers) {
            when (val admitted = originBranches(producer)) {
                is Refinement.Refined -> Unit
                is Refinement.Rejected -> return admitted
            }
        }
        for (path in paths) {
            when (val admitted = arrivalBranches(path)) {
                is Refinement.Refined -> Unit
                is Refinement.Rejected -> return admitted
            }
        }
        return Refinement.Refined(Unit)
    }

    private fun originBranches(producer: QueryImpactProducer): Refinement<Unit, QueryImpactLedgerFailure> {
        for (rule in representations.filterIsInstance<RepresentationRule.Origin>()) {
            val admitted = RepresentationEvidence.origin(producer.site, producer.invocation, rule)
            if (admitted is Refinement.Rejected) continue
            if (paths.none { it.retainsOrigin(producer, rule) }) return missingBranch()
        }
        return Refinement.Refined(Unit)
    }

    private fun arrivalBranches(path: QueryImpactPath): Refinement<Unit, QueryImpactLedgerFailure> {
        for (index in 0..path.steps.size) {
            if (path.stopsBeforeModels(index)) continue
            val site = if (index == 0) path.producer else path.steps[index - 1].target
            val siblings = paths.filter { it.sameModeledPrefix(path, index) }
            if (!representationBranches(path, site.role, siblings, index)) return missingBranch()
            val retained =
                boundaries
                    .filter { it.source.site == site }
                    .all { model ->
                        siblings.any { it.retainsBoundary(index, model) }
                    }
            if (!retained) return missingBranch()
        }
        return Refinement.Refined(Unit)
    }

    private fun representationBranches(
        path: QueryImpactPath,
        role: ValueRole,
        siblings: List<QueryImpactPath>,
        index: Int,
    ): Boolean {
        if (path.origins().isEmpty() || role !is ValueRole.Argument) return true
        return representations
            .filter { it.matchesArgument(role) }
            .all { rule ->
                siblings.any { it.retainsRepresentation(index, rule) }
            }
    }
}

private fun RepresentationRule.matchesArgument(argument: ValueRole.Argument): Boolean =
    when (this) {
        is RepresentationRule.Origin -> false
        is RepresentationRule.Transfer -> input.matchesArgument(argument)
        is RepresentationRule.Transformation -> input.matchesArgument(argument)
        is RepresentationRule.ConsumerExpectation -> input.matchesArgument(argument)
    }

private fun ExactModelCallablePosition.matchesArgument(argument: ValueRole.Argument): Boolean =
    ModelCallableReference(
        endpoint.lease.identity,
        endpoint.compilerIdentity,
        endpoint.file,
        endpoint.range,
        position,
    ) ==
        argument.call.callable.let {
            ModelCallableReference(
                it.lease.identity,
                it.compilerIdentity,
                it.file,
                it.range,
                ModelValuePosition.Argument(argument.position),
            )
        }

private fun QueryImpactPath.retainsRepresentation(index: Int, rule: RepresentationRule): Boolean =
    when (rule) {
        is RepresentationRule.Origin -> false
        is RepresentationRule.ConsumerExpectation ->
            steps.size == index && (terminal as? QueryImpactTerminal.Consumer)?.evidence?.rule == rule
        is RepresentationRule.Transfer ->
            ((steps.getOrNull(index) as? QueryImpactStep.ModeledRepresentation)?.application
                    as? RepresentationModelApplication.Transfer)
                ?.rule == rule
        is RepresentationRule.Transformation ->
            ((steps.getOrNull(index) as? QueryImpactStep.ModeledRepresentation)?.application
                    as? RepresentationModelApplication.Transformation)
                ?.rule == rule
    }

private fun QueryImpactPath.retainsOrigin(producer: QueryImpactProducer, rule: RepresentationRule.Origin): Boolean =
    this.producer == producer.site && origins().any { it.rule == rule && it.invocation == producer.invocation }

/** Exact steps retain every preceding model application; origin histories retain seed modeling provenance. */
internal fun QueryImpactPath.sameModeledPrefix(other: QueryImpactPath, index: Int): Boolean {
    if (origins() != other.origins()) return false
    return producer == other.producer && steps.size >= index && steps.take(index) == other.steps.take(index)
}

private fun QueryImpactPath.origins(): Set<RepresentationHistory.Origin> =
    when (val current = representation) {
        QueryImpactRepresentation.NotModeled -> emptySet()
        is QueryImpactRepresentation.Present ->
            current.evidence.branches.mapNotNull { it.history.firstOrNull() as? RepresentationHistory.Origin }.toSet()
    }

private fun QueryImpactPath.stopsBeforeModels(index: Int): Boolean =
    index == steps.size &&
        when (terminal) {
            is QueryImpactTerminal.Unresolved.ExecutionStop,
            is QueryImpactTerminal.Unresolved.ReadRejected,
            is QueryImpactTerminal.Unresolved.PeerContinuation,
            is QueryImpactTerminal.ExplicitScopeExclusion -> true
            is QueryImpactTerminal.Consumer,
            is QueryImpactTerminal.ModeledTerminal,
            is QueryImpactTerminal.SupportedDomainEnd,
            is QueryImpactTerminal.Unresolved.Boundary,
            is QueryImpactTerminal.Unresolved.Flow -> false
        }

private fun QueryImpactPath.retainsBoundary(index: Int, model: BoundaryModel): Boolean =
    when (model) {
        is BoundaryModel.Continuation ->
            (steps.getOrNull(index) as? QueryImpactStep.ModeledBoundary)?.connection?.model == model
        is BoundaryModel.Terminal ->
            steps.size == index && (terminal as? QueryImpactTerminal.ModeledTerminal)?.boundary?.model == model
    }

private fun missingBranch(): Refinement.Rejected<QueryImpactLedgerFailure> =
    Refinement.Rejected(QueryImpactLedgerFailure.MISSING_BRANCH)
