package io.github.amichne.kast.idea.backend.mutation

import io.github.amichne.kast.api.protocol.ReplacementProofFailureEvidence
import io.github.amichne.kast.api.protocol.ReplacementProofIncompleteException
import io.github.amichne.kast.api.protocol.ReplacementProofLimitation

internal data class ReplacementProofRejection(
    val limitation: ReplacementProofLimitation,
    val message: String,
    val knownMinimumCount: Int = 0,
)

internal sealed interface ReplacementAdmission<out Value> {
    data class Admitted<Value>(
        val value: Value,
    ) : ReplacementAdmission<Value>

    data class Rejected(
        val rejection: ReplacementProofRejection,
    ) : ReplacementAdmission<Nothing>
}

internal fun replacementRejection(
    limitation: ReplacementProofLimitation,
    message: String,
    knownMinimumCount: Int = 0,
): ReplacementAdmission.Rejected = ReplacementAdmission.Rejected(
    ReplacementProofRejection(limitation, message, knownMinimumCount),
)

/**
 * Transport projection: [ReplacementProofRejection] -> [ReplacementProofIncompleteException].
 *
 * This is the only replacement-planning boundary permitted to extract the closed rejection into
 * the JSON-RPC exception protocol.
 */
internal fun projectReplacementProofFailure(rejection: ReplacementProofRejection): Nothing =
    throw ReplacementProofIncompleteException(
        evidence = ReplacementProofFailureEvidence.of(
            rejection.limitation,
            knownMinimumCount = rejection.knownMinimumCount,
        ),
        message = rejection.message,
    )
