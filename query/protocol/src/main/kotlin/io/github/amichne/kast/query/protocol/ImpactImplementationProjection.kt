package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.BoundedProtocolList
import io.github.amichne.kast.protocol.contract.ImpactCallTargetDocument
import io.github.amichne.kast.protocol.contract.ImpactFlowObligationEvidenceDocument
import io.github.amichne.kast.protocol.contract.ImpactImplementationCoverageDocument
import io.github.amichne.kast.protocol.contract.ImpactImplementationDispatchDocument
import io.github.amichne.kast.protocol.contract.ImpactImplementationEndpointDocument
import io.github.amichne.kast.protocol.contract.ImpactImplementationExclusionDocument
import io.github.amichne.kast.protocol.contract.ImpactImplementationFactDocument
import io.github.amichne.kast.protocol.contract.ImpactImplementationOccurrenceDocument
import io.github.amichne.kast.protocol.contract.ImpactImplementationOmissionDocument
import io.github.amichne.kast.protocol.contract.QueryDiscoveryCountDocument
import io.github.amichne.kast.protocol.contract.RelationLimitationDocument
import io.github.amichne.kast.protocol.contract.RelationProviderDocument
import io.github.amichne.kast.relation.contract.RelationBatch
import io.github.amichne.kast.relation.contract.RelationEndpoint
import io.github.amichne.kast.relation.contract.RelationFact
import io.github.amichne.kast.relation.contract.RelationIncompleteCoverage
import io.github.amichne.kast.relation.contract.RelationOccurrence
import io.github.amichne.kast.relation.contract.ValueCallTarget
import io.github.amichne.kast.relation.contract.ValueFlowObligationEvidence
import io.github.amichne.kast.relation.contract.ValueImplementationCoverage
import io.github.amichne.kast.relation.contract.ValueImplementationDispatch

internal fun ValueCallTarget.impactDocument(): ImpactProjected<ImpactCallTargetDocument> =
    when (this) {
        ValueCallTarget.Direct -> Refinement.Refined(ImpactCallTargetDocument.Direct)
        is ValueCallTarget.Implementation ->
            dispatch
                .impactDocument()
                .impactZip(relation.impactImplementationFact())
                .impactZip(ownership.impactDocument())
                .impactMap { (target, owner) ->
                    ImpactCallTargetDocument.Implementation(target.first, target.second, owner)
                }
    }

internal fun ValueFlowObligationEvidence.impactDocument(): ImpactProjected<ImpactFlowObligationEvidenceDocument> =
    when (this) {
        ValueFlowObligationEvidence.None -> Refinement.Refined(ImpactFlowObligationEvidenceDocument.None)
        is ValueFlowObligationEvidence.CallbackInput ->
            Refinement.Refined(ImpactFlowObligationEvidenceDocument.CallbackInput(failure.impactDocument()))
        is ValueFlowObligationEvidence.Expression ->
            kind.value.impactText().impactZip(range.impactDocument()).impactMap { (kind, range) ->
                ImpactFlowObligationEvidenceDocument.Expression(kind, range)
            }
        is ValueFlowObligationEvidence.ImplementationDispatch ->
            dispatch.impactDocument().impactMap(ImpactFlowObligationEvidenceDocument::ImplementationDispatch)
    }

internal fun ValueImplementationDispatch.impactDocument(): ImpactProjected<ImpactImplementationDispatchDocument> =
    batch.request
        .impactDocument()
        .impactZip(batch.request.subject.fingerprint.value.impactText())
        .impactZip(batch.impactImplementationEvidence())
        .impactZip(batch.impactImplementationCounts())
        .impactZip(coverage.impactDocument())
        .impactMap { (batch, coverage) ->
            val (question, evidence) = batch.first
            val counts = batch.second
            ImpactImplementationDispatchDocument(
                question = question.first,
                subjectFingerprint = question.second,
                provider = RelationProviderDocument.valueOf(this.batch.request.providerCursor.provider.name),
                facts = evidence.facts,
                encodedBytes = counts.bytes,
                examinedWorkUnits = counts.work,
                resultCount = counts.results,
                omissions = evidence.omissions,
                scopeExclusions = evidence.exclusions,
                coverage = coverage,
            )
        }

private data class ProjectedImplementationEvidence(
    val facts: BoundedProtocolList<ImpactImplementationFactDocument>,
    val omissions: BoundedProtocolList<ImpactImplementationOmissionDocument>,
    val exclusions: BoundedProtocolList<ImpactImplementationExclusionDocument>,
)

private fun RelationBatch.impactImplementationEvidence(): ImpactProjected<ProjectedImplementationEvidence> {
    if (referenceOccurrences.isNotEmpty() || callbackObservations.isNotEmpty() || callableObservations.isNotEmpty())
        return Refinement.Rejected(ImpactPathProjectionFailure.DOMAIN_PROJECTION_REJECTED)
    return facts
        .impactEach { it.impactImplementationFact() }
        .impactZip(omissions.impactEach { it.impactImplementationOmission() })
        .impactZip(scopeExclusions.impactEach { it.impactImplementationExclusion() })
        .impactMap { (batch, exclusions) -> ProjectedImplementationEvidence(batch.first, batch.second, exclusions) }
}

private data class ProjectedImplementationCounts(
    val bytes: QueryDiscoveryCountDocument,
    val work: QueryDiscoveryCountDocument,
    val results: QueryDiscoveryCountDocument,
)

private fun RelationBatch.impactImplementationCounts(): ImpactProjected<ProjectedImplementationCounts> =
    encodedBytes.value
        .implementationCount()
        .impactZip(examinedWorkUnits.value.implementationCount())
        .impactZip(resultCount.value.toLong().implementationCount())
        .impactMap { (observed, results) -> ProjectedImplementationCounts(observed.first, observed.second, results) }

private fun RelationFact.impactImplementationFact(): ImpactProjected<ImpactImplementationFactDocument> =
    subject
        .impactImplementationEndpoint()
        .impactZip(source.impactImplementationEndpoint())
        .impactZip(target.impactImplementationEndpoint())
        .impactZip(occurrence.impactImplementationOccurrence())
        .impactZip(authority.impactDocument())
        .impactMap { (fact, authority) ->
            ImpactImplementationFactDocument(
                subject = fact.first.first.first,
                source = fact.first.first.second,
                target = fact.first.second,
                occurrence = fact.second,
                authority = authority,
                meaning = meaning.protocolDocument(),
                provenance = provenance.protocolDocument(),
                coverage = coverage.protocolDocument(),
            )
        }

private fun RelationEndpoint.impactImplementationEndpoint(): ImpactProjected<ImpactImplementationEndpointDocument> =
    impactDeclaration().impactZip(fingerprint.value.impactText()).impactMap { (declaration, fingerprint) ->
        ImpactImplementationEndpointDocument(declaration, fingerprint)
    }

internal fun RelationOccurrence.impactImplementationOccurrence():
    ImpactProjected<ImpactImplementationOccurrenceDocument> =
    file.stableValue.impactText().impactZip(range.impactDocument()).impactMap { (file, range) ->
        ImpactImplementationOccurrenceDocument(file, range)
    }

private fun ValueImplementationCoverage.impactDocument(): ImpactProjected<ImpactImplementationCoverageDocument> =
    when (this) {
        is ValueImplementationCoverage.Exhausted ->
            evidence.exactCount.value
                .toLong()
                .implementationCount()
                .impactMap(ImpactImplementationCoverageDocument::Exhausted)
        is ValueImplementationCoverage.Incomplete -> evidence.impactImplementationCoverage()
    }

private fun RelationIncompleteCoverage.impactImplementationCoverage():
    ImpactProjected<ImpactImplementationCoverageDocument> {
    val counts =
        knownMinimum.value
            .toLong()
            .implementationCount()
            .impactZip(
                limitations.sortedBy { it.ordinal }.map { RelationLimitationDocument.valueOf(it.name) }.impactBounded()
            )
    return when (this) {
        is RelationIncompleteCoverage.TerminalIncomplete ->
            counts.impactMap { (minimum, limits) ->
                ImpactImplementationCoverageDocument.TerminalIncomplete(minimum, limits)
            }
        is RelationIncompleteCoverage.Resumable ->
            counts.impactZip(continuation.impactResumeDocument()).impactMap { (coverage, continuation) ->
                ImpactImplementationCoverageDocument.Resumable(coverage.first, coverage.second, continuation)
            }
    }
}

internal fun Long.implementationCount(): ImpactProjected<QueryDiscoveryCountDocument> =
    QueryDiscoveryCountDocument.parse(this).impactFailure(ImpactPathProjectionFailure::Count)
