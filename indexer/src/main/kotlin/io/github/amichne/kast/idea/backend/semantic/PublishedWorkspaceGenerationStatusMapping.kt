package io.github.amichne.kast.idea.backend.semantic

import io.github.amichne.kast.api.contract.PublishedWorkspaceGenerationStatus
import io.github.amichne.kast.indexstore.snapshot.PublishedWorkspaceGenerationManifest
import io.github.amichne.kast.indexstore.snapshot.GraphEvidencePublication
import io.github.amichne.kast.api.contract.PublishedGraphEvidenceStatus
import io.github.amichne.kast.api.contract.PublishedGraphEvidenceBlocker

/**
 * Proof-preserving boundary transition:
 * `PublishedWorkspaceGenerationManifest -> PublishedWorkspaceGenerationStatus`.
 *
 * Extracts typed publication facts only into their serialized wire fields.
 */
internal fun PublishedWorkspaceGenerationManifest.toRuntimeStatus(): PublishedWorkspaceGenerationStatus =
    PublishedWorkspaceGenerationStatus(
        generation = generation.value,
        identity = identity.value,
        sourceIndexGeneration = sourceIndexGeneration.value,
        sourceRevision = sourceRevision.value,
        referenceRevision = referenceRevision.value,
        graphPublication = when (val graph = graphPublication) {
            is GraphEvidencePublication.Ready -> PublishedGraphEvidenceStatus.Ready(graph.revision.value)
            is GraphEvidencePublication.Blocked -> PublishedGraphEvidenceStatus.Blocked(
                PublishedGraphEvidenceBlocker.valueOf(graph.blocker.name),
            )
        },
        sourceIndexSchemaVersion = sourceIndexSchemaVersion.value,
        databaseFile = "source-index.db",
        publishedAtEpochMillis = publishedAt.value,
        repositoryOverlayFile = repositoryOverlay.serializedFileName(),
    )
