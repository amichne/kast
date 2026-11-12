package io.github.amichne.kast.relation.contract

import io.github.amichne.kast.kernel.ReadLimitParameter
import io.github.amichne.kast.kernel.ReadLimits

/** The operation actually entered or prevented, not a proposed unresolved transfer. */
enum class ValueNativeOperation {
    FLOW_READ,
    SITE_RESOLUTION,
    PRODUCER_SEED,
    MODEL_DECLARATION,
    MODEL_SITE,
}

enum class ValueNativePrerequisite {
    PROJECT_DISPOSED,
    INDEXES_UNAVAILABLE,
}

enum class ValueNativeRelationOrigin {
    IMPLEMENTATION_DISPATCH,
    RETURN_CALLER_REFERENCES,
}

enum class ValueNativeUnexpectedKind {
    RUNTIME,
    LINKAGE,
}

/** Sanitized adapter evidence. No throwable, message, source payload or unbounded stack escapes. */
data class ValueNativeUnexpectedFailure
private constructor(
    val kind: ValueNativeUnexpectedKind,
    val exceptionType: String,
    val adapterFrames: List<String>,
) {
    companion object {
        fun fromBoundary(
            kind: ValueNativeUnexpectedKind,
            exceptionType: String,
            adapterFrames: List<String>,
            limits: ReadLimits,
        ): ValueNativeUnexpectedFailure =
            ValueNativeUnexpectedFailure(
                kind,
                exceptionType.take(limits[ReadLimitParameter.DIAGNOSTIC_TEXT_CHARACTERS].value),
                java.util.Collections.unmodifiableList(
                    adapterFrames.take(limits[ReadLimitParameter.DIAGNOSTIC_FRAMES].value).map { frame ->
                        frame.take(limits[ReadLimitParameter.DIAGNOSTIC_TEXT_CHARACTERS].value)
                    }
                ),
            )
    }
}
