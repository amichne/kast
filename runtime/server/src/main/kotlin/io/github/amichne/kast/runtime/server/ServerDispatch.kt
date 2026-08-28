package io.github.amichne.kast.runtime.server

import io.github.amichne.kast.protocol.contract.CanonicalOperation
import io.github.amichne.kast.protocol.wire.WireFailure

/** Closed transport result for one canonical wire request frame. */
sealed interface ServerDispatch {
    data class Responded(
        val document: String,
    ) : ServerDispatch

    data class Rejected(
        val failure: ServerDispatchFailure,
    ) : ServerDispatch
}

/** Closed server failures kept distinct from semantic operation rejection outcomes. */
sealed interface ServerDispatchFailure {
    data class RequestAdmissionFailed(
        val failure: WireFailure,
    ) : ServerDispatchFailure

    data class UnsupportedOperation(
        val operation: CanonicalOperation,
    ) : ServerDispatchFailure

    data class RequestDecodingFailed(
        val operation: CanonicalOperation,
        val failure: WireFailure,
    ) : ServerDispatchFailure

    data class ResponseEncodingFailed(
        val operation: CanonicalOperation,
        val failure: WireFailure,
    ) : ServerDispatchFailure
}
