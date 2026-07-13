package io.github.amichne.kast.shared.proofloss.ir

sealed interface UnsupportedReason {
    val location: SourceSpan?

    data class MutableTrackedValue(
        override val location: SourceSpan,
        val value: TrackedValueId,
    ) : UnsupportedReason

    data class Loop(override val location: SourceSpan) : UnsupportedReason
    data class NestedLambda(override val location: SourceSpan) : UnsupportedReason
    data class NonDirectBoundaryArgument(override val location: SourceSpan) : UnsupportedReason
    data class UnresolvedCall(override val location: SourceSpan) : UnsupportedReason
    data class UnprovenMaterializationSuccess(override val location: SourceSpan) : UnsupportedReason
    data class UnsupportedArgumentMapping(override val location: SourceSpan) : UnsupportedReason
    data class UnsupportedControlFlow(override val location: SourceSpan) : UnsupportedReason
}
