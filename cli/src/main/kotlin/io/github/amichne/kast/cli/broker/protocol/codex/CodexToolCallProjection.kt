package io.github.amichne.kast.cli.broker.protocol.codex

import io.github.amichne.kast.cli.broker.core.BrokerCallId
import io.github.amichne.kast.cli.broker.core.ProviderNamespace
import io.github.amichne.kast.cli.broker.core.ToolAddress
import io.github.amichne.kast.cli.broker.core.ToolName
import io.github.amichne.kast.kernel.Refinement
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

internal enum class CodexToolCallLifecycle(
    val schema: CodexOwnedSchema,
) {
    STARTED(CodexOwnedSchema.ITEM_STARTED_NOTIFICATION),
    COMPLETED(CodexOwnedSchema.ITEM_COMPLETED_NOTIFICATION),
}

internal enum class CodexItemContainerShape {
    THREAD,
    TURN,
    THREADS_PAGE,
    SEARCH_RESULTS_PAGE,
    TURNS_PAGE,
    ITEM_ENTRIES_PAGE,
    TIMELINE_PAGE,
}

internal enum class CodexItemResponseRoute(
    val method: String,
    val requestSchema: CodexOwnedSchema,
    val responseSchema: CodexOwnedSchema,
    val shape: CodexItemContainerShape,
) {
    REVIEW_START(
        "review/start",
        CodexOwnedSchema.REVIEW_START_PARAMS,
        CodexOwnedSchema.REVIEW_START_RESPONSE,
        CodexItemContainerShape.TURN,
    ),
    THREAD_ITEMS_LIST(
        "thread/items/list",
        CodexOwnedSchema.THREAD_ITEMS_LIST_PARAMS,
        CodexOwnedSchema.THREAD_ITEMS_LIST_RESPONSE,
        CodexItemContainerShape.ITEM_ENTRIES_PAGE,
    ),
    THREAD_LIST(
        "thread/list",
        CodexOwnedSchema.THREAD_LIST_PARAMS,
        CodexOwnedSchema.THREAD_LIST_RESPONSE,
        CodexItemContainerShape.THREADS_PAGE,
    ),
    THREAD_METADATA_UPDATE(
        "thread/metadata/update",
        CodexOwnedSchema.THREAD_METADATA_UPDATE_PARAMS,
        CodexOwnedSchema.THREAD_METADATA_UPDATE_RESPONSE,
        CodexItemContainerShape.THREAD,
    ),
    THREAD_QUEUE_START(
        "thread/queue/start",
        CodexOwnedSchema.THREAD_QUEUE_START_PARAMS,
        CodexOwnedSchema.THREAD_QUEUE_START_RESPONSE,
        CodexItemContainerShape.TURN,
    ),
    THREAD_READ(
        "thread/read",
        CodexOwnedSchema.THREAD_READ_PARAMS,
        CodexOwnedSchema.THREAD_READ_RESPONSE,
        CodexItemContainerShape.THREAD,
    ),
    THREAD_REVERT(
        "thread/revert",
        CodexOwnedSchema.THREAD_REVERT_PARAMS,
        CodexOwnedSchema.THREAD_REVERT_RESPONSE,
        CodexItemContainerShape.THREAD,
    ),
    THREAD_ROLLBACK(
        "thread/rollback",
        CodexOwnedSchema.THREAD_ROLLBACK_PARAMS,
        CodexOwnedSchema.THREAD_ROLLBACK_RESPONSE,
        CodexItemContainerShape.THREAD,
    ),
    THREAD_SEARCH(
        "thread/search",
        CodexOwnedSchema.THREAD_SEARCH_PARAMS,
        CodexOwnedSchema.THREAD_SEARCH_RESPONSE,
        CodexItemContainerShape.SEARCH_RESULTS_PAGE,
    ),
    THREAD_TIMELINE_LIST(
        "thread/timeline/list",
        CodexOwnedSchema.THREAD_TIMELINE_LIST_PARAMS,
        CodexOwnedSchema.THREAD_TIMELINE_LIST_RESPONSE,
        CodexItemContainerShape.TIMELINE_PAGE,
    ),
    THREAD_TURNS_LIST(
        "thread/turns/list",
        CodexOwnedSchema.THREAD_TURNS_LIST_PARAMS,
        CodexOwnedSchema.THREAD_TURNS_LIST_RESPONSE,
        CodexItemContainerShape.TURNS_PAGE,
    ),
    THREAD_UNARCHIVE(
        "thread/unarchive",
        CodexOwnedSchema.THREAD_UNARCHIVE_PARAMS,
        CodexOwnedSchema.THREAD_UNARCHIVE_RESPONSE,
        CodexItemContainerShape.THREAD,
    ),
    TURN_START(
        "turn/start",
        CodexOwnedSchema.TURN_START_PARAMS,
        CodexOwnedSchema.TURN_START_RESPONSE,
        CodexItemContainerShape.TURN,
    ),

    ;

    companion object {
        private val byMethod = entries.associateBy(CodexItemResponseRoute::method)

        internal fun forMethod(method: String): CodexItemResponseRoute? = byMethod[method]
    }
}

internal enum class CodexItemNotificationRoute(
    val method: String,
    val schema: CodexOwnedSchema,
    val shape: CodexItemContainerShape,
) {
    THREAD_STARTED(
        "thread/started",
        CodexOwnedSchema.THREAD_STARTED_NOTIFICATION,
        CodexItemContainerShape.THREAD,
    ),
    TURN_COMPLETED(
        "turn/completed",
        CodexOwnedSchema.TURN_COMPLETED_NOTIFICATION,
        CodexItemContainerShape.TURN,
    ),
    TURN_STARTED(
        "turn/started",
        CodexOwnedSchema.TURN_STARTED_NOTIFICATION,
        CodexItemContainerShape.TURN,
    ),

    ;

    companion object {
        private val byMethod = entries.associateBy(CodexItemNotificationRoute::method)

        internal fun forMethod(method: String): CodexItemNotificationRoute? = byMethod[method]
    }
}

internal enum class CodexToolCallProjectionFailure {
    DISCRIMINATOR_MISMATCH,
    CALL_ID_INVALID,
    NAMESPACE_INVALID,
    TOOL_INVALID,
    ARGUMENTS_MISSING,
    LIFECYCLE_STATUS_MISMATCH,
    STARTED_COMPLETION_PRESENT,
    DURATION_INVALID,
    COMPLETION_SUCCESS_CONFLICT,
    CONTENT_ITEM_INVALID,
    MEDIA_CONTENT_UNSUPPORTED,
}

internal sealed interface CodexToolCallProjection {
    data class Projected(val item: JsonObject) : CodexToolCallProjection
    data class Rejected(
        val failure: CodexToolCallProjectionFailure,
    ) : CodexToolCallProjection
}

/**
 * Refines one schema-admitted, broker-owned dynamic call to a typed lifecycle before encoding the
 * MCP item understood by Codex's existing CLI renderer.
 */
internal object CodexToolCallProjector {
    internal fun projectStarted(item: JsonObject): CodexToolCallProjection = when (
        val admission = admitIdentity(item)
    ) {
        is IdentityAdmission.Admitted -> {
            if (item.strictString("status") != "inProgress") {
                rejected(CodexToolCallProjectionFailure.LIFECYCLE_STATUS_MISMATCH)
            } else if (
                item["success"] !in setOf(null, JsonNull) ||
                item["contentItems"] !in setOf(null, JsonNull)
            ) {
                rejected(CodexToolCallProjectionFailure.STARTED_COMPLETION_PRESENT)
            } else {
                when (val duration = admitDuration(item["durationMs"])) {
                    is DurationAdmission.Admitted -> project(
                        DynamicToolCall.Started(admission.identity, duration.duration),
                    )
                    is DurationAdmission.Rejected -> rejected(duration.failure)
                }
            }
        }
        is IdentityAdmission.Rejected -> rejected(admission.failure)
    }

    internal fun projectCompleted(item: JsonObject): CodexToolCallProjection = when (
        val admission = admitIdentity(item)
    ) {
        is IdentityAdmission.Admitted -> admitCompleted(item, admission.identity)
        is IdentityAdmission.Rejected -> rejected(admission.failure)
    }

    private fun admitIdentity(item: JsonObject): IdentityAdmission {
        if (item.strictString("type") != "dynamicToolCall") {
            return IdentityAdmission.Rejected(
                CodexToolCallProjectionFailure.DISCRIMINATOR_MISMATCH,
            )
        }
        val callId = item.strictString("id")?.let(BrokerCallId::admit)
            ?: return IdentityAdmission.Rejected(CodexToolCallProjectionFailure.CALL_ID_INVALID)
        val namespace = item.strictString("namespace")
            ?.let(ProviderNamespace::admit)
            ?.let(::refined)
            ?: return IdentityAdmission.Rejected(CodexToolCallProjectionFailure.NAMESPACE_INVALID)
        val tool = item.strictString("tool")
            ?.let(ToolName::admit)
            ?.let(::refined)
            ?: return IdentityAdmission.Rejected(CodexToolCallProjectionFailure.TOOL_INVALID)
        val arguments = item["arguments"]
            ?: return IdentityAdmission.Rejected(CodexToolCallProjectionFailure.ARGUMENTS_MISSING)
        return IdentityAdmission.Admitted(
            DynamicToolIdentity(callId, ToolAddress(namespace, tool), DynamicToolArguments(arguments)),
        )
    }

    private fun admitCompleted(
        item: JsonObject,
        identity: DynamicToolIdentity,
    ): CodexToolCallProjection {
        val completion = when (item.strictString("status")) {
            "completed" -> DynamicToolCompletion.SUCCEEDED
            "failed" -> DynamicToolCompletion.FAILED
            else -> return rejected(CodexToolCallProjectionFailure.LIFECYCLE_STATUS_MISMATCH)
        }
        val declaredSuccess = when (val candidate = item["success"]) {
            null, JsonNull -> null
            is JsonPrimitive -> candidate.booleanOrNull
                ?: return rejected(
                    CodexToolCallProjectionFailure.COMPLETION_SUCCESS_CONFLICT,
                )
            else -> return rejected(CodexToolCallProjectionFailure.COMPLETION_SUCCESS_CONFLICT)
        }
        if (declaredSuccess != null && declaredSuccess != completion.success) {
            return rejected(CodexToolCallProjectionFailure.COMPLETION_SUCCESS_CONFLICT)
        }
        val duration = when (val admission = admitDuration(item["durationMs"])) {
            is DurationAdmission.Admitted -> admission.duration
            is DurationAdmission.Rejected -> return rejected(admission.failure)
        }
        val contentItems = when (val candidate = item["contentItems"]) {
            null, JsonNull -> JsonArray(emptyList())
            is JsonArray -> candidate
            else -> return rejected(CodexToolCallProjectionFailure.CONTENT_ITEM_INVALID)
        }
        val result = when (val admission = admitTextResult(contentItems)) {
            is ToolResultAdmission.Admitted -> admission.result
            is ToolResultAdmission.Rejected -> return rejected(admission.failure)
        }
        return project(DynamicToolCall.Completed(identity, completion, duration, result))
    }

    private fun admitDuration(candidate: JsonElement?): DurationAdmission = when (candidate) {
        null, JsonNull -> DurationAdmission.Admitted(null)
        is JsonPrimitive -> candidate.takeUnless(JsonPrimitive::isString)
            ?.longOrNull
            ?.takeIf { value -> value >= 0L }
            ?.let(ToolDurationMs::of)
            ?.let(DurationAdmission::Admitted)
            ?: DurationAdmission.Rejected(CodexToolCallProjectionFailure.DURATION_INVALID)
        else -> DurationAdmission.Rejected(CodexToolCallProjectionFailure.DURATION_INVALID)
    }

    private fun admitTextResult(items: JsonArray): ToolResultAdmission {
        val texts = mutableListOf<ToolResultText>()
        items.forEach { candidate ->
            val item = candidate as? JsonObject
                ?: return ToolResultAdmission.Rejected(
                    CodexToolCallProjectionFailure.CONTENT_ITEM_INVALID,
                )
            when (item.strictString("type")) {
                "inputText" -> {
                    val text = item.strictString("text")
                        ?: return ToolResultAdmission.Rejected(
                            CodexToolCallProjectionFailure.CONTENT_ITEM_INVALID,
                        )
                    texts += ToolResultText(text)
                }
                "inputImage", "inputAudio" -> return ToolResultAdmission.Rejected(
                    CodexToolCallProjectionFailure.MEDIA_CONTENT_UNSUPPORTED,
                )
                else -> return ToolResultAdmission.Rejected(
                    CodexToolCallProjectionFailure.CONTENT_ITEM_INVALID,
                )
            }
        }
        return ToolResultAdmission.Admitted(
            DynamicToolResult(
                texts = texts,
                structuredObject = texts.singleOrNull()?.value?.let(::parseObject),
            ),
        )
    }

    private fun project(call: DynamicToolCall): CodexToolCallProjection.Projected =
        CodexToolCallProjection.Projected(
            buildJsonObject {
                put("type", "mcpToolCall")
                put("id", call.identity.callId.value)
                put("server", call.identity.address.namespace.value)
                put("tool", call.identity.address.tool.value)
                put("arguments", call.identity.arguments.value)
                when (call) {
                    is DynamicToolCall.Started -> {
                        put("status", "inProgress")
                        call.duration?.let { duration -> put("durationMs", duration.value) }
                    }
                    is DynamicToolCall.Completed -> {
                        put("status", call.completion.status)
                        call.duration?.let { duration -> put("durationMs", duration.value) }
                        put("result", buildJsonObject {
                            put("content", buildJsonArray {
                                call.result.texts.forEach { text ->
                                    add(buildJsonObject {
                                        put("type", "text")
                                        put("text", text.value)
                                    })
                                }
                            })
                            call.result.structuredObject?.let { structured ->
                                put("structuredContent", structured)
                            }
                        })
                    }
                }
            },
        )

    private fun parseObject(source: String): JsonObject? = try {
        Json.parseToJsonElement(source) as? JsonObject
    } catch (_: SerializationException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }

    private fun rejected(
        failure: CodexToolCallProjectionFailure,
    ): CodexToolCallProjection.Rejected = CodexToolCallProjection.Rejected(failure)

    private data class DynamicToolIdentity(
        val callId: BrokerCallId,
        val address: ToolAddress,
        val arguments: DynamicToolArguments,
    )

    @JvmInline
    private value class DynamicToolArguments(val value: JsonElement)

    @JvmInline
    private value class ToolDurationMs private constructor(val value: Long) {
        companion object {
            fun of(value: Long): ToolDurationMs = ToolDurationMs(value)
        }
    }

    @JvmInline
    private value class ToolResultText(val value: String)

    private data class DynamicToolResult(
        val texts: List<ToolResultText>,
        val structuredObject: JsonObject?,
    )

    private enum class DynamicToolCompletion(
        val success: Boolean,
        val status: String,
    ) {
        SUCCEEDED(true, "completed"),
        FAILED(false, "failed"),
    }

    private sealed interface DynamicToolCall {
        val identity: DynamicToolIdentity

        data class Started(
            override val identity: DynamicToolIdentity,
            val duration: ToolDurationMs?,
        ) : DynamicToolCall

        data class Completed(
            override val identity: DynamicToolIdentity,
            val completion: DynamicToolCompletion,
            val duration: ToolDurationMs?,
            val result: DynamicToolResult,
        ) : DynamicToolCall
    }

    private sealed interface IdentityAdmission {
        data class Admitted(val identity: DynamicToolIdentity) : IdentityAdmission
        data class Rejected(val failure: CodexToolCallProjectionFailure) : IdentityAdmission
    }

    private sealed interface DurationAdmission {
        data class Admitted(val duration: ToolDurationMs?) : DurationAdmission
        data class Rejected(val failure: CodexToolCallProjectionFailure) : DurationAdmission
    }

    private sealed interface ToolResultAdmission {
        data class Admitted(val result: DynamicToolResult) : ToolResultAdmission
        data class Rejected(val failure: CodexToolCallProjectionFailure) : ToolResultAdmission
    }
}

internal sealed interface CodexThreadHistoryProjectionFailure {
    data object ThreadMissing : CodexThreadHistoryProjectionFailure
    data object ThreadInvalid : CodexThreadHistoryProjectionFailure
    data object TurnsMissing : CodexThreadHistoryProjectionFailure
    data object TurnMissing : CodexThreadHistoryProjectionFailure
    data object TurnInvalid : CodexThreadHistoryProjectionFailure
    data object ItemsMissing : CodexThreadHistoryProjectionFailure
    data object ItemInvalid : CodexThreadHistoryProjectionFailure
    data object InitialTurnsPageInvalid : CodexThreadHistoryProjectionFailure
    data object InitialTurnsDataMissing : CodexThreadHistoryProjectionFailure
    data object PageDataMissing : CodexThreadHistoryProjectionFailure
    data object EntryInvalid : CodexThreadHistoryProjectionFailure
    data object EntryItemMissing : CodexThreadHistoryProjectionFailure
    data object EntryThreadMissing : CodexThreadHistoryProjectionFailure
    data object TimelineEntryTypeInvalid : CodexThreadHistoryProjectionFailure
    data class ToolCallRejected(
        val failure: CodexToolCallProjectionFailure,
    ) : CodexThreadHistoryProjectionFailure
}

internal sealed interface CodexThreadHistoryProjection {
    data object Unchanged : CodexThreadHistoryProjection
    data class Projected(val result: JsonObject) : CodexThreadHistoryProjection
    data class Rejected(
        val failure: CodexThreadHistoryProjectionFailure,
    ) : CodexThreadHistoryProjection
}

/** Projects owned tool items in every installed response or notification carrier shape. */
internal object CodexThreadHistoryProjector {
    internal fun project(
        result: JsonObject,
        ownedNamespaces: Set<ProviderNamespace>,
    ): CodexThreadHistoryProjection = projectContainer(
        CodexItemContainerShape.THREAD,
        result,
        ownedNamespaces,
    )

    internal fun projectContainer(
        shape: CodexItemContainerShape,
        container: JsonObject,
        ownedNamespaces: Set<ProviderNamespace>,
    ): CodexThreadHistoryProjection = when (shape) {
        CodexItemContainerShape.THREAD -> projectThreadContainer(container, ownedNamespaces)
        CodexItemContainerShape.TURN -> projectTurnContainer(container, ownedNamespaces)
        CodexItemContainerShape.THREADS_PAGE -> projectThreadsPage(container, ownedNamespaces)
        CodexItemContainerShape.SEARCH_RESULTS_PAGE ->
            projectSearchResultsPage(container, ownedNamespaces)
        CodexItemContainerShape.TURNS_PAGE -> projectTurnsPage(container, ownedNamespaces)
        CodexItemContainerShape.ITEM_ENTRIES_PAGE ->
            projectItemEntriesPage(container, ownedNamespaces)
        CodexItemContainerShape.TIMELINE_PAGE -> projectTimelinePage(container, ownedNamespaces)
    }

    internal fun containsOwnedCall(
        shape: CodexItemContainerShape,
        container: JsonObject,
        ownedNamespaces: Set<ProviderNamespace>,
    ): Boolean = when (shape) {
        CodexItemContainerShape.THREAD ->
            containsOwnedThread(container["thread"], ownedNamespaces) ||
                containsOwnedTurnsPage(container["initialTurnsPage"], ownedNamespaces)
        CodexItemContainerShape.TURN -> containsOwnedTurn(container["turn"], ownedNamespaces)
        CodexItemContainerShape.THREADS_PAGE ->
            (container["data"] as? JsonArray)?.any { thread ->
                containsOwnedThread(thread, ownedNamespaces)
            } == true
        CodexItemContainerShape.SEARCH_RESULTS_PAGE ->
            (container["data"] as? JsonArray)?.any { entry ->
                containsOwnedThread((entry as? JsonObject)?.get("thread"), ownedNamespaces)
            } == true
        CodexItemContainerShape.TURNS_PAGE ->
            (container["data"] as? JsonArray)?.any { turn ->
                containsOwnedTurn(turn, ownedNamespaces)
            } == true
        CodexItemContainerShape.ITEM_ENTRIES_PAGE,
        CodexItemContainerShape.TIMELINE_PAGE,
        -> (container["data"] as? JsonArray)?.any { entry ->
            containsOwnedItem((entry as? JsonObject)?.get("item"), ownedNamespaces)
        } == true
    }

    private fun projectThreadContainer(
        result: JsonObject,
        ownedNamespaces: Set<ProviderNamespace>,
    ): CodexThreadHistoryProjection {
        val thread = result["thread"] as? JsonObject
            ?: return rejected(CodexThreadHistoryProjectionFailure.ThreadMissing)
        var projectedResult = result
        var changed = false
        when (val projection = projectThread(thread, ownedNamespaces)) {
            ObjectProjection.Unchanged -> Unit
            is ObjectProjection.Changed -> {
                changed = true
                projectedResult = JsonObject(projectedResult + ("thread" to projection.value))
            }
            is ObjectProjection.Rejected -> return rejected(projection.failure)
        }

        when (val page = result["initialTurnsPage"]) {
            null, JsonNull -> Unit
            is JsonObject -> {
                val pageTurns = page["data"] as? JsonArray
                    ?: return rejected(
                        CodexThreadHistoryProjectionFailure.InitialTurnsDataMissing,
                    )
                when (val projection = projectTurns(pageTurns, ownedNamespaces)) {
                    ArrayProjection.Unchanged -> Unit
                    is ArrayProjection.Changed -> {
                        changed = true
                        projectedResult = JsonObject(
                            projectedResult + (
                                "initialTurnsPage" to JsonObject(
                                    page + ("data" to projection.value),
                                )
                            ),
                        )
                    }
                    is ArrayProjection.Rejected -> return rejected(projection.failure)
                }
            }
            else -> return rejected(CodexThreadHistoryProjectionFailure.InitialTurnsPageInvalid)
        }
        return if (changed) {
            CodexThreadHistoryProjection.Projected(projectedResult)
        } else {
            CodexThreadHistoryProjection.Unchanged
        }
    }

    private fun projectTurnContainer(
        result: JsonObject,
        ownedNamespaces: Set<ProviderNamespace>,
    ): CodexThreadHistoryProjection {
        val turn = result["turn"] as? JsonObject
            ?: return rejected(CodexThreadHistoryProjectionFailure.TurnMissing)
        return when (val projection = projectTurn(turn, ownedNamespaces)) {
            ObjectProjection.Unchanged -> CodexThreadHistoryProjection.Unchanged
            is ObjectProjection.Changed -> CodexThreadHistoryProjection.Projected(
                JsonObject(result + ("turn" to projection.value)),
            )
            is ObjectProjection.Rejected -> rejected(projection.failure)
        }
    }

    private fun projectThreadsPage(
        result: JsonObject,
        ownedNamespaces: Set<ProviderNamespace>,
    ): CodexThreadHistoryProjection = projectDataPage(result) { data ->
        projectThreads(data, ownedNamespaces)
    }

    private fun projectSearchResultsPage(
        result: JsonObject,
        ownedNamespaces: Set<ProviderNamespace>,
    ): CodexThreadHistoryProjection = projectDataPage(result) { data ->
        projectSearchEntries(data, ownedNamespaces)
    }

    private fun projectTurnsPage(
        result: JsonObject,
        ownedNamespaces: Set<ProviderNamespace>,
    ): CodexThreadHistoryProjection = projectDataPage(result) { data ->
        projectTurns(data, ownedNamespaces)
    }

    private fun projectItemEntriesPage(
        result: JsonObject,
        ownedNamespaces: Set<ProviderNamespace>,
    ): CodexThreadHistoryProjection = projectDataPage(result) { data ->
        projectItemEntries(data, ownedNamespaces)
    }

    private fun projectTimelinePage(
        result: JsonObject,
        ownedNamespaces: Set<ProviderNamespace>,
    ): CodexThreadHistoryProjection = projectDataPage(result) { data ->
        projectTimelineEntries(data, ownedNamespaces)
    }

    private fun projectDataPage(
        result: JsonObject,
        projectData: (JsonArray) -> ArrayProjection,
    ): CodexThreadHistoryProjection {
        val data = result["data"] as? JsonArray
            ?: return rejected(CodexThreadHistoryProjectionFailure.PageDataMissing)
        return when (val projection = projectData(data)) {
            ArrayProjection.Unchanged -> CodexThreadHistoryProjection.Unchanged
            is ArrayProjection.Changed -> CodexThreadHistoryProjection.Projected(
                JsonObject(result + ("data" to projection.value)),
            )
            is ArrayProjection.Rejected -> rejected(projection.failure)
        }
    }

    private fun projectThreads(
        threads: JsonArray,
        ownedNamespaces: Set<ProviderNamespace>,
    ): ArrayProjection {
        var changed = false
        val projected = mutableListOf<JsonElement>()
        threads.forEach { candidate ->
            val thread = candidate as? JsonObject
                ?: return arrayRejected(CodexThreadHistoryProjectionFailure.ThreadInvalid)
            when (val projection = projectThread(thread, ownedNamespaces)) {
                ObjectProjection.Unchanged -> projected += thread
                is ObjectProjection.Changed -> {
                    changed = true
                    projected += projection.value
                }
                is ObjectProjection.Rejected -> return arrayRejected(projection.failure)
            }
        }
        return changedArray(changed, projected)
    }

    private fun projectTurns(
        turns: JsonArray,
        ownedNamespaces: Set<ProviderNamespace>,
    ): ArrayProjection {
        var changed = false
        val projected = mutableListOf<JsonElement>()
        turns.forEach { candidate ->
            val turn = candidate as? JsonObject
                ?: return arrayRejected(CodexThreadHistoryProjectionFailure.TurnInvalid)
            when (val projection = projectTurn(turn, ownedNamespaces)) {
                ObjectProjection.Unchanged -> projected += turn
                is ObjectProjection.Changed -> {
                    changed = true
                    projected += projection.value
                }
                is ObjectProjection.Rejected -> return arrayRejected(projection.failure)
            }
        }
        return changedArray(changed, projected)
    }

    private fun projectItemEntries(
        entries: JsonArray,
        ownedNamespaces: Set<ProviderNamespace>,
    ): ArrayProjection {
        var changed = false
        val projected = mutableListOf<JsonElement>()
        entries.forEach { candidate ->
            val entry = candidate as? JsonObject
                ?: return arrayRejected(CodexThreadHistoryProjectionFailure.EntryInvalid)
            val item = entry["item"] as? JsonObject
                ?: return arrayRejected(CodexThreadHistoryProjectionFailure.EntryItemMissing)
            when (val projection = projectItem(item, ownedNamespaces)) {
                ItemProjection.Unchanged -> projected += entry
                is ItemProjection.Changed -> {
                    changed = true
                    projected += JsonObject(entry + ("item" to projection.item))
                }
                is ItemProjection.Rejected -> return arrayRejected(projection.failure)
            }
        }
        return changedArray(changed, projected)
    }

    private fun projectTimelineEntries(
        entries: JsonArray,
        ownedNamespaces: Set<ProviderNamespace>,
    ): ArrayProjection {
        var changed = false
        val projected = mutableListOf<JsonElement>()
        entries.forEach { candidate ->
            val entry = candidate as? JsonObject
                ?: return arrayRejected(CodexThreadHistoryProjectionFailure.EntryInvalid)
            when (entry.strictString("type")) {
                "realtime", "turnStarted", "turnCompleted" -> projected += entry
                "item" -> {
                    val item = entry["item"] as? JsonObject
                        ?: return arrayRejected(
                            CodexThreadHistoryProjectionFailure.EntryItemMissing,
                        )
                    when (val projection = projectItem(item, ownedNamespaces)) {
                        ItemProjection.Unchanged -> projected += entry
                        is ItemProjection.Changed -> {
                            changed = true
                            projected += JsonObject(entry + ("item" to projection.item))
                        }
                        is ItemProjection.Rejected -> return arrayRejected(projection.failure)
                    }
                }
                else -> return arrayRejected(
                    CodexThreadHistoryProjectionFailure.TimelineEntryTypeInvalid,
                )
            }
        }
        return changedArray(changed, projected)
    }

    private fun projectSearchEntries(
        entries: JsonArray,
        ownedNamespaces: Set<ProviderNamespace>,
    ): ArrayProjection {
        var changed = false
        val projected = mutableListOf<JsonElement>()
        entries.forEach { candidate ->
            val entry = candidate as? JsonObject
                ?: return arrayRejected(CodexThreadHistoryProjectionFailure.EntryInvalid)
            val thread = entry["thread"] as? JsonObject
                ?: return arrayRejected(CodexThreadHistoryProjectionFailure.EntryThreadMissing)
            when (val projection = projectThread(thread, ownedNamespaces)) {
                ObjectProjection.Unchanged -> projected += entry
                is ObjectProjection.Changed -> {
                    changed = true
                    projected += JsonObject(entry + ("thread" to projection.value))
                }
                is ObjectProjection.Rejected -> return arrayRejected(projection.failure)
            }
        }
        return changedArray(changed, projected)
    }

    private fun projectThread(
        thread: JsonObject,
        ownedNamespaces: Set<ProviderNamespace>,
    ): ObjectProjection {
        val turns = thread["turns"] as? JsonArray
            ?: return objectRejected(CodexThreadHistoryProjectionFailure.TurnsMissing)
        return when (val projection = projectTurns(turns, ownedNamespaces)) {
            ArrayProjection.Unchanged -> ObjectProjection.Unchanged
            is ArrayProjection.Changed -> ObjectProjection.Changed(
                JsonObject(thread + ("turns" to projection.value)),
            )
            is ArrayProjection.Rejected -> objectRejected(projection.failure)
        }
    }

    private fun projectTurn(
        turn: JsonObject,
        ownedNamespaces: Set<ProviderNamespace>,
    ): ObjectProjection {
        val items = turn["items"] as? JsonArray
            ?: return objectRejected(CodexThreadHistoryProjectionFailure.ItemsMissing)
        return when (val projection = projectItems(items, ownedNamespaces)) {
            ArrayProjection.Unchanged -> ObjectProjection.Unchanged
            is ArrayProjection.Changed -> ObjectProjection.Changed(
                JsonObject(turn + ("items" to projection.value)),
            )
            is ArrayProjection.Rejected -> objectRejected(projection.failure)
        }
    }

    private fun projectItems(
        items: JsonArray,
        ownedNamespaces: Set<ProviderNamespace>,
    ): ArrayProjection {
        var changed = false
        val projected = mutableListOf<JsonElement>()
        items.forEach { candidate ->
            val item = candidate as? JsonObject
                ?: return arrayRejected(CodexThreadHistoryProjectionFailure.ItemInvalid)
            when (val projection = projectItem(item, ownedNamespaces)) {
                ItemProjection.Unchanged -> projected += item
                is ItemProjection.Changed -> {
                    changed = true
                    projected += projection.item
                }
                is ItemProjection.Rejected -> return arrayRejected(projection.failure)
            }
        }
        return changedArray(changed, projected)
    }

    private fun projectItem(
        item: JsonObject,
        ownedNamespaces: Set<ProviderNamespace>,
    ): ItemProjection {
        if (item.strictString("type") != "dynamicToolCall") return ItemProjection.Unchanged
        val namespace = item.strictString("namespace")
            ?.let(ProviderNamespace::admit)
            ?.let(::refined)
        if (namespace !in ownedNamespaces) return ItemProjection.Unchanged
        val projection = when (item.strictString("status")) {
            "inProgress" -> CodexToolCallProjector.projectStarted(item)
            "completed", "failed" -> CodexToolCallProjector.projectCompleted(item)
            else -> CodexToolCallProjection.Rejected(
                CodexToolCallProjectionFailure.LIFECYCLE_STATUS_MISMATCH,
            )
        }
        return when (projection) {
            is CodexToolCallProjection.Projected -> ItemProjection.Changed(projection.item)
            is CodexToolCallProjection.Rejected -> ItemProjection.Rejected(
                CodexThreadHistoryProjectionFailure.ToolCallRejected(projection.failure),
            )
        }
    }

    private sealed interface ArrayProjection {
        data object Unchanged : ArrayProjection
        data class Changed(val value: JsonArray) : ArrayProjection
        data class Rejected(
            val failure: CodexThreadHistoryProjectionFailure,
        ) : ArrayProjection
    }

    private sealed interface ObjectProjection {
        data object Unchanged : ObjectProjection
        data class Changed(val value: JsonObject) : ObjectProjection
        data class Rejected(
            val failure: CodexThreadHistoryProjectionFailure,
        ) : ObjectProjection
    }

    private sealed interface ItemProjection {
        data object Unchanged : ItemProjection
        data class Changed(val item: JsonObject) : ItemProjection
        data class Rejected(
            val failure: CodexThreadHistoryProjectionFailure,
        ) : ItemProjection
    }

    private fun rejected(
        failure: CodexThreadHistoryProjectionFailure,
    ): CodexThreadHistoryProjection.Rejected = CodexThreadHistoryProjection.Rejected(failure)

    private fun arrayRejected(
        failure: CodexThreadHistoryProjectionFailure,
    ): ArrayProjection.Rejected = ArrayProjection.Rejected(failure)

    private fun objectRejected(
        failure: CodexThreadHistoryProjectionFailure,
    ): ObjectProjection.Rejected = ObjectProjection.Rejected(failure)

    private fun changedArray(
        changed: Boolean,
        values: List<JsonElement>,
    ): ArrayProjection = if (changed) {
        ArrayProjection.Changed(JsonArray(values))
    } else {
        ArrayProjection.Unchanged
    }

    private fun containsOwnedTurnsPage(
        candidate: JsonElement?,
        ownedNamespaces: Set<ProviderNamespace>,
    ): Boolean = ((candidate as? JsonObject)?.get("data") as? JsonArray)?.any { turn ->
        containsOwnedTurn(turn, ownedNamespaces)
    } == true

    private fun containsOwnedThread(
        candidate: JsonElement?,
        ownedNamespaces: Set<ProviderNamespace>,
    ): Boolean = ((candidate as? JsonObject)?.get("turns") as? JsonArray)?.any { turn ->
        containsOwnedTurn(turn, ownedNamespaces)
    } == true

    private fun containsOwnedTurn(
        candidate: JsonElement?,
        ownedNamespaces: Set<ProviderNamespace>,
    ): Boolean = ((candidate as? JsonObject)?.get("items") as? JsonArray)?.any { item ->
        containsOwnedItem(item, ownedNamespaces)
    } == true

    private fun containsOwnedItem(
        candidate: JsonElement?,
        ownedNamespaces: Set<ProviderNamespace>,
    ): Boolean {
        val item = candidate as? JsonObject ?: return false
        if (item.strictString("type") != "dynamicToolCall") return false
        val namespace = item.strictString("namespace")
            ?.let(ProviderNamespace::admit)
            ?.let(::refined)
        return namespace in ownedNamespaces
    }
}

private fun JsonObject.strictString(name: String): String? =
    (get(name) as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content

private fun <Strong, Failure> refined(
    refinement: Refinement<Strong, Failure>,
): Strong? = when (refinement) {
    is Refinement.Refined -> refinement.value
    is Refinement.Rejected -> null
}
