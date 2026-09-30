package io.github.amichne.kast.query.protocol

internal sealed interface QueryProjection<out Value> {
    data class Projected<Value>(val values: List<Value>) : QueryProjection<Value>

    data object Rejected : QueryProjection<Nothing>
}
