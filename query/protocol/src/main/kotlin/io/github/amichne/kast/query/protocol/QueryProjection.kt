package io.github.amichne.kast.query.protocol

internal sealed interface QueryProjection<out Value> {
    sealed interface Legacy<out Value> : QueryProjection<Value>

    data class Projected<Value>(val values: List<Value>) : Legacy<Value>

    data class ImpactRejected(val cause: ImpactPathProjectionFailure) : QueryProjection<Nothing>

    data object Rejected : Legacy<Nothing>
}
