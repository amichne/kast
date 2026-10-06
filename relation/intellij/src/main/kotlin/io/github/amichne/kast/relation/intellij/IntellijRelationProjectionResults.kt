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

    data class ParameterInvocation(
        val callable: org.jetbrains.kotlin.psi.KtNamedFunction,
        val parameter: org.jetbrains.kotlin.psi.KtParameter,
        val position: io.github.amichne.kast.relation.contract.ValueArgumentPosition,
        val call: org.jetbrains.kotlin.psi.KtCallExpression,
    ) : IntellijK2ResolvedDeclaration

    data class SourceLess(val callable: io.github.amichne.kast.relation.contract.SourceLessCallable) :
        IntellijK2ResolvedDeclaration

    /** The value reference is an invoke receiver; the independently resolved invoke reference owns the callee. */
    data object InvokeReceiver : IntellijK2ResolvedDeclaration

    data class Unsupported(val cause: IntellijResolvedCallableFailure) : IntellijK2ResolvedDeclaration

    data object Unresolved : IntellijK2ResolvedDeclaration
}

internal enum class IntellijResolvedCallableFailure {
    COMPILER_IDENTITY_UNAVAILABLE,
    UNSUPPORTED_MODULE,
    UNSUPPORTED_ORIGIN,
    MODULE_IDENTITY_UNAVAILABLE,
    PARAMETER_OWNER_UNAVAILABLE,
    PARAMETER_POSITION_UNAVAILABLE,
}

internal sealed interface IntellijDetachedRelationFile {
    data class Found(val identity: SymbolDiscoveryFileIdentity) : IntellijDetachedRelationFile

    data object Unsupported : IntellijDetachedRelationFile
}
