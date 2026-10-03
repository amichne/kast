package io.github.amichne.kast.query.contract

import io.github.amichne.kast.relation.contract.BoundaryArrival
import io.github.amichne.kast.relation.contract.BoundaryModel
import io.github.amichne.kast.relation.contract.ConsumerRepresentationEvidence
import io.github.amichne.kast.relation.contract.RepresentationEvidence
import io.github.amichne.kast.relation.contract.RepresentationHistory
import io.github.amichne.kast.relation.contract.RepresentationModelApplication
import io.github.amichne.kast.relation.contract.RepresentationRule
import io.github.amichne.kast.relation.contract.ValueFlowStep
import io.github.amichne.kast.relation.contract.ValueInvocation
import io.github.amichne.kast.relation.contract.ValueRole
import io.github.amichne.kast.relation.contract.ValueSite
import io.github.amichne.kast.workspace.contract.SemanticReadAuthority

/** The single query store admits one current authority; a model cannot merge independent repository epochs. */
internal fun QueryImpactPath.hasForeignBasis(lease: SemanticReadAuthority): Boolean =
    producer.hasForeignBasis(lease) ||
        steps.any { it.hasForeignBasis(lease) } ||
        when (val current = representation) {
            QueryImpactRepresentation.NotModeled -> false
            is QueryImpactRepresentation.Present -> current.evidence.hasForeignBasis(lease)
        } ||
        terminal.hasForeignBasis(lease)

internal fun ValueSite.hasForeignBasis(lease: SemanticReadAuthority): Boolean =
    enclosing.lease != lease ||
        when (val current = role) {
            is ValueRole.Argument -> current.call.hasForeignBasis(lease)
            ValueRole.ExpressionResult,
            ValueRole.LocalBinding,
            ValueRole.LocalRead,
            ValueRole.Return,
            ValueRole.PropertyAssignment -> false
        }

internal fun ValueInvocation.hasForeignBasis(lease: SemanticReadAuthority): Boolean =
    enclosing.lease != lease || callable.lease != lease

internal fun RepresentationRule.hasForeignBasis(lease: SemanticReadAuthority): Boolean =
    when (this) {
        is RepresentationRule.Origin -> output.endpoint.lease != lease
        is RepresentationRule.Transfer -> input.endpoint.lease != lease || output.endpoint.lease != lease
        is RepresentationRule.Transformation -> input.endpoint.lease != lease || output.endpoint.lease != lease
        is RepresentationRule.ConsumerExpectation -> input.endpoint.lease != lease
    }

private fun RepresentationModelApplication.hasForeignBasis(lease: SemanticReadAuthority): Boolean =
    source.hasForeignBasis(lease) ||
        target.hasForeignBasis(lease) ||
        invocation.hasForeignBasis(lease) ||
        when (this) {
            is RepresentationModelApplication.Transfer -> rule.hasForeignBasis(lease)
            is RepresentationModelApplication.Transformation -> rule.hasForeignBasis(lease)
        }

private fun BoundaryArrival.hasForeignBasis(lease: SemanticReadAuthority): Boolean =
    source.site.hasForeignBasis(lease) ||
        when (this) {
            is BoundaryArrival.Connected ->
                target.site.hasForeignBasis(lease) || model.source.site.hasForeignBasis(lease)
            is BoundaryArrival.Terminal -> model.source.site.hasForeignBasis(lease)
            is BoundaryArrival.Unresolved -> false
        } ||
        obligations.any { it.position.site.hasForeignBasis(lease) }

private fun QueryImpactStep.hasForeignBasis(lease: SemanticReadAuthority): Boolean =
    when (this) {
        is QueryImpactStep.Compiler -> source.hasForeignBasis(lease) || target.hasForeignBasis(lease)
        is QueryImpactStep.ModeledRepresentation -> application.hasForeignBasis(lease)
        is QueryImpactStep.ModeledBoundary -> connection.hasForeignBasis(lease)
    }

private fun RepresentationEvidence.hasForeignBasis(lease: SemanticReadAuthority): Boolean =
    site.hasForeignBasis(lease) ||
        branches.any { branch ->
            branch.history.any { history ->
                when (history) {
                    is RepresentationHistory.Origin ->
                        history.output.hasForeignBasis(lease) ||
                            history.invocation.hasForeignBasis(lease) ||
                            history.rule.hasForeignBasis(lease)
                    is RepresentationHistory.CompilerTransfer ->
                        history.transfer.source.hasForeignBasis(lease) || history.transfer.target.hasForeignBasis(lease)
                    is RepresentationModelApplication -> history.hasForeignBasis(lease)
                    is RepresentationHistory.Unmodeled ->
                        history.source.basis != lease.identity || history.target.basis != lease.identity
                    is RepresentationHistory.BoundaryModel ->
                        history.source.basis != lease.identity || history.target.basis != lease.identity
                }
            }
        }

private fun QueryImpactTerminal.hasForeignBasis(lease: SemanticReadAuthority): Boolean =
    site.hasForeignBasis(lease) ||
        when (this) {
            is QueryImpactTerminal.Consumer ->
                evidence.rule.hasForeignBasis(lease) ||
                    when (val consumer = evidence) {
                        is ConsumerRepresentationEvidence.Satisfied -> consumer.evidence.hasForeignBasis(lease)
                        is ConsumerRepresentationEvidence.Different -> consumer.evidence.hasForeignBasis(lease)
                        is ConsumerRepresentationEvidence.Unknown -> consumer.evidence.hasForeignBasis(lease)
                    }
            is QueryImpactTerminal.ModeledTerminal -> boundary.hasForeignBasis(lease)
            is QueryImpactTerminal.Unresolved.ExecutionStop ->
                when (val cutoff = stop) {
                    is QueryImpactExecutionStop.Cycle ->
                        cutoff.producer.hasForeignBasis(lease) || cutoff.prefix.any { it.hasForeignBasis(lease) }
                    is QueryImpactExecutionStop.CheckpointCapacity -> cutoff.source.hasForeignBasis(lease)
                }
            is QueryImpactTerminal.Unresolved.ReadRejected -> rejection.source.hasForeignBasis(lease)
            is QueryImpactTerminal.Unresolved.Flow -> obligation.site.hasForeignBasis(lease)
            is QueryImpactTerminal.Unresolved.Boundary -> boundary.hasForeignBasis(lease)
            is QueryImpactTerminal.SupportedDomainEnd -> observation.domain.subject.lease != lease
            is QueryImpactTerminal.ExplicitScopeExclusion -> exclusion.site.hasForeignBasis(lease)
        }

/** Even unused admitted model declarations and native observations remain authority-bearing retained state. */
internal fun QueryImpactLedger.hasForeignBasis(lease: SemanticReadAuthority): Boolean =
    requestedSites.any { it.site.hasForeignBasis(lease) } ||
        seeds.any { it.hasForeignBasis(lease) } ||
        producerEvidence.any { producer ->
            when (producer) {
                is QueryImpactProducerEvidence.Invocation ->
                    producer.site.hasForeignBasis(lease) || producer.producer.invocation.hasForeignBasis(lease)
                is QueryImpactProducerEvidence.SiteOnly -> producer.site.hasForeignBasis(lease)
            }
        } ||
        readRejections.any { it.source.hasForeignBasis(lease) } ||
        representationModels.any { it.hasForeignBasis(lease) } ||
        boundaryModels.any { it.hasForeignBasis(lease) } ||
        observations.any { it.hasForeignBasis(lease) } ||
        paths.any { it.hasForeignBasis(lease) }

internal fun BoundaryModel.hasForeignBasis(lease: SemanticReadAuthority): Boolean =
    source.site.hasForeignBasis(lease) ||
        when (this) {
            is BoundaryModel.Continuation -> target.site.hasForeignBasis(lease)
            is BoundaryModel.Terminal -> false
        }

internal fun ValueFlowStep.hasForeignBasis(lease: SemanticReadAuthority): Boolean =
    source.hasForeignBasis(lease) ||
        domain.subject.lease != lease ||
        transfers.any { it.source.hasForeignBasis(lease) || it.target.hasForeignBasis(lease) } ||
        obligations.any { it.site.hasForeignBasis(lease) }
