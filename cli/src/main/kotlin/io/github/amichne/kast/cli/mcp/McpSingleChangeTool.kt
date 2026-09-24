@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package io.github.amichne.kast.cli.mcp

import io.github.amichne.kast.cli.CliExit
import io.github.amichne.kast.cli.command.CliRequestDocumentInput
import io.github.amichne.kast.cli.ide.ExistingIdeCliCapabilities
import io.github.amichne.kast.cli.ide.executeExistingIdeCli
import io.github.amichne.kast.protocol.wire.presentation.CanonicalJsonDocument
import java.nio.file.Path
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonClassDiscriminator
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement

/** Public one-call mutation; native plan, write, verification, and recovery remain separate effects. */
internal class McpSingleChangeTool(
    private val root: Path,
    inputSchema: JsonElement,
    private val operation: (McpChangePhase, JsonObject) -> CliExit,
    private val sign: (ApprovalChallenge) -> String?,
) {
    val tool =
        McpSupplementalTool(
            name = "change",
            description =
                "Add one declaration to an existing Kotlin file identified by an exact Kast reference. " +
                    "Plans, applies, verifies, and attempts recovery within one call. Returns the preview and " +
                    "verified receipt, or explicit uncertain/recovery evidence. Changes source files.",
            inputSchema = inputSchema,
            readOnly = false,
            invoke = ::invoke,
        )

    private fun invoke(arguments: JsonObject): CliExit {
        val planned = operation(McpChangePhase.PLAN, arguments)
        val planDocument = document(planned) ?: return rejected(McpChangeFailure.INVALID_PLAN_RESULT)
        if (planned !is CliExit.Complete && planned !is CliExit.Qualified)
            return rejected(McpChangeFailure.PLAN_REJECTED, evidence = planDocument)
        val plan =
            try {
                changeJson.decodeFromJsonElement<McpStoredPlan>(planDocument)
            } catch (_: SerializationException) {
                return rejected(McpChangeFailure.INVALID_PLAN_RESULT, evidence = planDocument)
            }
        if (plan.status != McpPlanStatus.COMPLETE || !PLAN_ID.matches(plan.planIdentity))
            return rejected(McpChangeFailure.INVALID_PLAN_RESULT, evidence = planDocument)

        return apply(plan.planIdentity, planDocument)
    }

    private fun apply(identity: String, planDocument: JsonElement): CliExit {
        val assertion =
            authorize(McpChangePhase.PREPARE_APPLY, identity)
                ?: return rejected(McpChangeFailure.AUTHORIZATION_UNAVAILABLE, identity, planDocument)
        val applied =
            try {
                operation(McpChangePhase.APPLY, approvedArguments(identity, assertion))
            } catch (_: RuntimeException) {
                return recover(identity, planDocument, null, McpChangeIssue.APPLICATION_RESPONSE_UNAVAILABLE)
            }
        val application =
            document(applied)
                ?: return recover(
                    identity,
                    planDocument,
                    null,
                    McpChangeIssue.APPLICATION_RESPONSE_UNAVAILABLE,
                )
        if (applied is CliExit.OperationRejected)
            return rejected(McpChangeFailure.APPLY_REJECTED, identity, application)
        val state =
            try {
                changeJson.decodeFromJsonElement<McpApplicationState>(application)
            } catch (_: SerializationException) {
                return recover(
                    identity,
                    planDocument,
                    application,
                    McpChangeIssue.APPLICATION_RESPONSE_UNAVAILABLE,
                )
            }
        if (
            applied is CliExit.Complete &&
                state.status == McpApplicationStatus.COMPLETE &&
                state.state == McpApplicationOutcome.VERIFIED
        )
            return CliExit.Complete(
                changeDocument.create(McpSingleChangeResult.Complete(identity, planDocument, application))
            )
        return recover(identity, planDocument, application, McpChangeIssue.APPLICATION_UNVERIFIED)
    }

    private fun recover(
        identity: String,
        plan: JsonElement,
        application: JsonElement?,
        issue: McpChangeIssue,
    ): CliExit {
        val assertion = runCatching { authorize(McpChangePhase.PREPARE_RECOVER, identity) }.getOrNull()
        val recovery =
            if (assertion == null) null
            else
                runCatching { document(operation(McpChangePhase.RECOVER, approvedArguments(identity, assertion))) }
                    .getOrNull()
        return CliExit.Qualified(
            changeDocument.create(
                McpSingleChangeResult.Qualified(
                    identity,
                    plan,
                    application,
                    recovery,
                    if (recovery == null) McpChangeIssue.RECOVERY_UNAVAILABLE else issue,
                )
            )
        )
    }

    private fun authorize(phase: McpChangePhase, identity: String): String? {
        val prepared = operation(phase, planIdentityArguments(identity))
        if (prepared !is CliExit.Complete) return null
        val challenge =
            try {
                changeJson.decodeFromString<ApprovalChallenge>(prepared.document.value)
            } catch (_: SerializationException) {
                return null
            }
        val expected = if (phase == McpChangePhase.PREPARE_APPLY) "CHANGE_APPLY" else "CHANGE_RECOVER"
        if (challenge.version != 1 || challenge.operation != expected) return null
        if (challenge.root != root.toString()) return null
        if (challenge.planId != identity.removePrefix("plan:")) return null
        if (!CHALLENGE_ID.matches(challenge.challenge)) return null
        if (challenge.preview.path.isBlank() || challenge.preview.diff.isBlank()) return null
        return sign(challenge)
    }

    private fun document(exit: CliExit): JsonObject? = runCatching {
        changeJson.parseToJsonElement(exit.document.value) as? JsonObject
    }
        .getOrNull()

    private fun rejected(
        failure: McpChangeFailure,
        identity: String? = null,
        evidence: JsonElement? = null,
    ): CliExit.OperationRejected =
        CliExit.OperationRejected(
            changeDocument.create(McpSingleChangeResult.Rejected(McpChangeError(failure, identity, evidence)))
        )

    companion object {
        private val PLAN_ID = Regex("plan:[0-9a-f]{64}")
        private val CHALLENGE_ID = Regex("[0-9a-f]{64}")

        fun installed(
            root: Path,
            home: Path,
            inputSchema: JsonElement,
            capabilities: ExistingIdeCliCapabilities,
        ): McpSingleChangeTool =
            McpSingleChangeTool(
                root,
                inputSchema,
                { phase, arguments ->
                    val argv =
                        when (phase) {
                            McpChangePhase.PLAN -> listOf("change", "plan")
                            McpChangePhase.PREPARE_APPLY -> listOf("change", "apply", "--hosted-approval-prepare")
                            McpChangePhase.APPLY -> listOf("change", "apply", "--hosted-approved-invocation")
                            McpChangePhase.PREPARE_RECOVER -> listOf("change", "recover", "--hosted-approval-prepare")
                            McpChangePhase.RECOVER -> listOf("change", "recover", "--hosted-approved-invocation")
                        }
                    executeExistingIdeCli(
                        argv = argv,
                        start = root,
                        capabilities = capabilities,
                        requestInput =
                            CliRequestDocumentInput.Provided(
                                changeJson.encodeToString(JsonObject.serializer(), arguments)
                            ),
                    )
                },
                { challenge -> McpApprovalHelper.sign(home, challenge) },
            )
    }
}

internal enum class McpChangePhase {
    PLAN,
    PREPARE_APPLY,
    APPLY,
    PREPARE_RECOVER,
    RECOVER,
}

@Serializable private data class McpPlanIdentityInput(val planIdentity: String)

private fun planIdentityArguments(identity: String): JsonObject =
    changeJson.encodeToJsonElement(McpPlanIdentityInput(identity)) as JsonObject

private fun approvedArguments(identity: String, assertion: String): JsonObject =
    changeJson.encodeToJsonElement(McpApprovedArguments(planIdentityArguments(identity), assertion)) as JsonObject

@Serializable private data class McpStoredPlan(val status: McpPlanStatus, val planIdentity: String)

@Serializable
private enum class McpPlanStatus {
    @SerialName("complete") COMPLETE,
    @SerialName("qualified") QUALIFIED,
}

@Serializable private data class McpApplicationState(val status: McpApplicationStatus, val state: McpApplicationOutcome)

@Serializable
private enum class McpApplicationStatus {
    @SerialName("complete") COMPLETE,
    @SerialName("qualified") QUALIFIED,
}

@Serializable
private enum class McpApplicationOutcome {
    @SerialName("verified") VERIFIED,
    @SerialName("applied_unverified") APPLIED_UNVERIFIED,
    @SerialName("recovery_required") RECOVERY_REQUIRED,
}

@Serializable
@JsonClassDiscriminator("status")
private sealed interface McpSingleChangeResult {
    @Serializable
    @SerialName("complete")
    data class Complete(val planIdentity: String, val plan: JsonElement, val application: JsonElement) :
        McpSingleChangeResult

    @Serializable
    @SerialName("qualified")
    data class Qualified(
        val planIdentity: String,
        val plan: JsonElement,
        val application: JsonElement?,
        val recovery: JsonElement?,
        val issue: McpChangeIssue,
    ) : McpSingleChangeResult

    @Serializable @SerialName("rejected") data class Rejected(val error: McpChangeError) : McpSingleChangeResult
}

@Serializable
private data class McpChangeError(val code: McpChangeFailure, val planIdentity: String?, val evidence: JsonElement?)

@Serializable
private enum class McpChangeFailure {
    PLAN_REJECTED,
    INVALID_PLAN_RESULT,
    AUTHORIZATION_UNAVAILABLE,
    APPLY_REJECTED,
}

@Serializable
private enum class McpChangeIssue {
    APPLICATION_UNVERIFIED,
    APPLICATION_RESPONSE_UNAVAILABLE,
    RECOVERY_UNAVAILABLE,
}

private val changeJson = Json {
    encodeDefaults = true
    ignoreUnknownKeys = true
}
private val changeDocument = CanonicalJsonDocument.generated(McpSingleChangeResult.serializer())
