package io.github.amichne.kast.api.contract.mutation

import kotlinx.serialization.Serializable

@Serializable
data class KastMutationSubmissionReceipt(
    val operation: KastMutationOperationSnapshot,
    val deduplicated: Boolean,
)
