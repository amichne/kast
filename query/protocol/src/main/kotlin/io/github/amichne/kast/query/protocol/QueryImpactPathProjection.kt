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
internal fun QueryImpactPath.impactDocument(references: QueryReferenceAuthority): ImpactProjected<ImpactPathDocument> =
    producer
        .impactDocument(references)
        .impactZip(steps.impactEach { it.impactDocument(references) })
        .impactZip(
            when (val representation = representation) {
                QueryImpactRepresentation.NotModeled ->
                    Refinement.Refined(ImpactRepresentationEvidenceDocument.NotModeled)
                is QueryImpactRepresentation.Present -> representation.evidence.impactDocument(references)
            }
        )
        .impactZip(terminal.impactDocument(references))
        .impactMap { (path, terminal) ->
            ImpactPathDocument(path.first.first, path.first.second, path.second, terminal)
        }

internal fun QueryImpactStep.impactDocument(
    references: QueryReferenceAuthority
): ImpactProjected<ImpactPathStepDocument> =
    when (this) {
        is QueryImpactStep.Compiler -> transfer.impactDocument(references).impactMap(ImpactPathStepDocument::Compiler)
        is QueryImpactStep.ModeledRepresentation ->
            application.impactDocument(references).impactMap(ImpactPathStepDocument::ModeledRepresentation)
        is QueryImpactStep.ModeledValue ->
            application.impactDocument(references).impactMap(ImpactPathStepDocument::ModeledValue)
        is QueryImpactStep.ModeledBoundary ->
            connection.impactDocument(references).impactMap(ImpactPathStepDocument::ModeledBoundary)
    }

private fun QueryImpactTerminal.impactDocument(
    references: QueryReferenceAuthority
): ImpactProjected<ImpactPathTerminalDocument> =
    when (this) {
        is QueryImpactTerminal.Consumer -> projectConsumer(references)
        is QueryImpactTerminal.ModeledTerminal -> projectModeledTerminal(references)
        is QueryImpactTerminal.Unresolved.PeerContinuation -> projectPeerTerminal(references)
        is QueryImpactTerminal.Unresolved.Flow -> projectFlow(references)
        is QueryImpactTerminal.Unresolved.ExecutionStop -> projectExecutionStop(references)
        is QueryImpactTerminal.Unresolved.ReadRejected -> projectReadRejected(references)
        is QueryImpactTerminal.Unresolved.Boundary -> projectBoundary(references)
        is QueryImpactTerminal.SupportedDomainEnd -> projectSupportedDomainEnd(references)
        is QueryImpactTerminal.ExplicitScopeExclusion -> projectExplicitScopeExclusion(references)
    }

private fun QueryImpactTerminal.Consumer.projectConsumer(
    references: QueryReferenceAuthority
): ImpactProjected<ImpactPathTerminalDocument> =
    site
        .impactDocument(references)
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

private fun QueryImpactTerminal.ModeledTerminal.projectModeledTerminal(
    references: QueryReferenceAuthority
): ImpactProjected<ImpactPathTerminalDocument> =
    boundary.model.reference
        .impactDocument()
        .impactZip(boundary.model.impactDocument(references))
        .impactZip(boundary.obligations.impactEach { it.impactDocument(references) })
        .impactMap { (model, obligations) ->
            ImpactPathTerminalDocument.ModeledTerminal(
                model.first,
                model.second as ImpactBoundaryRuleDocument.Terminal,
                obligations,
            )
        }

private fun QueryImpactTerminal.Unresolved.Flow.projectFlow(
    references: QueryReferenceAuthority
): ImpactProjected<ImpactPathTerminalDocument> =
    site
        .impactDocument(references)
        .impactZip(obligation.evidence.impactDocument(references, obligation.site.enclosing.lease))
        .impactMap { (site, evidence) ->
            ImpactPathTerminalDocument.UnresolvedFlow(
                site,
                obligation.cause.impactDocument(),
                evidence,
            )
        }

private fun QueryImpactTerminal.Unresolved.ExecutionStop.projectExecutionStop(
    references: QueryReferenceAuthority
): ImpactProjected<ImpactPathTerminalDocument> =
    stop.impactDocument(references).impactMap(ImpactPathTerminalDocument::ExecutionStop)

private fun QueryImpactTerminal.Unresolved.ReadRejected.projectReadRejected(
    references: QueryReferenceAuthority
): ImpactProjected<ImpactPathTerminalDocument> =
    rejection.impactDocument(references).impactMap(ImpactPathTerminalDocument::UnresolvedRead)

private fun QueryImpactTerminal.Unresolved.Boundary.projectBoundary(
    references: QueryReferenceAuthority
): ImpactProjected<ImpactPathTerminalDocument> =
    boundary.source.impactDocument(references).impactZip(boundary.obligation.impactDocument(references)).impactMap {
        (source, obligation) ->
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

private fun QueryImpactTerminal.SupportedDomainEnd.projectSupportedDomainEnd(
    references: QueryReferenceAuthority
): ImpactProjected<ImpactPathTerminalDocument> =
    observation.source
        .impactDocument(references)
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

private fun QueryImpactTerminal.ExplicitScopeExclusion.projectExplicitScopeExclusion(
    references: QueryReferenceAuthority
): ImpactProjected<ImpactPathTerminalDocument> =
    site.impactDocument(references).impactThen { site ->
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
