@file:OptIn(org.jetbrains.kotlin.analysis.api.KaExperimentalApi::class)

package io.github.amichne.kast.relation.intellij

import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.util.PsiTreeUtil
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.RelationCallableBody
import io.github.amichne.kast.relation.contract.RelationEndpoint
import io.github.amichne.kast.relation.contract.ReviewedCallbackValueBinding
import io.github.amichne.kast.relation.contract.ReviewedCallbackValueFailure
import io.github.amichne.kast.relation.contract.ReviewedLibraryArtifact
import io.github.amichne.kast.relation.contract.ReviewedLibraryCallable
import io.github.amichne.kast.relation.contract.ReviewedLibraryOperation
import io.github.amichne.kast.relation.contract.ValueArgumentPosition
import io.github.amichne.kast.relation.contract.ValueFlowUnsupportedCause
import io.github.amichne.kast.workspace.intellij.read.IntellijReadCall
import io.github.amichne.kast.workspace.intellij.read.IntellijReadTermination
import io.github.amichne.kast.workspace.intellij.read.call
import io.github.amichne.kast.workspace.intellij.read.observedAnalyze
import org.jetbrains.kotlin.analysis.api.symbols.KaNamedFunctionSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaSymbolOrigin
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtFunction
import org.jetbrains.kotlin.psi.KtLambdaExpression

/** Source and binary artifacts belong to the compiler-selected library entry. */
internal fun readReviewedCallbackValueBinding(
    literal: KtFunction,
    enclosing: RelationEndpoint,
    context: IntellijCallbackFlowContext,
): Refinement<ReviewedCallbackValueBinding, ReviewedCallbackValueFailure> =
    context.observation.call(IntellijReadCall.VALUE_REVIEWED_CALLBACK_ADMISSION) {
        val result = reviewedBinding(literal, enclosing, context)
        context.observation.terminated(
            when (result) {
                is Refinement.Refined -> IntellijReadTermination.VALUE_REVIEWED_CALLBACK_ADMITTED
                is Refinement.Rejected -> result.failure.termination()
            }
        )
        result
    }

private fun reviewedBinding(
    literal: KtFunction,
    enclosing: RelationEndpoint,
    context: IntellijCallbackFlowContext,
): Refinement<ReviewedCallbackValueBinding, ReviewedCallbackValueFailure> {
    when (val permit = reviewedPermit(context)) {
        is Refinement.Refined -> Unit
        is Refinement.Rejected -> return permit
    }
    val expression =
        literal.parent as? KtLambdaExpression ?: return Refinement.Rejected(ReviewedCallbackValueFailure.OWNER_MISMATCH)
    val call =
        PsiTreeUtil.getParentOfType(expression, KtCallExpression::class.java, true)
            ?: return Refinement.Rejected(ReviewedCallbackValueFailure.CALLABLE_MISMATCH)
    val selected =
        when (val read = context.observation.observedAnalyze(call) { reviewedTarget(context, call, expression) }) {
            is Refinement.Refined -> read.value
            is Refinement.Rejected -> return read
        }
    val body = context.anonymous(literal) ?: return Refinement.Rejected(ReviewedCallbackValueFailure.OWNER_MISMATCH)
    val supplier =
        context.owner(call) as? RelationCallableBody.Named
            ?: return Refinement.Rejected(ReviewedCallbackValueFailure.OWNER_MISMATCH)
    val occurrence =
        context.occurrence(call.valueInvocationExpression())
            ?: return Refinement.Rejected(ReviewedCallbackValueFailure.OWNER_MISMATCH)
    val artifact =
        when (val read = reviewedArtifact(call, selected, context)) {
            is Refinement.Refined -> read.value
            is Refinement.Rejected -> return read
        }
    return ReviewedCallbackValueBinding.admit(
        enclosing = enclosing,
        body = body,
        occurrence = occurrence,
        supplier = supplier,
        selected = selected.callable,
        artifact = artifact,
    )
}

private fun org.jetbrains.kotlin.analysis.api.KaSession.reviewedTarget(
    context: IntellijCallbackFlowContext,
    call: KtCallExpression,
    expression: KtLambdaExpression,
): Refinement<NativeReviewedTarget, ReviewedCallbackValueFailure> {
    val resolved = call.resolveCall() ?: return Refinement.Rejected(ReviewedCallbackValueFailure.CALLABLE_MISMATCH)
    val symbol =
        resolved.signature.symbol as? KaNamedFunctionSymbol
            ?: return Refinement.Rejected(ReviewedCallbackValueFailure.CALLABLE_MISMATCH)
    if (
        symbol.origin != KaSymbolOrigin.LIBRARY ||
            symbol.callableId?.asSingleFqName()?.asString() !in
                setOf(
                    "kotlin.collections.map",
                    "kotlin.collections.sortedBy",
                    "kotlin.collections.associate",
                    "kotlin.let",
                    "kotlin.also",
                )
    )
        return Refinement.Rejected(ReviewedCallbackValueFailure.CALLABLE_MISMATCH)
    val formal =
        resolved.valueArgumentMapping[expression]?.symbol
            ?: return Refinement.Rejected(ReviewedCallbackValueFailure.FORMAL_MISMATCH)
    if (formal.name.asString() != symbol.reviewedFormalName())
        return Refinement.Rejected(ReviewedCallbackValueFailure.FORMAL_MISMATCH)
    val position =
        when (val parsed = ValueArgumentPosition.parse(symbol.valueParameters.indexOf(formal))) {
            is Refinement.Refined -> parsed.value
            is Refinement.Rejected -> return Refinement.Rejected(ReviewedCallbackValueFailure.FORMAL_MISMATCH)
        }
    val declaration =
        symbol.psi as? PsiNamedElement ?: return Refinement.Rejected(ReviewedCallbackValueFailure.CALLABLE_MISMATCH)
    val file =
        declaration.containingFile?.virtualFile
            ?: return Refinement.Rejected(ReviewedCallbackValueFailure.ARTIFACT_UNAVAILABLE)
    val evidence =
        contractTarget(context, symbol, declaration, file)
            ?: return Refinement.Rejected(ReviewedCallbackValueFailure.CALLABLE_MISMATCH)
    return when (val admitted = ReviewedLibraryCallable.admit(evidence, position)) {
        is Refinement.Refined -> Refinement.Refined(NativeReviewedTarget(admitted.value, file))
        is Refinement.Rejected -> admitted
    }
}

private fun KaNamedFunctionSymbol.reviewedFormalName(): String? =
    when (callableId?.asSingleFqName()?.asString()) {
        "kotlin.collections.map",
        "kotlin.collections.associate" -> "transform"
        "kotlin.collections.sortedBy" -> "selector"
        "kotlin.let",
        "kotlin.also" -> "block"
        else -> null
    }

private fun reviewedArtifact(
    call: KtCallExpression,
    selected: NativeReviewedTarget,
    context: IntellijCallbackFlowContext,
): Refinement<ReviewedLibraryArtifact, ReviewedCallbackValueFailure> {
    val entry =
        when (selected.callable.operation) {
            ReviewedLibraryOperation.ITERABLE_MAP,
            ReviewedLibraryOperation.ITERABLE_SORTED_BY,
            ReviewedLibraryOperation.ITERABLE_ASSOCIATE -> "commonMain/generated/_Collections.kt"
            ReviewedLibraryOperation.LET,
            ReviewedLibraryOperation.ALSO -> "commonMain/kotlin/util/Standard.kt"
            ReviewedLibraryOperation.TO,
            ReviewedLibraryOperation.PAIR_CONSTRUCTOR,
            ReviewedLibraryOperation.PAIR_FIRST,
            ReviewedLibraryOperation.PAIR_SECOND ->
                return Refinement.Rejected(ReviewedCallbackValueFailure.CALLABLE_MISMATCH)
        }
    return when (val read = readReviewedLibraryArtifact(call, selected.file, entry, context)) {
        is Refinement.Refined -> ReviewedLibraryArtifact.admit(selected.callable, read.value.binary, read.value.sources)
        is Refinement.Rejected -> read
    }
}

internal fun ReviewedCallbackValueFailure.valueFlowCause(): ValueFlowUnsupportedCause =
    when (this) {
        ReviewedCallbackValueFailure.WORK_LIMIT_REACHED -> ValueFlowUnsupportedCause.WORK_LIMIT_REACHED
        ReviewedCallbackValueFailure.TIME_LIMIT_REACHED -> ValueFlowUnsupportedCause.TIME_LIMIT_REACHED
        ReviewedCallbackValueFailure.ARTIFACT_UNAVAILABLE,
        ReviewedCallbackValueFailure.ARTIFACT_CHANGED,
        ReviewedCallbackValueFailure.ARTIFACT_MISMATCH,
        ReviewedCallbackValueFailure.CALLABLE_MISMATCH,
        ReviewedCallbackValueFailure.FORMAL_MISMATCH,
        ReviewedCallbackValueFailure.OWNER_MISMATCH,
        ReviewedCallbackValueFailure.BASIS_MISMATCH,
        ReviewedCallbackValueFailure.RETURN_MISMATCH -> ValueFlowUnsupportedCause.NESTED_EXECUTION
    }

internal fun ReviewedCallbackValueFailure.termination(): IntellijReadTermination =
    when (this) {
        ReviewedCallbackValueFailure.ARTIFACT_UNAVAILABLE -> IntellijReadTermination.VALUE_REVIEWED_ARTIFACT_UNAVAILABLE
        ReviewedCallbackValueFailure.ARTIFACT_CHANGED -> IntellijReadTermination.VALUE_REVIEWED_ARTIFACT_CHANGED
        ReviewedCallbackValueFailure.ARTIFACT_MISMATCH -> IntellijReadTermination.VALUE_REVIEWED_ARTIFACT_MISMATCH
        ReviewedCallbackValueFailure.CALLABLE_MISMATCH -> IntellijReadTermination.VALUE_REVIEWED_CALLABLE_MISMATCH
        ReviewedCallbackValueFailure.FORMAL_MISMATCH -> IntellijReadTermination.VALUE_REVIEWED_FORMAL_MISMATCH
        ReviewedCallbackValueFailure.OWNER_MISMATCH -> IntellijReadTermination.VALUE_REVIEWED_OWNER_MISMATCH
        ReviewedCallbackValueFailure.BASIS_MISMATCH -> IntellijReadTermination.VALUE_REVIEWED_BASIS_MISMATCH
        ReviewedCallbackValueFailure.RETURN_MISMATCH -> IntellijReadTermination.VALUE_REVIEWED_RETURN_MISMATCH
        ReviewedCallbackValueFailure.WORK_LIMIT_REACHED -> IntellijReadTermination.WORK_LIMIT
        ReviewedCallbackValueFailure.TIME_LIMIT_REACHED -> IntellijReadTermination.TIME_LIMIT
    }

private data class NativeReviewedTarget(
    val callable: ReviewedLibraryCallable,
    val file: VirtualFile,
)
