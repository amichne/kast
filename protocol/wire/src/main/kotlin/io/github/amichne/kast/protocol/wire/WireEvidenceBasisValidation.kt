package io.github.amichne.kast.protocol.wire

import io.github.amichne.kast.kernel.EvidenceBasis
import io.github.amichne.kast.protocol.contract.OperationResult
import io.github.amichne.kast.protocol.contract.SourceReadResult
import io.github.amichne.kast.protocol.contract.SourceSnapshotContextDocument

/** Repeated snapshot evidence must preserve the exact admitted envelope, not just its shape. */
internal fun OperationResult.retainsEvidenceBasis(basis: EvidenceBasis): Boolean =
    when (this) {
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
