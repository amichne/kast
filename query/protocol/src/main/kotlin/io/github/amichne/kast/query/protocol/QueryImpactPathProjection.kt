package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.ImpactBoundaryRuleDocument
import io.github.amichne.kast.protocol.contract.ImpactBoundaryUnresolvedDocument
import io.github.amichne.kast.protocol.contract.ImpactConsumerOutcomeDocument
import io.github.amichne.kast.protocol.contract.ImpactFlowEndObservationDocument
import io.github.amichne.kast.protocol.contract.ImpactFlowTerminalDocument
import io.github.amichne.kast.protocol.contract.ImpactPathDocument
import io.github.amichne.kast.protocol.contract.ImpactPathStepDocument
import io.github.amichne.kast.protocol.contract.ImpactPathTerminalDocument
import io.github.amichne.kast.protocol.contract.ImpactRepresentationEvidenceDocument
import io.github.amichne.kast.protocol.contract.ImpactRepresentationRuleDocument
import io.github.amichne.kast.protocol.contract.ImpactScopeExclusionDocument
import io.github.amichne.kast.protocol.contract.QueryDiscoveryCountDocument
import io.github.amichne.kast.protocol.contract.reason
import io.github.amichne.kast.query.contract.QueryImpactExclusionCause
import io.github.amichne.kast.query.contract.QueryImpactPath
import io.github.amichne.kast.query.contract.QueryImpactRepresentation
import io.github.amichne.kast.query.contract.QueryImpactStep
import io.github.amichne.kast.query.contract.QueryImpactTerminal
import io.github.amichne.kast.relation.contract.BoundaryUnresolvedReason
import io.github.amichne.kast.relation.contract.ConsumerRepresentationEvidence
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryConstraints

/** Pure evidence projection. It cannot introduce rows, connect sites or determine query completion. */
internal fun QueryImpactPath.impactDocument(): ImpactProjected<ImpactPathDocument> =
    producer
        .impactDocument()
        .impactZip(steps.impactEach { it.impactDocument() })
        .impactZip(
            when (val representation = representation) {
                QueryImpactRepresentation.NotModeled ->
                    Refinement.Refined(ImpactRepresentationEvidenceDocument.NotModeled)
                is QueryImpactRepresentation.Present -> representation.evidence.impactDocument()
            }
        )
        .impactZip(terminal.impactDocument())
        .impactMap { (path, terminal) ->
            ImpactPathDocument(path.first.first, path.first.second, path.second, terminal)
        }

internal fun QueryImpactStep.impactDocument(): ImpactProjected<ImpactPathStepDocument> =
    when (this) {
        is QueryImpactStep.Compiler -> transfer.impactDocument().impactMap(ImpactPathStepDocument::Compiler)
        is QueryImpactStep.ModeledRepresentation ->
            application.impactDocument().impactMap(ImpactPathStepDocument::ModeledRepresentation)
        is QueryImpactStep.ModeledBoundary ->
            connection.impactDocument().impactMap(ImpactPathStepDocument::ModeledBoundary)
    }

private fun QueryImpactTerminal.impactDocument(): ImpactProjected<ImpactPathTerminalDocument> =
    when (this) {
        is QueryImpactTerminal.Consumer -> projectConsumer()
        is QueryImpactTerminal.ModeledTerminal -> projectModeledTerminal()
        is QueryImpactTerminal.Unresolved.PeerContinuation -> projectPeerTerminal()
        is QueryImpactTerminal.Unresolved.Flow -> projectFlow()
        is QueryImpactTerminal.Unresolved.ExecutionStop -> projectExecutionStop()
        is QueryImpactTerminal.Unresolved.ReadRejected -> projectReadRejected()
        is QueryImpactTerminal.Unresolved.Boundary -> projectBoundary()
        is QueryImpactTerminal.SupportedDomainEnd -> projectSupportedDomainEnd()
        is QueryImpactTerminal.ExplicitScopeExclusion -> projectExplicitScopeExclusion()
    }

private fun QueryImpactTerminal.Consumer.projectConsumer(): ImpactProjected<ImpactPathTerminalDocument> =
    site
        .impactDocument()
        .impactZip(evidence.reference.impactDocument())
        .impactZip(evidence.rule.impactDocument())
        .impactMap { (consumer, rule) ->
            ImpactPathTerminalDocument.Consumer(
                consumer.first,
                consumer.second,
                rule as ImpactRepresentationRuleDocument.ConsumerExpectation,
                when (evidence) {
                    is ConsumerRepresentationEvidence.Satisfied -> ImpactConsumerOutcomeDocument.SATISFIED
                    is ConsumerRepresentationEvidence.Different -> ImpactConsumerOutcomeDocument.DIFFERENT
                    is ConsumerRepresentationEvidence.Unknown -> ImpactConsumerOutcomeDocument.UNKNOWN
                },
            )
        }

private fun QueryImpactTerminal.ModeledTerminal.projectModeledTerminal(): ImpactProjected<ImpactPathTerminalDocument> =
    boundary.model.reference
        .impactDocument()
        .impactZip(boundary.model.impactDocument())
        .impactZip(boundary.obligations.impactEach { it.impactDocument() })
        .impactMap { (model, obligations) ->
            ImpactPathTerminalDocument.ModeledTerminal(
                model.first,
                model.second as ImpactBoundaryRuleDocument.Terminal,
                obligations,
            )
        }

private fun QueryImpactTerminal.Unresolved.Flow.projectFlow(): ImpactProjected<ImpactPathTerminalDocument> =
    site.impactDocument().impactMap {
        ImpactPathTerminalDocument.UnresolvedFlow(
            it,
            obligation.cause.impactDocument(),
        )
    }

private fun QueryImpactTerminal.Unresolved.ExecutionStop.projectExecutionStop():
    ImpactProjected<ImpactPathTerminalDocument> =
    stop.impactDocument().impactMap(ImpactPathTerminalDocument::ExecutionStop)

private fun QueryImpactTerminal.Unresolved.ReadRejected.projectReadRejected():
    ImpactProjected<ImpactPathTerminalDocument> =
    rejection.impactDocument().impactMap(ImpactPathTerminalDocument::UnresolvedRead)

private fun QueryImpactTerminal.Unresolved.Boundary.projectBoundary(): ImpactProjected<ImpactPathTerminalDocument> =
    boundary.source.impactDocument().impactZip(boundary.obligation.impactDocument()).impactMap { (source, obligation) ->
        ImpactPathTerminalDocument.UnresolvedBoundary(
            source,
            when (boundary.reason) {
                BoundaryUnresolvedReason.MISSING_MODEL -> ImpactBoundaryUnresolvedDocument.MISSING_MODEL
                BoundaryUnresolvedReason.INVALID_MODEL -> ImpactBoundaryUnresolvedDocument.INVALID_MODEL
                BoundaryUnresolvedReason.STALE_MODEL -> ImpactBoundaryUnresolvedDocument.STALE_MODEL
                BoundaryUnresolvedReason.MISSING_CONSUMER -> ImpactBoundaryUnresolvedDocument.MISSING_CONSUMER
            },
            obligation,
        )
    }

private fun QueryImpactTerminal.SupportedDomainEnd.projectSupportedDomainEnd():
    ImpactProjected<ImpactPathTerminalDocument> =
    observation.source
        .impactDocument()
        .impactZip(observation.domain.impactDocument())
        .impactZip(
            QueryDiscoveryCountDocument.parse(observation.examinedWorkUnits.value)
                .impactFailure(ImpactPathProjectionFailure::Count)
        )
        .impactZip(
            QueryDiscoveryCountDocument.parse(observation.retainedBytes)
                .impactFailure(ImpactPathProjectionFailure::Count)
        )
        .impactMap { (observation, bytes) ->
            ImpactPathTerminalDocument.SupportedDomainEnd(
                ImpactFlowEndObservationDocument(
                    observation.first.first,
                    observation.first.second,
                    observation.second,
                    bytes,
                    ImpactFlowTerminalDocument.SUPPORTED_DOMAIN_EXHAUSTED,
                )
            )
        }

private fun QueryImpactTerminal.ExplicitScopeExclusion.projectExplicitScopeExclusion():
    ImpactProjected<ImpactPathTerminalDocument> =
    site.impactDocument().impactThen { site ->
        val domain =
            relationDomainDocument(
                exclusion.domain.scope,
                SymbolDiscoveryConstraints(
                    directory = exclusion.domain.directory,
                    packageName = null,
                    sourceSets = exclusion.domain.sourceSets,
                ),
            ) ?: return@impactThen Refinement.Rejected(ImpactPathProjectionFailure.DOMAIN_PROJECTION_REJECTED)
        Refinement.Refined(
            ImpactPathTerminalDocument.ExplicitScopeExclusion(
                site,
                domain,
                when (exclusion.cause) {
                    io.github.amichne.kast.query.contract.QueryImpactExclusionCause.OUTSIDE_EXACT_FILE ->
                        ImpactScopeExclusionDocument.OUTSIDE_EXACT_FILE
                    io.github.amichne.kast.query.contract.QueryImpactExclusionCause.OUTSIDE_DIRECTORY ->
                        ImpactScopeExclusionDocument.OUTSIDE_DIRECTORY
                },
            )
        )
    }
