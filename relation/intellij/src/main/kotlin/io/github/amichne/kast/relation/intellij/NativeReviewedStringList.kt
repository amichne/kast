@file:OptIn(org.jetbrains.kotlin.analysis.api.KaExperimentalApi::class)

package io.github.amichne.kast.relation.intellij

import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiNamedElement
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.RelationBudget
import io.github.amichne.kast.relation.contract.RelationEndpoint
import io.github.amichne.kast.relation.contract.ReviewedDistinctKeyFailure
import io.github.amichne.kast.relation.contract.ReviewedStringListArtifact
import io.github.amichne.kast.relation.contract.ReviewedStringListInput
import io.github.amichne.kast.relation.contract.ReviewedStringVarargArgument
import io.github.amichne.kast.relation.contract.ReviewedVarargFormal
import io.github.amichne.kast.relation.contract.ValueArgumentPosition
import io.github.amichne.kast.relation.contract.ValueExecutionOwner
import io.github.amichne.kast.relation.contract.ValueInvocation
import io.github.amichne.kast.symbol.contract.ExactDeclarationTextRange
import io.github.amichne.kast.workspace.intellij.read.observedAnalyze
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.base.KaConstantValue
import org.jetbrains.kotlin.analysis.api.resolution.KaFunctionCall
import org.jetbrains.kotlin.analysis.api.symbols.KaNamedFunctionSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaSymbolOrigin
import org.jetbrains.kotlin.psi.KtCallExpression

/** Only a complete direct vararg inventory can establish immutable literal-list keys. */
internal fun readReviewedStringList(
    call: KtCallExpression,
    owner: ValueExecutionOwner,
    context: IntellijCallbackFlowContext,
): Refinement<ReviewedStringListInput, NativeReviewedKeyFailure> {
    when (val permit = reviewedPermit(context)) {
        is Refinement.Refined -> Unit
        is Refinement.Rejected -> return Refinement.Rejected(permit.failure.keyFailure())
    }
    val observed =
        when (val read = context.observation.observedAnalyze(call) { stringListObservation(call, owner, context) }) {
            is Refinement.Refined -> read.value
            is Refinement.Rejected -> return read
        }
    val digests =
        when (
            val read =
                readReviewedLibraryArtifact(
                    call,
                    observed.file,
                    "commonMain/kotlin/collections/Collections.kt",
                    context,
                )
        ) {
            is Refinement.Refined -> read.value
            is Refinement.Rejected -> {
                context.observation.terminated(read.failure.termination())
                return Refinement.Rejected(read.failure.keyFailure())
            }
        }
    val artifact =
        when (val admitted = ReviewedStringListArtifact.admit(observed.formal, digests.binary, digests.sources)) {
            is Refinement.Refined -> admitted.value
            is Refinement.Rejected -> return keyUnproved(admitted.failure)
        }
    return ReviewedStringListInput.fromCompiler(
            formal = observed.formal,
            artifact = artifact,
            rawActualArgumentCount = observed.argumentCount,
            actualArgumentRanges = observed.ranges,
            arguments = observed.arguments,
        )
        .keyRead()
}

private data class NativeStringListObservation(
    val formal: ReviewedVarargFormal,
    val argumentCount: Int,
    val ranges: List<ExactDeclarationTextRange>,
    val arguments: List<ReviewedStringVarargArgument>,
    val file: VirtualFile,
)

private fun KaSession.stringListObservation(
    call: KtCallExpression,
    owner: ValueExecutionOwner,
    context: IntellijCallbackFlowContext,
): Refinement<NativeStringListObservation, NativeReviewedKeyFailure> {
    val resolved = call.resolveCall() ?: return keyUnproved(ReviewedDistinctKeyFailure.CALLABLE_MISMATCH)
    val symbol =
        resolved.signature.symbol as? KaNamedFunctionSymbol
            ?: return keyUnproved(ReviewedDistinctKeyFailure.CALLABLE_MISMATCH)
    if (
        symbol.origin != KaSymbolOrigin.LIBRARY ||
            symbol.callableId?.asSingleFqName()?.asString() != "kotlin.collections.listOf"
    )
        return keyUnproved(ReviewedDistinctKeyFailure.CALLABLE_MISMATCH)
    val parameter =
        symbol.valueParameters.singleOrNull() ?: return keyUnproved(ReviewedDistinctKeyFailure.FORMAL_MISMATCH)
    if (!parameter.isVararg) return keyUnproved(ReviewedDistinctKeyFailure.NOT_VARARG)
    val declaration = symbol.psi as? PsiNamedElement ?: return keyUnproved(ReviewedDistinctKeyFailure.CALLABLE_MISMATCH)
    val file =
        declaration.containingFile?.virtualFile ?: return keyUnproved(ReviewedDistinctKeyFailure.ARTIFACT_MISMATCH)
    val formal =
        when (val admitted = stringListFormal(call, owner, context, symbol, declaration, file)) {
            is Refinement.Refined -> admitted.value
            is Refinement.Rejected -> return admitted
        }
    val actualArgumentCount = call.valueArguments.size
    val ranges =
        when (val admitted = stringListRanges(call, context)) {
            is Refinement.Refined -> admitted.value
            is Refinement.Rejected -> return admitted
        }
    val arguments =
        when (val admitted = stringListArguments(call, resolved, formal, context)) {
            is Refinement.Refined -> admitted.value
            is Refinement.Rejected -> return admitted
        }
    return Refinement.Refined(
        NativeStringListObservation(
            formal,
            actualArgumentCount,
            ranges,
            arguments,
            file,
        )
    )
}

private fun KaSession.stringListFormal(
    call: KtCallExpression,
    owner: ValueExecutionOwner,
    context: IntellijCallbackFlowContext,
    symbol: KaNamedFunctionSymbol,
    declaration: PsiNamedElement,
    file: VirtualFile,
): Refinement<ReviewedVarargFormal, NativeReviewedKeyFailure> {
    val target =
        contractTarget(context, symbol, declaration, file)
            ?: return keyUnproved(ReviewedDistinctKeyFailure.CALLABLE_MISMATCH)
    val endpoint =
        when (
            val admitted =
                RelationEndpoint.resolve(
                    owner.enclosing.lease,
                    context.scope.request.searchScope,
                    target,
                    context.scope.request.searchConstraints,
                )
        ) {
            is Refinement.Refined -> admitted.value
            is Refinement.Rejected -> return keyUnproved(ReviewedDistinctKeyFailure.INVOCATION_MISMATCH)
        }
    val invocation =
        when (val admitted = ValueInvocation.fromCompiler(owner.enclosing, sourceValueRange(call), endpoint, owner)) {
            is Refinement.Refined -> admitted.value
            is Refinement.Rejected -> return keyUnproved(ReviewedDistinctKeyFailure.INVOCATION_MISMATCH)
        }
    val position =
        when (val parsed = ValueArgumentPosition.parse(0)) {
            is Refinement.Refined -> parsed.value
            is Refinement.Rejected -> return keyUnproved(ReviewedDistinctKeyFailure.FORMAL_MISMATCH)
        }
    return ReviewedVarargFormal.fromCompiler(invocation, target, position).keyRead()
}

private fun KaSession.stringListArguments(
    call: KtCallExpression,
    resolved: KaFunctionCall<*>,
    formal: ReviewedVarargFormal,
    context: IntellijCallbackFlowContext,
): Refinement<List<ReviewedStringVarargArgument>, NativeReviewedKeyFailure> {
    val values = mutableListOf<ReviewedStringVarargArgument>()
    var retainedBytes = 0L
    for ((index, argument) in call.valueArguments.withIndex()) {
        when (val permit = reviewedPermit(context)) {
            is Refinement.Refined -> Unit
            is Refinement.Rejected -> return Refinement.Rejected(permit.failure.keyFailure())
        }
        val expression =
            argument.getArgumentExpression() ?: return keyUnproved(ReviewedDistinctKeyFailure.UNSUPPORTED_ARGUMENT)
        if (
            argument.getSpreadElement() != null ||
                resolved.valueArgumentMapping[expression]?.symbol != resolved.signature.symbol.valueParameters.single()
        )
            return keyUnproved(ReviewedDistinctKeyFailure.FORMAL_MISMATCH)
        val constant =
            expression.evaluate() as? KaConstantValue.StringValue
                ?: return keyUnproved(ReviewedDistinctKeyFailure.UNKNOWN_STRING_CONSTANT)
        val ordinal =
            when (val parsed = ValueArgumentPosition.parse(index)) {
                is Refinement.Refined -> parsed.value
                is Refinement.Rejected -> return keyUnproved(ReviewedDistinctKeyFailure.ARGUMENT_INVENTORY_MISMATCH)
            }
        val value =
            when (
                val admitted =
                    ReviewedStringVarargArgument.fromCompiler(
                        formal,
                        ordinal,
                        sourceValueRange(expression),
                        constant.value,
                    )
            ) {
                is Refinement.Refined -> admitted.value
                is Refinement.Rejected -> return keyUnproved(admitted.failure)
            }
        retainedBytes =
            when (val counted = stringProofBytes(retainedBytes, value.retainedBytes, context.scope.request.budget)) {
                is Refinement.Refined -> counted.value
                is Refinement.Rejected -> return counted
            }
        values.add(value)
    }
    return Refinement.Refined(values.toList())
}

private fun stringProofBytes(
    current: Long,
    added: Long,
    budget: RelationBudget,
): Refinement<Long, NativeReviewedKeyFailure> =
    if (added > Long.MAX_VALUE - current || !budget.permitsReturnedBytes(current + added))
        Refinement.Rejected(NativeReviewedKeyFailure.Limited(NativeReviewedKeyLimit.BYTES))
    else Refinement.Refined(current + added)

private fun stringListRanges(
    call: KtCallExpression,
    context: IntellijCallbackFlowContext,
): Refinement<List<ExactDeclarationTextRange>, NativeReviewedKeyFailure> {
    val ranges = mutableListOf<ExactDeclarationTextRange>()
    for (argument in call.valueArguments) {
        when (val permit = reviewedPermit(context)) {
            is Refinement.Refined -> Unit
            is Refinement.Rejected -> return Refinement.Rejected(permit.failure.keyFailure())
        }
        if (!context.scope.request.budget.permitsReturnedBytes((ranges.size.toLong() + 1) * NATIVE_RANGE_BYTES))
            return Refinement.Rejected(NativeReviewedKeyFailure.Limited(NativeReviewedKeyLimit.BYTES))
        val expression =
            argument.getArgumentExpression()
                ?: return keyUnproved(ReviewedDistinctKeyFailure.ARGUMENT_INVENTORY_MISMATCH)
        ranges.add(sourceValueRange(expression))
    }
    return Refinement.Refined(ranges.toList())
}

private const val NATIVE_RANGE_BYTES = 32L
