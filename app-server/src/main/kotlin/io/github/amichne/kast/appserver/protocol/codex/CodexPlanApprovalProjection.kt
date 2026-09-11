package io.github.amichne.kast.appserver.protocol.codex

import io.github.amichne.kast.appserver.runtime.HostedPlanApprovalChallenge
import io.github.amichne.kast.appserver.runtime.HostedPlanApprovalFailure
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.Validation
import java.time.Instant
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal enum class PlanApprovalItemCompletion(val wire: String) {
    COMPLETED("completed"),
    FAILED("failed"),
    DECLINED("declined"),
}

/** A distinct preview item; the upstream dynamic tool item retains its original identity and content. */
internal class CodexPlanApprovalProjection
private constructor(
    val started: JsonObject,
    val request: JsonObject,
    val resolved: JsonObject,
    private val completions: Map<PlanApprovalItemCompletion, JsonObject>,
    private val contracts: CodexProtocolContracts,
) {
    fun completed(status: PlanApprovalItemCompletion, at: Instant): Refinement<JsonObject, HostedPlanApprovalFailure> {
        val template = completions.getValue(status)
        val params = JsonObject(template + ("completedAtMs" to JsonPrimitive(at.toEpochMilli())))
        return if (contracts.admit(CodexOwnedSchema.ITEM_COMPLETED_NOTIFICATION, params) is Validation.Validated)
            Refinement.Refined(
                buildJsonObject {
                    put("method", "item/completed")
                    put("params", params)
                }
            )
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
            fun item(status: String): JsonObject = buildJsonObject {
                put("type", "fileChange")
                put("id", itemId)
                put("status", status)
                put(
                    "changes",
                    buildJsonArray {
                        add(
                            buildJsonObject {
                                put(
                                    "path",
                                    invocation.workingDirectory.path.resolve(challenge.preview.path.value).toString(),
                                )
                                put("diff", challenge.preview.diff.value)
                                put("kind", buildJsonObject { put("type", "update") })
                            }
                        )
                    },
                )
            }
            fun lifecycle(status: String, completed: Boolean): JsonObject = buildJsonObject {
                put("threadId", invocation.threadId.value)
                put("turnId", invocation.turnId.value)
                put(if (completed) "completedAtMs" else "startedAtMs", startedAt.toEpochMilli())
                put("item", item(status))
            }
            val started = lifecycle("inProgress", false)
            val requested = buildJsonObject {
                put("threadId", invocation.threadId.value)
                put("turnId", invocation.turnId.value)
                put("itemId", itemId)
                put("startedAtMs", startedAt.toEpochMilli())
                put(
                    "reason",
                    "Authorize only the displayed stored Kast ${challenge.subject.operation.canonical.id.value} " +
                        "plan plan:${challenge.subject.planIdentity}.",
                )
            }
            val resolved = buildJsonObject {
                put("threadId", invocation.threadId.value)
                put("requestId", requestId)
            }
            val completions = PlanApprovalItemCompletion.entries.associateWith { lifecycle(it.wire, true) }
            val shapes =
                listOf(
                    CodexOwnedSchema.ITEM_STARTED_NOTIFICATION to started,
                    CodexOwnedSchema.FILE_CHANGE_REQUEST_APPROVAL_PARAMS to requested,
                    CodexOwnedSchema.SERVER_REQUEST_RESOLVED_NOTIFICATION to resolved,
                ) + completions.values.map { CodexOwnedSchema.ITEM_COMPLETED_NOTIFICATION to it }
            if (shapes.any { (schema, value) -> contracts.admit(schema, value) !is Validation.Validated })
                return Refinement.Rejected(HostedPlanApprovalFailure.NATIVE_SCHEMA_REJECTED)
            fun notification(method: String, params: JsonObject): JsonObject = buildJsonObject {
                put("method", method)
                put("params", params)
            }
            return Refinement.Refined(
                CodexPlanApprovalProjection(
                    started = notification("item/started", started),
                    request =
                        buildJsonObject {
                            put("id", requestId)
                            put("method", "item/fileChange/requestApproval")
                            put("params", requested)
                        },
                    resolved = notification("serverRequest/resolved", resolved),
                    completions = completions,
                    contracts = contracts,
                )
            )
        }

        internal fun qualificationWitnesses(): List<Pair<CodexOwnedSchema, JsonObject>> =
            listOf(
                CodexOwnedSchema.FILE_CHANGE_REQUEST_APPROVAL_PARAMS to
                    buildJsonObject {
                        put("itemId", "kast-plan-preview-probe")
                        put("threadId", "thread-probe")
                        put("turnId", "turn-probe")
                        put("startedAtMs", 0)
                        put("reason", "Approve exactly one stored plan.")
                    },
                CodexOwnedSchema.SERVER_REQUEST_RESOLVED_NOTIFICATION to
                    buildJsonObject {
                        put("threadId", "thread-probe")
                        put("requestId", "kast-plan-approval-probe")
                    },
            ) +
                lifecycleWitnesses() +
                listOf("accept", "acceptForSession", "decline", "cancel").map { decision ->
                    CodexOwnedSchema.FILE_CHANGE_REQUEST_APPROVAL_RESPONSE to
                        buildJsonObject { put("decision", decision) }
                }

        private fun lifecycleWitnesses(): List<Pair<CodexOwnedSchema, JsonObject>> =
            listOf("inProgress", "completed", "failed", "declined").map { status ->
                val schema =
                    if (status == "inProgress") CodexOwnedSchema.ITEM_STARTED_NOTIFICATION
                    else CodexOwnedSchema.ITEM_COMPLETED_NOTIFICATION
                schema to
                    buildJsonObject {
                        put("threadId", "thread-probe")
                        put("turnId", "turn-probe")
                        put(if (status == "inProgress") "startedAtMs" else "completedAtMs", 0)
                        put(
                            "item",
                            buildJsonObject {
                                put("type", "fileChange")
                                put("id", "kast-plan-preview-probe")
                                put("status", status)
                                put(
                                    "changes",
                                    buildJsonArray {
                                        add(
                                            buildJsonObject {
                                                put("path", "/tmp/kast-plan-preview.kt")
                                                put("diff", "@@ -1 +1 @@\n-old\n+new\n")
                                                put("kind", buildJsonObject { put("type", "update") })
                                            }
                                        )
                                    },
                                )
                            },
                        )
                    }
            }
    }
}
