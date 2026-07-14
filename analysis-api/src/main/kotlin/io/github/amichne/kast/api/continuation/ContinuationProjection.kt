package io.github.amichne.kast.api.continuation

abstract class ContinuationProjection {
    data class Value<out Output>(val value: Output) : ContinuationProjection()
}
