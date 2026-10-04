package io.github.amichne.kast.protocol.wire.presentation

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
internal enum class QueryCliOperation {
    @SerialName("query.run") RUN
}

@Serializable
internal enum class QueryCompleteCliStatus {
    @SerialName("complete") COMPLETE
}

@Serializable
internal enum class QueryQualifiedCliStatus {
    @SerialName("qualified") QUALIFIED
}

@Serializable
internal enum class QueryRejectedCliStatus {
    @SerialName("rejected") REJECTED
}
