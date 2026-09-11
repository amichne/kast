package io.github.amichne.kast.appserver.provider

import io.github.amichne.kast.appserver.query.PublicToolContract
import io.github.amichne.kast.appserver.query.PublicToolInputFailure
import io.github.amichne.kast.protocol.registry.AgentToolInputBinding
import io.github.amichne.kast.appserver.query.PublicQueryContract
import io.github.amichne.kast.appserver.query.PublicQueryInputFailure
import io.github.amichne.kast.appserver.schema.ValidatedJsonValue
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.Validation
import io.github.amichne.kast.protocol.contract.CanonicalOperation
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

internal sealed interface KastToolInputFailure {
    data class Facade(val reason: PublicToolInputFailure) : KastToolInputFailure
    data object NotObject : KastToolInputFailure
    data object SchemaMismatch : KastToolInputFailure
    data class Query(val reason: PublicQueryInputFailure) : KastToolInputFailure
}

internal fun admitKastInput(
    operation: CanonicalOperation,
    admitted: ValidatedJsonValue,
    binding: AgentToolInputBinding = AgentToolInputBinding.Canonical,
): Validation<KastInvocationInput, KastToolInputFailure> =
    if (binding is AgentToolInputBinding.Facade) {
        if (operation != binding.identity.operation) Validation.rejected(KastToolInputFailure.SchemaMismatch)
        else when (val request = PublicToolContract.admit(binding.identity, admitted)) {
            is Refinement.Refined -> Validation.validated(KastInvocationInput.Facade(request.value))
            is Refinement.Rejected -> Validation.rejected(KastToolInputFailure.Facade(request.failure))
        }
    } else if (operation == CanonicalOperation.QUERY_RUN) {
        when (val query = PublicQueryContract.admit(admitted)) {
            is Refinement.Refined -> Validation.validated(KastInvocationInput.Query(query.value))
            is Refinement.Rejected -> Validation.rejected(KastToolInputFailure.Query(query.failure))
        }
    } else if (admitted.element is JsonObject) {
        Validation.validated(KastInvocationInput.Canonical(operation, admitted))
    } else {
        Validation.rejected(KastToolInputFailure.NotObject)
    }

/** Re-encoding is permitted only at the effect boundary and for the admitted route. */
internal fun KastInvocationInput.encodeFor(
    tool: QualifiedKastTool,
): Refinement<JsonElement, KastToolInputFailure> = when (this) {
    is KastInvocationInput.Facade -> if (
        tool.inputBinding == AgentToolInputBinding.Facade(request.identity) &&
        tool.hostedDefinition.operation == request.identity.operation &&
        tool.inputSchema.digest == PublicToolContract.schema(request.identity).digest
    ) Refinement.Refined(PublicToolContract.encode(request))
    else Refinement.Rejected(KastToolInputFailure.SchemaMismatch)
    is KastInvocationInput.Query -> if (
        tool.inputBinding == AgentToolInputBinding.Canonical &&
        tool.hostedDefinition.operation == CanonicalOperation.QUERY_RUN &&
        tool.inputSchema.digest == PublicQueryContract.schema.digest
    ) {
        Refinement.Refined(PublicQueryContract.encode(request))
    } else {
        Refinement.Rejected(KastToolInputFailure.SchemaMismatch)
    }
    is KastInvocationInput.Canonical -> if (
        tool.inputBinding == AgentToolInputBinding.Canonical &&
        operation == tool.hostedDefinition.operation &&
        operation != CanonicalOperation.QUERY_RUN &&
        arguments.schemaDigest == tool.inputSchema.digest
    ) {
        Refinement.Refined(arguments.element)
    } else {
        Refinement.Rejected(KastToolInputFailure.SchemaMismatch)
    }
}
