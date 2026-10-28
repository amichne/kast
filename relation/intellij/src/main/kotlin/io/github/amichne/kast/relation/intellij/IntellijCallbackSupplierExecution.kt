package io.github.amichne.kast.relation.intellij

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.CallbackInvocationFlowCause
import io.github.amichne.kast.relation.contract.CallbackInvocationFlowFailure
import io.github.amichne.kast.relation.contract.RelationCallableBody
import org.jetbrains.kotlin.psi.KtFunction

/** Supplier activation remains one operation for ordinary and implementation-selected source flow. */
internal class IntellijCallbackSupplierExecution(
    private val context: IntellijCallbackFlowContext,
    private val prepared: PreparedCallbackFlow,
    private val summaries: CallbackParameterSummaries,
) {
    fun read(
        evidence: CallbackFormalScanSnapshot
    ): Refinement<CallbackFormalScanSnapshot, CallbackInvocationFlowFailure> {
        return when (val origin = prepared.origin) {
            is PreparedCallbackOrigin.Default -> Refinement.Refined(evidence)
            is PreparedCallbackOrigin.Argument -> {
                val owner = origin.binding.invocationOwner
                val named =
                    owner is RelationCallableBody.Named &&
                        owner.compilerIdentity == origin.binding.invocation.enclosing.compilerIdentity
                val supplied =
                    if (named) evidence
                    else evidence.withSupplier(setOf(CallbackInvocationFlowCause.NESTED_CALLBACK_EXECUTION))
                if (owner !is RelationCallableBody.Anonymous || evidence.owners.any { it.body == owner })
                    Refinement.Refined(supplied)
                else
                    when (supplied.supplierActivation()) {
                        CallbackSupplierActivation.CAPACITY_EXHAUSTED -> Refinement.Refined(supplied)
                        CallbackSupplierActivation.AVAILABLE -> observeAnonymousSupplier(origin, owner, supplied)
                    }
            }
        }
    }

    private fun observeAnonymousSupplier(
        origin: PreparedCallbackOrigin.Argument,
        owner: RelationCallableBody.Anonymous,
        evidence: CallbackFormalScanSnapshot,
    ): Refinement<CallbackFormalScanSnapshot, CallbackInvocationFlowFailure> {
        val literal =
            (origin.call.nearestDeclaration() as? ContainingDeclaration.Deferred)?.boundary as? KtFunction
                ?: return Refinement.Refined(
                    evidence.withSupplier(setOf(CallbackInvocationFlowCause.ANONYMOUS_IDENTITY_UNAVAILABLE))
                )
        return when (
            val read =
                IntellijCallbackOwnerBindingReader(context, summaries)
                    .read(literal, owner, origin.binding.invocation.enclosing)
        ) {
            is Refinement.Rejected -> read
            is Refinement.Refined ->
                when (val allowed = summaries.retention.admitOwner(read.value)) {
                    is Refinement.Rejected ->
                        Refinement.Refined(evidence.withSupplier(read.value.obligations + allowed.failure))
                    is Refinement.Refined ->
                        Refinement.Refined(evidence.withSupplier(read.value.obligations, listOf(read.value)))
                }
        }
    }
}
