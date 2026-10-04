package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.ImpactBoundaryUnresolvedDocument
import io.github.amichne.kast.protocol.contract.ImpactConsumerOutcomeDocument
import io.github.amichne.kast.protocol.contract.ImpactFindingExecutionStopDocument
import io.github.amichne.kast.protocol.contract.ImpactFindingTerminalDocument
import io.github.amichne.kast.protocol.contract.ImpactFlowEndObservationDocument
import io.github.amichne.kast.protocol.contract.ImpactFlowTerminalDocument
import io.github.amichne.kast.protocol.contract.ImpactScopeExclusionDocument
import io.github.amichne.kast.protocol.contract.QueryDiscoveryCountDocument
import io.github.amichne.kast.query.contract.QueryImpactExclusionCause
import io.github.amichne.kast.query.contract.QueryImpactExecutionStop
import io.github.amichne.kast.query.contract.QueryImpactTerminal
import io.github.amichne.kast.relation.contract.BoundaryUnresolvedReason
import io.github.amichne.kast.relation.contract.ConsumerRepresentationEvidence
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryConstraints

internal fun QueryImpactTerminal.findingDocument(): ImpactProjected<ImpactFindingTerminalDocument> =
    when (this) {
        is QueryImpactTerminal.Consumer -> projectFindingConsumer()
        is QueryImpactTerminal.ModeledTerminal -> projectFindingModeledTerminal()
        is QueryImpactTerminal.Unresolved.PeerContinuation -> projectPeerFindingTerminal()
        is QueryImpactTerminal.Unresolved.Flow ->
            Refinement.Refined(ImpactFindingTerminalDocument.UnresolvedFlow(obligation.cause.impactDocument()))
        is QueryImpactTerminal.Unresolved.ReadRejected ->
            rejection.impactDocument().impactMap(ImpactFindingTerminalDocument::UnresolvedRead)
        is QueryImpactTerminal.Unresolved.ExecutionStop ->
            stop.findingDocument().impactMap(ImpactFindingTerminalDocument::ExecutionStop)
        is QueryImpactTerminal.Unresolved.Boundary -> projectFindingBoundary()
        is QueryImpactTerminal.SupportedDomainEnd -> projectFindingEnd()
        is QueryImpactTerminal.ExplicitScopeExclusion -> projectFindingExclusion()
    }

private fun QueryImpactTerminal.Consumer.projectFindingConsumer(): ImpactProjected<ImpactFindingTerminalDocument> =
    evidence.reference.impactDocument().impactZip(evidence.rule.expected.impactDocument()).impactMap {
        (reference, expected) ->
        ImpactFindingTerminalDocument.Consumer(
            reference,
            expected,
            when (evidence) {
                is ConsumerRepresentationEvidence.Satisfied -> ImpactConsumerOutcomeDocument.SATISFIED
                is ConsumerRepresentationEvidence.Different -> ImpactConsumerOutcomeDocument.DIFFERENT
                is ConsumerRepresentationEvidence.Unknown -> ImpactConsumerOutcomeDocument.UNKNOWN
            },
        )
    }

private fun QueryImpactTerminal.ModeledTerminal.projectFindingModeledTerminal():
    ImpactProjected<ImpactFindingTerminalDocument> =
    boundary.model.reference
        .impactDocument()
        .impactZip(boundary.source.impactDocument())
        .impactZip(boundary.obligations.impactEach { it.impactDocument() })
        .impactMap { (position, obligations) ->
            ImpactFindingTerminalDocument.ModeledTerminal(
                position.first,
                position.second,
                boundary.model.meaning.impactDocument(),
                obligations,
            )
        }

private fun QueryImpactTerminal.Unresolved.Boundary.projectFindingBoundary():
    ImpactProjected<ImpactFindingTerminalDocument> =
    boundary.source.impactDocument().impactZip(boundary.obligation.impactDocument()).impactMap { (source, obligation) ->
        ImpactFindingTerminalDocument.UnresolvedBoundary(
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

private fun QueryImpactTerminal.SupportedDomainEnd.projectFindingEnd(): ImpactProjected<ImpactFindingTerminalDocument> =
    observation.source
        .impactDocument()
        .impactZip(observation.domain.impactDocument())
        .impactZip(count(observation.examinedWorkUnits.value))
        .impactZip(count(observation.retainedBytes))
        .impactMap { (end, bytes) ->
            ImpactFindingTerminalDocument.SupportedDomainEnd(
                ImpactFlowEndObservationDocument(
                    end.first.first,
                    end.first.second,
                    end.second,
                    bytes,
                    ImpactFlowTerminalDocument.SUPPORTED_DOMAIN_EXHAUSTED,
                )
            )
        }

private fun QueryImpactTerminal.ExplicitScopeExclusion.projectFindingExclusion():
    ImpactProjected<ImpactFindingTerminalDocument> {
    val domain =
        relationDomainDocument(
            exclusion.domain.scope,
            SymbolDiscoveryConstraints(
                directory = exclusion.domain.directory,
                packageName = null,
                sourceSets = exclusion.domain.sourceSets,
            ),
        ) ?: return Refinement.Rejected(ImpactPathProjectionFailure.DOMAIN_PROJECTION_REJECTED)
    return Refinement.Refined(
        ImpactFindingTerminalDocument.ExplicitScopeExclusion(
            domain,
            when (exclusion.cause) {
                QueryImpactExclusionCause.OUTSIDE_EXACT_FILE -> ImpactScopeExclusionDocument.OUTSIDE_EXACT_FILE
                QueryImpactExclusionCause.OUTSIDE_DIRECTORY -> ImpactScopeExclusionDocument.OUTSIDE_DIRECTORY
            },
        )
    )
}

private fun QueryImpactExecutionStop.findingDocument(): ImpactProjected<ImpactFindingExecutionStopDocument> =
    when (this) {
        is QueryImpactExecutionStop.Cycle ->
            count(repeatedAt.toLong()).impactMap(ImpactFindingExecutionStopDocument::Cycle)
        is QueryImpactExecutionStop.CheckpointCapacity ->
            count(required.value).impactZip(count(available.value)).impactMap { (required, available) ->
                ImpactFindingExecutionStopDocument.CheckpointCapacity(required, available)
            }
    }

private fun count(value: Long): ImpactProjected<QueryDiscoveryCountDocument> =
    QueryDiscoveryCountDocument.parse(value).impactFailure(ImpactPathProjectionFailure::Count)
