package io.github.amichne.kast.relation.intellij

import com.intellij.psi.PsiNamedElement
import io.github.amichne.kast.symbol.contract.CompilerGroundedSymbolEvidence
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryFileIdentity

internal enum class IntellijRelationSubjectFailure {
    STALE_SELECTOR,
    OUTSIDE_SCOPE,
    AMBIGUOUS_SUBJECT,
    UNSUPPORTED_SUBJECT,
    COMPILER_IDENTITY_UNAVAILABLE,
}

internal sealed interface IntellijRelationSubjectLookup {
    data class Found(
        val declaration: PsiNamedElement,
        val evidence: CompilerGroundedSymbolEvidence,
    ) : IntellijRelationSubjectLookup

    data class Rejected(val reason: IntellijRelationSubjectFailure) : IntellijRelationSubjectLookup
}

internal sealed interface IntellijRelationDeclarationProjection {
    data class Projected(
        val declaration: PsiNamedElement,
        val evidence: CompilerGroundedSymbolEvidence,
    ) : IntellijRelationDeclarationProjection

    data object Unsupported : IntellijRelationDeclarationProjection
}

internal sealed interface IntellijReferenceTargetResult {
    data class Confirmed(val target: io.github.amichne.kast.relation.contract.RelationConfirmedReferenceTarget) :
        IntellijReferenceTargetResult

    data object Different : IntellijReferenceTargetResult

    data object Unresolved : IntellijReferenceTargetResult
}

internal enum class IntellijK2TargetConfirmation {
    EXACT_SUBJECT,
    DIFFERENT_SYMBOL,
    UNRESOLVED,
}

internal enum class IntellijK2DefinitionConfirmation {
    CONFIRMED,
    DIFFERENT_RELATION,
    UNSUPPORTED,
}

internal sealed interface IntellijK2ResolvedDeclaration {
    data class Found(val declaration: PsiNamedElement) : IntellijK2ResolvedDeclaration

    data object Unresolved : IntellijK2ResolvedDeclaration
}

internal sealed interface IntellijDetachedRelationFile {
    data class Found(val identity: SymbolDiscoveryFileIdentity) : IntellijDetachedRelationFile

    data object Unsupported : IntellijDetachedRelationFile
}
