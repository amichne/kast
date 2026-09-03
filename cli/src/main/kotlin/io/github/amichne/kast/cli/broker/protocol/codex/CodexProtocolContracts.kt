package io.github.amichne.kast.cli.broker.protocol.codex

import io.github.amichne.kast.cli.broker.core.ProviderNamespace
import io.github.amichne.kast.cli.broker.schema.CompiledJsonSchema
import io.github.amichne.kast.cli.broker.schema.JsonConstraintViolation
import io.github.amichne.kast.cli.broker.schema.NetworkntJsonSchemaCompiler
import io.github.amichne.kast.cli.broker.schema.ValidatedJsonValue
import io.github.amichne.kast.kernel.NonEmptyFailures
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.Validation
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal enum class CodexOwnedSchema(val fileName: String) {
    DYNAMIC_TOOL_CALL_PARAMS("DynamicToolCallParams.json"),
    DYNAMIC_TOOL_CALL_RESPONSE("DynamicToolCallResponse.json"),
    INITIALIZE_PARAMS("InitializeParams.json"),
    ITEM_COMPLETED_NOTIFICATION("ItemCompletedNotification.json"),
    ITEM_STARTED_NOTIFICATION("ItemStartedNotification.json"),
    REVIEW_START_PARAMS("ReviewStartParams.json"),
    REVIEW_START_RESPONSE("ReviewStartResponse.json"),
    THREAD_FORK_PARAMS("ThreadForkParams.json"),
    THREAD_FORK_RESPONSE("ThreadForkResponse.json"),
    THREAD_ITEMS_LIST_PARAMS("ThreadItemsListParams.json"),
    THREAD_ITEMS_LIST_RESPONSE("ThreadItemsListResponse.json"),
    THREAD_LIST_PARAMS("ThreadListParams.json"),
    THREAD_LIST_RESPONSE("ThreadListResponse.json"),
    THREAD_METADATA_UPDATE_PARAMS("ThreadMetadataUpdateParams.json"),
    THREAD_METADATA_UPDATE_RESPONSE("ThreadMetadataUpdateResponse.json"),
    THREAD_QUEUE_START_PARAMS("ThreadQueueStartParams.json"),
    THREAD_QUEUE_START_RESPONSE("ThreadQueueStartResponse.json"),
    THREAD_READ_PARAMS("ThreadReadParams.json"),
    THREAD_READ_RESPONSE("ThreadReadResponse.json"),
    THREAD_RESUME_PARAMS("ThreadResumeParams.json"),
    THREAD_RESUME_RESPONSE("ThreadResumeResponse.json"),
    THREAD_REVERT_PARAMS("ThreadRevertParams.json"),
    THREAD_REVERT_RESPONSE("ThreadRevertResponse.json"),
    THREAD_ROLLBACK_PARAMS("ThreadRollbackParams.json"),
    THREAD_ROLLBACK_RESPONSE("ThreadRollbackResponse.json"),
    THREAD_SEARCH_PARAMS("ThreadSearchParams.json"),
    THREAD_SEARCH_RESPONSE("ThreadSearchResponse.json"),
    THREAD_START_PARAMS("ThreadStartParams.json"),
    THREAD_START_RESPONSE("ThreadStartResponse.json"),
    THREAD_STARTED_NOTIFICATION("ThreadStartedNotification.json"),
    THREAD_TIMELINE_LIST_PARAMS("ThreadTimelineListParams.json"),
    THREAD_TIMELINE_LIST_RESPONSE("ThreadTimelineListResponse.json"),
    THREAD_TURNS_LIST_PARAMS("ThreadTurnsListParams.json"),
    THREAD_TURNS_LIST_RESPONSE("ThreadTurnsListResponse.json"),
    THREAD_UNARCHIVE_PARAMS("ThreadUnarchiveParams.json"),
    THREAD_UNARCHIVE_RESPONSE("ThreadUnarchiveResponse.json"),
    TURN_COMPLETED_NOTIFICATION("TurnCompletedNotification.json"),
    TURN_INTERRUPT_PARAMS("TurnInterruptParams.json"),
    TURN_STARTED_NOTIFICATION("TurnStartedNotification.json"),
    TURN_START_PARAMS("TurnStartParams.json"),
    TURN_START_RESPONSE("TurnStartResponse.json"),
}

internal sealed interface CodexProtocolContractFailure {
    data class Missing(val schema: CodexOwnedSchema) : CodexProtocolContractFailure
    data class Invalid(val schema: CodexOwnedSchema) : CodexProtocolContractFailure
    data object InitializeMutationIncompatible : CodexProtocolContractFailure
    data class ToolCallProjectionIncompatible(
        val schema: CodexOwnedSchema,
    ) : CodexProtocolContractFailure
}

/** Compiled, complete set of installed Codex schemas for every broker-owned protocol shape. */
internal class CodexProtocolContracts private constructor(
    private val contracts: Map<CodexOwnedSchema, CompiledJsonSchema>,
) {
    internal fun admit(
        schema: CodexOwnedSchema,
        candidate: JsonElement,
    ): Validation<ValidatedJsonValue, JsonConstraintViolation> =
        checkNotNull(contracts[schema]).admit(candidate)

    companion object {
        internal fun define(
            documents: Map<CodexOwnedSchema, JsonObject>,
        ): Validation<CodexProtocolContracts, CodexProtocolContractFailure> {
            val failures = mutableListOf<CodexProtocolContractFailure>()
            val compiled = linkedMapOf<CodexOwnedSchema, CompiledJsonSchema>()
            CodexOwnedSchema.entries.forEach { schema ->
                val document = documents[schema]
                if (document == null) {
                    failures += CodexProtocolContractFailure.Missing(schema)
                } else {
                    when (val compilation = NetworkntJsonSchemaCompiler.compile(document)) {
                        is Refinement.Refined -> compiled[schema] = compilation.value
                        is Refinement.Rejected -> failures +=
                            CodexProtocolContractFailure.Invalid(schema)
                    }
                }
            }
            val initialize = compiled[CodexOwnedSchema.INITIALIZE_PARAMS]
            if (
                failures.isEmpty() && initialize?.admit(INITIALIZE_MUTATION_WITNESS)
                    !is Validation.Validated
            ) {
                failures += CodexProtocolContractFailure.InitializeMutationIncompatible
            }
            if (failures.isEmpty()) {
                TOOL_CALL_PROJECTION_WITNESSES.forEach { witness ->
                    if (!projectionIsAdmitted(compiled, witness)) {
                        failures += CodexProtocolContractFailure.ToolCallProjectionIncompatible(
                            witness.lifecycle.schema,
                        )
                    }
                }
            }
            if (failures.isEmpty()) {
                ITEM_CONTAINER_PROJECTION_WITNESSES.forEach { witness ->
                    if (!containerProjectionIsAdmitted(compiled, witness)) {
                        failures += CodexProtocolContractFailure.ToolCallProjectionIncompatible(
                            witness.schema,
                        )
                    }
                }
            }
            return if (failures.isEmpty()) {
                Validation.Validated(CodexProtocolContracts(compiled.toMap()))
            } else {
                Validation.Rejected(
                    NonEmptyFailures.from(failures.first(), failures.drop(1)),
                )
            }
        }

        private val INITIALIZE_MUTATION_WITNESS = buildJsonObject {
            put("clientInfo", buildJsonObject {
                put("name", "kast-broker-contract-probe")
                put("version", "0")
            })
            put("capabilities", buildJsonObject { put("experimentalApi", true) })
        }

        private val TOOL_CALL_PROJECTION_WITNESSES =
            ToolCallWitnessLifecycle.entries.map { witnessLifecycle ->
                val notificationLifecycle = when (witnessLifecycle) {
                    ToolCallWitnessLifecycle.STARTED -> CodexToolCallLifecycle.STARTED
                    ToolCallWitnessLifecycle.SUCCEEDED,
                    ToolCallWitnessLifecycle.FAILED,
                    -> CodexToolCallLifecycle.COMPLETED
                }
                ToolCallProjectionWitness(notificationLifecycle, buildJsonObject {
                    put("threadId", "thread-contract-probe")
                    put("turnId", "turn-contract-probe")
                    when (notificationLifecycle) {
                        CodexToolCallLifecycle.STARTED -> put("startedAtMs", 0)
                        CodexToolCallLifecycle.COMPLETED -> put("completedAtMs", 4)
                    }
                    put("item", dynamicToolCallWitness(witnessLifecycle))
                })
            }

        private val ITEM_CONTAINER_PROJECTION_WITNESSES = buildList {
            CodexItemNotificationRoute.entries.forEach { route ->
                add(
                    ItemContainerProjectionWitness(
                        route.schema,
                        route.shape,
                        notificationWitness(route),
                    ),
                )
            }
            CodexItemResponseRoute.entries.forEach { route ->
                add(
                    ItemContainerProjectionWitness(
                        route.responseSchema,
                        route.shape,
                        responseWitness(route),
                    ),
                )
            }
            listOf(
                CodexOwnedSchema.THREAD_START_RESPONSE,
                CodexOwnedSchema.THREAD_RESUME_RESPONSE,
                CodexOwnedSchema.THREAD_FORK_RESPONSE,
            ).forEach { schema ->
                add(
                    ItemContainerProjectionWitness(
                        schema,
                        CodexItemContainerShape.THREAD,
                        threadSessionResponseWitness(
                            includeInitialPage = schema == CodexOwnedSchema.THREAD_RESUME_RESPONSE,
                        ),
                    ),
                )
            }
        }

        private fun dynamicToolCallWitness(
            lifecycle: ToolCallWitnessLifecycle,
        ): JsonObject = buildJsonObject {
            put("type", "dynamicToolCall")
            put("id", lifecycle.callId)
            put("namespace", "contract-probe")
            put("tool", "read")
            put("arguments", buildJsonObject { put("path", "cli") })
            when (lifecycle) {
                ToolCallWitnessLifecycle.STARTED -> put("status", "inProgress")
                ToolCallWitnessLifecycle.SUCCEEDED -> {
                    put("status", "completed")
                    put("contentItems", buildJsonArray {
                        add(buildJsonObject {
                            put("type", "inputText")
                            put("text", "{\"visible\":true}")
                        })
                    })
                    put("success", true)
                    put("durationMs", 4)
                }
                ToolCallWitnessLifecycle.FAILED -> {
                    put("status", "failed")
                    put("contentItems", buildJsonArray {
                        add(buildJsonObject {
                            put("type", "inputText")
                            put("text", "{\"failure\":\"contract-probe\"}")
                        })
                    })
                    put("success", false)
                    put("durationMs", 4)
                }
            }
        }

        private fun projectionIsAdmitted(
            contracts: Map<CodexOwnedSchema, CompiledJsonSchema>,
            witness: ToolCallProjectionWitness,
        ): Boolean {
            val contract = contracts[witness.lifecycle.schema] ?: return false
            val admittedParams = when (val admission = contract.admit(witness.params)) {
                is Validation.Validated -> admission.value.element as? JsonObject ?: return false
                is Validation.Rejected -> return false
            }
            val item = admittedParams["item"] as? JsonObject ?: return false
            val projection = when (witness.lifecycle) {
                CodexToolCallLifecycle.STARTED -> CodexToolCallProjector.projectStarted(item)
                CodexToolCallLifecycle.COMPLETED -> CodexToolCallProjector.projectCompleted(item)
            }
            val projectedItem = (projection as? CodexToolCallProjection.Projected)?.item
                ?: return false
            val projectedParams = JsonObject(admittedParams + ("item" to projectedItem))
            return contract.admit(projectedParams) is Validation.Validated
        }

        private fun containerProjectionIsAdmitted(
            contracts: Map<CodexOwnedSchema, CompiledJsonSchema>,
            witness: ItemContainerProjectionWitness,
        ): Boolean {
            val contract = contracts[witness.schema] ?: return false
            val admittedContainer = when (val admission = contract.admit(witness.container)) {
                is Validation.Validated -> admission.value.element as? JsonObject ?: return false
                is Validation.Rejected -> return false
            }
            val namespace = when (val admission = ProviderNamespace.admit("contract-probe")) {
                is Refinement.Refined -> admission.value
                is Refinement.Rejected -> return false
            }
            val projected = when (
                val projection = CodexThreadHistoryProjector.projectContainer(
                    witness.shape,
                    admittedContainer,
                    setOf(namespace),
                )
            ) {
                is CodexThreadHistoryProjection.Projected -> projection.result
                CodexThreadHistoryProjection.Unchanged,
                is CodexThreadHistoryProjection.Rejected,
                -> return false
            }
            return contract.admit(projected) is Validation.Validated
        }

        private fun notificationWitness(route: CodexItemNotificationRoute): JsonObject =
            when (route) {
                CodexItemNotificationRoute.THREAD_STARTED -> buildJsonObject {
                    put("thread", threadWitness())
                }
                CodexItemNotificationRoute.TURN_COMPLETED,
                CodexItemNotificationRoute.TURN_STARTED,
                -> buildJsonObject {
                    put("threadId", "thread-contract-probe")
                    put("turn", turnWitness())
                }
            }

        private fun responseWitness(route: CodexItemResponseRoute): JsonObject = when (route) {
            CodexItemResponseRoute.REVIEW_START -> buildJsonObject {
                put("reviewThreadId", "review-thread-contract-probe")
                put("turn", turnWitness())
            }
            CodexItemResponseRoute.THREAD_ITEMS_LIST -> buildJsonObject {
                put("data", buildJsonArray {
                    ToolCallWitnessLifecycle.entries.forEach { lifecycle ->
                        add(buildJsonObject {
                            put("turnId", "turn-contract-probe")
                            put("item", dynamicToolCallWitness(lifecycle))
                        })
                    }
                })
            }
            CodexItemResponseRoute.THREAD_LIST -> buildJsonObject {
                put("data", buildJsonArray { add(threadWitness()) })
            }
            CodexItemResponseRoute.THREAD_METADATA_UPDATE,
            CodexItemResponseRoute.THREAD_READ,
            CodexItemResponseRoute.THREAD_REVERT,
            CodexItemResponseRoute.THREAD_ROLLBACK,
            CodexItemResponseRoute.THREAD_UNARCHIVE,
            -> buildJsonObject { put("thread", threadWitness()) }
            CodexItemResponseRoute.THREAD_QUEUE_START,
            CodexItemResponseRoute.TURN_START,
            -> buildJsonObject { put("turn", turnWitness()) }
            CodexItemResponseRoute.THREAD_SEARCH -> buildJsonObject {
                put("data", buildJsonArray {
                    add(buildJsonObject {
                        put("snippet", "contract probe")
                        put("thread", threadWitness())
                    })
                })
            }
            CodexItemResponseRoute.THREAD_TIMELINE_LIST -> buildJsonObject {
                put("data", buildJsonArray {
                    ToolCallWitnessLifecycle.entries.forEachIndexed { position, lifecycle ->
                        add(buildJsonObject {
                            put("type", "item")
                            put("position", position)
                            put("turnId", "turn-contract-probe")
                            put("item", dynamicToolCallWitness(lifecycle))
                        })
                    }
                })
            }
            CodexItemResponseRoute.THREAD_TURNS_LIST -> buildJsonObject {
                put("data", buildJsonArray { add(turnWitness()) })
            }
        }

        private fun threadSessionResponseWitness(includeInitialPage: Boolean): JsonObject =
            buildJsonObject {
                put("approvalPolicy", "never")
                put("approvalsReviewer", "user")
                put("cwd", "/tmp/kast-broker-contract-probe")
                put("model", "contract-probe-model")
                put("modelProvider", "contract-probe-provider")
                put("sandbox", buildJsonObject { put("type", "dangerFullAccess") })
                put("thread", threadWitness())
                if (includeInitialPage) {
                    put("initialTurnsPage", buildJsonObject {
                        put("data", buildJsonArray { add(turnWitness()) })
                    })
                }
            }

        private fun threadWitness(): JsonObject = buildJsonObject {
            put("cliVersion", "0")
            put("createdAt", 0)
            put("cwd", "/tmp/kast-broker-contract-probe")
            put("ephemeral", false)
            put("id", "thread-contract-probe")
            put("modelProvider", "contract-probe-provider")
            put("preview", "contract probe")
            put("projectId", JsonNull)
            put("sessionId", "session-contract-probe")
            put("source", "appServer")
            put("status", buildJsonObject { put("type", "idle") })
            put("turns", buildJsonArray { add(turnWitness()) })
            put("updatedAt", 0)
        }

        private fun turnWitness(): JsonObject = buildJsonObject {
            put("id", "turn-contract-probe")
            put("items", buildJsonArray {
                ToolCallWitnessLifecycle.entries.forEach { lifecycle ->
                    add(dynamicToolCallWitness(lifecycle))
                }
            })
            put("status", "completed")
        }

        private enum class ToolCallWitnessLifecycle(val callId: String) {
            STARTED("call-started-contract-probe"),
            SUCCEEDED("call-succeeded-contract-probe"),
            FAILED("call-failed-contract-probe"),
        }

        private data class ToolCallProjectionWitness(
            val lifecycle: CodexToolCallLifecycle,
            val params: JsonObject,
        )

        private data class ItemContainerProjectionWitness(
            val schema: CodexOwnedSchema,
            val shape: CodexItemContainerShape,
            val container: JsonObject,
        )
    }
}
