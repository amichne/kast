package io.github.amichne.kast.appserver.provider

import io.github.amichne.kast.appserver.query.PublicSourceReadIntent
import io.github.amichne.kast.appserver.query.PublicToolContract
import io.github.amichne.kast.appserver.query.PublicToolInputFailure
import io.github.amichne.kast.appserver.query.lower
import io.github.amichne.kast.appserver.schema.ValidatedJsonValue
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.Validation
import io.github.amichne.kast.protocol.contract.CanonicalOperation
import io.github.amichne.kast.protocol.registry.AgentToolInputBinding
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

internal sealed interface KastToolInputFailure {
    data class Source(val reason: io.github.amichne.kast.protocol.contract.SourceReadCause) : KastToolInputFailure

    data class Facade(val reason: PublicToolInputFailure) : KastToolInputFailure

    data object NotObject : KastToolInputFailure

    data object SchemaMismatch : KastToolInputFailure
}

private val facadeOnlyOperations = setOf(CanonicalOperation.QUERY_RUN, CanonicalOperation.DIAGNOSTIC_CHECK)

internal fun admitKastInput(
    operation: CanonicalOperation,
    admitted: ValidatedJsonValue,
    binding: AgentToolInputBinding = AgentToolInputBinding.Canonical,
): Validation<KastInvocationInput, KastToolInputFailure> =
    if (binding is AgentToolInputBinding.Facade) {
        admitFacadeInput(operation, admitted, binding)
    } else if (operation == CanonicalOperation.SOURCE_READ) {
        admitSourceInput(admitted)
    } else if (operation in facadeOnlyOperations) {
        Validation.rejected(KastToolInputFailure.SchemaMismatch)
    } else if (admitted.element is JsonObject) {
        Validation.validated(KastInvocationInput.Canonical(operation, admitted))
    } else {
        Validation.rejected(KastToolInputFailure.NotObject)
    }

/** Re-encoding is permitted only at the effect boundary and for the admitted route. */
internal fun KastInvocationInput.encodeFor(tool: QualifiedKastTool): Refinement<JsonElement, KastToolInputFailure> =
    when (this) {
        is KastInvocationInput.Source -> encodeSourceFor(tool)
        is KastInvocationInput.Facade ->
            if (
                tool.inputBinding == AgentToolInputBinding.Facade(request.identity) &&
                    tool.hostedDefinition.operation == request.identity.operation &&
                    tool.inputSchema.digest == PublicToolContract.schema(request.identity).digest
            )
                Refinement.Refined(PublicToolContract.encode(request))
            else Refinement.Rejected(KastToolInputFailure.SchemaMismatch)
        is KastInvocationInput.Canonical ->
            if (operation in facadeOnlyOperations) {
                Refinement.Rejected(KastToolInputFailure.SchemaMismatch)
            } else if (
                tool.inputBinding == AgentToolInputBinding.Canonical &&
                    operation == tool.hostedDefinition.operation &&
                    arguments.schemaDigest == tool.inputSchema.digest
            ) {
                Refinement.Refined(arguments.element)
            } else {
                Refinement.Rejected(KastToolInputFailure.SchemaMismatch)
            }
    }

private val sourceRequestJson =
    kotlinx.serialization.json.Json {
        classDiscriminator = "type"
        encodeDefaults = true
        ignoreUnknownKeys = false
    }

private fun admitSourceInput(admitted: ValidatedJsonValue): Validation<KastInvocationInput, KastToolInputFailure> =
    when (
        val source =
            if (
                (admitted.element as? JsonObject)?.get("anchor") is JsonObject &&
                    "symbolRef" in (admitted.element["anchor"] as JsonObject)
            ) {
                val intent =
                    try {
                        sourceRequestJson.decodeFromJsonElement(PublicSourceReadIntent.serializer(), admitted.element)
                    } catch (_: kotlinx.serialization.SerializationException) {
                        return Validation.rejected(KastToolInputFailure.SchemaMismatch)
                    }
                intent.lower()
            } else {
                io.github.amichne.kast.protocol.contract.SourceRequestIngress.decodePublic(
                    admitted.element,
                    sourceRequestJson,
                )
            }
    ) {
        is Refinement.Refined -> Validation.validated(KastInvocationInput.Source(source.value, admitted.schemaDigest))
        is Refinement.Rejected -> Validation.rejected(KastToolInputFailure.Source(source.failure))
    }

private fun KastInvocationInput.Source.encodeSourceFor(
    tool: QualifiedKastTool
): Refinement<JsonElement, KastToolInputFailure> =
    if (
        tool.inputBinding == AgentToolInputBinding.Canonical &&
            tool.hostedDefinition.operation == CanonicalOperation.SOURCE_READ &&
            schemaDigest == tool.inputSchema.digest
    )
        Refinement.Refined(
            sourceRequestJson.encodeToJsonElement(
                io.github.amichne.kast.protocol.contract.SourceReadRequest.serializer(),
                request,
            )
        )
    else Refinement.Rejected(KastToolInputFailure.SchemaMismatch)

private fun admitFacadeInput(
    operation: CanonicalOperation,
    admitted: ValidatedJsonValue,
    binding: AgentToolInputBinding.Facade,
): Validation<KastInvocationInput, KastToolInputFailure> =
    if (operation != binding.identity.operation) Validation.rejected(KastToolInputFailure.SchemaMismatch)
    else
        when (val request = PublicToolContract.admit(binding.identity, admitted)) {
            is Refinement.Refined -> Validation.validated(KastInvocationInput.Facade(request.value))
            is Refinement.Rejected -> Validation.rejected(KastToolInputFailure.Facade(request.failure))
        }
