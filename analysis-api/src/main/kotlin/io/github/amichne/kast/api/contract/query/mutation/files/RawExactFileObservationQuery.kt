package io.github.amichne.kast.api.contract.query

import io.github.amichne.kast.api.docs.DocField
import kotlinx.serialization.Serializable

@Serializable
data class RawExactFileObservationQuery(
    @DocField(description = "Canonical normalized path relative to the exact workspace root.")
    val filePath: String,
    @DocField(description = "Optional canonical UUID-v4 active mutation attempt required by this observation.")
    val mutationAttemptId: String? = null,
)
