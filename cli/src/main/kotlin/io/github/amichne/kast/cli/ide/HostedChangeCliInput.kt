package io.github.amichne.kast.cli.ide

import io.github.amichne.kast.appserver.ide.ExistingIdeFailure
import io.github.amichne.kast.appserver.ide.ExistingIdeOperation
import io.github.amichne.kast.appserver.ide.HostedMutationOperation
import io.github.amichne.kast.appserver.ide.HostedPlanIdentity
import io.github.amichne.kast.cli.command.CliRequestDocumentInput
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.wire.presentation.HostedRequestEffect
import io.github.amichne.kast.protocol.wire.presentation.PreparedOperationRequest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import tools.jackson.core.StreamReadFeature
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.json.JsonMapper

private const val MAXIMUM_REQUEST_BYTES = 1024 * 1024

internal class HostedCliInput(val argv: List<String>, val input: CliRequestDocumentInput) {
    fun operation(request: PreparedOperationRequest): Refinement<ExistingIdeOperation, ExistingIdeFailure> =
        when (val effect = request.hostedEffect) {
            is HostedRequestEffect.ChangePlan -> ExistingIdeOperation.Plan.admit(request)
            is HostedRequestEffect.ChangeApply ->
                mutation(request, HostedMutationOperation.CHANGE_APPLY, effect.planIdentity.value)
            is HostedRequestEffect.ChangeRecover ->
                mutation(request, HostedMutationOperation.CHANGE_RECOVER, effect.planIdentity.value)
            is HostedRequestEffect.Operation -> ExistingIdeOperation.Read.admit(request)
        }
}

private fun mutation(
    request: PreparedOperationRequest,
    kind: HostedMutationOperation,
    identity: String,
): Refinement<ExistingIdeOperation, ExistingIdeFailure> =
    when (val parsed = HostedPlanIdentity.parse(identity)) {
        is Refinement.Refined -> ExistingIdeOperation.Mutation.admit(request, kind, parsed.value)
        is Refinement.Rejected -> parsed
    }

private val hostedInputMapper =
    JsonMapper.builder()
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
        .build()

/** Removed private approval modes are rejected rather than interpreted as canonical requests. */
private val retiredFlags = setOf("--hosted-approval-prepare", "--hosted-approved-invocation")
private val inputFailure = Refinement.Rejected(ExistingIdeFailure.INVALID_REQUEST)

internal fun admitHostedCliInput(
    argv: List<String>,
    input: CliRequestDocumentInput,
): Refinement<HostedCliInput, ExistingIdeFailure> {
    val args = if (argv.firstOrNull() == "--") argv.drop(1) else argv
    if (args.any { it in retiredFlags } || args.count { it == "--stdin" } > 1) return inputFailure
    if (args.firstOrNull() != "change" || args.any { it == "--help" || it == "-h" })
        return Refinement.Refined(HostedCliInput(args, input))
    val supplied = if (input is CliRequestDocumentInput.Deferred) input.read() else input
    if (supplied !is CliRequestDocumentInput.Provided) return inputFailure
    return try {
        if (supplied.document.toByteArray(Charsets.UTF_8).size > MAXIMUM_REQUEST_BYTES) return inputFailure
        hostedInputMapper.readTree(supplied.document)
        if (Json.parseToJsonElement(supplied.document) !is JsonObject) return inputFailure
        Refinement.Refined(HostedCliInput(args.filterNot { it == "--stdin" }, supplied))
    } catch (_: RuntimeException) {
        inputFailure
    }
}
