package io.github.amichne.kast.cli.broker.protocol.codex

import io.github.amichne.kast.cli.broker.core.BrokerCallId
import io.github.amichne.kast.cli.broker.core.ObserverPresentation
import io.github.amichne.kast.cli.broker.provider.KastObserverProjector
import io.github.amichne.kast.cli.broker.provider.ObserverWorkingDirectory
import io.github.amichne.kast.kernel.Refinement
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.put

/** Rebuilds expandable native observer results from canonical history; no pending state is read. */
internal object CodexHistoricalObserverProjector {
    internal fun project(shape: CodexItemContainerShape, container: JsonObject): JsonObject {
        val scope = scope(container)
        return when (shape) {
            CodexItemContainerShape.THREAD -> {
                val withThread = container.mapObject("thread") { thread -> thread(thread, scope) }
                withThread.mapObject("initialTurnsPage") { page -> turnsPage(page, scope) }
            }
            CodexItemContainerShape.TURN -> container.mapObject("turn") { turn(it, scope) }
            CodexItemContainerShape.THREADS_PAGE -> container.mapArray("data") { thread(it, scope) }
            CodexItemContainerShape.SEARCH_RESULTS_PAGE -> container.mapArray("data") { entry ->
                entry.mapObject("thread") { thread(it, scope) }
            }
            CodexItemContainerShape.TURNS_PAGE -> turnsPage(container, scope)
            CodexItemContainerShape.ITEM_ENTRIES_PAGE,
            CodexItemContainerShape.TIMELINE_PAGE -> container
        }
    }

    private fun thread(thread: JsonObject, inherited: Scope): JsonObject {
        val scope = scope(thread).or(inherited)
        return thread.mapArray("turns") { turn(it, scope) }
    }

    private fun turnsPage(page: JsonObject, scope: Scope): JsonObject =
        page.mapArray("data") { turn(it, scope) }

    private fun turn(turn: JsonObject, scope: Scope): JsonObject {
        if (scope !is Scope.Known) return turn
        val items = turn["items"] as? JsonArray ?: return turn
        val projected = items.map { candidate ->
            val item = candidate as? JsonObject ?: return@map candidate
            expandable(item, scope.directory) ?: item
        }
        return JsonObject(turn + ("items" to JsonArray(projected)))
    }

    private fun expandable(item: JsonObject, directory: ObserverWorkingDirectory): JsonObject? {
        if (item["type"] != JsonPrimitive("mcpToolCall") ||
            item["server"] != JsonPrimitive("kast") ||
            item["status"] != JsonPrimitive("completed")
        ) return null
        val document = (item["result"] as? JsonObject)?.get("structuredContent") as? JsonObject
            ?: return null
        val observer = try {
            KastObserverProjector.projectHistorical(document, directory)
        } catch (_: RuntimeException) {
            return null
        }
        return when (observer) {
            is ObserverPresentation.Markdown -> {
                val result = JsonObject(
                    mapOf(
                        "content" to buildJsonArray {
                            add(JsonObject(mapOf(
                                "type" to JsonPrimitive("text"),
                                "text" to JsonPrimitive(observer.source.value),
                            )))
                        },
                    ),
                )
                JsonObject(item + ("result" to result) + ("readOnlyHint" to JsonPrimitive(true)))
            }
            is ObserverPresentation.FileChanges -> {
                val callId = (item["id"] as? JsonPrimitive)?.content
                    ?.let(BrokerCallId::admit) ?: return null
                CodexFileChangeProjector.completed(callId, observer.files)
            }
            ObserverPresentation.None -> null
        }
    }

    private fun scope(container: JsonObject): Scope {
        val raw = (container["cwd"] as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content
            ?: return Scope.Unavailable
        return when (val admitted = ObserverWorkingDirectory.admit(raw)) {
            is Refinement.Refined -> Scope.Known(admitted.value)
            is Refinement.Rejected -> Scope.Rejected
        }
    }

    private sealed interface Scope {
        data class Known(val directory: ObserverWorkingDirectory) : Scope
        data object Unavailable : Scope
        data object Rejected : Scope

        fun or(inherited: Scope): Scope = when (this) {
            is Known -> when (inherited) {
                is Known -> if (directory.path == inherited.directory.path) this else Rejected
                Rejected -> Rejected
                Unavailable -> this
            }
            Unavailable -> inherited
            Rejected -> Rejected
        }
    }

    private fun JsonObject.mapObject(key: String, transform: (JsonObject) -> JsonObject): JsonObject {
        val child = this[key] as? JsonObject ?: return this
        return JsonObject(this + (key to transform(child)))
    }

    private fun JsonObject.mapArray(key: String, transform: (JsonObject) -> JsonElement): JsonObject {
        val array = this[key] as? JsonArray ?: return this
        return JsonObject(this + (key to JsonArray(array.map { (it as? JsonObject)?.let(transform) ?: it })))
    }
}
