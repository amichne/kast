package io.github.amichne.kast.protocol.wire

import io.github.amichne.kast.kernel.EvidenceBasis
import io.github.amichne.kast.kernel.LiveReadContentView
import io.github.amichne.kast.kernel.LiveReadEvidence
import io.github.amichne.kast.protocol.contract.ImpactContentViewDocument
import io.github.amichne.kast.protocol.contract.ImpactSemanticBasisDocument
import io.github.amichne.kast.protocol.contract.OperationResult
import io.github.amichne.kast.protocol.contract.QueryRunResult
import io.github.amichne.kast.protocol.contract.SourceReadResult
import io.github.amichne.kast.protocol.contract.SourceSnapshotContextDocument

/** Repeated snapshot evidence must preserve the exact admitted envelope, not just its shape. */
internal fun OperationResult.retainsEvidenceBasis(basis: EvidenceBasis): Boolean =
    when (this) {
        is QueryRunResult ->
            relationObservations.values.all { relation ->
                relation.callbackObservations.values.all { matchesEnvelopeBasis(it.basis, basis) } &&
                    relation.callableObservations.values.all { matchesEnvelopeBasis(it.basis, basis) }
            } &&
                walkObservations.values.all { walk ->
                    walk.callbackObservations.values.all { matchesEnvelopeBasis(it.observation.basis, basis) } &&
                        walk.callableObservations.values.all { matchesEnvelopeBasis(it.observation.basis, basis) }
                }
        is SourceReadResult ->
            when (val context = snapshot.context) {
                is SourceSnapshotContextDocument.Published ->
                    basis is EvidenceBasis.Published && context.generation == basis.generation
                is SourceSnapshotContextDocument.Live ->
                    basis is EvidenceBasis.Live &&
                        context.evidence == basis.evidence &&
                        snapshot.canonicalRoot.value == basis.evidence.workspaceRoot
            }
        else -> true
    }

private fun matchesEnvelopeBasis(observed: ImpactSemanticBasisDocument, expected: EvidenceBasis): Boolean =
    when (observed) {
        is ImpactSemanticBasisDocument.Published ->
            when (expected) {
                is EvidenceBasis.Published -> observed.generation.value == expected.generation.value
                is EvidenceBasis.Live -> false
            }
        is ImpactSemanticBasisDocument.Live ->
            when (expected) {
                is EvidenceBasis.Published -> false
                is EvidenceBasis.Live -> matchesLiveBasis(observed, expected.evidence)
            }
    }

private fun matchesLiveBasis(observed: ImpactSemanticBasisDocument.Live, expected: LiveReadEvidence): Boolean =
    observed.root.value == expected.workspaceRoot &&
        observed.host.value == expected.host.toString() &&
        observed.epoch.value == expected.epoch &&
        observed.referenceVersion.value == expected.version &&
        when (observed.contentView) {
            ImpactContentViewDocument.SAVED_PSI_COMMITTED ->
                expected.contentView == LiveReadContentView.SAVED_PSI_COMMITTED
        }
