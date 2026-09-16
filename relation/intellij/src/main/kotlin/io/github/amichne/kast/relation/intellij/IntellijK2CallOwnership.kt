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
import org.jetbrains.kotlin.psi.KtCallElement
import org.jetbrains.kotlin.psi.KtFunctionLiteral
import org.jetbrains.kotlin.psi.KtLambdaExpression
import org.jetbrains.kotlin.psi.KtValueArgument
import org.jetbrains.kotlin.psi.KtValueArgumentList

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
            ContainingDeclaration.Unsupported -> Refinement.Rejected(CallOwnershipFailure.UNSUPPORTED_BOUNDARY)
            is ContainingDeclaration.Deferred -> {
                val literal = lexical.boundary as? KtFunctionLiteral
                if (literal == null) Refinement.Rejected(CallOwnershipFailure.UNSUPPORTED_BOUNDARY)
                else analyze(literal) { refineInlineOwner(lexical) }
            }
        }
    when (result) {
        is Refinement.Refined -> observation.count(IntellijReadCounter.RELATION_CALL_OWNERS_FOUND)
        is Refinement.Rejected -> {
            observation.count(IntellijReadCounter.RELATION_CALL_OWNERS_UNAVAILABLE)
            when (result.failure) {
                CallOwnershipFailure.UNSUPPORTED_BOUNDARY ->
                    observation.terminated(IntellijReadTermination.RELATION_CALL_OWNER_UNSUPPORTED)
                CallOwnershipFailure.UNRESOLVED_ARGUMENT_MAPPING ->
                    observation.terminated(IntellijReadTermination.K2_UNRESOLVED_SYMBOL)
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
        ContainingDeclaration.Unsupported -> Refinement.Rejected(CallOwnershipFailure.UNSUPPORTED_BOUNDARY)
    }
}

private fun KaSession.inlineEnclosingOwner(
    owner: ContainingDeclaration.Deferred
): Refinement<ContainingDeclaration, CallOwnershipFailure> {
    val lambda =
        owner.boundary.parent as? KtLambdaExpression
            ?: return Refinement.Rejected(CallOwnershipFailure.UNSUPPORTED_BOUNDARY)
    val argument =
        lambda.parent as? KtValueArgument ?: return Refinement.Rejected(CallOwnershipFailure.UNSUPPORTED_BOUNDARY)
    val call =
        when (val container = argument.parent) {
            is KtCallElement -> container
            is KtValueArgumentList -> container.parent as? KtCallElement
            else -> null
        } ?: return Refinement.Rejected(CallOwnershipFailure.UNSUPPORTED_BOUNDARY)
    val resolved = call.resolveCall() ?: return Refinement.Rejected(CallOwnershipFailure.UNRESOLVED_ARGUMENT_MAPPING)
    val function =
        resolved.signature.symbol as? KaNamedFunctionSymbol
            ?: return Refinement.Rejected(CallOwnershipFailure.UNSUPPORTED_BOUNDARY)
    val parameter =
        resolved.valueArgumentMapping[lambda]?.symbol
            ?: return Refinement.Rejected(CallOwnershipFailure.UNRESOLVED_ARGUMENT_MAPPING)
    if (!function.isInline) return Refinement.Rejected(CallOwnershipFailure.UNSUPPORTED_BOUNDARY)
    if (parameter.isNoinline || parameter.isCrossinline || parameter.returnType !is KaFunctionType) {
        return Refinement.Rejected(CallOwnershipFailure.UNSUPPORTED_BOUNDARY)
    }
    return Refinement.Refined(call.nearestDeclaration())
}
