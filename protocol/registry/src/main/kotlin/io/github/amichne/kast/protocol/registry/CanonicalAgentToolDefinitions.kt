package io.github.amichne.kast.protocol.registry

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.protocol.contract.RelationKindDocument

private const val MAX_AGENT_TOOL_NAME_LENGTH = 64
private val AGENT_TOOL_NAME_PATTERN = Regex("[a-z][a-z0-9_]*")
private val AGENT_TOOL_INPUT_NAME_PATTERN = Regex("[a-z][A-Za-z0-9]*")

enum class AgentToolNameFailure {
    BLANK,
    TOO_LONG,
    INVALID_FORMAT,
}

/** One lowercase snake-case identity exposed by an agent tool adapter. */
@JvmInline
value class AgentToolName private constructor(
    val value: String,
) {
    companion object {
        /**
         * Proof transition: `String -> Refinement<AgentToolName, AgentToolNameFailure>`.
         *
         * Establishes a non-blank, bounded lowercase snake-case tool identity.
         * [AgentToolNameFailure] is the closed expected failure. Raw extraction is permitted only
         * at an agent protocol projection boundary.
         */
        fun parse(raw: String): Refinement<AgentToolName, AgentToolNameFailure> = when {
            raw.isBlank() -> Refinement.Rejected(AgentToolNameFailure.BLANK)
            raw.length > MAX_AGENT_TOOL_NAME_LENGTH ->
                Refinement.Rejected(AgentToolNameFailure.TOO_LONG)
            !AGENT_TOOL_NAME_PATTERN.matches(raw) ->
                Refinement.Rejected(AgentToolNameFailure.INVALID_FORMAT)
            else -> Refinement.Refined(AgentToolName(raw))
        }
    }
}

enum class AgentToolInputNameFailure {
    BLANK,
    TOO_LONG,
    INVALID_FORMAT,
}

/** One lower-camel-case property identity in a modeled agent tool input. */
@JvmInline
value class AgentToolInputName private constructor(
    val value: String,
) {
    companion object {
        /**
         * Proof transition: `String -> Refinement<AgentToolInputName,
         * AgentToolInputNameFailure>`.
         *
         * Establishes a non-blank, bounded lower-camel-case input property identity.
         * [AgentToolInputNameFailure] is the closed expected failure. Raw extraction is permitted
         * only at an agent protocol schema projection boundary.
         */
        fun parse(raw: String): Refinement<AgentToolInputName, AgentToolInputNameFailure> = when {
            raw.isBlank() -> Refinement.Rejected(AgentToolInputNameFailure.BLANK)
            raw.length > MAX_AGENT_TOOL_NAME_LENGTH ->
                Refinement.Rejected(AgentToolInputNameFailure.TOO_LONG)
            !AGENT_TOOL_INPUT_NAME_PATTERN.matches(raw) ->
                Refinement.Rejected(AgentToolInputNameFailure.INVALID_FORMAT)
            else -> Refinement.Refined(AgentToolInputName(raw))
        }
    }
}

data class AgentToolTextInput(
    val name: AgentToolInputName,
    val description: ProtocolText,
)

data class AgentToolRelationInput(
    val name: AgentToolInputName,
    val description: ProtocolText,
) {
    val kinds: List<RelationKindDocument> = RelationKindDocument.entries
}

sealed interface AgentToolInput {
    data class ExactSymbolName(
        val query: AgentToolTextInput,
    ) : AgentToolInput

    data class ExactRelation(
        val exactSelector: AgentToolTextInput,
        val relation: AgentToolRelationInput,
    ) : AgentToolInput
}

/** A non-empty, ordered composition of existing canonical operation models. */
class AgentToolExecution private constructor(
    val operations: List<OperationDefinition<*, *, *, *, *>>,
) {
    val output: OperationDefinition<*, *, *, *, *>
        get() = operations.last()

    fun then(operation: OperationDefinition<*, *, *, *, *>): AgentToolExecution =
        AgentToolExecution(operations + operation)

    companion object {
        fun start(operation: OperationDefinition<*, *, *, *, *>): AgentToolExecution =
            AgentToolExecution(listOf(operation))
    }
}

data class AgentToolDefinition(
    val name: AgentToolName,
    val description: ProtocolText,
    val input: AgentToolInput,
    val execution: AgentToolExecution,
)

/** Canonical agent-facing read tools projected from the existing Kast operation models. */
object CanonicalAgentToolDefinitions {
    val symbolInspect = AgentToolDefinition(
        name = toolName("symbol_inspect"),
        description = text(
            "Find one exact Kotlin symbol by source name and return canonical Kast " +
                "symbol.inspect JSON; retain its returned JSON and opaque selector for " +
                "the next call in the same exec program.",
        ),
        input = AgentToolInput.ExactSymbolName(
            AgentToolTextInput(
                inputName("query"),
                text("Exact Kotlin source name to discover."),
            ),
        ),
        execution = AgentToolExecution.start(CanonicalOperationDefinitions.symbolDiscover)
            .then(CanonicalOperationDefinitions.symbolInspect),
    )

    val relationRead = AgentToolDefinition(
        name = toolName("relation_read"),
        description = text(
            "Read one-hop Kast semantic relations from the exact selector returned by " +
                "symbol_inspect, passed as exactSelector, without inspecting again.",
        ),
        input = AgentToolInput.ExactRelation(
            exactSelector = AgentToolTextInput(
                inputName("exactSelector"),
                text("Opaque symbol.selector returned by kast.symbol_inspect."),
            ),
            relation = AgentToolRelationInput(
                inputName("relation"),
                text("One canonical Kast relation kind."),
            ),
        ),
        execution = AgentToolExecution.start(CanonicalOperationDefinitions.relationRead),
    )

    val all: List<AgentToolDefinition> = listOf(symbolInspect, relationRead)

    private fun toolName(raw: String): AgentToolName = refined(AgentToolName.parse(raw))

    private fun inputName(raw: String): AgentToolInputName = refined(AgentToolInputName.parse(raw))

    private fun text(raw: String): ProtocolText = refined(ProtocolText.parse(raw))

    private fun <Value, Failure> refined(value: Refinement<Value, Failure>): Value = when (value) {
        is Refinement.Refined -> value.value
        is Refinement.Rejected -> error("Invalid canonical agent tool metadata: ${value.failure}")
    }
}
