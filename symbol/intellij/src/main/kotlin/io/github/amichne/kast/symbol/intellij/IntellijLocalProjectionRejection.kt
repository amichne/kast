package io.github.amichne.kast.symbol.intellij

import io.github.amichne.kast.symbol.contract.LocalDeclarationProjectionFailure
import io.github.amichne.kast.workspace.intellij.read.IntellijReadObservation
import io.github.amichne.kast.workspace.intellij.read.localIdentityRejected

internal fun localProjectionRejected(
    failure: LocalDeclarationProjectionFailure,
    observation: IntellijReadObservation,
): IntellijCompilerSymbolProjectionResult.Rejected {
    observation.localIdentityRejected(failure)
    return IntellijCompilerSymbolProjectionResult.Rejected(
        when (failure) {
            LocalDeclarationProjectionFailure.WorkLimitReached,
            LocalDeclarationProjectionFailure.OwnerDepthExceeded -> IntellijSymbolSelectorRejection.WORK_LIMIT_REACHED
            LocalDeclarationProjectionFailure.CompilerTypeError,
            LocalDeclarationProjectionFailure.CompilerTypeUnsupported,
            LocalDeclarationProjectionFailure.UnsupportedDeclaration,
            LocalDeclarationProjectionFailure.SignatureUnavailable,
            LocalDeclarationProjectionFailure.SourceUnavailable,
            LocalDeclarationProjectionFailure.OwnerUnavailable,
            LocalDeclarationProjectionFailure.CompilerOwnerUnavailable,
            LocalDeclarationProjectionFailure.LexicalAncestryUnavailable,
            is LocalDeclarationProjectionFailure.InvalidAddress ->
                IntellijSymbolSelectorRejection.COMPILER_IDENTITY_UNAVAILABLE
        }
    )
}
