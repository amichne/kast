package io.github.amichne.kast.relation.intellij

import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.CallbackDependencyContract
import io.github.amichne.kast.relation.contract.CallbackInvocationFlowCause
import io.github.amichne.kast.relation.contract.DeclaredCallbackResultEffect
import io.github.amichne.kast.relation.contract.DependencyClassDigest
import io.github.amichne.kast.relation.contract.RelationOccurrence
import io.github.amichne.kast.relation.contract.ReviewedCallbackValueFailure
import io.github.amichne.kast.relation.contract.ReviewedIteration
import io.github.amichne.kast.relation.contract.ValueArgumentPosition
import io.github.amichne.kast.symbol.contract.CompilerGroundedSymbolEvidence
import org.jetbrains.kotlin.analysis.api.symbols.KaNamedFunctionSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaValueParameterSymbol
import org.jetbrains.kotlin.analysis.api.types.KaClassType
import org.jetbrains.kotlin.psi.KtCallElement

internal fun KaNamedFunctionSymbol.isIterationAction(parameter: KaValueParameterSymbol): Boolean =
    callableId?.asSingleFqName()?.asString() == "kotlin.collections.forEach" &&
        (receiverParameter?.returnType as? KaClassType)?.classId?.asSingleFqName()?.asString() in iterationReceivers &&
        valueParameters.singleOrNull() == parameter &&
        parameter.name.asString() == "action"

internal fun reviewedIteration(
    context: IntellijCallbackFlowContext,
    file: VirtualFile,
    target: CompilerGroundedSymbolEvidence,
    position: ValueArgumentPosition,
    classDigest: DependencyClassDigest,
): Refinement<ReviewedIteration, CallbackInvocationFlowCause> {
    val archive =
        VfsUtilCore.getVirtualFileForJar(file)
            ?: return Refinement.Rejected(CallbackInvocationFlowCause.DEPENDENCY_BINARY_UNAVAILABLE)
    return when (val read = reviewedArtifactDigest(archive, context)) {
        is Refinement.Refined ->
            ReviewedIteration.admit(
                target = target,
                position = position,
                classDigest = classDigest,
                archive = read.value,
            )
        is Refinement.Rejected ->
            Refinement.Rejected(
                when (read.failure) {
                    ReviewedCallbackValueFailure.WORK_LIMIT_REACHED -> CallbackInvocationFlowCause.WORK_LIMIT_REACHED
                    ReviewedCallbackValueFailure.TIME_LIMIT_REACHED -> CallbackInvocationFlowCause.TIME_LIMIT_REACHED
                    ReviewedCallbackValueFailure.ARTIFACT_CHANGED ->
                        CallbackInvocationFlowCause.DEPENDENCY_BINARY_CHANGED
                    else -> CallbackInvocationFlowCause.DEPENDENCY_BINARY_UNAVAILABLE
                }
            )
    }
}

internal fun bindReviewedIteration(
    context: IntellijCallbackFlowContext,
    call: KtCallElement,
    occurrence: RelationOccurrence,
    operation: ReviewedIteration,
): CallbackBindingPreparation {
    val owner =
        context.owner(call)
            ?: return CallbackBindingPreparation.Unavailable(CallbackInvocationFlowCause.ANONYMOUS_IDENTITY_UNAVAILABLE)
    return when (
        val read =
            CallbackDependencyContract.fromReviewedIteration(
                basis = context.scope.request.subject.lease.identity,
                occurrence = occurrence,
                owner = owner,
                operation = operation,
            )
    ) {
        is Refinement.Refined ->
            CallbackBindingPreparation.DependencyContract(read.value, DeclaredCallbackResultEffect.UNSPECIFIED)
        is Refinement.Rejected -> CallbackBindingPreparation.ContractRejected(read.failure)
    }
}

private val iterationReceivers = setOf("kotlin.IntArray", "kotlin.collections.Iterable")
