package io.github.amichne.kast.api.protocol

import io.github.amichne.kast.api.contract.result.RelationshipResultEvidence

class MutationProofIncompleteException(
    val evidence: RelationshipResultEvidence.Limited,
    message: String = "Mutation semantic proof is incomplete",
) : AnalysisException(
    statusCode = 409,
    errorCode = "MUTATION_PROOF_INCOMPLETE",
    message = message,
    retryable = true,
    details = mapOf(
        "knownMinimumCount" to evidence.cardinality.knownMinimumCount.toString(),
        "limitations" to evidence.coverage.limitations.joinToString(",") { limitation -> limitation.name },
    ),
)
