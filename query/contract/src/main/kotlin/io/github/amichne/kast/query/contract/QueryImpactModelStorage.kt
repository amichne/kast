package io.github.amichne.kast.query.contract

import io.github.amichne.kast.relation.contract.BoundaryArrival
import io.github.amichne.kast.relation.contract.BoundaryModel
import io.github.amichne.kast.relation.contract.BoundaryPosition
import io.github.amichne.kast.relation.contract.BoundaryPositionReference
import io.github.amichne.kast.relation.contract.ContractModelIdentity
import io.github.amichne.kast.relation.contract.ExactModelCallablePosition
import io.github.amichne.kast.relation.contract.ModelRuleReference
import io.github.amichne.kast.relation.contract.RepresentationCurrent
import io.github.amichne.kast.relation.contract.RepresentationEvidence
import io.github.amichne.kast.relation.contract.RepresentationHistory
import io.github.amichne.kast.relation.contract.RepresentationModelApplication
import io.github.amichne.kast.relation.contract.RepresentationRule
import io.github.amichne.kast.relation.contract.RepresentationState

private fun ContractModelIdentity.storageBytes(g: QueryImpactRetainedGraph): Long =
    g.node(this) {
        g.text(id.value).saturatedAdd(g.text(provenance.value))
    }

internal fun ModelRuleReference.storageBytes(g: QueryImpactRetainedGraph): Long =
    g.node(this) {
        model.storageBytes(g).saturatedAdd(g.text(rule.value))
    }

private fun ExactModelCallablePosition.storageBytes(g: QueryImpactRetainedGraph): Long =
    g.node(this) {
        endpoint.storageBytes(g).saturatedAdd(g.node(position) { 0L })
    }

private fun RepresentationState.storageBytes(g: QueryImpactRetainedGraph): Long =
    g.node(this) {
        g.text(id.value)
            .saturatedAdd(
                g.node(domain) {
                    domain.model.storageBytes(g).saturatedAdd(g.collection(domain.states) { g.text(it.value) })
                }
            )
    }

internal fun RepresentationRule.storageBytes(g: QueryImpactRetainedGraph): Long =
    g.node(this) {
        reference
            .storageBytes(g)
            .saturatedAdd(
                when (this) {
                    is RepresentationRule.Origin -> output.storageBytes(g).saturatedAdd(state.storageBytes(g))
                    is RepresentationRule.Transfer -> input.storageBytes(g).saturatedAdd(output.storageBytes(g))
                    is RepresentationRule.Transformation ->
                        input
                            .storageBytes(g)
                            .saturatedAdd(output.storageBytes(g))
                            .saturatedAdd(from.storageBytes(g))
                            .saturatedAdd(to.storageBytes(g))
                    is RepresentationRule.ConsumerExpectation ->
                        input.storageBytes(g).saturatedAdd(expected.storageBytes(g))
                }
            )
    }

internal fun RepresentationModelApplication.storageBytes(g: QueryImpactRetainedGraph): Long =
    g.node(this) {
        source
            .storageBytes(g)
            .saturatedAdd(target.storageBytes(g))
            .saturatedAdd(invocation.storageBytes(g))
            .saturatedAdd(
                when (this) {
                    is RepresentationModelApplication.Transfer -> rule.storageBytes(g)
                    is RepresentationModelApplication.Transformation -> rule.storageBytes(g)
                }
            )
    }

internal fun BoundaryPosition.storageBytes(g: QueryImpactRetainedGraph): Long =
    g.node(this) {
        site.storageBytes(g).saturatedAdd(reference.storageBytes(g))
    }

private fun BoundaryPositionReference.storageBytes(g: QueryImpactRetainedGraph): Long =
    g.node(this) {
        site
            .storageBytes(g)
            .saturatedAdd(g.node(basis) { 0L })
            .saturatedAdd(g.node(contract) { g.text(contract.id.value) })
            .saturatedAdd(g.text(slot.value))
    }

internal fun BoundaryModel.storageBytes(g: QueryImpactRetainedGraph): Long =
    g.node(this) {
        reference
            .storageBytes(g)
            .saturatedAdd(source.storageBytes(g))
            .saturatedAdd(
                when (this) {
                    is BoundaryModel.Continuation ->
                        target.storageBytes(g).saturatedAdd(g.collection(assumptions) { g.node(it) { 0L } })
                    is BoundaryModel.Terminal -> 0L
                }
            )
    }

internal fun BoundaryArrival.storageBytes(g: QueryImpactRetainedGraph): Long =
    g.node(this) {
        source
            .storageBytes(g)
            .saturatedAdd(
                g.collection(obligations) { obligation ->
                    g.node(obligation) {
                        obligation.position
                            .storageBytes(g)
                            .saturatedAdd(g.collection(obligation.required) { g.node(it) { 0L } })
                    }
                }
            )
            .saturatedAdd(
                when (this) {
                    is BoundaryArrival.Connected -> model.storageBytes(g)
                    is BoundaryArrival.Terminal -> model.storageBytes(g)
                    is BoundaryArrival.Unresolved -> 0L
                }
            )
    }

internal fun RepresentationHistory.storageBytes(g: QueryImpactRetainedGraph): Long =
    if (this is RepresentationModelApplication) storageBytes(g)
    else
        g.node(this) {
            when (this) {
                is RepresentationHistory.Origin ->
                    rule.storageBytes(g).saturatedAdd(output.storageBytes(g)).saturatedAdd(invocation.storageBytes(g))
                is RepresentationHistory.CompilerTransfer -> transfer.storageBytes(g)
                is RepresentationHistory.Unmodeled -> source.storageBytes(g).saturatedAdd(target.storageBytes(g))
                is RepresentationHistory.BoundaryModel ->
                    reference.storageBytes(g).saturatedAdd(source.storageBytes(g)).saturatedAdd(target.storageBytes(g))
                is RepresentationModelApplication -> 0L
            }
        }

internal fun RepresentationEvidence.storageBytes(g: QueryImpactRetainedGraph): Long =
    g.node(this) {
        site
            .storageBytes(g)
            .saturatedAdd(
                g.collection(branches) { branch ->
                    g.node(branch) {
                        g.node(branch.current) {
                                when (val current = branch.current) {
                                    is RepresentationCurrent.Known -> current.state.storageBytes(g)
                                    is RepresentationCurrent.Unknown -> 0L
                                }
                            }
                            .saturatedAdd(g.collection(branch.history) { it.storageBytes(g) })
                    }
                }
            )
    }
