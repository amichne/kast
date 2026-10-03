package io.github.amichne.kast.protocol.contract

import io.github.amichne.kast.kernel.Refinement

internal fun QueryRunResult.validatePeerEvidence(): Refinement<Unit, ImpactAccountingFailure> {
    val accounting = impactAccounting as? ImpactAccountingDocument.Investigated ?: return Refinement.Refined(Unit)
    val basis = accounting.seeds.values.firstOrNull()?.enclosing?.basis ?: return Refinement.Refined(Unit)
    if (
        accounting.seeds.values.any { !it.isOnBasis(basis) } ||
            accounting.requestedSites.values.any { !it.isOnBasis(basis) }
    )
        return Refinement.Rejected(ImpactAccountingFailure.WITNESS_VIEW_MISMATCH)
    val valid =
        items.values.all { item ->
            when (item) {
                is QueryResultItemDocument.ValuePath ->
                    item.path.hasValidPeerContinuation(basis) &&
                        item.path.terminal.hasRequiredPeerClosure(accounting.status) &&
                        question.declaresPeerPath(item.path)
                is QueryResultItemDocument.ImpactWitness ->
                    item.item.witness.hasValidPeerEvidence(basis, accounting.status, question)
                else -> true
            }
        }
    return if (valid) Refinement.Refined(Unit) else Refinement.Rejected(ImpactAccountingFailure.WITNESS_VIEW_MISMATCH)
}

private fun ImpactWitnessDocument.hasValidPeerEvidence(
    basis: ImpactSemanticBasisDocument,
    status: ImpactAccountingStatusDocument,
    question: QueryQuestionDocument,
): Boolean =
    when (this) {
        is ImpactWitnessDocument.PeerBoundaryModel -> hasValidPeerModel(basis, question)
        is ImpactWitnessDocument.Finding ->
            finding.hasValidPeerEvidence(basis, status) && question.declaresPeerFinding(finding)
        else -> hasSourceBasis(basis)
    }

private fun QueryQuestionDocument.declaresPeerPath(path: ImpactPathDocument): Boolean {
    val peer = path.terminal as? ImpactPathTerminalDocument.UnresolvedPeerContinuation ?: return true
    val last = path.steps.values.lastOrNull() as? ImpactPathStepDocument.ModeledBoundary ?: return false
    return declaresPeerModel(peer.reference, last.connection.rule)
}

private fun QueryQuestionDocument.declaresPeerFinding(finding: ImpactFindingDocument): Boolean {
    val peer = finding.terminal as? ImpactFindingTerminalDocument.UnresolvedPeerContinuation ?: return true
    val source = from as? QueryFromDocument.Impact ?: return false
    return source.investigation.models.values.filterIsInstance<ImpactModelDocument.Boundary>().any { model ->
        model.model == peer.reference.model &&
            model.rules.values.any { rule ->
                rule is ImpactBoundaryRuleDocument.Continuation &&
                    rule.id == peer.reference.rule &&
                    rule.target.site == peer.target &&
                    rule.source.site.isOnBasis(finding.producer.enclosing.basis)
            }
    }
}

private fun QueryQuestionDocument.declaresPeerModel(
    reference: ImpactRuleReferenceDocument,
    rule: ImpactBoundaryRuleDocument.Continuation,
): Boolean {
    val source = from as? QueryFromDocument.Impact ?: return false
    return source.investigation.models.values.filterIsInstance<ImpactModelDocument.Boundary>().any {
        it.model == reference.model &&
            it.rules.values.any { declared -> declared == rule && declared.id == reference.rule }
    }
}

private fun ImpactFindingDocument.hasValidPeerEvidence(
    basis: ImpactSemanticBasisDocument,
    status: ImpactAccountingStatusDocument,
): Boolean {
    val peer =
        terminal as? ImpactFindingTerminalDocument.UnresolvedPeerContinuation
            ?: return ImpactWitnessDocument.Finding(this).hasSourceBasis(basis)
    if (!producer.isOnBasis(basis) || destination != peer.target) return false
    if (
        peer.admission.validatePeerAdmission(basis, peer.target) !is Refinement.Refined ||
            !status.hasBoundaryObligation()
    )
        return false
    if (boundaryObligations.values.any { !it.position.site.isOnBasis(basis) }) return false
    return when (val evidence = representation) {
        ImpactFindingRepresentationDocument.NotModeled -> true
        is ImpactFindingRepresentationDocument.Present ->
            evidence.branches.values.all {
                val last = it.provenance.values.lastOrNull() as? ImpactFindingProvenanceDocument.BoundaryModel
                last?.reference == peer.reference
            }
    }
}

private fun ImpactPathTerminalDocument.hasRequiredPeerClosure(status: ImpactAccountingStatusDocument): Boolean =
    this !is ImpactPathTerminalDocument.UnresolvedPeerContinuation || status.hasBoundaryObligation()

private fun ImpactAccountingStatusDocument.hasBoundaryObligation(): Boolean =
    when (this) {
        ImpactAccountingStatusDocument.Conserved -> false
        is ImpactAccountingStatusDocument.Unresolved -> ImpactRequiredObligationDocument.BOUNDARY in required.values
        is ImpactAccountingStatusDocument.SelectedSubset -> {
            val original = originalClosure as? ImpactClosureDocument.Unresolved
            original != null && ImpactRequiredObligationDocument.BOUNDARY in original.required.values
        }
    }

private fun ImpactWitnessDocument.PeerBoundaryModel.hasValidPeerModel(
    basis: ImpactSemanticBasisDocument,
    question: QueryQuestionDocument,
): Boolean {
    val continuation = rule as? ImpactBoundaryRuleDocument.Continuation ?: return false
    return continuation.source.site.isOnBasis(basis) &&
        admission.validatePeerAdmission(basis, continuation.target.site) is Refinement.Refined &&
        question.declaresPeerModel(reference, continuation)
}
