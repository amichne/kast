package io.github.amichne.kast.distribution.cli

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
internal enum class ReleaseReadStage {
    OPEN,
    RESPONSE,
    REDIRECT,
    BODY,
}

@Serializable
internal enum class ReleaseReadFailure {
    URL_REJECTED,
    TLS_FAILURE,
    TIMED_OUT,
    IO_FAILURE,
    HTTP_REJECTED,
    REDIRECT_REJECTED,
    REDIRECT_LIMIT,
    DOCUMENT_TOO_LARGE,
    INVALID_ENCODING,
    INVALID_ARGUMENT,
}

/** No URLs, certificate details, exception messages, or response payloads are retained. */
@Serializable
internal sealed interface ReleaseReadObservation {
    @Serializable
    @SerialName("READ")
    data class Read(val bytes: Int) : ReleaseReadObservation {
        val component: String = "kast-release-document"
        val stage: ReleaseReadStage = ReleaseReadStage.BODY
    }

    @Serializable
    @SerialName("UNAVAILABLE")
    data class Unavailable(val stage: ReleaseReadStage, val failure: ReleaseReadFailure) : ReleaseReadObservation {
        val component: String = "kast-release-document"
    }
}
