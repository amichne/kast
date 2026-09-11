package io.github.amichne.kast.cli.ide

import io.github.amichne.kast.cli.PreparedCliRequest
import io.github.amichne.kast.cli.command.CliRequestDocumentInput
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.CanonicalOperation
import java.util.Base64
import kotlinx.serialization.json.*
import tools.jackson.core.StreamReadFeature
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.json.JsonMapper

/** Syntax proof only: the hosted owner remains the signature and challenge authority. */
class HostedApprovalAssertion private constructor(val value: String) {
    companion object {
        fun parse(raw: String): Refinement<HostedApprovalAssertion, ExistingIdeFailure> {
            if (raw.length !in 1..16384 || !raw.matches(Regex("[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]{86}")))
                return Refinement.Rejected(ExistingIdeFailure.APPROVAL_REJECTED)
            return try {
                val parts = raw.split('.')
                val decoder = Base64.getUrlDecoder()
                val encoder = Base64.getUrlEncoder().withoutPadding()
                val payload = decoder.decode(parts[0])
                val signature = decoder.decode(parts[1])
                if (
                    payload.isEmpty() ||
                        signature.size != 64 ||
                        encoder.encodeToString(payload) != parts[0] ||
                        encoder.encodeToString(signature) != parts[1]
                )
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
                            request,
                            selected.kind,
                            selected.identity,
                            selected.assertion,
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

internal fun admitHostedCliInput(
    argv: List<String>,
    input: CliRequestDocumentInput,
): Refinement<HostedCliInput, ExistingIdeFailure> {
    val args = if (argv.firstOrNull() == "--") argv.drop(1) else argv
    val prepare = "--hosted-approval-prepare"
    val approved = "--hosted-approved-invocation"
    val privateFlags = setOf(prepare, approved)
    val failure = Refinement.Rejected(ExistingIdeFailure.APPROVAL_REJECTED)
    if (args.firstOrNull() != "change")
        return if (args.any { it in privateFlags }) failure
        else Refinement.Refined(HostedCliInput(args, input, HostedChangeMode.Canonical))
    if (args.any { it == "--help" || it == "-h" } && args.none { it in privateFlags })
        return Refinement.Refined(HostedCliInput(args, input, HostedChangeMode.Canonical))
    if (args.count { it == "--stdin" } > 1 || args.count { it in privateFlags } > 1) return failure
    val normalized = args.filterNot { it == "--stdin" || it in privateFlags }
    val privateMode = args.firstOrNull { it in privateFlags }
    if (privateMode != null && (normalized.size != 2 || normalized[1] !in setOf("apply", "recover"))) return failure
    if (privateMode == null && normalized.getOrNull(1) in setOf("apply", "recover"))
        return Refinement.Rejected(ExistingIdeFailure.APPROVAL_REQUIRED)
    val supplied = if (input is CliRequestDocumentInput.Deferred) input.read() else input
    if (supplied !is CliRequestDocumentInput.Provided) return failure
    val document =
        try {
            if (supplied.document.toByteArray(Charsets.UTF_8).size > 1024 * 1024) return failure
            hostedInputMapper.readTree(supplied.document)
            Json.parseToJsonElement(supplied.document) as? JsonObject ?: return failure
        } catch (_: RuntimeException) {
            return failure
        }
    if (privateMode == null) return Refinement.Refined(HostedCliInput(normalized, supplied, HostedChangeMode.Canonical))
    val arguments =
        if (privateMode == approved) {
            if (document.keys != setOf("arguments", "approval")) return failure
            document["arguments"] as? JsonObject ?: return failure
        } else document
    val identityText = arguments["planIdentity"] as? JsonPrimitive ?: return failure
    if (arguments.keys != setOf("planIdentity") || !identityText.isString) return failure
    val identity =
        when (val parsed = HostedPlanIdentity.parse(identityText.content)) {
            is Refinement.Refined -> parsed.value
            is Refinement.Rejected -> return parsed
        }
    val kind =
        if (normalized[1] == "apply") HostedMutationOperation.CHANGE_APPLY else HostedMutationOperation.CHANGE_RECOVER
    val mode =
        if (privateMode == prepare) HostedChangeMode.Prepare(kind, identity)
        else {
            val assertionText = document["approval"] as? JsonPrimitive ?: return failure
            if (!assertionText.isString) return failure
            val assertion =
                when (val parsed = HostedApprovalAssertion.parse(assertionText.content)) {
                    is Refinement.Refined -> parsed.value
                    is Refinement.Rejected -> return parsed
                }
            HostedChangeMode.Approved(kind, identity, assertion)
        }
    return Refinement.Refined(HostedCliInput(normalized, CliRequestDocumentInput.Provided(arguments.toString()), mode))
}
