package io.github.amichne.kast.query.contract

import io.github.amichne.kast.relation.contract.RelationFact
import io.github.amichne.kast.relation.contract.RelationIncompleteCoverage
import io.github.amichne.kast.relation.contract.RelationReferenceOwnership
import io.github.amichne.kast.relation.contract.ValueCallFrame
import io.github.amichne.kast.relation.contract.ValueCallTarget
import io.github.amichne.kast.relation.contract.ValueFlowObligation
import io.github.amichne.kast.relation.contract.ValueFlowObligationEvidence
import io.github.amichne.kast.relation.contract.ValueImplementationCoverage
import io.github.amichne.kast.relation.contract.ValueImplementationDispatch
import io.github.amichne.kast.workspace.contract.SemanticReadAuthority

internal fun ValueCallFrame.hasForeignBasis(lease: SemanticReadAuthority): Boolean =
    invocation.hasForeignBasis(lease) ||
        callee.enclosing.lease != lease ||
        when (val proof = target) {
            ValueCallTarget.Direct -> false
            is ValueCallTarget.Implementation ->
                proof.dispatch.hasForeignBasis(lease) ||
                    proof.relation.hasForeignBasis(lease) ||
                    proof.ownership.basis != lease.identity
        }

internal fun ValueFlowObligation.hasForeignBasis(lease: SemanticReadAuthority): Boolean =
    site.hasForeignBasis(lease) ||
        when (val proof = evidence) {
            ValueFlowObligationEvidence.None -> false
            is ValueFlowObligationEvidence.ImplementationDispatch -> proof.dispatch.hasForeignBasis(lease)
        }

private fun RelationFact.hasForeignBasis(lease: SemanticReadAuthority): Boolean =
    subject.lease != lease || source.lease != lease || target.lease != lease || authority != lease.identity

private fun ValueImplementationDispatch.hasForeignBasis(lease: SemanticReadAuthority): Boolean =
    batch.request.subject.lease != lease ||
        batch.facts.any { it.hasForeignBasis(lease) } ||
        batch.scopeExclusions.any { it.basis != lease } ||
        batch.callbackObservations.any { it.basis != lease } ||
        batch.callableObservations.any { it.basis != lease } ||
        batch.referenceOccurrences.any {
            it.target.lease != lease ||
                it.authority != lease.identity ||
                when (val owner = it.ownership) {
                    is RelationReferenceOwnership.DeclarationOwned -> owner.declaration.lease != lease
                    is RelationReferenceOwnership.FileScoped,
                    is RelationReferenceOwnership.Unavailable -> false
                }
        } ||
        when (val covered = coverage) {
            is ValueImplementationCoverage.Exhausted -> false
            is ValueImplementationCoverage.Incomplete ->
                when (val current = covered.evidence) {
                    is RelationIncompleteCoverage.TerminalIncomplete -> false
                    is RelationIncompleteCoverage.Resumable -> current.continuation.authority != lease.identity
                }
        }
