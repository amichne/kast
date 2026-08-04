package io.github.amichne.kast.idea

import io.github.amichne.kast.idea.transition.WorkspaceStateIdentity
import io.github.amichne.kast.indexstore.api.index.FileStageVersion
import io.github.amichne.kast.indexstore.api.index.FileStageVersions

internal fun semanticContextStageVersions(identity: WorkspaceStateIdentity?): FileStageVersions =
    if (identity == null) {
        FileStageVersions.CURRENT
    } else {
        FileStageVersions.CURRENT.copy(
            relationships = FileStageVersion.parse(
                "${FileStageVersions.CURRENT.relationships.value}-${identity.value}",
            ),
            semanticGraph = FileStageVersion.parse(
                "${FileStageVersions.CURRENT.semanticGraph.value}-${identity.value}",
            ),
        )
    }
