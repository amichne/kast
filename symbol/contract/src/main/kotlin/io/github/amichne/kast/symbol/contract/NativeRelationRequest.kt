package io.github.amichne.kast.symbol.contract

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.ResourceBudget

enum class NativeRelationFamily {
    REFERENCES,
    IMPLEMENTATIONS,
    INHERITORS,
    OVERRIDES,
    CALLERS,
    CALLEES,
}

enum class NativeRelationByteLimitFailure {
    NOT_POSITIVE,
}

@JvmInline
value class NativeRelationByteLimit private constructor(
    val value: Long,
) {
    companion object {
        /**
         * Proof transition:
         * Long to Refinement<NativeRelationByteLimit, NativeRelationByteLimitFailure>.
         *
         * Establishes a positive upper bound for detached relation-result bytes.
         * [NativeRelationByteLimitFailure] is the closed expected failure. Raw byte limits may be
         * extracted only by the bounded native collector or transport admission boundary.
         */
        fun parse(
            raw: Long,
        ): Refinement<NativeRelationByteLimit, NativeRelationByteLimitFailure> =
            if (raw > 0L) {
                Refinement.Refined(NativeRelationByteLimit(raw))
            } else {
                Refinement.Rejected(NativeRelationByteLimitFailure.NOT_POSITIVE)
            }
    }
}

data class NativeRelationBudget(
    val resources: ResourceBudget,
    val returnedBytes: NativeRelationByteLimit,
)

/**
 * Generation/scope-bound one-hop relation request. The exact selector is the only subject input;
 * callers cannot supply another scope, raw name, FQN, file, offset, depth, or traversal state.
 */
data class NativeRelationRequest(
    val selector: ExactDeclarationSelector,
    val family: NativeRelationFamily,
    val budget: NativeRelationBudget,
)
