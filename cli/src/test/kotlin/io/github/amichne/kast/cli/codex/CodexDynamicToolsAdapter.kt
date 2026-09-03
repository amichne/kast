package io.github.amichne.kast.cli.codex

import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.OperationQualification
import io.github.amichne.kast.protocol.contract.OperationRejection
import io.github.amichne.kast.protocol.contract.OperationResult
import io.github.amichne.kast.protocol.contract.ProtocolCount
import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.protocol.contract.RelationKindDocument
import io.github.amichne.kast.protocol.contract.RelationReadQualification
import io.github.amichne.kast.protocol.contract.RelationReadRejection
import io.github.amichne.kast.protocol.contract.RelationReadRequest
import io.github.amichne.kast.protocol.contract.RelationReadResult
import io.github.amichne.kast.protocol.contract.SymbolInspectQualification
import io.github.amichne.kast.protocol.contract.SymbolInspectRejection
import io.github.amichne.kast.protocol.contract.SymbolInspectRequest
import io.github.amichne.kast.protocol.contract.SymbolInspectResult
import io.github.amichne.kast.protocol.contract.SymbolInspectTarget
import io.github.amichne.kast.protocol.contract.SymbolDiscoverQualification
import io.github.amichne.kast.protocol.contract.SymbolDiscoverRejection
import io.github.amichne.kast.protocol.contract.SymbolDiscoverRequest
import io.github.amichne.kast.protocol.contract.SymbolDiscoverResult
import io.github.amichne.kast.protocol.contract.SymbolDiscoverTargetDocument
import io.github.amichne.kast.protocol.contract.SymbolDiscoveryDocument
import io.github.amichne.kast.protocol.contract.SymbolDiscoveryMatchDocument
import io.github.amichne.kast.protocol.contract.SymbolNameKindDocument
import io.github.amichne.kast.protocol.registry.CanonicalAgentToolDefinitions
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

private const val KAST_NAMESPACE = "kast"
private const val SPIKE_RESULT_LIMIT = 100

private val boundaryJson = Json {
    explicitNulls = false
    ignoreUnknownKeys = false
}

@Serializable
internal data class SymbolInspectArgumentsDocument(
    val query: String,
)

@Serializable
internal data class RelationReadArgumentsDocument(
    val exactSelector: String,
    val relation: RelationKindArgumentDocument,
)

@Serializable
internal enum class RelationKindArgumentDocument {
    @SerialName("references") REFERENCES,
    @SerialName("callers") CALLERS,
    @SerialName("callees") CALLEES,
    @SerialName("implementations") IMPLEMENTATIONS,
    @SerialName("inheritors") INHERITORS,
    @SerialName("overrides") OVERRIDES,
    @SerialName("type_uses") TYPE_USES,
}

internal sealed interface CodexDynamicToolCallResult {
    data class Succeeded(
        val canonicalJson: String,
    ) : CodexDynamicToolCallResult

    data class Rejected(
        val failure: CodexDynamicToolFailure,
    ) : CodexDynamicToolCallResult
}

internal enum class CodexDynamicToolFailure {
    UNKNOWN_TOOL,
    INVALID_ARGUMENTS,
    KAST_EXCHANGE_REJECTED,
    KAST_OPERATION_REJECTED,
    EXACT_SYMBOL_NOT_UNIQUE,
    SELECTOR_CONTRACT_MISMATCH,
    SELECTOR_NOT_REUSED,
}

internal data class CodexDynamicToolMetrics(
    val dynamicToolCalls: Int,
    val malformedInvocations: Int,
    val correctiveInvocations: Int,
    val selectorRoundTripUnchanged: Boolean,
    val relationTargetNames: List<String>,
    val relationQualificationNames: List<String>,
    val relationRejectionNames: List<String>,
)

/** Two-tool adapter that composes only existing canonical Kast read operations. */
internal class CodexDynamicToolsAdapter(
    private val kast: CanonicalKastReadOperations,
) {
    private var selectorState: SelectorState = SelectorState.Absent
    private var flow: DynamicToolFlow = DynamicToolFlow.AwaitingSymbolInspection
    private var callCount = 0
    private var malformedCount = 0
    private var correctiveCount = 0
    private var relationTargetNames = emptyList<String>()
    private var relationQualificationNames = emptyList<String>()
    private var relationRejectionNames = emptyList<String>()

    /**
     * Proof transition: `(String?, String, JsonElement) -> CodexDynamicToolCallResult`.
     *
     * Establishes exact `kast` namespace identity, one of two closed tool identities, generated
     * argument decoding, and refinement to existing Kast request types before operation execution.
     * [CodexDynamicToolFailure] is the closed expected failure. Raw arguments remain at this
     * app-server boundary.
     */
    fun call(
        namespace: String?,
        tool: String,
        arguments: JsonElement,
    ): CodexDynamicToolCallResult {
        callCount += 1
        val identity = KastDynamicTool.from(namespace, tool)
            ?: return rejected(CodexDynamicToolFailure.UNKNOWN_TOOL, malformed = true)
        if (!flow.expects(identity)) {
            correctiveCount += 1
        }
        val result = when (identity) {
            KastDynamicTool.SYMBOL_INSPECT -> inspect(arguments)
            KastDynamicTool.RELATION_READ -> relation(arguments)
        }
        if (result is CodexDynamicToolCallResult.Succeeded) {
            flow = flow.complete(identity)
        }
        return result
    }

    fun metrics(): CodexDynamicToolMetrics = CodexDynamicToolMetrics(
        dynamicToolCalls = callCount,
        malformedInvocations = malformedCount,
        correctiveInvocations = correctiveCount,
        selectorRoundTripUnchanged = selectorState is SelectorState.Reused,
        relationTargetNames = relationTargetNames,
        relationQualificationNames = relationQualificationNames,
        relationRejectionNames = relationRejectionNames,
    )

    private fun inspect(arguments: JsonElement): CodexDynamicToolCallResult {
        val document = decode<SymbolInspectArgumentsDocument>(arguments)
            ?: return rejected(CodexDynamicToolFailure.INVALID_ARGUMENTS, malformed = true)
        val query = document.query.protocolText()
            ?: return rejected(CodexDynamicToolFailure.INVALID_ARGUMENTS, malformed = true)
        val limit = ProtocolCount.parse(SPIKE_RESULT_LIMIT).refinedValue()
        val discovery = when (
            val attempt = kast.discover(
                SymbolDiscoverRequest(
                    SymbolDiscoverTargetDocument.Name(
                        query,
                        SymbolNameKindDocument.SYMBOL,
                        SymbolDiscoveryMatchDocument.EXACT_NAME,
                    ),
                    limit,
                ),
            )
        ) {
            is CanonicalKastReadAttempt.Read -> attempt.value
            is CanonicalKastReadAttempt.Rejected -> return rejected(
                CodexDynamicToolFailure.KAST_EXCHANGE_REJECTED,
            )
        }
        val discoveryResult = discovery.outcome.completePayload()
            ?: return rejected(CodexDynamicToolFailure.KAST_OPERATION_REJECTED)
        val candidates = discoveryResult.items.values.filterIsInstance<
            SymbolDiscoveryDocument.Declaration
            >()
        if (candidates.size != 1) {
            return rejected(CodexDynamicToolFailure.EXACT_SYMBOL_NOT_UNIQUE)
        }
        val inspection = when (
            val attempt = kast.inspect(
                SymbolInspectRequest(
                    SymbolInspectTarget.Candidate(candidates.single().candidateSelector),
                ),
            )
        ) {
            is CanonicalKastReadAttempt.Read -> attempt.value
            is CanonicalKastReadAttempt.Rejected -> return rejected(
                CodexDynamicToolFailure.KAST_EXCHANGE_REJECTED,
            )
        }
        val inspected = inspection.outcome.completePayload()
            ?: return rejected(CodexDynamicToolFailure.KAST_OPERATION_REJECTED)
        selectorState = SelectorState.Produced(inspected.symbol.selector)
        return CodexDynamicToolCallResult.Succeeded(inspection.canonicalJson)
    }

    private fun relation(arguments: JsonElement): CodexDynamicToolCallResult {
        val document = decode<RelationReadArgumentsDocument>(arguments)
            ?: return rejected(CodexDynamicToolFailure.INVALID_ARGUMENTS, malformed = true)
        val selector = document.exactSelector.protocolText()
            ?: return rejected(CodexDynamicToolFailure.INVALID_ARGUMENTS, malformed = true)
        val produced = selectorState as? SelectorState.Produced
            ?: return rejected(CodexDynamicToolFailure.SELECTOR_NOT_REUSED)
        if (selector != produced.selector) {
            return rejected(CodexDynamicToolFailure.SELECTOR_NOT_REUSED)
        }
        val request = RelationReadRequest(
            selector,
            document.relation.toContract(),
            ProtocolCount.parse(SPIKE_RESULT_LIMIT).refinedValue(),
        )
        val relation = when (val attempt = kast.relation(request)) {
            is CanonicalKastReadAttempt.Read -> attempt.value
            is CanonicalKastReadAttempt.Rejected -> return rejected(
                CodexDynamicToolFailure.KAST_EXCHANGE_REJECTED,
            )
        }
        val result = when (val outcome = relation.outcome) {
            is OperationOutcome.Complete -> outcome.evidence.payload
            is OperationOutcome.Qualified -> {
                relationQualificationNames = outcome.qualification.limitations.map { it.name }
                return rejected(CodexDynamicToolFailure.KAST_OPERATION_REJECTED)
            }
            is OperationOutcome.Rejected -> {
                relationRejectionNames = listOf(outcome.reason.name)
                return rejected(CodexDynamicToolFailure.KAST_OPERATION_REJECTED)
            }
        }
        selectorState = SelectorState.Reused(produced.selector)
        relationTargetNames = result.relations.values.map { it.target.name.value }
        return CodexDynamicToolCallResult.Succeeded(relation.canonicalJson)
    }

    private inline fun <reified Document> decode(arguments: JsonElement): Document? = try {
        boundaryJson.decodeFromJsonElement(
            kotlinx.serialization.serializer<Document>(),
            arguments,
        )
    } catch (_: SerializationException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }

    private fun rejected(
        failure: CodexDynamicToolFailure,
        malformed: Boolean = false,
    ): CodexDynamicToolCallResult.Rejected {
        if (malformed) malformedCount += 1
        return CodexDynamicToolCallResult.Rejected(failure)
    }

    private sealed interface SelectorState {
        data object Absent : SelectorState
        data class Produced(val selector: ProtocolText) : SelectorState
        data class Reused(val selector: ProtocolText) : SelectorState
    }

    private sealed interface DynamicToolFlow {
        data object AwaitingSymbolInspection : DynamicToolFlow
        data object AwaitingRelationRead : DynamicToolFlow
        data object Completed : DynamicToolFlow

        fun expects(tool: KastDynamicTool): Boolean = when (this) {
            AwaitingSymbolInspection -> tool == KastDynamicTool.SYMBOL_INSPECT
            AwaitingRelationRead -> tool == KastDynamicTool.RELATION_READ
            Completed -> false
        }

        fun complete(tool: KastDynamicTool): DynamicToolFlow = when {
            this == AwaitingSymbolInspection && tool == KastDynamicTool.SYMBOL_INSPECT ->
                AwaitingRelationRead
            this == AwaitingRelationRead && tool == KastDynamicTool.RELATION_READ -> Completed
            else -> this
        }
    }

    private enum class KastDynamicTool {
        SYMBOL_INSPECT,
        RELATION_READ,
        ;

        companion object {
            fun from(namespace: String?, tool: String): KastDynamicTool? = when {
                namespace != KAST_NAMESPACE -> null
                tool == CanonicalAgentToolDefinitions.symbolInspect.name.value -> SYMBOL_INSPECT
                tool == CanonicalAgentToolDefinitions.relationRead.name.value -> RELATION_READ
                else -> null
            }
        }
    }
}

private fun String.protocolText(): ProtocolText? = when (val refined = ProtocolText.parse(this)) {
    is Refinement.Refined -> refined.value
    is Refinement.Rejected -> null
}

private fun RelationKindArgumentDocument.toContract(): RelationKindDocument = when (this) {
    RelationKindArgumentDocument.REFERENCES -> RelationKindDocument.REFERENCES
    RelationKindArgumentDocument.CALLERS -> RelationKindDocument.CALLERS
    RelationKindArgumentDocument.CALLEES -> RelationKindDocument.CALLEES
    RelationKindArgumentDocument.IMPLEMENTATIONS -> RelationKindDocument.IMPLEMENTATIONS
    RelationKindArgumentDocument.INHERITORS -> RelationKindDocument.INHERITORS
    RelationKindArgumentDocument.OVERRIDES -> RelationKindDocument.OVERRIDES
    RelationKindArgumentDocument.TYPE_USES -> RelationKindDocument.TYPE_USES
}

private fun <Result : OperationResult, Qualification : OperationQualification, Rejection : OperationRejection>
    OperationOutcome<Result, Qualification, Rejection>.completePayload(): Result? = when (this) {
    is OperationOutcome.Complete -> evidence.payload
    is OperationOutcome.Qualified,
    is OperationOutcome.Rejected,
        -> null
}

private fun <Value, Failure> Refinement<Value, Failure>.refinedValue(): Value = when (this) {
    is Refinement.Refined -> value
    is Refinement.Rejected -> error("constant refinement failed: $failure")
}
