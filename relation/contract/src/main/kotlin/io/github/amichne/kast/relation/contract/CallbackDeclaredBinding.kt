package io.github.amichne.kast.relation.contract

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.workspace.contract.SemanticReadIdentity

/** A declared default is selected only at calls that omit this formal argument. */
@ConsistentCopyVisibility
data class CallbackDefaultBinding
private constructor(
    val parameter: CallbackParameterIdentity,
    val defaultValue: RelationOccurrence,
) {
    companion object {
        fun fromCompiler(
            parameter: CallbackParameterIdentity,
            defaultValue: RelationOccurrence,
        ): Refinement<CallbackDefaultBinding, CallbackInvocationFlowFailure> =
            if (
                defaultValue.file != parameter.parameter.file ||
                    !parameter.parameter.range.containsValueRange(defaultValue.range)
            )
                Refinement.Rejected(CallbackInvocationFlowFailure.BODY_OUTSIDE_ARGUMENT)
            else Refinement.Refined(CallbackDefaultBinding(parameter, defaultValue))
    }
}

/** Direct syntax and compiler-confirmed invoke retain the actual callable owner. */
@ConsistentCopyVisibility
data class CallbackDirectInvocationBinding
private constructor(
    val basis: SemanticReadIdentity,
    val occurrence: RelationOccurrence,
    val owner: RelationCallableBody,
) {
    companion object {
        fun fromCompiler(
            basis: SemanticReadIdentity,
            occurrence: RelationOccurrence,
            owner: RelationCallableBody,
        ): Refinement<CallbackDirectInvocationBinding, CallbackInvocationFlowFailure> =
            if (occurrence.file != owner.file || !owner.range.containsValueRange(occurrence.range))
                Refinement.Rejected(CallbackInvocationFlowFailure.INVOCATION_OUTSIDE_OWNER)
            else Refinement.Refined(CallbackDirectInvocationBinding(basis, occurrence, owner))
    }
}

/** Completeness of formal-invocation enumeration, independent of runtime activation. */
enum class CallbackInvocationScan {
    EXHAUSTIVE,
    INCOMPLETE,
    NOT_APPLICABLE,
}
