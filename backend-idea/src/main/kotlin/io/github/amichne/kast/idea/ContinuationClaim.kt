package io.github.amichne.kast.idea

internal sealed interface ContinuationClaim<out Value> {
    data class Claimed<Value>(val value: Value) : ContinuationClaim<Value>

    data object Expired : ContinuationClaim<Nothing>

    data object Absent : ContinuationClaim<Nothing>
}
