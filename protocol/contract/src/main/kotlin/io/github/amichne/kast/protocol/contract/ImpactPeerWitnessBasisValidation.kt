package io.github.amichne.kast.protocol.contract

/** Peer admission is the only foreign native proof; ordinary witness rows remain source-owned. */
internal fun ImpactWitnessDocument.hasSourceBasis(basis: ImpactSemanticBasisDocument): Boolean =
    when (this) {
        is ImpactWitnessDocument.PeerBoundaryModel -> false
        is ImpactWitnessDocument.Finding -> finding.hasSourceBasis(basis)
        is ImpactWitnessDocument.Producer -> site.isOnBasis(basis) && invocation.callable.basis == basis
        is ImpactWitnessDocument.ProducerSiteOnly -> site.isOnBasis(basis)
        is ImpactWitnessDocument.RepresentationModel -> rule.isOnBasis(basis)
        is ImpactWitnessDocument.BoundaryModel -> rule.isOnBasis(basis)
        is ImpactWitnessDocument.NativeRead ->
            source.isOnBasis(basis) && domain.subject.basis == basis && receipts.hasSourceBasis(basis)
        is ImpactWitnessDocument.CompilerTransfer ->
            transfer.admitsEvidence() && transfer.source.isOnBasis(basis) && transfer.target.isOnBasis(basis)
        is ImpactWitnessDocument.ReviewedTransfer -> transfer.admitsEvidence() && transfer.binding.basis == basis
        is ImpactWitnessDocument.FlowObligation -> site.isOnBasis(basis) && evidence.admitsObligation(site, basis)
        is ImpactWitnessDocument.ReadRejection -> rejection.hasSourceBasis(basis) && receipts.hasSourceBasis(basis)
        is ImpactWitnessDocument.SiteAccounting -> accounting.site.isOnBasis(basis)
    }

private fun BoundedProtocolList<ImpactNativeReadReceiptDocument>.hasSourceBasis(
    basis: ImpactSemanticBasisDocument
): Boolean = values.all { it.domain.subject.basis == basis }

private fun ImpactFindingDocument.hasSourceBasis(basis: ImpactSemanticBasisDocument): Boolean =
    producer.isOnBasis(basis) &&
        destination.isOnBasis(basis) &&
        reviewedEvidenceHasSourceBasis(basis) &&
        terminal.hasSourceBasis(basis, destination) &&
        boundaryObligations.values.all { it.position.site.isOnBasis(basis) }

internal fun ImpactFindingDocument.reviewedEvidenceHasSourceBasis(basis: ImpactSemanticBasisDocument): Boolean =
    reviewedRelations.values.all { it.basis == basis && it.admits(it.enclosing) } &&
        representation.hasReviewedSourceBasis(basis)

private fun ImpactFindingRepresentationDocument.hasReviewedSourceBasis(basis: ImpactSemanticBasisDocument): Boolean =
    when (this) {
        ImpactFindingRepresentationDocument.NotModeled -> true
        is ImpactFindingRepresentationDocument.Present ->
            branches.values.all { branch ->
                branch.provenance.values.all { provenance ->
                    when (provenance) {
                        is ImpactFindingProvenanceDocument.ReviewedLibrary ->
                            provenance.binding.basis == basis && provenance.binding.admits(provenance.binding.enclosing)
                        is ImpactFindingProvenanceDocument.Origin,
                        is ImpactFindingProvenanceDocument.ModeledTransfer,
                        is ImpactFindingProvenanceDocument.ModeledTransformation,
                        is ImpactFindingProvenanceDocument.BoundaryModel,
                        ImpactFindingProvenanceDocument.Unmodeled -> true
                    }
                }
            }
    }

private fun ImpactFindingTerminalDocument.hasSourceBasis(
    basis: ImpactSemanticBasisDocument,
    destination: ImpactValueSiteReferenceDocument,
): Boolean =
    when (this) {
        is ImpactFindingTerminalDocument.UnresolvedPeerContinuation -> false
        is ImpactFindingTerminalDocument.UnresolvedFlow -> evidence.admitsObligation(destination, basis)
        is ImpactFindingTerminalDocument.Consumer,
        is ImpactFindingTerminalDocument.ExecutionStop,
        is ImpactFindingTerminalDocument.ExplicitScopeExclusion -> true
        is ImpactFindingTerminalDocument.ModeledTerminal ->
            source.site.isOnBasis(basis) && obligations.values.all { it.position.site.isOnBasis(basis) }
        is ImpactFindingTerminalDocument.UnresolvedRead -> rejection.hasSourceBasis(basis)
        is ImpactFindingTerminalDocument.UnresolvedBoundary ->
            source.site.isOnBasis(basis) && obligation.position.site.isOnBasis(basis)
        is ImpactFindingTerminalDocument.SupportedDomainEnd ->
            observation.source.isOnBasis(basis) && observation.domain.subject.basis == basis
    }

private fun ImpactReadRejectionDocument.hasSourceBasis(basis: ImpactSemanticBasisDocument): Boolean =
    when (this) {
        is ImpactReadRejectionDocument.Native -> source.isOnBasis(basis)
        is ImpactReadRejectionDocument.Contract -> source.isOnBasis(basis)
    }

private fun ImpactBoundaryRuleDocument.isOnBasis(basis: ImpactSemanticBasisDocument): Boolean =
    when (this) {
        is ImpactBoundaryRuleDocument.Terminal -> source.site.isOnBasis(basis)
        is ImpactBoundaryRuleDocument.Continuation -> source.site.isOnBasis(basis) && target.site.isOnBasis(basis)
    }
