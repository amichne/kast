package io.github.amichne.kast.appserver

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
internal data class WorkspaceRegistryDocument(val schemaVersion: Int, val revision: Long, val roots: List<String>)

internal fun encodeWorkspaceRegistry(snapshot: WorkspaceRegistrySnapshot): String =
    Json.encodeToString(
        WorkspaceRegistryDocument(
            2,
            snapshot.revision.value,
            snapshot.workspaces.map { it.root.path.toString() }.sorted(),
        )
    )
