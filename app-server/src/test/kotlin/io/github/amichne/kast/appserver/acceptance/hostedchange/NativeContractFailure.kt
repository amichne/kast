package io.github.amichne.kast.appserver.acceptance.hostedchange

import io.github.amichne.kast.appserver.protocol.codex.CodexOwnedSchema
import io.github.amichne.kast.appserver.protocol.codex.CodexProtocolContractFailure
import io.github.amichne.kast.appserver.schema.JsonConstraintViolation
import io.github.amichne.kast.kernel.Validation
import kotlinx.serialization.Serializable

@Serializable
internal enum class NativeContractStage {
    SCHEMA_INVENTORY,
    CONTRACT_DEFINITION,
    INITIALIZE,
    THREAD_START,
    THREAD_STARTED,
}

@Serializable
internal enum class NativeContractKind {
    SCHEMA_FILE_REJECTED,
    MISSING,
    INVALID,
    INITIALIZE_MUTATION_INCOMPATIBLE,
    TOOL_CALL_PROJECTION_INCOMPATIBLE,
    PLAN_APPROVAL_INCOMPATIBLE,
    PAYLOAD_REJECTED,
}

@Serializable
internal data class NativeContractObservation(val contract: NativeContractKind, val schema: CodexOwnedSchema)

@Serializable
internal data class NativeContractFailureDocument(
    val stage: NativeContractStage,
    val observations: List<NativeContractObservation>,
)

internal class NativeContractRejected(val document: NativeContractFailureDocument) :
    RuntimeException("CONTRACT_REJECTED")

internal fun <T> Validation<T, CodexProtocolContractFailure>.nativeContracts(): T =
    when (this) {
        is Validation.Validated -> value
        is Validation.Rejected ->
            throw NativeContractRejected(
                NativeContractFailureDocument(
                    NativeContractStage.CONTRACT_DEFINITION,
                    failures.toList().map { it.observation() },
                )
            )
    }

internal fun <T> Validation<T, JsonConstraintViolation>.nativeEnvelope(
    stage: NativeContractStage,
    schema: CodexOwnedSchema,
): T =
    when (this) {
        is Validation.Validated -> value
        is Validation.Rejected ->
            throw NativeContractRejected(
                NativeContractFailureDocument(
                    stage,
                    listOf(NativeContractObservation(NativeContractKind.PAYLOAD_REJECTED, schema)),
                )
            )
    }

internal fun nativeSchemaFileRejected(schema: CodexOwnedSchema): Nothing =
    throw NativeContractRejected(
        NativeContractFailureDocument(
            NativeContractStage.SCHEMA_INVENTORY,
            listOf(NativeContractObservation(NativeContractKind.SCHEMA_FILE_REJECTED, schema)),
        )
    )

private fun CodexProtocolContractFailure.observation(): NativeContractObservation =
    when (this) {
        is CodexProtocolContractFailure.Missing -> NativeContractObservation(NativeContractKind.MISSING, schema)
        is CodexProtocolContractFailure.Invalid -> NativeContractObservation(NativeContractKind.INVALID, schema)
        CodexProtocolContractFailure.InitializeMutationIncompatible ->
            NativeContractObservation(
                NativeContractKind.INITIALIZE_MUTATION_INCOMPATIBLE,
                CodexOwnedSchema.INITIALIZE_PARAMS,
            )
        is CodexProtocolContractFailure.ToolCallProjectionIncompatible ->
            NativeContractObservation(
                NativeContractKind.TOOL_CALL_PROJECTION_INCOMPATIBLE,
                schema,
            )
        is CodexProtocolContractFailure.PlanApprovalIncompatible ->
            NativeContractObservation(
                NativeContractKind.PLAN_APPROVAL_INCOMPATIBLE,
                schema,
            )
    }
