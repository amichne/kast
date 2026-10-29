package io.github.amichne.kast.cli.publicshell

import io.github.amichne.kast.cli.rpc.ToolRpcFailure
import io.github.amichne.kast.cli.rpc.ToolRpcReply
import io.github.amichne.kast.protocol.contract.QuerySemanticHeaderDocumentCoverageType
import io.github.amichne.kast.protocol.contract.QuerySemanticHeaderDocumentOriginalOutcome
import io.github.amichne.kast.protocol.contract.ReadResumeActionDocument
import io.github.amichne.kast.protocol.wire.presentation.CanonicalQueryCliDocuments
import io.github.amichne.kast.protocol.wire.presentation.CanonicalSourceReadCliDocuments
import io.github.amichne.kast.protocol.wire.presentation.QueryPresentationHeaderDocument
import io.github.amichne.kast.protocol.wire.presentation.QueryPresentationHeaderStop
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement

@Suppress("MagicNumber") // Published shell status constants.
internal enum class PublicShellExit(val code: Int) {
    COMPLETE(0),
    DEFECT(1),
    INPUT(2),
    WORKSPACE(3),
    RUNTIME(4),
    IO(5),
    PROTOCOL(6),
    BOOTSTRAP(9),
    SEMANTIC(10),
    DELIVERY(11),
    ELAPSED(124),
    INTERRUPTED(130),
    TERMINATED(143),
}

internal enum class PublicElapsedGrant {
    ABSENT,
    EXPLICIT,
}

/** Called only after the shared RPC result owner admits the complete canonical document. */
internal fun classifyPublicReply(
    reply: ToolRpcReply,
    grant: PublicElapsedGrant = PublicElapsedGrant.ABSENT,
): PublicShellExit =
    when (reply) {
        is ToolRpcReply.Catalog -> PublicShellExit.COMPLETE
        is ToolRpcReply.Rejected ->
            when (reply.failure) {
                ToolRpcFailure.INVALID_COMMAND,
                ToolRpcFailure.REQUEST_TOO_LARGE,
                ToolRpcFailure.UNKNOWN_TOOL,
                ToolRpcFailure.INVALID_ARGUMENTS,
                ToolRpcFailure.QUERY_SESSION_TRANSITION_REJECTED -> PublicShellExit.INPUT
                ToolRpcFailure.OUT_OF_SCOPE -> PublicShellExit.WORKSPACE
                ToolRpcFailure.INSTALLATION_STOPPED,
                ToolRpcFailure.CATALOG_UNAVAILABLE -> PublicShellExit.RUNTIME
                ToolRpcFailure.OBSERVATION_UNAVAILABLE,
                ToolRpcFailure.INVOCATION_FAILED -> PublicShellExit.DEFECT
                ToolRpcFailure.INVALID_RESULT -> PublicShellExit.PROTOCOL
            }
        is ToolRpcReply.Complete -> classifyDocument(reply.document, grant)
        is ToolRpcReply.Qualified -> classifyDocument(reply.document, grant)
        is ToolRpcReply.RejectedDocument -> classifyDocument(reply.document, grant)
    }

private fun classifyDocument(document: JsonObject, grant: PublicElapsedGrant): PublicShellExit =
    try {
        val shape = classificationJson.decodeFromJsonElement<PublicOutcomeShape>(document)
        when (shape.type) {
            PublicOutcomeType.HOST_REJECTED ->
                classifyPublicHostRejection(classificationJson.decodeFromJsonElement(document))
            PublicOutcomeType.HEADER -> classifyHeader(classificationJson.decodeFromJsonElement(document), grant)
            null -> shape.boundary?.let(::classifyBoundary) ?: classifyOperation(document, grant)
        }
    } catch (_: SerializationException) {
        PublicShellExit.PROTOCOL
    }

private fun classifyBoundary(category: PublicFailureCategory): PublicShellExit =
    when (category) {
        PublicFailureCategory.USAGE -> PublicShellExit.INPUT
        PublicFailureCategory.ROOT -> PublicShellExit.WORKSPACE
        PublicFailureCategory.RUNTIME -> PublicShellExit.RUNTIME
        PublicFailureCategory.TRANSPORT -> PublicShellExit.IO
        PublicFailureCategory.PROTOCOL -> PublicShellExit.PROTOCOL
        PublicFailureCategory.BOOTSTRAP -> PublicShellExit.BOOTSTRAP
    }

private fun classifyOperation(document: JsonObject, grant: PublicElapsedGrant): PublicShellExit =
    when (classificationJson.decodeFromJsonElement<PublicOperationFacts>(document).operation) {
        PublicSemanticOperation.QUERY ->
            classifySemantic(CanonicalQueryCliDocuments.classificationFacts(document), grant)
        PublicSemanticOperation.SOURCE ->
            classifySource(CanonicalSourceReadCliDocuments.classificationFacts(document), grant)
    }

private fun classifyHeader(header: QueryPresentationHeaderDocument, grant: PublicElapsedGrant): PublicShellExit {
    if (!header.hasConsistentOutcome()) return PublicShellExit.PROTOCOL
    // The header contract carries interpretation text. Refine its finite public values before policy.
    val interpretation =
        when (header.semantic.completion.interpretation?.value) {
            null -> null
            "QUERY_RESULT" -> PublicHeaderInterpretation.QUERY_RESULT
            "SOURCE_RELATIONSHIPS" -> PublicHeaderInterpretation.SOURCE_RELATIONSHIPS
            "POLICY_REJECTED_EVIDENCE" -> PublicHeaderInterpretation.POLICY_REJECTED_EVIDENCE
            else -> return PublicShellExit.PROTOCOL
        }
    if (grant == PublicElapsedGrant.EXPLICIT && header.semantic.blockers.stop?.value == "TIME_LIMIT")
        return PublicShellExit.ELAPSED
    if (
        interpretation == PublicHeaderInterpretation.POLICY_REJECTED_EVIDENCE ||
            header.semantic.coverage.type == QuerySemanticHeaderDocumentCoverageType.QUALIFIED
    )
        return PublicShellExit.SEMANTIC
    return classifyHeaderDelivery(header)
}

private fun classifyHeaderDelivery(header: QueryPresentationHeaderDocument): PublicShellExit =
    when (header.semantic.originalOutcome) {
        QuerySemanticHeaderDocumentOriginalOutcome.QUALIFIED,
        QuerySemanticHeaderDocumentOriginalOutcome.REJECTED,
        QuerySemanticHeaderDocumentOriginalOutcome.REJECTED_DOCUMENT -> PublicShellExit.SEMANTIC
        QuerySemanticHeaderDocumentOriginalOutcome.COMPLETE ->
            when (header.deliveryStop) {
                QueryPresentationHeaderStop.BYTE_LIMIT -> PublicShellExit.DELIVERY
                QueryPresentationHeaderStop.DETAIL_SECTION ->
                    if (header.detail == null) PublicShellExit.DELIVERY else PublicShellExit.COMPLETE
            }
    }

@Serializable private data class PublicOperationFacts(val operation: PublicSemanticOperation)

@Serializable
private enum class PublicSemanticOperation {
    @SerialName("query.run") QUERY,
    @SerialName("source.read") SOURCE,
}

/** Narrow typed projections of schema-admitted results. They neither rewrite nor remove output fields. */
@Serializable
private data class PublicOutcomeShape(val type: PublicOutcomeType? = null, val boundary: PublicFailureCategory? = null)

@Serializable
private enum class PublicOutcomeType {
    @SerialName("QUERY_PRESENTATION_HEADER") HEADER,
    HOST_REJECTED,
}

@Serializable
private enum class PublicFailureCategory {
    @SerialName("usage") USAGE,
    @SerialName("root") ROOT,
    @SerialName("runtime") RUNTIME,
    @SerialName("transport") TRANSPORT,
    @SerialName("protocol") PROTOCOL,
    @SerialName("bootstrap") BOOTSTRAP,
}

private enum class PublicHeaderInterpretation {
    QUERY_RESULT,
    SOURCE_RELATIONSHIPS,
    POLICY_REJECTED_EVIDENCE,
}

internal fun classifyResumeAction(action: ReadResumeActionDocument): PublicShellExit =
    when (action) {
        ReadResumeActionDocument.RESUME -> PublicShellExit.COMPLETE
        ReadResumeActionDocument.INCREASE_EXECUTION_BUDGET -> PublicShellExit.DELIVERY
    }

private val classificationJson = Json { ignoreUnknownKeys = true }
