package io.github.amichne.kast.source.contract

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.symbol.contract.CompilerSymbolKind
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryCandidateLocation
import io.github.amichne.kast.symbol.contract.SymbolSelector

enum class SourceDeclarationVisibilityFailure {
    MISSING_OR_AMBIGUOUS_DECLARATION,
    DECLARATION_MISMATCH,
}

/** Visibility proved for the selected declaration itself in one exact source snapshot. */
class SourceDeclarationVisibility
private constructor(
    val selector: SymbolSelector,
    val snapshot: SourceSnapshot,
    val visibility: DeclarationVisibility,
) {
    companion object {
        fun admit(
            selector: SymbolSelector,
            result: SourceReadResult.Complete,
        ): Refinement<SourceDeclarationVisibility, SourceDeclarationVisibilityFailure> {
            val entity =
                result.entities.singleOrNull() as? SourceEntity.Declaration
                    ?: return Refinement.Rejected(SourceDeclarationVisibilityFailure.MISSING_OR_AMBIGUOUS_DECLARATION)
            val selection =
                when (val identity = entity.semanticIdentity) {
                    is DeclarationSemanticIdentity.Candidate -> identity.selector.selection
                }
            val location =
                selection.candidate.location as? SymbolDiscoveryCandidateLocation.Declaration
                    ?: return Refinement.Rejected(SourceDeclarationVisibilityFailure.DECLARATION_MISMATCH)
            val range = entity.selector.range
            if (
                result.snapshot.lease != selector.lease ||
                    result.snapshot.file != selector.file ||
                    result.snapshot.readScope != SourceReadScope.Constrained(selector.scope, selector.constraints) ||
                    result.region.kind != SourceRegionKind.DECLARATION ||
                    range != result.region.selector.range ||
                    range.startInclusive.value != selector.range.startInclusive ||
                    range.endExclusive.value != selector.range.endExclusive ||
                    entity.nestingDepth.value != 0 ||
                    entity.kind.compilerKind() != selector.kind ||
                    selection.lease != selector.lease ||
                    selection.scope != selector.scope ||
                    selection.constraints.copy(declarationKinds = null) !=
                        selector.constraints.copy(declarationKinds = null) ||
                    selection.candidate.name.value != selector.name.value ||
                    location.file != selector.file ||
                    location.offset.value != selector.range.startInclusive
            ) {
                return Refinement.Rejected(SourceDeclarationVisibilityFailure.DECLARATION_MISMATCH)
            }
            return Refinement.Refined(SourceDeclarationVisibility(selector, result.snapshot, entity.visibility))
        }
    }
}

private fun DeclarationKind.compilerKind(): CompilerSymbolKind =
    when (this) {
        DeclarationKind.CLASSLIKE -> CompilerSymbolKind.CLASSLIKE
        DeclarationKind.CONSTRUCTOR -> CompilerSymbolKind.CONSTRUCTOR
        DeclarationKind.FUNCTION -> CompilerSymbolKind.FUNCTION
        DeclarationKind.PROPERTY -> CompilerSymbolKind.PROPERTY
        DeclarationKind.TYPE_ALIAS -> CompilerSymbolKind.TYPE_ALIAS
    }
