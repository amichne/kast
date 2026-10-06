@file:OptIn(org.jetbrains.kotlin.analysis.api.KaExperimentalApi::class)

package io.github.amichne.kast.relation.intellij

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.workspace.intellij.read.IntellijReadCounter
import io.github.amichne.kast.workspace.intellij.read.IntellijReadObservation
import io.github.amichne.kast.workspace.intellij.read.IntellijReadTermination
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.symbols.KaNamedFunctionSymbol
import org.jetbrains.kotlin.analysis.api.types.KaFunctionType
import org.jetbrains.kotlin.psi.KtFunction

/**
 * Refines lexical ownership without changing the occurrence or resolving its target. Every crossed literal lambda must
 * map through a successful K2 call to a function parameter of an inline callable, excluding noinline/crossinline. Found
 * retains the first named owner; unsupported outer boundaries and local named functions are never skipped. All PSI and
 * compiler values remain inside the authorized read. Exact ownership makes no runtime execution claim.
 */
internal fun refineCallOwnership(
    lexical: ContainingDeclaration,
    observation: IntellijReadObservation,
): Refinement<ContainingDeclaration.Found, CallOwnershipFailure> {
    val result =
        when (lexical) {
            is ContainingDeclaration.Found -> Refinement.Refined(lexical)
            ContainingDeclaration.Unsupported -> Refinement.Rejected(CallOwnershipFailure.UnsupportedBoundary)
            is ContainingDeclaration.Deferred -> {
                val literal = lexical.boundary as? KtFunction
                if (literal == null) Refinement.Rejected(CallOwnershipFailure.UnsupportedBoundary)
                else analyze(literal) { refineInlineOwner(lexical) }
            }
        }
    when (result) {
        is Refinement.Refined -> observation.count(IntellijReadCounter.RELATION_CALL_OWNERS_FOUND)
        is Refinement.Rejected -> {
            when (result.failure) {
                is CallOwnershipFailure.ExcludedCallback ->
                    observation.count(IntellijReadCounter.RELATION_CALL_OWNERS_EXCLUDED)
                CallOwnershipFailure.UnsupportedBoundary -> {
                    observation.count(IntellijReadCounter.RELATION_CALL_OWNERS_UNAVAILABLE)
                    observation.terminated(IntellijReadTermination.RELATION_CALL_OWNER_UNSUPPORTED)
                }
                CallOwnershipFailure.UnresolvedArgumentMapping -> {
                    observation.count(IntellijReadCounter.RELATION_CALL_OWNERS_UNAVAILABLE)
                    observation.terminated(IntellijReadTermination.K2_UNRESOLVED_SYMBOL)
                }
            }
        }
    }
    return result
}

private fun KaSession.refineInlineOwner(
    lexical: ContainingDeclaration
): Refinement<ContainingDeclaration.Found, CallOwnershipFailure> {
    var owner = lexical
    while (owner is ContainingDeclaration.Deferred) {
        owner =
            when (val admitted = inlineEnclosingOwner(owner)) {
                is Refinement.Refined -> admitted.value
                is Refinement.Rejected -> return admitted
            }
    }
    return when (owner) {
        is ContainingDeclaration.Found -> Refinement.Refined(owner)
        is ContainingDeclaration.Deferred,
        ContainingDeclaration.Unsupported -> Refinement.Rejected(CallOwnershipFailure.UnsupportedBoundary)
    }
}

private fun KaSession.inlineEnclosingOwner(
    owner: ContainingDeclaration.Deferred
): Refinement<ContainingDeclaration, CallOwnershipFailure> {
    val functionBody =
        owner.boundary as? KtFunction ?: return Refinement.Rejected(CallOwnershipFailure.UnsupportedBoundary)
    return when (val supply = classifyCallbackFunctionSupply(functionBody)) {
        is IntellijCallbackLambdaSupply.Argument -> inlineArgumentOwner(owner, supply)
        is IntellijCallbackLambdaSupply.Invocation -> directInvocationOwner(supply)
        is IntellijCallbackLambdaSupply.DefaultParameter ->
            excludedCallback(
                owner,
                io.github.amichne.kast.relation.contract.CallbackExclusionReason.DEFAULT_PARAMETER,
            )
        IntellijCallbackLambdaSupply.Stored ->
            excludedCallback(owner, io.github.amichne.kast.relation.contract.CallbackExclusionReason.STORED_CALLBACK)
        is IntellijCallbackLambdaSupply.Returned ->
            excludedCallback(owner, io.github.amichne.kast.relation.contract.CallbackExclusionReason.RETURNED_CALLBACK)
        IntellijCallbackLambdaSupply.Unsupported -> Refinement.Rejected(CallOwnershipFailure.UnsupportedBoundary)
    }
}

private fun KaSession.directInvocationOwner(
    supply: IntellijCallbackLambdaSupply.Invocation
): Refinement<ContainingDeclaration, CallOwnershipFailure> {
    val resolved =
        supply.call.resolveCall() ?: return Refinement.Rejected(CallOwnershipFailure.UnresolvedArgumentMapping)
    val function =
        resolved.signature.symbol as? KaNamedFunctionSymbol
            ?: return Refinement.Rejected(CallOwnershipFailure.UnsupportedBoundary)
    if (!confirmsBuiltinFunctionInvoke(function)) return Refinement.Rejected(CallOwnershipFailure.UnsupportedBoundary)
    return Refinement.Refined(supply.call.nearestDeclaration())
}

private fun KaSession.inlineArgumentOwner(
    owner: ContainingDeclaration.Deferred,
    argument: IntellijCallbackLambdaSupply.Argument,
): Refinement<ContainingDeclaration, CallOwnershipFailure> {
    val call =
        supplyingCallbackCall(argument.argument) ?: return Refinement.Rejected(CallOwnershipFailure.UnsupportedBoundary)
    val resolved = call.resolveCall() ?: return Refinement.Rejected(CallOwnershipFailure.UnresolvedArgumentMapping)
    val function =
        resolved.signature.symbol as? KaNamedFunctionSymbol
            ?: return Refinement.Rejected(CallOwnershipFailure.UnsupportedBoundary)
    val parameter =
        resolved.valueArgumentMapping[argument.expression]?.symbol
            ?: return Refinement.Rejected(CallOwnershipFailure.UnresolvedArgumentMapping)
    if (parameter.returnType !is KaFunctionType) return Refinement.Rejected(CallOwnershipFailure.UnsupportedBoundary)
    when (
        classifyInlineCallback(
            function.isInline,
            parameter.isNoinline,
            parameter.isCrossinline,
            parameter.returnType is KaFunctionType,
        )
    ) {
        InlineCallbackClassification.INLINE -> Unit
        InlineCallbackClassification.EXCLUDED ->
            return excludedCallback(
                owner,
                when {
                    parameter.isNoinline ->
                        io.github.amichne.kast.relation.contract.CallbackExclusionReason.NOINLINE_ARGUMENT
                    parameter.isCrossinline ->
                        io.github.amichne.kast.relation.contract.CallbackExclusionReason.CROSSINLINE_ARGUMENT
                    else -> io.github.amichne.kast.relation.contract.CallbackExclusionReason.NON_INLINE_ARGUMENT
                },
            )
    }
    return Refinement.Refined(call.nearestDeclaration())
}

internal enum class InlineCallbackClassification {
    INLINE,
    EXCLUDED,
}

internal fun classifyInlineCallback(
    inlineFunction: Boolean,
    noinlineParameter: Boolean,
    crossinlineParameter: Boolean,
    functionParameter: Boolean,
): InlineCallbackClassification =
    when {
        inlineFunction && !noinlineParameter && !crossinlineParameter && functionParameter ->
            InlineCallbackClassification.INLINE
        else -> InlineCallbackClassification.EXCLUDED
    }

private fun excludedCallback(
    owner: ContainingDeclaration.Deferred,
    reason: io.github.amichne.kast.relation.contract.CallbackExclusionReason,
): Refinement<Nothing, CallOwnershipFailure> =
    when (val literal = owner.boundary as? KtFunction) {
        null -> Refinement.Rejected(CallOwnershipFailure.UnsupportedBoundary)
        else -> Refinement.Rejected(CallOwnershipFailure.ExcludedCallback(literal, reason))
    }
