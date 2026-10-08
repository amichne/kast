package io.github.amichne.kast.protocol.contract

/** Foreign native identity is permitted only at the final reviewed connection with its completed peer receipt. */
internal fun ImpactPathDocument.hasValidPeerContinuation(sourceBasis: ImpactSemanticBasisDocument): Boolean {
    val peer = terminal as? ImpactPathTerminalDocument.UnresolvedPeerContinuation ?: return hasSourceBasis(sourceBasis)
    val last = steps.values.lastOrNull() as? ImpactPathStepDocument.ModeledBoundary ?: return false
    val connection = last.connection
    val previous = steps.values.dropLast(1).lastOrNull()?.destination() ?: producer
    if (connection.rule.source.site != previous) return false
    if (!producer.isOnBasis(sourceBasis) || !connection.matchesPeer(peer, sourceBasis)) return false
    if (steps.values.dropLast(1).any { !it.isOnBasis(sourceBasis) }) return false
    return representation.hasValidPeerRepresentation(peer, connection, sourceBasis)
}

private fun ImpactPathDocument.hasSourceBasis(basis: ImpactSemanticBasisDocument): Boolean =
    producer.isOnBasis(basis) &&
        steps.values.all { it.isOnBasis(basis) } &&
        representation.isOnBasis(basis) &&
        terminal.isOnBasis(basis)

private fun ImpactRepresentationEvidenceDocument.hasValidPeerRepresentation(
    peer: ImpactPathTerminalDocument.UnresolvedPeerContinuation,
    connection: ImpactBoundaryConnectionDocument,
    sourceBasis: ImpactSemanticBasisDocument,
): Boolean =
    when (this) {
        ImpactRepresentationEvidenceDocument.NotModeled -> true
        is ImpactRepresentationEvidenceDocument.Present ->
            site == peer.target &&
                branches.values.isNotEmpty() &&
                branches.values.all { it.history.hasValidPeerHistory(connection, sourceBasis) }
    }

private fun ImpactBoundaryConnectionDocument.matchesPeer(
    peer: ImpactPathTerminalDocument.UnresolvedPeerContinuation,
    basis: ImpactSemanticBasisDocument,
): Boolean =
    reference == peer.reference &&
        rule.target.site == peer.target &&
        rule.source.site.isOnBasis(basis) &&
        peer.admission.validatePeerAdmission(basis, peer.target) is io.github.amichne.kast.kernel.Refinement.Refined &&
        obligations.values.all { it.position == rule.source }

private fun BoundedProtocolList<ImpactRepresentationHistoryDocument>.hasValidPeerHistory(
    connection: ImpactBoundaryConnectionDocument,
    basis: ImpactSemanticBasisDocument,
): Boolean {
    val last = values.lastOrNull() as? ImpactRepresentationHistoryDocument.BoundaryModel ?: return false
    return last.reference == connection.reference &&
        last.source == connection.rule.source &&
        last.target == connection.rule.target &&
        values.dropLast(1).all { it.isOnBasis(basis) }
}

private fun ImpactPathStepDocument.isOnBasis(basis: ImpactSemanticBasisDocument): Boolean =
    when (this) {
        is ImpactPathStepDocument.Compiler -> transfer.isOnBasis(basis)
        is ImpactPathStepDocument.ModeledRepresentation -> application.isOnBasis(basis)
        is ImpactPathStepDocument.ModeledBoundary ->
            connection.rule.source.site.isOnBasis(basis) &&
                connection.rule.target.site.isOnBasis(basis) &&
                connection.obligations.values.all { it.position.site.isOnBasis(basis) }
    }

private fun ImpactRepresentationHistoryDocument.isOnBasis(basis: ImpactSemanticBasisDocument): Boolean =
    when (this) {
        is ImpactRepresentationHistoryDocument.Origin ->
            output.isOnBasis(basis) && invocation.callable.basis == basis && rule.output.declaration.basis == basis
        is ImpactRepresentationHistoryDocument.CompilerTransfer -> transfer.isOnBasis(basis)
        is ImpactRepresentationHistoryDocument.ModeledApplication -> application.isOnBasis(basis)
        is ImpactRepresentationHistoryDocument.Unmodeled -> source.isOnBasis(basis) && target.isOnBasis(basis)
        is ImpactRepresentationHistoryDocument.BoundaryModel ->
            source.site.isOnBasis(basis) && target.site.isOnBasis(basis)
    }

private fun ImpactCompilerTransferDocument.isOnBasis(basis: ImpactSemanticBasisDocument): Boolean =
    admitsEvidence() && source.isOnBasis(basis) && target.isOnBasis(basis)

private fun ImpactRepresentationApplicationDocument.isOnBasis(basis: ImpactSemanticBasisDocument): Boolean =
    source.isOnBasis(basis) && target.isOnBasis(basis) && invocation.callable.basis == basis && rule.isOnBasis(basis)

internal fun ImpactRepresentationRuleDocument.isOnBasis(basis: ImpactSemanticBasisDocument): Boolean =
    when (this) {
        is ImpactRepresentationRuleDocument.Origin -> output.declaration.basis == basis
        is ImpactRepresentationRuleDocument.Transfer ->
            input.declaration.basis == basis && output.declaration.basis == basis
        is ImpactRepresentationRuleDocument.Transformation ->
            input.declaration.basis == basis && output.declaration.basis == basis
        is ImpactRepresentationRuleDocument.ConsumerExpectation -> input.declaration.basis == basis
    }

private fun ImpactRepresentationEvidenceDocument.isOnBasis(basis: ImpactSemanticBasisDocument): Boolean =
    when (this) {
        ImpactRepresentationEvidenceDocument.NotModeled -> true
        is ImpactRepresentationEvidenceDocument.Present ->
            site.isOnBasis(basis) && branches.values.all { branch -> branch.history.values.all { it.isOnBasis(basis) } }
    }

private fun ImpactPathTerminalDocument.isOnBasis(basis: ImpactSemanticBasisDocument): Boolean =
    when (this) {
        is ImpactPathTerminalDocument.UnresolvedPeerContinuation -> false
        is ImpactPathTerminalDocument.Consumer -> site.isOnBasis(basis) && rule.isOnBasis(basis)
        is ImpactPathTerminalDocument.ModeledTerminal ->
            rule.source.site.isOnBasis(basis) && obligations.values.all { it.position.site.isOnBasis(basis) }
        is ImpactPathTerminalDocument.UnresolvedFlow -> site.isOnBasis(basis)
        is ImpactPathTerminalDocument.UnresolvedBoundary ->
            source.site.isOnBasis(basis) && obligation.position.site.isOnBasis(basis)
        is ImpactPathTerminalDocument.UnresolvedRead ->
            when (val rejected = rejection) {
                is ImpactReadRejectionDocument.Native -> rejected.source.isOnBasis(basis)
                is ImpactReadRejectionDocument.Contract -> rejected.source.isOnBasis(basis)
            }
        is ImpactPathTerminalDocument.SupportedDomainEnd ->
            observation.source.isOnBasis(basis) && observation.domain.subject.basis == basis
        is ImpactPathTerminalDocument.ExplicitScopeExclusion -> site.isOnBasis(basis)
        is ImpactPathTerminalDocument.ExecutionStop ->
            when (val stopped = stop) {
                is ImpactExecutionStopDocument.CheckpointCapacity -> stopped.source.isOnBasis(basis)
                is ImpactExecutionStopDocument.Cycle ->
                    stopped.producer.isOnBasis(basis) &&
                        stopped.source.isOnBasis(basis) &&
                        stopped.prefix.values.all { it.isOnBasis(basis) }
            }
    }

private fun ImpactPathStepDocument.destination(): ImpactValueSiteReferenceDocument =
    when (this) {
        is ImpactPathStepDocument.Compiler -> transfer.target
        is ImpactPathStepDocument.ModeledRepresentation -> application.target
        is ImpactPathStepDocument.ModeledBoundary -> connection.rule.target.site
    }
