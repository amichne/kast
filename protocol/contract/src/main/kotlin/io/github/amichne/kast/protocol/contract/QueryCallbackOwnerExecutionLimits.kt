package io.github.amichne.kast.protocol.contract

/** Shared limits for recursive owner documents before decoding and during semantic admission. */
object QueryCallbackOwnerExecutionLimits {
    const val EXCLUSIVE_DEPTH = 64
    const val MAXIMUM_COUNT = 1000
}
