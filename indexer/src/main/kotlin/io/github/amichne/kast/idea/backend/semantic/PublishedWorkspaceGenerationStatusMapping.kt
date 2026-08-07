package io.github.amichne.kast.idea.backend.semantic

import io.github.amichne.kast.api.contract.PublishedWorkspaceGenerationStatus
import io.github.amichne.kast.indexstore.snapshot.PublishedWorkspaceGenerationManifest

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
        sourceIndexSchemaVersion = sourceIndexSchemaVersion.value,
        databaseFile = "source-index.db",
        publishedAtEpochMillis = publishedAt.value,
        repositoryOverlayFile = repositoryOverlay.serializedFileName(),
    )
