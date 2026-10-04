package io.github.amichne.kast.protocol.contract

import kotlinx.serialization.Serializable

/** Original admitted semantic question; retained presentation never replaces its source, operators, or output. */
@Serializable
data class QueryQuestionDocument(
    val from: QueryFromDocument,
    val steps: BoundedProtocolList<QueryStepDocument>,
    val output: QueryOutputDocument,
) {
    companion object {
        fun from(request: QueryRunRequest.Run): QueryQuestionDocument =
            QueryQuestionDocument(request.from, request.steps, request.output)
    }
}
