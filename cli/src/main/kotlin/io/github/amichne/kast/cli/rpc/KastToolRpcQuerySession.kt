package io.github.amichne.kast.cli.rpc

import io.github.amichne.kast.appserver.query.AdmittedPublicTool
import io.github.amichne.kast.appserver.query.PublicToolCanonical
import io.github.amichne.kast.appserver.query.PublicToolContract
import io.github.amichne.kast.cli.MAXIMUM_PROTOCOL_TEXT_LENGTH
import io.github.amichne.kast.cli.direct.DirectToolStage
import io.github.amichne.kast.cli.direct.observeDirectToolStage
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.QueryCheckpointDocument
import io.github.amichne.kast.protocol.contract.QueryCompletionRetentionFailure
import io.github.amichne.kast.protocol.contract.QueryExecutionContinuation
import io.github.amichne.kast.protocol.contract.QueryQualifiedProgressDocument
import io.github.amichne.kast.protocol.contract.QueryResultReference
import io.github.amichne.kast.protocol.contract.QueryResultRetention
import io.github.amichne.kast.protocol.contract.QueryRunRequest
import io.github.amichne.kast.protocol.registry.PublicToolIdentity
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement

internal const val MAXIMUM_QUERY_SESSION_REQUESTS = 64

/** One caller-owned child, one query, then only presentation of authority issued by its preceding reply. */
internal class KastToolRpcQuerySession(private val invoke: (AdmittedPublicTool) -> ToolRpcReply) {
    fun run(input: InputStream, emit: (ToolRpcReply) -> Unit) {
        val records = BufferedInputStream(input)
        var state: QuerySessionState = QuerySessionState.First
        repeat(MAXIMUM_QUERY_SESSION_REQUESTS) {
            val bytes =
                when (val frame = readQuerySessionFrame(records)) {
                    QuerySessionFrame.End -> return
                    is QuerySessionFrame.Rejected -> {
                        emit(ToolRpcReply.Rejected(frame.failure))
                        return
                    }
                    is QuerySessionFrame.Record -> frame.bytes
                }
            val admitted =
                when (val request = admitQuerySessionInput(bytes)) {
                    is Refinement.Refined -> request.value
                    is Refinement.Rejected -> {
                        emit(ToolRpcReply.Rejected(request.failure))
                        return
                    }
                }
            if (!state.permits(admitted.query)) {
                emit(ToolRpcReply.Rejected(ToolRpcFailure.QUERY_SESSION_TRANSITION_REJECTED))
                return
            }
            val reply = invoke(admitted.tool)
            emit(reply)
            state = reply.nextQuerySessionState()
            if (state == QuerySessionState.Closed) return
        }
        // The fixed child ends after its 64th reply; a new logical invocation owns any later detail read.
    }
}

private sealed interface QuerySessionState {
    data object First : QuerySessionState

    data class Output(
        val result: QueryResultRetention,
        val continuation: QueryExecutionContinuation.Output?,
    ) : QuerySessionState

    data object Closed : QuerySessionState
}

private fun QuerySessionState.permits(request: QueryRunRequest): Boolean =
    when (this) {
        QuerySessionState.First -> true
        QuerySessionState.Closed -> false
        is QuerySessionState.Output ->
            when (request) {
                is QueryRunRequest.Run -> false
                is QueryRunRequest.ReadResult ->
                    result is QueryResultRetention.Retained && request.result == result.reference
                is QueryRunRequest.Resume -> continuation != null && request.continuation == continuation
            }
    }

private data class QuerySessionInput(val tool: AdmittedPublicTool, val query: QueryRunRequest)

private fun admitQuerySessionInput(bytes: ByteArray): Refinement<QuerySessionInput, ToolRpcFailure> {
    val input =
        when (val decoded = decodeToolRpcInput(bytes)) {
            is Refinement.Refined -> decoded.value
            is Refinement.Rejected -> return decoded
        }
    val arguments =
        try {
            Json.decodeFromString<JsonObject>(input)
        } catch (_: SerializationException) {
            return Refinement.Rejected(ToolRpcFailure.INVALID_ARGUMENTS)
        }
    return when (
        val admitted =
            observeDirectToolStage(DirectToolStage.REQUEST_ADMISSION) {
                PublicToolContract.admit(PublicToolIdentity.QUERY_SYMBOLS, arguments)
            }
    ) {
        is Refinement.Refined ->
            when (val canonical = admitted.value.canonical) {
                is PublicToolCanonical.Query -> Refinement.Refined(QuerySessionInput(admitted.value, canonical.request))
                is PublicToolCanonical.Source,
                is PublicToolCanonical.Diagnostics,
                is PublicToolCanonical.Change -> Refinement.Rejected(ToolRpcFailure.QUERY_SESSION_TRANSITION_REJECTED)
            }
        is Refinement.Rejected -> Refinement.Rejected(ToolRpcFailure.INVALID_ARGUMENTS)
    }
}

private fun ToolRpcReply.nextQuerySessionState(): QuerySessionState =
    when (this) {
        is ToolRpcReply.Rejected,
        is ToolRpcReply.Catalog -> QuerySessionState.Closed
        is ToolRpcReply.Complete -> document.querySessionAuthority()
        is ToolRpcReply.Qualified -> document.querySessionAuthority()
        is ToolRpcReply.RejectedDocument -> document.rejectedQuerySessionAuthority()
    }

private fun JsonObject.querySessionAuthority(): QuerySessionState =
    try {
        val authority = authorityJson.decodeFromJsonElement<QuerySessionResultAuthority>(this)
        val progress = authority.qualification?.progress as? QueryQualifiedProgressDocument.Resumable
        val continuation = (progress?.checkpoint as? QueryCheckpointDocument.RetainedOutput)?.token
        if (authority.retention is QueryResultRetention.Retained || continuation != null)
            QuerySessionState.Output(authority.retention, continuation)
        else QuerySessionState.Closed
    } catch (_: SerializationException) {
        QuerySessionState.Closed
    }

private fun JsonObject.rejectedQuerySessionAuthority(): QuerySessionState =
    try {
        val rejected = authorityJson.decodeFromJsonElement<QuerySessionRejectedAuthority>(this)
        when (val evidence = rejected.rejection.detail?.evidence) {
            is QuerySessionEvidence.Retained ->
                QuerySessionState.Output(QueryResultRetention.Retained(evidence.result), null)
            is QuerySessionEvidence.Unavailable,
            null -> QuerySessionState.Closed
        }
    } catch (_: SerializationException) {
        // Preserve the original precise rejection; unsupported rejection variants issue no output authority.
        QuerySessionState.Closed
    }

/** Narrow projections of canonical documents already validated by KastToolRpcBridge. */
@Serializable
private data class QuerySessionResultAuthority(
    val retention: QueryResultRetention,
    val qualification: QuerySessionQualification? = null,
)

@Serializable private data class QuerySessionQualification(val progress: QueryQualifiedProgressDocument)

@Serializable private data class QuerySessionRejectedAuthority(val rejection: QuerySessionRejection)

@Serializable private data class QuerySessionRejection(val detail: QuerySessionRejectedDetail? = null)

@Serializable private data class QuerySessionRejectedDetail(val evidence: QuerySessionEvidence)

@Serializable
private sealed interface QuerySessionEvidence {
    @Serializable @SerialName("RETAINED") data class Retained(val result: QueryResultReference) : QuerySessionEvidence

    @Serializable
    @SerialName("UNAVAILABLE")
    data class Unavailable(val cause: QueryCompletionRetentionFailure) : QuerySessionEvidence
}

private val authorityJson = Json { ignoreUnknownKeys = true }

private sealed interface QuerySessionFrame {
    data object End : QuerySessionFrame

    data class Record(val bytes: ByteArray) : QuerySessionFrame

    data class Rejected(val failure: ToolRpcFailure) : QuerySessionFrame
}

private fun readQuerySessionFrame(input: InputStream): QuerySessionFrame {
    val bytes = ByteArrayOutputStream()
    while (bytes.size() <= MAXIMUM_PROTOCOL_TEXT_LENGTH) {
        val next = input.read()
        if (next == -1)
            return if (bytes.size() == 0) QuerySessionFrame.End else QuerySessionFrame.Record(bytes.toByteArray())
        if (next == '\n'.code) return QuerySessionFrame.Record(bytes.toByteArray())
        bytes.write(next)
    }
    return QuerySessionFrame.Rejected(ToolRpcFailure.REQUEST_TOO_LARGE)
}
