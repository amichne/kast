package io.github.amichne.kast.appserver.provider

import io.github.amichne.kast.appserver.core.BrokerInvocationContext
import io.github.amichne.kast.appserver.core.ProviderFailureCode
import io.github.amichne.kast.appserver.runtime.BrokerInvocationApproval
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.CanonicalOperation
import io.github.amichne.kast.protocol.registry.HostedApprovalPolicy
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/** Only this subprocess boundary projects a trusted grant; the model's public arguments remain unchanged. */
internal class KastInvocationTransport private constructor(val command: List<String>, val document: JsonElement) {
    companion object {
        const val APPROVED_INVOCATION_FLAG = "--hosted-approved-invocation"

        fun prepare(
            tool: QualifiedKastTool,
            arguments: JsonElement,
            context: BrokerInvocationContext,
        ): Refinement<KastInvocationTransport, ProviderFailureCode> {
            val operation = tool.hostedDefinition.operation
            val approval = context.approval
            if (tool.hostedDefinition.approval != HostedApprovalPolicy.EXPLICIT) {
                return if (approval == BrokerInvocationApproval.Absent)
                    Refinement.Refined(KastInvocationTransport(tool.command, arguments))
                else Refinement.Rejected(ProviderFailureCode.APPROVAL_BINDING_REJECTED)
            }
            if (approval !is BrokerInvocationApproval.Granted)
                return Refinement.Rejected(ProviderFailureCode.APPROVAL_REQUIRED)
            if (
                operation !in setOf(CanonicalOperation.CHANGE_APPLY, CanonicalOperation.CHANGE_RECOVER) ||
                    !approval.grant.matchesRequest(operation, context, arguments)
            )
                return Refinement.Rejected(ProviderFailureCode.APPROVAL_BINDING_REJECTED)
            return Refinement.Refined(
                KastInvocationTransport(
                    tool.command + APPROVED_INVOCATION_FLAG,
                    Json.encodeToJsonElement(
                        ApprovedInvocationEnvelope.serializer(),
                        ApprovedInvocationEnvelope(arguments, approval.grant.assertion),
                    ),
                )
            )
        }
    }
}

@Serializable private data class ApprovedInvocationEnvelope(val arguments: JsonElement, val approval: String)
