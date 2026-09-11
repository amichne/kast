package io.github.amichne.kast.cli.ide

import io.github.amichne.kast.cli.PreparedCliRequest
import io.github.amichne.kast.cli.command.CliRequestDocumentInput
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.CanonicalOperation
import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import tools.jackson.core.StreamReadFeature
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.json.JsonMapper

private const val MAXIMUM_ASSERTION_BYTES = 16384
private const val ED25519_SIGNATURE_BYTES = 64
private const val MAXIMUM_REQUEST_BYTES = 1024 * 1024

/** Syntax proof only: the hosted owner remains the signature and challenge authority. */
class HostedApprovalAssertion private constructor(val value: String) {
    companion object {
        fun parse(raw: String): Refinement<HostedApprovalAssertion, ExistingIdeFailure> {
            if (raw.length !in 1..MAXIMUM_ASSERTION_BYTES || !raw.matches(Regex("[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]{86}")))
                return Refinement.Rejected(ExistingIdeFailure.APPROVAL_REJECTED)
            return try {
                val parts = raw.split('.')
                val decoder = Base64.getUrlDecoder()
                val encoder = Base64.getUrlEncoder().withoutPadding()
                val payload = decoder.decode(parts[0])
                val signature = decoder.decode(parts[1])
                if (payload.isEmpty() || signature.size != ED25519_SIGNATURE_BYTES)
                    return Refinement.Rejected(ExistingIdeFailure.APPROVAL_REJECTED)
                if (encoder.encodeToString(payload) != parts[0] || encoder.encodeToString(signature) != parts[1])
                    Refinement.Rejected(ExistingIdeFailure.APPROVAL_REJECTED)
                else Refinement.Refined(HostedApprovalAssertion(raw))
            } catch (_: IllegalArgumentException) {
                Refinement.Rejected(ExistingIdeFailure.APPROVAL_REJECTED)
            }
        }
    }
}

class HostedPlanIdentity private constructor(val value: String) {
    companion object {
        fun parse(raw: String): Refinement<HostedPlanIdentity, ExistingIdeFailure> =
            if (raw.matches(Regex("plan:[0-9a-f]{64}"))) Refinement.Refined(HostedPlanIdentity(raw))
            else Refinement.Rejected(ExistingIdeFailure.APPROVAL_REJECTED)
    }
}

enum class HostedMutationOperation(val canonical: CanonicalOperation) {
    CHANGE_APPLY(CanonicalOperation.CHANGE_APPLY),
    CHANGE_RECOVER(CanonicalOperation.CHANGE_RECOVER),
}

internal sealed interface HostedChangeMode {
    data object Canonical : HostedChangeMode

    data class Prepare(val kind: HostedMutationOperation, val identity: HostedPlanIdentity) : HostedChangeMode

    data class Approved(
        val kind: HostedMutationOperation,
        val identity: HostedPlanIdentity,
        val assertion: HostedApprovalAssertion,
    ) : HostedChangeMode
}

internal class HostedCliInput(val argv: List<String>, val input: CliRequestDocumentInput, val mode: HostedChangeMode) {
    fun operation(request: PreparedCliRequest): Refinement<ExistingIdeOperation, ExistingIdeFailure> =
        when (val selected = mode) {
            HostedChangeMode.Canonical ->
                when (request.operation) {
                    CanonicalOperation.CHANGE_PLAN -> ExistingIdeOperation.Plan.admit(request)
                    CanonicalOperation.CHANGE_APPLY,
                    CanonicalOperation.CHANGE_RECOVER -> Refinement.Rejected(ExistingIdeFailure.APPROVAL_REQUIRED)
                    else -> ExistingIdeOperation.Read.admit(request)
                }
            is HostedChangeMode.Prepare ->
                if (request.operation == selected.kind.canonical)
                    Refinement.Refined(ExistingIdeOperation.ApprovalPreparation(selected.kind, selected.identity))
                else Refinement.Rejected(ExistingIdeFailure.APPROVAL_REJECTED)
            is HostedChangeMode.Approved ->
                if (request.operation == selected.kind.canonical)
                    Refinement.Refined(
                        ExistingIdeOperation.ApprovedMutation(
                            request = request,
                            kind = selected.kind,
                            identity = selected.identity,
                            assertion = selected.assertion,
                        )
                    )
                else Refinement.Rejected(ExistingIdeFailure.APPROVAL_REJECTED)
        }
}

private val hostedInputMapper =
    JsonMapper.builder()
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
        .build()

private enum class HostedInputMode {
    CANONICAL,
    PREPARE,
    APPROVED,
}

private sealed interface HostedArgumentSelection {
    val argv: List<String>

    data class PassThrough(override val argv: List<String>) : HostedArgumentSelection

    data class Change(override val argv: List<String>, val mode: HostedInputMode) : HostedArgumentSelection
}

private const val PREPARE_FLAG = "--hosted-approval-prepare"
private const val APPROVED_FLAG = "--hosted-approved-invocation"
private val privateFlags = setOf(PREPARE_FLAG, APPROVED_FLAG)
private val inputFailure = Refinement.Rejected(ExistingIdeFailure.APPROVAL_REJECTED)

internal fun admitHostedCliInput(
    argv: List<String>,
    input: CliRequestDocumentInput,
): Refinement<HostedCliInput, ExistingIdeFailure> {
    val selection =
        when (val admitted = selectHostedArguments(argv)) {
            is Refinement.Refined -> admitted.value
            is Refinement.Rejected -> return admitted
        }
    if (selection is HostedArgumentSelection.PassThrough)
        return Refinement.Refined(HostedCliInput(selection.argv, input, HostedChangeMode.Canonical))
    selection as HostedArgumentSelection.Change
    val document =
        when (val parsed = readHostedDocument(input)) {
            is Refinement.Refined -> parsed.value
            is Refinement.Rejected -> return parsed
        }
    return when (selection.mode) {
        HostedInputMode.CANONICAL ->
            Refinement.Refined(
                HostedCliInput(
                    selection.argv,
                    CliRequestDocumentInput.Provided(document.toString()),
                    HostedChangeMode.Canonical,
                )
            )
        HostedInputMode.PREPARE,
        HostedInputMode.APPROVED -> admitMutationInput(selection, document)
    }
}

private fun selectHostedArguments(argv: List<String>): Refinement<HostedArgumentSelection, ExistingIdeFailure> {
    val args = if (argv.firstOrNull() == "--") argv.drop(1) else argv
    if (args.firstOrNull() != "change")
        return if (args.any { it in privateFlags }) inputFailure
        else Refinement.Refined(HostedArgumentSelection.PassThrough(args))
    if (args.any { it in setOf("--help", "-h") } && args.none { it in privateFlags })
        return Refinement.Refined(HostedArgumentSelection.PassThrough(args))
    return selectChangeArguments(args)
}

private fun selectChangeArguments(args: List<String>): Refinement<HostedArgumentSelection.Change, ExistingIdeFailure> {
    if (args.count { it == "--stdin" } > 1 || args.count { it in privateFlags } > 1) return inputFailure
    val normalized = args.filterNot { it == "--stdin" || it in privateFlags }
    val mode =
        when (args.firstOrNull { it in privateFlags }) {
            PREPARE_FLAG -> HostedInputMode.PREPARE
            APPROVED_FLAG -> HostedInputMode.APPROVED
            null -> HostedInputMode.CANONICAL
            else -> return inputFailure
        }
    val mutation = normalized.getOrNull(1) in setOf("apply", "recover")
    if (mode == HostedInputMode.CANONICAL && mutation) return Refinement.Rejected(ExistingIdeFailure.APPROVAL_REQUIRED)
    if (mode != HostedInputMode.CANONICAL && (normalized.size != 2 || !mutation)) return inputFailure
    return Refinement.Refined(HostedArgumentSelection.Change(normalized, mode))
}

private fun readHostedDocument(input: CliRequestDocumentInput): Refinement<JsonObject, ExistingIdeFailure> {
    val supplied = if (input is CliRequestDocumentInput.Deferred) input.read() else input
    if (supplied !is CliRequestDocumentInput.Provided) return inputFailure
    return try {
        if (supplied.document.toByteArray(Charsets.UTF_8).size > MAXIMUM_REQUEST_BYTES) return inputFailure
        hostedInputMapper.readTree(supplied.document)
        val document = Json.parseToJsonElement(supplied.document) as? JsonObject ?: return inputFailure
        Refinement.Refined(document)
    } catch (_: RuntimeException) {
        inputFailure
    }
}

private fun admitMutationInput(
    selection: HostedArgumentSelection.Change,
    document: JsonObject,
): Refinement<HostedCliInput, ExistingIdeFailure> {
    val arguments =
        when (selection.mode) {
            HostedInputMode.APPROVED -> {
                if (document.keys != setOf("arguments", "approval")) return inputFailure
                document["arguments"] as? JsonObject ?: return inputFailure
            }
            HostedInputMode.PREPARE -> document
            HostedInputMode.CANONICAL -> return inputFailure
        }
    val identityText = arguments["planIdentity"] as? JsonPrimitive ?: return inputFailure
    if (arguments.keys != setOf("planIdentity") || !identityText.isString) return inputFailure
    val identity =
        when (val parsed = HostedPlanIdentity.parse(identityText.content)) {
            is Refinement.Refined -> parsed.value
            is Refinement.Rejected -> return parsed
        }
    val kind =
        if (selection.argv[1] == "apply") HostedMutationOperation.CHANGE_APPLY
        else HostedMutationOperation.CHANGE_RECOVER
    val mode =
        when (selection.mode) {
            HostedInputMode.PREPARE -> HostedChangeMode.Prepare(kind, identity)
            HostedInputMode.APPROVED -> {
                val assertion =
                    when (val parsed = parseAssertion(document)) {
                        is Refinement.Refined -> parsed.value
                        is Refinement.Rejected -> return parsed
                    }
                HostedChangeMode.Approved(kind, identity, assertion)
            }
            HostedInputMode.CANONICAL -> return inputFailure
        }
    return Refinement.Refined(
        HostedCliInput(selection.argv, CliRequestDocumentInput.Provided(arguments.toString()), mode)
    )
}

private fun parseAssertion(document: JsonObject): Refinement<HostedApprovalAssertion, ExistingIdeFailure> {
    val assertion = document["approval"] as? JsonPrimitive ?: return inputFailure
    return if (assertion.isString) HostedApprovalAssertion.parse(assertion.content) else inputFailure
}
