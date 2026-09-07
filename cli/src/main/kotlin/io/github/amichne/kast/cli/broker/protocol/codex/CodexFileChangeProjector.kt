package io.github.amichne.kast.cli.broker.protocol.codex

import io.github.amichne.kast.cli.broker.core.BrokerCallId
import io.github.amichne.kast.cli.broker.core.ObserverFileChangeKind
import io.github.amichne.kast.cli.broker.core.ObserverFileChangeSet
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Projects proven Kast mutations into Codex's native expandable diff item. */
internal object CodexFileChangeProjector {
    internal fun completed(callId: BrokerCallId, files: ObserverFileChangeSet): JsonObject =
        buildJsonObject {
            put("type", "fileChange")
            put("id", callId.value)
            put("status", "completed")
            put("changes", buildJsonArray {
                files.entries.forEach { file ->
                    add(buildJsonObject {
                        put("path", file.path.value)
                        put("diff", file.diff.value)
                        put("kind", buildJsonObject {
                            put("type", JsonPrimitive(file.kind.codexName()))
                        })
                    })
                }
            })
        }

    private fun ObserverFileChangeKind.codexName(): String = when (this) {
        ObserverFileChangeKind.ADD -> "add"
        ObserverFileChangeKind.DELETE -> "delete"
        ObserverFileChangeKind.UPDATE -> "update"
    }
}
