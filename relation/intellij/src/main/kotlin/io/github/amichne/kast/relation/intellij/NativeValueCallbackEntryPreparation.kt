package io.github.amichne.kast.relation.intellij

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.CallbackInvocationFlowCause
import io.github.amichne.kast.relation.contract.RelationCallableBody
import io.github.amichne.kast.relation.contract.ValueExecutionOwner
import io.github.amichne.kast.relation.contract.ValueFlowUnsupportedCause
import io.github.amichne.kast.relation.contract.ValueFormalPosition
import io.github.amichne.kast.relation.contract.ValueRole
import io.github.amichne.kast.workspace.contract.SemanticReadIdentity
import io.github.amichne.kast.workspace.intellij.read.IntellijReadObservation

/** Entry preparation spends its existing work unit before callback enumeration can exhaust that allowance. */
internal sealed interface NativeValueCallbackEntryPreparation {
    data class Ready(
        val body: RelationCallableBody.Anonymous,
        val execution: ValueExecutionOwner,
        val formal: ValueFormalPosition,
    ) : NativeValueCallbackEntryPreparation

    data class Refused(val cause: CallbackInvocationFlowCause) : NativeValueCallbackEntryPreparation

    data class Unsupported(val cause: ValueFlowUnsupportedCause) : NativeValueCallbackEntryPreparation
}

internal fun nativePrepareCallbackEntry(
    supply: NativeValueCallbackSupply,
    role: ValueRole.FunctionArgument,
    scope: CompiledRelationScope,
    observation: IntellijReadObservation,
    basis: SemanticReadIdentity,
): NativeValueCallbackEntryPreparation {
    val body =
        nativeAnonymousCallableBody(supply.literal, scope, observation)
            ?: return NativeValueCallbackEntryPreparation.Unsupported(ValueFlowUnsupportedCause.NESTED_EXECUTION)
    val execution =
        when (val value = ValueExecutionOwner.fromCompiler(supply.supplyingFrame.invocation.enclosing, body, basis)) {
            is Refinement.Refined -> value.value
            is Refinement.Rejected ->
                return NativeValueCallbackEntryPreparation.Unsupported(ValueFlowUnsupportedCause.NESTED_EXECUTION)
        }
    return when (val formal = nativeCallbackFormal(supply.literal, execution, role.position, observation)) {
        is Refinement.Refined -> NativeValueCallbackEntryPreparation.Ready(body, execution, formal.value)
        is Refinement.Rejected -> NativeValueCallbackEntryPreparation.Unsupported(formal.failure)
    }
}
