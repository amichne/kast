package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.ImpactFindingBranchDocument
import io.github.amichne.kast.protocol.contract.ImpactFindingDocument
import io.github.amichne.kast.protocol.contract.ImpactFindingEvidenceReferenceDocument
import io.github.amichne.kast.protocol.contract.ImpactFindingProvenanceDocument
import io.github.amichne.kast.protocol.contract.ImpactFindingRepresentationDocument
import io.github.amichne.kast.protocol.contract.QueryDiscoveryCountDocument
import io.github.amichne.kast.protocol.contract.QueryResultRowReference
import io.github.amichne.kast.query.contract.QueryImpactFinding
import io.github.amichne.kast.query.contract.QueryImpactPath
import io.github.amichne.kast.query.contract.QueryImpactRepresentation
import io.github.amichne.kast.query.contract.QueryImpactStep
import io.github.amichne.kast.query.contract.QueryImpactTerminal
import io.github.amichne.kast.relation.contract.BoundaryObligation
import io.github.amichne.kast.relation.contract.RepresentationHistory
import io.github.amichne.kast.relation.contract.RepresentationModelApplication

/** The admitted original path link expands every omitted compiler step and full model payload. */
internal fun QueryImpactFinding.findingDocument(
    rowId: QueryResultRowReference
): ImpactProjected<ImpactFindingDocument> =
    QueryDiscoveryCountDocument.parse(pathOrdinal.value.toLong())
        .impactFailure(ImpactPathProjectionFailure::Count)
        .impactZip(path.producer.impactDocument())
        .impactZip(path.destination.impactDocument())
        .impactZip(path.representation.findingDocument())
        .impactZip(path.terminal.findingDocument())
        .impactZip(path.boundaryObligations().impactEach { it.impactDocument() })
        .impactMap { (finding, obligations) ->
            ImpactFindingDocument(
                ImpactFindingEvidenceReferenceDocument(finding.first.first.first.first, rowId),
                finding.first.first.first.second,
                finding.first.first.second,
                finding.first.second,
                finding.second,
                obligations,
            )
        }

internal fun QueryImpactRepresentation.findingDocument(): ImpactProjected<ImpactFindingRepresentationDocument> =
    when (this) {
        QueryImpactRepresentation.NotModeled -> Refinement.Refined(ImpactFindingRepresentationDocument.NotModeled)
        is QueryImpactRepresentation.Present ->
            evidence.branches
                .impactEach { branch ->
                    branch.current
                        .impactDocument()
                        .impactZip(
                            branch.history
                                .filterNot { it is RepresentationHistory.CompilerTransfer }
                                .impactEach { it.findingDocument() }
                        )
                        .impactMap { (current, provenance) -> ImpactFindingBranchDocument(current, provenance) }
                }
                .impactMap(ImpactFindingRepresentationDocument::Present)
    }

private fun RepresentationHistory.findingDocument(): ImpactProjected<ImpactFindingProvenanceDocument> =
    when (this) {
        is RepresentationHistory.Origin ->
            reference.impactDocument().impactZip(rule.state.impactDocument()).impactMap { (reference, state) ->
                ImpactFindingProvenanceDocument.Origin(reference, state)
            }
        is RepresentationModelApplication.Transfer ->
            reference.impactDocument().impactMap(ImpactFindingProvenanceDocument::ModeledTransfer)
        is RepresentationModelApplication.Transformation ->
            reference.impactDocument().impactMap(ImpactFindingProvenanceDocument::ModeledTransformation)
        is RepresentationHistory.BoundaryModel ->
            reference.impactDocument().impactMap(ImpactFindingProvenanceDocument::BoundaryModel)
        is RepresentationHistory.Unmodeled -> Refinement.Refined(ImpactFindingProvenanceDocument.Unmodeled)
        is RepresentationHistory.CompilerTransfer ->
            Refinement.Rejected(ImpactPathProjectionFailure.DOMAIN_PROJECTION_REJECTED)
    }

private fun QueryImpactPath.boundaryObligations(): List<BoundaryObligation> =
    steps.flatMap { step ->
        when (step) {
            is QueryImpactStep.ModeledBoundary -> step.connection.obligations
            is QueryImpactStep.Compiler,
            is QueryImpactStep.ModeledRepresentation -> emptyList()
        }
    } +
        when (val terminal = terminal) {
            is QueryImpactTerminal.ModeledTerminal -> terminal.boundary.obligations
            is QueryImpactTerminal.Unresolved.Boundary -> listOf(terminal.boundary.obligation)
            is QueryImpactTerminal.Consumer,
            is QueryImpactTerminal.Unresolved.Flow,
            is QueryImpactTerminal.Unresolved.ExecutionStop,
            is QueryImpactTerminal.Unresolved.ReadRejected,
            is QueryImpactTerminal.SupportedDomainEnd,
            is QueryImpactTerminal.ExplicitScopeExclusion -> emptyList()
        }
