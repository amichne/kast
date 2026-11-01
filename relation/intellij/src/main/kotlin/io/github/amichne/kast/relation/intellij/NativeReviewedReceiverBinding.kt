@file:OptIn(org.jetbrains.kotlin.analysis.api.KaExperimentalApi::class)

package io.github.amichne.kast.relation.intellij

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.RelationEndpoint
import io.github.amichne.kast.relation.contract.ReviewedCallbackValueBinding
import io.github.amichne.kast.relation.contract.ReviewedLibraryOperation
import io.github.amichne.kast.relation.contract.ValueArgumentPosition
import io.github.amichne.kast.relation.contract.ValueFlowUnsupportedCause
import io.github.amichne.kast.relation.contract.ValueReceiverKind
import io.github.amichne.kast.symbol.contract.ExactDeclarationTextRange
import io.github.amichne.kast.workspace.intellij.read.observedAnalyze
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtFunctionLiteral
import org.jetbrains.kotlin.psi.KtQualifiedExpression

internal fun nativeReviewedReceiverBinding(
    receiverRange: ExactDeclarationTextRange,
    enclosing: RelationEndpoint,
    qualified: KtQualifiedExpression,
    context: IntellijCallbackFlowContext,
): Refinement<ReviewedCallbackValueBinding, ValueFlowUnsupportedCause> {
    if (sourceValueRange(qualified.receiverExpression) != receiverRange) return receiverUnsupported()
    val call = qualified.selectorExpression as? KtCallExpression ?: return receiverUnsupported()
    val receiver = reviewedReceiverKind(call, qualified, context)
    if (receiver != ValueReceiverKind.EXTENSION) return receiverUnsupported()
    val literal =
        when (val read = reviewedReceiverLiteral(qualified, context)) {
            is Refinement.Refined -> read.value
            is Refinement.Rejected -> return read
        }
    return when (val read = readReviewedCallbackValueBinding(literal, enclosing, context)) {
        is Refinement.Refined ->
            when (read.value.selected.operation) {
                ReviewedLibraryOperation.RUN_RECEIVER,
                ReviewedLibraryOperation.APPLY,
                ReviewedLibraryOperation.ALSO,
                ReviewedLibraryOperation.ITERABLE_MAP,
                ReviewedLibraryOperation.ITERABLE_GROUP_BY,
                ReviewedLibraryOperation.ITERABLE_GROUP_BY_TRANSFORM,
                ReviewedLibraryOperation.ITERABLE_FILTER,
                ReviewedLibraryOperation.ITERABLE_MAP_NOT_NULL,
                ReviewedLibraryOperation.ITERABLE_SORTED_BY,
                ReviewedLibraryOperation.ITERABLE_ASSOCIATE,
                ReviewedLibraryOperation.MAP_VALUES -> read
                ReviewedLibraryOperation.RUN_TOP_LEVEL,
                ReviewedLibraryOperation.WITH,
                ReviewedLibraryOperation.LET,
                ReviewedLibraryOperation.TO,
                ReviewedLibraryOperation.PAIR_CONSTRUCTOR,
                ReviewedLibraryOperation.PAIR_FIRST,
                ReviewedLibraryOperation.PAIR_SECOND,
                ReviewedLibraryOperation.MAP_ENTRY_KEY,
                ReviewedLibraryOperation.MAP_ENTRY_VALUE,
                ReviewedLibraryOperation.MAP_ENTRY_COMPONENT1,
                ReviewedLibraryOperation.MAP_ENTRY_COMPONENT2 -> receiverUnsupported()
            }
        is Refinement.Rejected -> Refinement.Rejected(read.failure.valueFlowCause())
    }
}

private fun reviewedReceiverKind(
    call: KtCallExpression,
    qualified: KtQualifiedExpression,
    context: IntellijCallbackFlowContext,
): ValueReceiverKind? =
    context.observation.observedAnalyze(call) {
        val resolved = call.resolveCall() ?: return@observedAnalyze null
        val symbol =
            resolved.signature.symbol as? org.jetbrains.kotlin.analysis.api.symbols.KaNamedFunctionSymbol
                ?: return@observedAnalyze null
        if (
            symbol.origin != org.jetbrains.kotlin.analysis.api.symbols.KaSymbolOrigin.LIBRARY ||
                symbol.callableId?.asSingleFqName()?.asString() !in
                    setOf(
                        "kotlin.also",
                        "kotlin.run",
                        "kotlin.apply",
                        "kotlin.collections.map",
                        "kotlin.collections.filter",
                        "kotlin.collections.mapNotNull",
                        "kotlin.collections.sortedBy",
                        "kotlin.collections.associate",
                        "kotlin.collections.mapValues",
                        "kotlin.collections.groupBy",
                    )
        )
            return@observedAnalyze null
        nativeExplicitReceiverKind(
            qualified.receiverExpression,
            resolved.extensionReceiver,
            resolved.dispatchReceiver,
        )
    }

internal fun reviewedReceiverLiteral(
    qualified: KtQualifiedExpression,
    context: IntellijCallbackFlowContext,
): Refinement<KtFunctionLiteral, ValueFlowUnsupportedCause> =
    when (val position = ValueArgumentPosition.parse(0)) {
        is Refinement.Refined -> nativeReviewedReceiverLiteral(qualified, position.value, context)
        is Refinement.Rejected -> receiverUnsupported()
    }

private fun receiverUnsupported() = Refinement.Rejected(ValueFlowUnsupportedCause.EXTERNAL_CALL)
