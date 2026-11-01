@file:OptIn(org.jetbrains.kotlin.analysis.api.KaExperimentalApi::class)

package io.github.amichne.kast.relation.intellij

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.ReviewedCallValueBinding
import io.github.amichne.kast.relation.contract.ReviewedLibraryOperation
import io.github.amichne.kast.relation.contract.ValueExecutionOwner
import io.github.amichne.kast.relation.contract.ValueFlowUnsupportedCause
import io.github.amichne.kast.relation.contract.ValueReceiverKind
import io.github.amichne.kast.workspace.intellij.read.observedAnalyze
import org.jetbrains.kotlin.analysis.api.symbols.KaNamedFunctionSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaSymbolOrigin
import org.jetbrains.kotlin.psi.KtDestructuringDeclarationEntry

/** A reviewed component borrows its caller's work and expiry capability. */
internal sealed interface NativeReviewedComponentScope {
    data object Unavailable : NativeReviewedComponentScope

    data class Available(val execution: ValueExecutionOwner, val context: IntellijCallbackFlowContext) :
        NativeReviewedComponentScope
}

/** Library component selection supplies correspondence, never stored-field or getter-purity evidence. */
internal fun nativeReviewedComponentTarget(
    entry: KtDestructuringDeclarationEntry,
    target: NativeComponentTarget,
    scope: NativeReviewedComponentScope,
): Refinement<NativeComponentTarget, ValueFlowUnsupportedCause> {
    if (scope !is NativeReviewedComponentScope.Available || target !is NativeComponentTarget.Source)
        return Refinement.Refined(target)
    val candidate =
        scope.context.observation.observedAnalyze(entry) {
            val symbol = entry.resolveCall()?.signature?.symbol as? KaNamedFunctionSymbol
            symbol?.origin == KaSymbolOrigin.LIBRARY &&
                symbol.callableId?.asSingleFqName()?.asString() in
                    setOf("kotlin.collections.component1", "kotlin.collections.component2")
        }
    if (!candidate) return Refinement.Refined(target)
    val binding =
        when (val selected = readReviewedTupleBinding(entry, scope.execution, scope.context)) {
            is Refinement.Refined -> selected.value
            is Refinement.Rejected -> return Refinement.Rejected(selected.failure.valueFlowCause())
        }
    if (
        binding.selected.operation !in
            setOf(
                ReviewedLibraryOperation.MAP_ENTRY_COMPONENT1,
                ReviewedLibraryOperation.MAP_ENTRY_COMPONENT2,
            ) || target.receiver != ValueReceiverKind.EXTENSION
    )
        return Refinement.Rejected(ValueFlowUnsupportedCause.UNMODELED_CALL)
    return Refinement.Refined(NativeComponentTarget.Reviewed(target.declaration, target.receiver, binding))
}

/** Component re-entry reacquires every invoked sibling under the same allowance. */
internal fun nativeReviewedComponentBinding(
    entry: KtDestructuringDeclarationEntry,
    execution: ValueExecutionOwner,
    scope: CompiledRelationScope,
    projection: IntellijK2RelationProjection,
    context: IntellijCallbackFlowContext,
): Refinement<ReviewedCallValueBinding, ValueFlowUnsupportedCause> {
    val declaration =
        entry.parent as? org.jetbrains.kotlin.psi.KtDestructuringDeclaration
            ?: return Refinement.Rejected(ValueFlowUnsupportedCause.UNSUPPORTED_EXPRESSION)
    val inventory =
        when (
            val admitted =
                NativeDestructuringAdmission.readReviewed(
                    declaration = declaration,
                    scope = scope,
                    projection = projection,
                    permit = {
                        when (val allowed = reviewedPermit(context)) {
                            is Refinement.Refined -> allowed
                            is Refinement.Rejected -> Refinement.Rejected(allowed.failure.valueFlowCause())
                        }
                    },
                    reviewed = NativeReviewedComponentScope.Available(execution, context),
                )
        ) {
            is Refinement.Refined -> admitted.value
            is Refinement.Rejected -> return admitted
        }
    return when (val selected = inventory.target(entry)) {
        is Refinement.Rejected -> selected
        is Refinement.Refined ->
            when (val target = selected.value) {
                is NativeComponentTarget.Reviewed -> Refinement.Refined(target.binding)
                else -> Refinement.Rejected(ValueFlowUnsupportedCause.UNMODELED_CALL)
            }
    }
}
