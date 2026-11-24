package io.github.amichne.kast.relation.intellij

import com.intellij.psi.PsiNamedElement
import com.intellij.psi.PsiReference
import io.github.amichne.kast.relation.contract.RelationLimitation
import io.github.amichne.kast.relation.contract.RelationRequest
import io.github.amichne.kast.symbol.contract.ExactDeclarationTextRange
import io.github.amichne.kast.symbol.contract.SymbolLibraryPolicy
import io.github.amichne.kast.symbol.contract.SymbolSearchScope
import io.github.amichne.kast.workspace.intellij.read.IntellijReadCounter
import io.github.amichne.kast.workspace.intellij.read.IntellijReadObservation

/** Convention sites become ordinary callees only after K2 selects one source target for their role. */
internal class IntellijConventionCallees(
    private val request: RelationRequest,
    private val projection: IntellijK2RelationProjection,
    private val collector: IntellijRelationCollector,
    private val observation: IntellijReadObservation,
    private val incomplete: (CalleeProviderItem, RelationLimitation) -> Boolean,
    private val found: (CalleeProviderItem.Convention, PsiNamedElement) -> Boolean,
) {
    fun process(candidate: CalleeProviderItem.Convention): Boolean =
        when (val selected = projection.resolveConvention(candidate.reference, candidate.role)) {
            ConventionCallTarget.NoCall -> collector.dismissProviderItem()
            ConventionCallTarget.Binary -> dismissBinary(candidate)
            is ConventionCallTarget.Selected ->
                when (val target = selected.declaration) {
                    is IntellijK2ResolvedDeclaration.Found -> {
                        observation.count(IntellijReadCounter.RELATION_K2_CONFIRMED_TARGETS)
                        found(candidate, target.declaration)
                    }
                    is IntellijK2ResolvedDeclaration.SourceLess,
                    is IntellijK2ResolvedDeclaration.Unsupported ->
                        incomplete(candidate, RelationLimitation.UNSUPPORTED_ITEM)
                    IntellijK2ResolvedDeclaration.InvokeReceiver,
                    is IntellijK2ResolvedDeclaration.FunctionInvocation,
                    is IntellijK2ResolvedDeclaration.ParameterInvocation,
                    IntellijK2ResolvedDeclaration.Unresolved -> {
                        observation.count(IntellijReadCounter.RELATION_K2_UNAVAILABLE_TARGETS)
                        incomplete(candidate, RelationLimitation.UNRESOLVED_TARGET)
                    }
                }
        }

    /** Operators on binary types must not consume the capacity of a domain that excludes libraries. */
    private fun dismissBinary(candidate: CalleeProviderItem.Convention): Boolean {
        if ((request.searchScope as? SymbolSearchScope.Workspace)?.libraries == SymbolLibraryPolicy.INCLUDE)
            return incomplete(candidate, RelationLimitation.UNSUPPORTED_ITEM)
        observation.count(IntellijReadCounter.SCOPE_FILTERED)
        return collector.dismissProviderItem()
    }
}

internal fun CallOwnershipFailure.callbackPolicy(): NativeCallbackPolicy =
    when (this) {
        is CallOwnershipFailure.ExcludedCallback -> NativeCallbackPolicy.Excluded(this)
        is CallOwnershipFailure.Incomplete -> NativeCallbackPolicy.Unavailable(this)
    }

internal fun producerInvocationRange(reference: PsiReference, target: PsiNamedElement): ExactDeclarationTextRange? {
    if (target !is org.jetbrains.kotlin.psi.KtNamedFunction && target !is org.jetbrains.kotlin.psi.KtConstructor<*>)
        return null
    val element = reference.element
    // A convention site is never the callee of the call expression that encloses it.
    if (element.isConventionCallSite()) return null
    val call =
        com.intellij.psi.util.PsiTreeUtil.getParentOfType(
            element,
            org.jetbrains.kotlin.psi.KtCallExpression::class.java,
            false,
        ) ?: return null
    if (element !== call && call.calleeExpression?.textRange?.contains(element.textRange) != true) return null
    return sourceValueRange(call.valueInvocationExpression())
}
