package io.github.amichne.kast.appserver.runtime

import io.github.amichne.kast.appserver.core.BrokerInvocationContext
import io.github.amichne.kast.appserver.core.ObserverFileChange
import io.github.amichne.kast.appserver.core.ObserverFileChangeKind
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.CanonicalOperation
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

internal enum class HostedChangeApprovalOperation(val canonical: CanonicalOperation) {
    APPLY(CanonicalOperation.CHANGE_APPLY),
    RECOVER(CanonicalOperation.CHANGE_RECOVER),
}

internal enum class HostedPlanApprovalFailure {
    UNAVAILABLE,
    INVALID_REQUEST,
    PLAN_UNAVAILABLE,
    PLAN_INCOMPATIBLE,
    CHALLENGE_REJECTED,
    PREVIEW_REJECTED,
    CONTROLLER_UNAVAILABLE,
    CONTROLLER_REJECTED,
    REQUEST_CAPACITY_EXCEEDED,
    NATIVE_SCHEMA_REJECTED,
    DECLINED,
    CANCELLED,
    DISCONNECTED,
    TIMED_OUT,
    OWNER_RETIRED,
    SIGNING_UNAVAILABLE,
    SIGNING_REJECTED,
    GRANT_REJECTED,
}

/** An admitted request to load an existing immutable plan, never a request to derive a preview from caller text. */
internal class HostedPlanApprovalRequest
private constructor(
    val operation: HostedChangeApprovalOperation,
    val invocation: BrokerInvocationContext,
    val planIdentity: String,
    val arguments: JsonObject,
) {
    companion object {
        fun admit(
            operation: HostedChangeApprovalOperation,
            invocation: BrokerInvocationContext,
            arguments: JsonElement,
        ): Refinement<HostedPlanApprovalRequest, HostedPlanApprovalFailure> {
            val document =
                arguments as? JsonObject ?: return Refinement.Rejected(HostedPlanApprovalFailure.INVALID_REQUEST)
            val identity = document["planIdentity"] as? JsonPrimitive
            if (
                document.keys != setOf("planIdentity") ||
                    identity?.isString != true ||
                    !identity.content.matches(Regex("plan:[0-9a-f]{64}"))
            )
                return Refinement.Rejected(HostedPlanApprovalFailure.INVALID_REQUEST)
            return Refinement.Refined(
                HostedPlanApprovalRequest(
                    operation = operation,
                    invocation = invocation,
                    planIdentity = identity.content.removePrefix("plan:"),
                    arguments = document,
                )
            )
        }
    }
}

/** Gateway-owned evidence obtained from the hosted plan store. Preview is bounded and has exactly one authored file. */
internal class HostedPlanApprovalChallenge
private constructor(
    val request: HostedPlanApprovalRequest,
    val subject: ExactPlanApprovalSubject,
    val preview: ObserverFileChange,
) {
    companion object {
        fun fromStoredPlan(
            request: HostedPlanApprovalRequest,
            subject: ExactPlanApprovalSubject,
            preview: ObserverFileChange,
        ): Refinement<HostedPlanApprovalChallenge, HostedPlanApprovalFailure> =
            when {
                request.planIdentity != subject.planIdentity ||
                    request.operation != subject.operation ||
                    request.invocation.workingDirectory != subject.root ->
                    Refinement.Rejected(HostedPlanApprovalFailure.PLAN_INCOMPATIBLE)
                preview.kind != ObserverFileChangeKind.UPDATE ->
                    Refinement.Rejected(HostedPlanApprovalFailure.PREVIEW_REJECTED)
                else -> Refinement.Refined(HostedPlanApprovalChallenge(request, subject, preview))
            }
    }
}

/** The signing boundary accepts controller proof, not a caller-supplied approval flag or challenge echo. */
internal interface HostedPlanApprovalGateway {
    suspend fun prepare(
        request: HostedPlanApprovalRequest
    ): Refinement<HostedPlanApprovalChallenge, HostedPlanApprovalFailure>

    suspend fun redeem(approval: ControllerApprovedPlan): Refinement<HostedPlanApprovalGrant, HostedPlanApprovalFailure>

    data object Unavailable : HostedPlanApprovalGateway {
        override suspend fun prepare(
            request: HostedPlanApprovalRequest
        ): Refinement<HostedPlanApprovalChallenge, HostedPlanApprovalFailure> =
            Refinement.Rejected(HostedPlanApprovalFailure.UNAVAILABLE)

        override suspend fun redeem(
            approval: ControllerApprovedPlan
        ): Refinement<HostedPlanApprovalGrant, HostedPlanApprovalFailure> =
            Refinement.Rejected(HostedPlanApprovalFailure.UNAVAILABLE)
    }
}

/**
 * Opaque signed assertion produced by the trusted gateway after explicit controller approval. This wrapper preserves
 * correlation; the hosted owner still verifies the Ed25519 signature against its enrolled key and consumes its
 * challenge.
 */
internal class HostedPlanApprovalGrant
private constructor(
    val approval: ControllerApprovedPlan,
    internal val assertion: String,
) {
    fun matchesInvocation(context: BrokerInvocationContext): Boolean =
        approval.invocation.invocationId == context.invocationId &&
            approval.invocation.workingDirectory == context.workingDirectory

    fun matchesRequest(
        operation: CanonicalOperation,
        context: BrokerInvocationContext,
        arguments: JsonElement,
    ): Boolean =
        matchesInvocation(context) &&
            approval.subject.operation.canonical == operation &&
            arguments is JsonObject &&
            arguments.keys == setOf("planIdentity") &&
            arguments["planIdentity"] == JsonPrimitive("plan:${approval.subject.planIdentity}")

    companion object {
        private const val MAXIMUM_ASSERTION_CHARACTERS = 16 * 1024

        /** Called only by the configured signing gateway; shape validation is not signature verification. */
        fun fromSignedControllerApproval(
            approval: ControllerApprovedPlan,
            assertion: String,
        ): Refinement<HostedPlanApprovalGrant, HostedPlanApprovalFailure> =
            if (
                assertion.length <= MAXIMUM_ASSERTION_CHARACTERS &&
                    assertion.matches(Regex("[A-Za-z0-9_-]{1,16384}\\.[A-Za-z0-9_-]{86}"))
            )
                Refinement.Refined(HostedPlanApprovalGrant(approval, assertion))
            else Refinement.Rejected(HostedPlanApprovalFailure.GRANT_REJECTED)
    }
}

internal sealed interface BrokerInvocationApproval {
    data object Absent : BrokerInvocationApproval

    data class Granted(val grant: HostedPlanApprovalGrant) : BrokerInvocationApproval
}
