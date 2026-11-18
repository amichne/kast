@file:OptIn(
    org.jetbrains.kotlin.analysis.api.KaExperimentalApi::class,
    org.jetbrains.kotlin.psi.KtExperimentalApi::class,
)

package io.github.amichne.kast.relation.intellij

import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiNamedElement
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.RelationEndpoint
import io.github.amichne.kast.relation.contract.ReviewedArtifactEvidence
import io.github.amichne.kast.relation.contract.ReviewedCallValueBinding
import io.github.amichne.kast.relation.contract.ReviewedCallbackValueFailure
import io.github.amichne.kast.relation.contract.ReviewedLibraryArtifact
import io.github.amichne.kast.relation.contract.ReviewedOrdinaryLibraryCallable
import io.github.amichne.kast.relation.contract.ReviewedPrimitiveArtifact
import io.github.amichne.kast.relation.contract.ReviewedPrimitiveFamily
import io.github.amichne.kast.relation.contract.ReviewedPrimitiveRule
import io.github.amichne.kast.relation.contract.ValueExecutionOwner
import io.github.amichne.kast.relation.contract.ValueInvocation
import io.github.amichne.kast.symbol.contract.CanonicalCompilerSignature
import io.github.amichne.kast.symbol.contract.CanonicalCompilerType
import io.github.amichne.kast.workspace.intellij.read.IntellijReadCall
import io.github.amichne.kast.workspace.intellij.read.IntellijReadTermination
import io.github.amichne.kast.workspace.intellij.read.call
import io.github.amichne.kast.workspace.intellij.read.observedAnalyze
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.resolution.KaSingleCall
import org.jetbrains.kotlin.analysis.api.symbols.KaSymbolOrigin
import org.jetbrains.kotlin.analysis.api.types.KaClassType
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtBinaryExpression
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtDestructuringDeclarationEntry
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtQualifiedExpression

/** Actual compiler selection and exact artifacts admit an ordinary reviewed call. */
internal fun readReviewedTupleBinding(
    expression: KtExpression,
    execution: ValueExecutionOwner,
    context: IntellijCallbackFlowContext,
): Refinement<ReviewedCallValueBinding, ReviewedCallbackValueFailure> =
    context.observation.call(IntellijReadCall.VALUE_REVIEWED_CALL_ADMISSION) {
        val result = reviewedTupleBinding(expression, execution, context)
        context.observation.terminated(
            when (result) {
                is Refinement.Refined -> IntellijReadTermination.VALUE_REVIEWED_CALL_ADMITTED
                is Refinement.Rejected -> result.failure.termination()
            }
        )
        result
    }

private fun reviewedTupleBinding(
    expression: KtExpression,
    execution: ValueExecutionOwner,
    context: IntellijCallbackFlowContext,
): Refinement<ReviewedCallValueBinding, ReviewedCallbackValueFailure> {
    when (val permit = reviewedPermit(context)) {
        is Refinement.Refined -> Unit
        is Refinement.Rejected -> return permit
    }
    val selected =
        when (
            val read =
                context.observation.observedAnalyze(expression) {
                    reviewedOrdinaryTarget(expression, context)
                }
        ) {
            is Refinement.Refined -> read.value
            is Refinement.Rejected -> return read
        }
    val artifact =
        when (val admitted = selected.artifact(expression, context)) {
            is Refinement.Refined -> admitted.value
            is Refinement.Rejected -> return admitted
        }
    val invocation =
        when (val read = reviewedOrdinaryInvocation(expression, execution, selected, context)) {
            is Refinement.Refined -> read.value
            is Refinement.Rejected -> return read
        }
    return ReviewedCallValueBinding.admit(invocation, selected.callable, artifact)
}

private fun NativeReviewedOrdinaryTarget.artifact(
    expression: KtExpression,
    context: IntellijCallbackFlowContext,
): Refinement<ReviewedArtifactEvidence, ReviewedCallbackValueFailure> {
    if (callable.primitiveRule?.family == ReviewedPrimitiveFamily.MEMBER) {
        val binary =
            com.intellij.openapi.vfs.VfsUtilCore.getVirtualFileForJar(file)
                ?: return Refinement.Rejected(ReviewedCallbackValueFailure.ARTIFACT_UNAVAILABLE)
        return when (val digest = reviewedArtifactDigest(binary, context)) {
            is Refinement.Refined -> ReviewedPrimitiveArtifact.fromBuiltinMetadata(callable, digest.value)
            is Refinement.Rejected -> digest
        }
    }
    val digests =
        when (
            val read =
                readReviewedLibraryArtifact(
                    expression,
                    file,
                    callable.sourceEntry,
                    context,
                )
        ) {
            is Refinement.Refined -> read.value
            is Refinement.Rejected -> return read
        }
    return ReviewedLibraryArtifact.admit(callable, digests.binary, digests.sources)
}

private fun reviewedOrdinaryInvocation(
    expression: KtExpression,
    execution: ValueExecutionOwner,
    selected: NativeReviewedOrdinaryTarget,
    context: IntellijCallbackFlowContext,
): Refinement<ValueInvocation, ReviewedCallbackValueFailure> {
    val endpoint =
        when (
            val admitted =
                RelationEndpoint.resolve(
                    execution.enclosing.lease,
                    context.scope.request.searchScope,
                    selected.callable.target,
                    context.scope.request.searchConstraints,
                )
        ) {
            is Refinement.Refined -> admitted.value
            is Refinement.Rejected -> return Refinement.Rejected(ReviewedCallbackValueFailure.OWNER_MISMATCH)
        }
    return when (
        val admitted =
            if (endpoint.signature is CanonicalCompilerSignature.Property)
                ValueInvocation.getterFromCompiler(
                    execution.enclosing,
                    sourceValueRange(expression),
                    endpoint,
                    execution,
                )
            else ValueInvocation.fromCompiler(execution.enclosing, sourceValueRange(expression), endpoint, execution)
    ) {
        is Refinement.Refined -> admitted
        is Refinement.Rejected -> return Refinement.Rejected(ReviewedCallbackValueFailure.OWNER_MISMATCH)
    }
}

private fun KaSession.reviewedOrdinaryTarget(
    expression: KtExpression,
    context: IntellijCallbackFlowContext,
): Refinement<NativeReviewedOrdinaryTarget, ReviewedCallbackValueFailure> {
    val call =
        resolvedReviewedOrdinaryCall(expression)
            ?: return Refinement.Rejected(ReviewedCallbackValueFailure.CALLABLE_MISMATCH)
    val symbol = call.signature.symbol
    if (symbol.origin != KaSymbolOrigin.LIBRARY)
        return Refinement.Rejected(ReviewedCallbackValueFailure.CALLABLE_MISMATCH)
    val declaration =
        symbol.psi as? PsiNamedElement ?: return Refinement.Rejected(ReviewedCallbackValueFailure.CALLABLE_MISMATCH)
    val file =
        declaration.containingFile?.virtualFile
            ?: return Refinement.Rejected(ReviewedCallbackValueFailure.ARTIFACT_UNAVAILABLE)
    val evidence =
        contractTarget(context, symbol, declaration, file)
            ?: return Refinement.Rejected(ReviewedCallbackValueFailure.CALLABLE_MISMATCH)
    val rule =
        ReviewedPrimitiveRule.entries.singleOrNull { it.callable == symbol.callableId?.asSingleFqName()?.asString() }
    val selected =
        if (rule == null) ReviewedOrdinaryLibraryCallable.admit(evidence)
        else admitPrimitiveCall(expression, symbol.returnType, evidence)
    return when (val admitted = selected) {
        is Refinement.Refined -> Refinement.Refined(NativeReviewedOrdinaryTarget(admitted.value, file))
        is Refinement.Rejected -> admitted
    }
}

private fun KaSession.admitPrimitiveCall(
    expression: KtExpression,
    returnType: org.jetbrains.kotlin.analysis.api.types.KaType,
    evidence: io.github.amichne.kast.symbol.contract.CompilerGroundedSymbolEvidence,
): Refinement<ReviewedOrdinaryLibraryCallable, ReviewedCallbackValueFailure> {
    if (
        expression is KtBinaryExpression &&
            expression.operationToken !in setOf(KtTokens.PLUS, KtTokens.MINUS, KtTokens.MUL)
    )
        return Refinement.Rejected(ReviewedCallbackValueFailure.CALLABLE_MISMATCH)
    val result =
        returnType as? KaClassType ?: return Refinement.Rejected(ReviewedCallbackValueFailure.CALLABLE_MISMATCH)
    if (result.isMarkedNullable) return Refinement.Rejected(ReviewedCallbackValueFailure.CALLABLE_MISMATCH)
    return when (val detached = CanonicalCompilerType.fromCompiler(result.classId.asString())) {
        is Refinement.Refined -> ReviewedOrdinaryLibraryCallable.admitPrimitive(evidence, detached.value)
        is Refinement.Rejected -> return Refinement.Rejected(ReviewedCallbackValueFailure.CALLABLE_MISMATCH)
    }
}

internal fun KaSession.resolvedReviewedOrdinaryCall(expression: KtExpression): KaSingleCall<*, *>? =
    when (expression) {
        is KtBinaryExpression -> expression.resolveCall() as? KaSingleCall<*, *>
        is KtCallExpression -> expression.resolveCall()
        is KtDestructuringDeclarationEntry -> expression.resolveCall()
        is KtQualifiedExpression ->
            when (val selector = expression.selectorExpression) {
                is KtCallExpression -> selector.resolveCall()
                is KtNameReferenceExpression -> selector.resolveCall()
                else -> null
            }
        else -> null
    }

private data class NativeReviewedOrdinaryTarget(val callable: ReviewedOrdinaryLibraryCallable, val file: VirtualFile)
