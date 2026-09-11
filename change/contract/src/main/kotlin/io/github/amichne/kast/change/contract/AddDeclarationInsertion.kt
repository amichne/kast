package io.github.amichne.kast.change.contract

import io.github.amichne.kast.symbol.contract.CompilerSymbolKind
import io.github.amichne.kast.symbol.contract.ExactDeclarationTextRange
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryFileIdentity

/** Shared pure placement rule, reached only after each evidence basis admits its target. */
internal fun planAddDeclarationInsertion(
    file: SymbolDiscoveryFileIdentity.Workspace,
    range: ExactDeclarationTextRange,
    kind: CompilerSymbolKind,
    declaration: AddDeclarationSourceText,
): AddDeclarationPlannedEdit =
    when (kind) {
        CompilerSymbolKind.CLASSLIKE -> AddDeclarationPlannedEdit.InsertIntoClassBody(file, range, declaration)
        CompilerSymbolKind.CONSTRUCTOR,
        CompilerSymbolKind.FUNCTION,
        CompilerSymbolKind.PROPERTY,
        CompilerSymbolKind.TYPE_ALIAS -> AddDeclarationPlannedEdit.InsertAfterDeclaration(file, range, declaration)
    }

internal fun AddDeclarationPlannedEdit.sourceMutation(): SourceTextMutation =
    when (this) {
        is AddDeclarationPlannedEdit.InsertAfterDeclaration ->
            SourceTextMutation.InsertAfterDeclaration(anchor, declaration)
        is AddDeclarationPlannedEdit.InsertIntoClassBody -> SourceTextMutation.InsertIntoClassBody(anchor, declaration)
    }
