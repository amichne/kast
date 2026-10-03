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
        is ImpactWitnessDocument.NativeRead -> source.isOnBasis(basis) && domain.subject.basis == basis
        is ImpactWitnessDocument.CompilerTransfer ->
            transfer.source.isOnBasis(basis) && transfer.target.isOnBasis(basis)
        is ImpactWitnessDocument.FlowObligation -> site.isOnBasis(basis)
        is ImpactWitnessDocument.ReadRejection -> rejection.hasSourceBasis(basis)
        is ImpactWitnessDocument.SiteAccounting -> accounting.site.isOnBasis(basis)
    }

private fun ImpactFindingDocument.hasSourceBasis(basis: ImpactSemanticBasisDocument): Boolean =
    producer.isOnBasis(basis) &&
        destination.isOnBasis(basis) &&
        terminal.hasSourceBasis(basis) &&
        boundaryObligations.values.all { it.position.site.isOnBasis(basis) }

private fun ImpactFindingTerminalDocument.hasSourceBasis(basis: ImpactSemanticBasisDocument): Boolean =
    when (this) {
        is ImpactFindingTerminalDocument.UnresolvedPeerContinuation -> false
        is ImpactFindingTerminalDocument.Consumer,
        is ImpactFindingTerminalDocument.UnresolvedFlow,
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
