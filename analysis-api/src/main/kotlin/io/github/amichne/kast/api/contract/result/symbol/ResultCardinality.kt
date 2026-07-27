package io.github.amichne.kast.api.contract.result

import io.github.amichne.kast.api.docs.DocField
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
sealed interface ResultCardinality {
    fun knownMinimum(): Int

    @Serializable
    @SerialName("EXACT")
    data class Exact(
        @DocField(description = "Exact number of results across every page.")
        val totalCount: Int,
    ) : ResultCardinality {
        init {
            require(totalCount >= 0) { "totalCount must be non-negative" }
        }

        override fun knownMinimum(): Int = totalCount
    }

    @Serializable
    @SerialName("KNOWN_MINIMUM")
    data class KnownMinimum(
        @DocField(description = "Number of results proven so far without claiming exhaustive work.")
        val knownMinimumCount: Int,
    ) : ResultCardinality {
        init {
            require(knownMinimumCount >= 0) { "knownMinimumCount must be non-negative" }
        }

        override fun knownMinimum(): Int = knownMinimumCount
    }
}
