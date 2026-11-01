@file:OptIn(org.jetbrains.kotlin.analysis.api.KaExperimentalApi::class)

package io.github.amichne.kast.relation.intellij

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.ReviewedCallbackValueBinding
import io.github.amichne.kast.relation.contract.ReviewedDirectFormalKey
import io.github.amichne.kast.relation.contract.ReviewedDistinctKeyFailure
import io.github.amichne.kast.relation.contract.ReviewedLibraryOperation
import io.github.amichne.kast.relation.contract.ValueArgumentPosition
import io.github.amichne.kast.relation.contract.ValueExecutionOwner
import io.github.amichne.kast.relation.contract.ValueFormalParameter
import io.github.amichne.kast.relation.contract.ValuePathSegment
import io.github.amichne.kast.relation.contract.ValueRole
import io.github.amichne.kast.relation.contract.ValueSite
import io.github.amichne.kast.workspace.intellij.read.observedAnalyze
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.resolution.KaExplicitReceiverValue
import org.jetbrains.kotlin.analysis.api.resolution.KaFunctionCall
import org.jetbrains.kotlin.analysis.api.symbols.KaFunctionSymbol
import org.jetbrains.kotlin.idea.references.KtReference
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtFunctionLiteral
import org.jetbrains.kotlin.psi.KtNameReferenceExpression

/** A sole tuple return and exact resolved formal forbid transformed or unrelated literal keys. */
internal fun readReviewedDirectKey(
    literal: KtFunctionLiteral,
    source: ValueSite,
    binding: ReviewedCallbackValueBinding,
    context: IntellijCallbackFlowContext,
): Refinement<ReviewedDirectFormalKey, NativeReviewedKeyFailure> {
    val component =
        source.valuePath.segments.firstOrNull() as? ValuePathSegment.PairComponent
            ?: return keyUnproved(ReviewedDistinctKeyFailure.TUPLE_MISMATCH)
    val statements =
        literal.bodyExpression?.statements ?: return keyUnproved(ReviewedDistinctKeyFailure.CALLBACK_SHAPE_UNSUPPORTED)
    val tuple = statements.singleOrNull() ?: return keyUnproved(ReviewedDistinctKeyFailure.CALLBACK_SHAPE_UNSUPPORTED)
    if (sourceValueRange(tuple) != component.origin.binding.invocation.range)
        return keyUnproved(ReviewedDistinctKeyFailure.TUPLE_MISMATCH)
    when (val permit = reviewedPermit(context)) {
        is Refinement.Refined -> Unit
        is Refinement.Rejected -> return Refinement.Rejected(permit.failure.keyFailure())
    }
    val owner = ValueExecutionOwner.reviewedCallback(binding)
    val selected =
        when (
            val read =
                context.observation.observedAnalyze(literal) {
                    directKeySelection(literal, tuple, owner, component.origin.binding.selected.operation)
                }
        ) {
            is Refinement.Refined -> read.value
            is Refinement.Rejected -> return read
        }
    val role =
        when (val read = nativeReviewedTupleRole(selected.expression, tuple, owner, context)) {
            is Refinement.Refined -> read.value
            is Refinement.Rejected -> return Refinement.Rejected(read.failure.keyFlowFailure())
        }
    val site =
        when (val admitted = selected.keySite(source, role, component)) {
            is Refinement.Refined -> admitted.value
            is Refinement.Rejected -> return keyUnproved(ReviewedDistinctKeyFailure.KEY_REFERENCE_MISMATCH)
        }
    return ReviewedDirectFormalKey.fromCompiler(
            binding = binding,
            resolvedFormal = selected.formal,
            tupleOrigin = component.origin,
            keyUse = site,
            bodyStatementRanges = statements.map(::sourceValueRange),
        )
        .keyRead()
}

private data class NativeDirectKeySelection(val expression: KtNameReferenceExpression, val formal: ValueFormalParameter)

private fun NativeDirectKeySelection.keySite(
    source: ValueSite,
    role: ValueRole,
    component: ValuePathSegment.PairComponent,
) =
    ValueSite.fromCompiler(
        enclosing = source.enclosing,
        range = sourceValueRange(expression),
        role = role,
        executionOwner = formal.executionOwner,
        callStack = component.origin.callStack,
    )

private fun KaSession.directKeySelection(
    literal: KtFunctionLiteral,
    tuple: KtExpression,
    owner: ValueExecutionOwner,
    operation: ReviewedLibraryOperation,
): Refinement<NativeDirectKeySelection, NativeReviewedKeyFailure> {
    val call =
        resolvedReviewedOrdinaryCall(tuple) as? KaFunctionCall<*>
            ?: return keyUnproved(ReviewedDistinctKeyFailure.TUPLE_MISMATCH)
    val expression =
        when (operation) {
            ReviewedLibraryOperation.TO -> (call.extensionReceiver as? KaExplicitReceiverValue)?.expression
            ReviewedLibraryOperation.PAIR_CONSTRUCTOR ->
                call.valueArgumentMapping.entries
                    .singleOrNull {
                        it.value.symbol == call.signature.symbol.valueParameters.firstOrNull()
                    }
                    ?.key
            else -> null
        }
            as? KtNameReferenceExpression ?: return keyUnproved(ReviewedDistinctKeyFailure.KEY_REFERENCE_MISMATCH)
    val callback = literal.symbol as? KaFunctionSymbol ?: return keyUnproved(ReviewedDistinctKeyFailure.FORMAL_MISMATCH)
    val expected =
        callback.valueParameters.singleOrNull() ?: return keyUnproved(ReviewedDistinctKeyFailure.FORMAL_MISMATCH)
    val actual = expression.references.filterIsInstance<KtReference>().singleOrNull()?.resolveToSymbol()
    if (actual != expected) return keyUnproved(ReviewedDistinctKeyFailure.KEY_REFERENCE_MISMATCH)
    val position =
        when (val parsed = ValueArgumentPosition.parse(0)) {
            is Refinement.Refined -> parsed.value
            is Refinement.Rejected -> return keyUnproved(ReviewedDistinctKeyFailure.FORMAL_MISMATCH)
        }
    return when (val formal = nativeCallbackInputParameter(literal, owner, position)) {
        is Refinement.Refined -> Refinement.Refined(NativeDirectKeySelection(expression, formal.value))
        is Refinement.Rejected -> keyUnproved(ReviewedDistinctKeyFailure.FORMAL_MISMATCH)
    }
}
