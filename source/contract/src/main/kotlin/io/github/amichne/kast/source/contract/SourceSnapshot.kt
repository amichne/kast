package io.github.amichne.kast.source.contract

import io.github.amichne.kast.symbol.contract.SymbolDiscoveryFileIdentity
import io.github.amichne.kast.workspace.contract.SemanticReadAuthority
import io.github.amichne.kast.workspace.contract.SemanticReadLease
import io.github.amichne.kast.workspace.contract.WorkspaceStateIdentity

/** Exact identity of a normalized document, retaining its admitted content view. */
@ConsistentCopyVisibility
data class SourceSnapshot private constructor(
    val context: SourceReadContext,
    val file: SymbolDiscoveryFileIdentity.Workspace,
    val textIdentity: SourceTextIdentity,
    val length: Utf16CodeUnitCount,
    val readScope: SourceReadScope,
) {
    val lease: SemanticReadAuthority get() = context.lease

    companion object {
        /** A committed-document capture preserves actual text identity and content-view evidence. */
        fun create(
            context: SourceReadContext,
            file: SymbolDiscoveryFileIdentity.Workspace,
            textIdentity: SourceTextIdentity,
            length: Utf16CodeUnitCount,
            readScope: SourceReadScope,
        ): SourceSnapshot = SourceSnapshot(context, file, textIdentity, length, readScope)

        fun create(
            lease: SemanticReadLease,
            sourceState: WorkspaceStateIdentity,
            file: SymbolDiscoveryFileIdentity.Workspace,
            textIdentity: SourceTextIdentity,
            length: Utf16CodeUnitCount,
        ): SourceSnapshot = create(SourceReadContext.Published(lease, sourceState), file, textIdentity, length, SourceReadScope.ExactFile)
    }
}
