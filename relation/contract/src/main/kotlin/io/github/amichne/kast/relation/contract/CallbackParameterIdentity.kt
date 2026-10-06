package io.github.amichne.kast.relation.contract

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.symbol.contract.CanonicalCompilerSignature

enum class CallbackParameterIdentityFailure {
    NOT_CALLABLE,
    INVALID_PARAMETER_POSITION,
    PARAMETER_OUTSIDE_CALLABLE,
}

/** Exact formal identity; the native compiler proves the parameter mapping before admission. */
@ConsistentCopyVisibility
data class CallbackParameterIdentity
private constructor(
    val callable: RelationEndpoint,
    val position: ValueArgumentPosition,
    val parameter: RelationOccurrence,
) {
    companion object {
        fun fromCompiler(
            callable: RelationEndpoint,
            position: ValueArgumentPosition,
            parameter: RelationOccurrence,
        ): Refinement<CallbackParameterIdentity, CallbackParameterIdentityFailure> {
            val signature =
                callable.signature as? CanonicalCompilerSignature.Function
                    ?: return Refinement.Rejected(CallbackParameterIdentityFailure.NOT_CALLABLE)
            return when {
                position.value !in signature.valueParameters.indices ->
                    Refinement.Rejected(CallbackParameterIdentityFailure.INVALID_PARAMETER_POSITION)
                parameter.file != callable.file || !callable.range.containsValueRange(parameter.range) ->
                    Refinement.Rejected(CallbackParameterIdentityFailure.PARAMETER_OUTSIDE_CALLABLE)
                else -> Refinement.Refined(CallbackParameterIdentity(callable, position, parameter))
            }
        }
    }
}
