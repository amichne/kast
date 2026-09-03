package io.github.amichne.kast.source.contract

import io.github.amichne.kast.symbol.contract.SymbolDiscoveryFileIdentity
import io.github.amichne.kast.workspace.contract.SemanticReadLease
import io.github.amichne.kast.workspace.contract.WorkspaceStateIdentity

/**
 * Exact detached identity of one normalized committed IntelliJ document in one publication.
 */
@ConsistentCopyVisibility
data class SourceSnapshot private constructor(
    val lease: SemanticReadLease,
    val sourceState: WorkspaceStateIdentity,
    val file: SymbolDiscoveryFileIdentity.Workspace,
    val textIdentity: SourceTextIdentity,
    val length: Utf16CodeUnitCount,
) {
    companion object {
        /**
         * Proof transition: `(SemanticReadLease, WorkspaceStateIdentity, workspace file,
         * SourceTextIdentity, Utf16CodeUnitCount) -> SourceSnapshot`.
         *
         * Preserves exact root, generation, source publication, file, normalized document digest,
         * and coordinate length as one indivisible source-selection authority. Only an IntelliJ
         * committed-document capture may call this transition with freshly observed evidence.
         */
        fun create(
            lease: SemanticReadLease,
            sourceState: WorkspaceStateIdentity,
            file: SymbolDiscoveryFileIdentity.Workspace,
            textIdentity: SourceTextIdentity,
            length: Utf16CodeUnitCount,
        ): SourceSnapshot = SourceSnapshot(
            lease = lease,
            sourceState = sourceState,
            file = file,
            textIdentity = textIdentity,
            length = length,
        )
    }
}
