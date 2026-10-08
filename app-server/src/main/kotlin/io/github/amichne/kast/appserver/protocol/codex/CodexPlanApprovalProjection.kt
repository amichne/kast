package io.github.amichne.kast.appserver.protocol.codex

import io.github.amichne.kast.appserver.protocol.codex.CodexPlanApprovalDocuments.ApprovalRequest
import io.github.amichne.kast.appserver.protocol.codex.CodexPlanApprovalDocuments.Decision
import io.github.amichne.kast.appserver.protocol.codex.CodexPlanApprovalDocuments.FileUpdate
import io.github.amichne.kast.appserver.protocol.codex.CodexPlanApprovalDocuments.PreviewItem
import io.github.amichne.kast.appserver.protocol.codex.CodexPlanApprovalDocuments.Resolved
import io.github.amichne.kast.appserver.protocol.codex.CodexPlanApprovalDocuments.Started
import io.github.amichne.kast.appserver.protocol.codex.CodexPlanApprovalDocuments.encode
import kotlinx.serialization.json.JsonObject

/** Canonical Codex approval item schema witnesses, including upstream file-change items. */
internal object CodexPlanApprovalProjection {
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
