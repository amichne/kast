package io.github.amichne.kast.cli.broker.protocol.codex

import io.github.amichne.kast.cli.broker.schema.CompiledJsonSchema
import io.github.amichne.kast.cli.broker.schema.JsonConstraintViolation
import io.github.amichne.kast.cli.broker.schema.NetworkntJsonSchemaCompiler
import io.github.amichne.kast.cli.broker.schema.ValidatedJsonValue
import io.github.amichne.kast.kernel.NonEmptyFailures
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.Validation
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal enum class CodexOwnedSchema(val fileName: String) {
    DYNAMIC_TOOL_CALL_PARAMS("DynamicToolCallParams.json"),
    DYNAMIC_TOOL_CALL_RESPONSE("DynamicToolCallResponse.json"),
    INITIALIZE_PARAMS("InitializeParams.json"),
    THREAD_FORK_PARAMS("ThreadForkParams.json"),
    THREAD_FORK_RESPONSE("ThreadForkResponse.json"),
    THREAD_RESUME_PARAMS("ThreadResumeParams.json"),
    THREAD_RESUME_RESPONSE("ThreadResumeResponse.json"),
    THREAD_START_PARAMS("ThreadStartParams.json"),
    THREAD_START_RESPONSE("ThreadStartResponse.json"),
    TURN_INTERRUPT_PARAMS("TurnInterruptParams.json"),
}

internal sealed interface CodexProtocolContractFailure {
    data class Missing(val schema: CodexOwnedSchema) : CodexProtocolContractFailure
    data class Invalid(val schema: CodexOwnedSchema) : CodexProtocolContractFailure
    data object InitializeMutationIncompatible : CodexProtocolContractFailure
}

/** Compiled, complete set of installed Codex schemas for every broker-owned protocol shape. */
internal class CodexProtocolContracts private constructor(
    private val contracts: Map<CodexOwnedSchema, CompiledJsonSchema>,
) {
    internal fun admit(
        schema: CodexOwnedSchema,
        candidate: JsonElement,
    ): Validation<ValidatedJsonValue, JsonConstraintViolation> =
        checkNotNull(contracts[schema]).admit(candidate)

    companion object {
        internal fun define(
            documents: Map<CodexOwnedSchema, JsonObject>,
        ): Validation<CodexProtocolContracts, CodexProtocolContractFailure> {
            val failures = mutableListOf<CodexProtocolContractFailure>()
            val compiled = linkedMapOf<CodexOwnedSchema, CompiledJsonSchema>()
            CodexOwnedSchema.entries.forEach { schema ->
                val document = documents[schema]
                if (document == null) {
                    failures += CodexProtocolContractFailure.Missing(schema)
                } else {
                    when (val compilation = NetworkntJsonSchemaCompiler.compile(document)) {
                        is Refinement.Refined -> compiled[schema] = compilation.value
                        is Refinement.Rejected -> failures +=
                            CodexProtocolContractFailure.Invalid(schema)
                    }
                }
            }
            val initialize = compiled[CodexOwnedSchema.INITIALIZE_PARAMS]
            if (
                failures.isEmpty() && initialize?.admit(INITIALIZE_MUTATION_WITNESS)
                    !is Validation.Validated
            ) {
                failures += CodexProtocolContractFailure.InitializeMutationIncompatible
            }
            return if (failures.isEmpty()) {
                Validation.Validated(CodexProtocolContracts(compiled.toMap()))
            } else {
                Validation.Rejected(
                    NonEmptyFailures.from(failures.first(), failures.drop(1)),
                )
            }
        }

        private val INITIALIZE_MUTATION_WITNESS = buildJsonObject {
            put("clientInfo", buildJsonObject {
                put("name", "kast-broker-contract-probe")
                put("version", "0")
            })
            put("capabilities", buildJsonObject { put("experimentalApi", true) })
        }
    }
}
