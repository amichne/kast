package io.github.amichne.kast.appserver.protocol.codex

import io.github.amichne.kast.appserver.protocol.codex.CodexPlanApprovalDocuments.ApprovalRequest
import io.github.amichne.kast.appserver.protocol.codex.CodexPlanApprovalDocuments.ApprovalRequestEnvelope
import io.github.amichne.kast.appserver.protocol.codex.CodexPlanApprovalDocuments.Completed
import io.github.amichne.kast.appserver.protocol.codex.CodexPlanApprovalDocuments.CompletedNotification
import io.github.amichne.kast.appserver.protocol.codex.CodexPlanApprovalDocuments.Decision
import io.github.amichne.kast.appserver.protocol.codex.CodexPlanApprovalDocuments.FileUpdate
import io.github.amichne.kast.appserver.protocol.codex.CodexPlanApprovalDocuments.PreviewItem
import io.github.amichne.kast.appserver.protocol.codex.CodexPlanApprovalDocuments.Resolved
import io.github.amichne.kast.appserver.protocol.codex.CodexPlanApprovalDocuments.ResolvedNotification
import io.github.amichne.kast.appserver.protocol.codex.CodexPlanApprovalDocuments.Started
import io.github.amichne.kast.appserver.protocol.codex.CodexPlanApprovalDocuments.StartedNotification
import io.github.amichne.kast.appserver.protocol.codex.CodexPlanApprovalDocuments.encode
import io.github.amichne.kast.appserver.runtime.HostedPlanApprovalChallenge
import io.github.amichne.kast.appserver.runtime.HostedPlanApprovalFailure
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.Validation
import java.time.Instant
import kotlinx.serialization.json.JsonObject

/** A distinct preview item; the upstream dynamic tool item retains its original identity and content. */
internal class CodexPlanApprovalProjection
private constructor(
    val started: JsonObject,
    val request: JsonObject,
    val resolved: JsonObject,
    private val completions: Map<PlanApprovalItemCompletion, Completed>,
    private val contracts: CodexProtocolContracts,
) {
    fun completed(status: PlanApprovalItemCompletion, at: Instant): Refinement<JsonObject, HostedPlanApprovalFailure> {
        val params = completions.getValue(status).copy(completedAtMs = at.toEpochMilli())
        return if (
            contracts.admit(CodexOwnedSchema.ITEM_COMPLETED_NOTIFICATION, encode(params)) is Validation.Validated
        )
            Refinement.Refined(encode(CompletedNotification(params)))
        else Refinement.Rejected(HostedPlanApprovalFailure.NATIVE_SCHEMA_REJECTED)
    }

    companion object {
        fun prepare(
            challenge: HostedPlanApprovalChallenge,
            requestId: String,
            startedAt: Instant,
            contracts: CodexProtocolContracts,
        ): Refinement<CodexPlanApprovalProjection, HostedPlanApprovalFailure> {
            val invocation = challenge.request.invocation
            val itemId = "$requestId-preview"
            val changes =
                listOf(
                    FileUpdate(
                        invocation.workingDirectory.path.resolve(challenge.preview.path.value).toString(),
                        challenge.preview.diff.value,
                    )
                )
            val started =
                Started(
                    threadId = invocation.threadId.value,
                    turnId = invocation.turnId.value,
                    startedAtMs = startedAt.toEpochMilli(),
                    item = PreviewItem(itemId, PlanApprovalItemStarted.IN_PROGRESS, changes),
                )
            val requested =
                ApprovalRequest(
                    threadId = invocation.threadId.value,
                    turnId = invocation.turnId.value,
                    itemId = itemId,
                    startedAtMs = startedAt.toEpochMilli(),
                    reason =
                        "Authorize only the displayed stored Kast ${challenge.subject.operation.canonical.id.value} " +
                            "plan plan:${challenge.subject.planIdentity}.",
                )
            val resolved = Resolved(invocation.threadId.value, requestId)
            val completions = PlanApprovalItemCompletion.entries.associateWith(started::completionTemplate)
            val shapes =
                listOf(
                    CodexOwnedSchema.ITEM_STARTED_NOTIFICATION to encode(started),
                    CodexOwnedSchema.FILE_CHANGE_REQUEST_APPROVAL_PARAMS to encode(requested),
                    CodexOwnedSchema.SERVER_REQUEST_RESOLVED_NOTIFICATION to encode(resolved),
                ) + completions.values.map { CodexOwnedSchema.ITEM_COMPLETED_NOTIFICATION to encode(it) }
            if (shapes.any { (schema, value) -> contracts.admit(schema, value) !is Validation.Validated })
                return Refinement.Rejected(HostedPlanApprovalFailure.NATIVE_SCHEMA_REJECTED)
            return Refinement.Refined(
                CodexPlanApprovalProjection(
                    started = encode(StartedNotification(started)),
                    request = encode(ApprovalRequestEnvelope(requestId, requested)),
                    resolved = encode(ResolvedNotification(resolved)),
                    completions = completions,
                    contracts = contracts,
                )
            )
        }

        internal fun qualificationWitnesses(): List<Pair<CodexOwnedSchema, JsonObject>> =
            listOf(
                CodexOwnedSchema.FILE_CHANGE_REQUEST_APPROVAL_PARAMS to
                    encode(
                        ApprovalRequest(
                            threadId = "thread-probe",
                            turnId = "turn-probe",
                            itemId = "kast-plan-preview-probe",
                            startedAtMs = 0,
                            reason = "Approve exactly one stored plan.",
                        )
                    ),
                CodexOwnedSchema.SERVER_REQUEST_RESOLVED_NOTIFICATION to
                    encode(Resolved("thread-probe", "kast-plan-approval-probe")),
            ) +
                lifecycleWitnesses() +
                PlanApprovalDecisionWitness.entries.map { decision ->
                    CodexOwnedSchema.FILE_CHANGE_REQUEST_APPROVAL_RESPONSE to encode(Decision(decision))
                }

        private fun lifecycleWitnesses(): List<Pair<CodexOwnedSchema, JsonObject>> {
            val changes = listOf(FileUpdate("/tmp/kast-plan-preview.kt", "@@ -1 +1 @@\n-old\n+new\n"))
            val started =
                Started(
                    threadId = "thread-probe",
                    turnId = "turn-probe",
                    startedAtMs = 0,
                    item = PreviewItem("kast-plan-preview-probe", PlanApprovalItemStarted.IN_PROGRESS, changes),
                )
            return listOf(CodexOwnedSchema.ITEM_STARTED_NOTIFICATION to encode(started)) +
                PlanApprovalItemCompletion.entries.map { status ->
                    CodexOwnedSchema.ITEM_COMPLETED_NOTIFICATION to encode(started.completionTemplate(status))
                }
        }
    }
}
