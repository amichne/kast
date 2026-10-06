package io.github.amichne.kast.relation.intellij

import com.intellij.psi.PsiElement
import com.intellij.psi.PsiNamedElement
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.CallbackParameterIdentity
import io.github.amichne.kast.relation.contract.RelationCallableObservation
import io.github.amichne.kast.relation.contract.RelationCallableTarget
import io.github.amichne.kast.relation.contract.RelationLimitation
import io.github.amichne.kast.relation.contract.RelationRequest
import io.github.amichne.kast.relation.contract.SourceLessCallable
import io.github.amichne.kast.relation.contract.SourceLessCallableDisposition
import io.github.amichne.kast.relation.contract.SourceLessCallableModuleKind
import io.github.amichne.kast.symbol.contract.SymbolLibraryPolicy
import io.github.amichne.kast.symbol.contract.SymbolSearchScope

/** Emits exact symbolic callees and source-less compiler boundaries on the current native read. */
internal class IntellijCallableObservationEmitter(
    private val request: RelationRequest,
    private val subject: PsiNamedElement,
    private val projection: IntellijK2RelationProjection,
    scope: CompiledRelationScope,
    private val collector: IntellijRelationCollector,
    private val incomplete: (CalleeProviderItem, RelationLimitation) -> Boolean,
) {
    private val callableContext = IntellijCallbackFlowContext(scope, projection, collector::admitCallbackWork)

    fun retainParameterInvocation(
        candidate: CalleeProviderItem.Reference,
        resolved: IntellijK2ResolvedDeclaration.ParameterInvocation,
    ): Boolean {
        val evidence =
            when (val result = projection.project(resolved.callable)) {
                is IntellijRelationDeclarationProjection.Projected -> result.evidence
                IntellijRelationDeclarationProjection.Unsupported ->
                    return incomplete(candidate, RelationLimitation.UNSUPPORTED_ITEM)
            }
        val endpoint =
            callableContext.endpoint(evidence) ?: return incomplete(candidate, RelationLimitation.UNSUPPORTED_ITEM)
        val parameter =
            callableContext.occurrence(resolved.parameter)
                ?: return incomplete(candidate, RelationLimitation.UNSUPPORTED_ITEM)
        val identity =
            when (
                val admitted =
                    CallbackParameterIdentity.fromCompiler(
                        endpoint,
                        resolved.position,
                        parameter,
                    )
            ) {
                is Refinement.Refined -> admitted.value
                is Refinement.Rejected -> return incomplete(candidate, RelationLimitation.UNSUPPORTED_ITEM)
            }
        return retainCallable(
            candidate,
            resolved.call.valueInvocationExpression(),
            RelationCallableTarget.ParameterInvocation(identity),
        )
    }

    fun retainSourceLessCallable(
        candidate: CalleeProviderItem.Reference,
        callable: SourceLessCallable,
    ): Boolean {
        val libraries = (request.searchScope as? SymbolSearchScope.Workspace)?.libraries ?: SymbolLibraryPolicy.EXCLUDE
        val disposition =
            when (callable.moduleKind) {
                SourceLessCallableModuleKind.BUILTINS -> SourceLessCallableDisposition.BUILTIN_BOUNDARY
                SourceLessCallableModuleKind.LIBRARY ->
                    when (libraries) {
                        SymbolLibraryPolicy.EXCLUDE -> SourceLessCallableDisposition.LIBRARY_POLICY_EXCLUDED
                        SymbolLibraryPolicy.INCLUDE -> SourceLessCallableDisposition.LIBRARY_SOURCE_UNAVAILABLE
                    }
            }
        return retainCallable(
            candidate,
            candidate.reference.element,
            RelationCallableTarget.SourceLess(callable, disposition),
        )
    }

    private fun retainCallable(
        candidate: CalleeProviderItem.Reference,
        site: PsiElement,
        target: RelationCallableTarget,
    ): Boolean {
        val occurrence =
            callableContext.occurrence(site) ?: return incomplete(candidate, RelationLimitation.UNSUPPORTED_ITEM)
        val body = callableContext.owner(site) ?: return incomplete(candidate, RelationLimitation.UNSUPPORTED_ITEM)
        val lexicalOwner =
            when (val projected = projection.project(subject)) {
                is IntellijRelationDeclarationProjection.Projected -> projected.evidence
                IntellijRelationDeclarationProjection.Unsupported ->
                    return incomplete(candidate, RelationLimitation.UNSUPPORTED_ITEM)
            }
        return when (
            val admitted =
                RelationCallableObservation.fromNativeBoundary(
                    request,
                    occurrence,
                    lexicalOwner,
                    body,
                    target,
                )
        ) {
            is Refinement.Refined -> collector.acceptCallableObservation(admitted.value)
            is Refinement.Rejected -> collector.blockPartition(RelationLimitation.PROVIDER_INCOMPLETE)
        }
    }
}
