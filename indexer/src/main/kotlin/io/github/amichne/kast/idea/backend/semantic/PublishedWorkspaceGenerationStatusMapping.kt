package io.github.amichne.kast.idea.backend.semantic

import io.github.amichne.kast.api.contract.PublishedWorkspaceGenerationStatus
import io.github.amichne.kast.indexstore.snapshot.PublishedWorkspaceGenerationManifest

internal fun PublishedWorkspaceGenerationManifest.toRuntimeStatus(): PublishedWorkspaceGenerationStatus =
    PublishedWorkspaceGenerationStatus(
        generation = generation.value,
        identity = identity.value,
        sourceIndexGeneration = sourceIndexGeneration.value,
        sourceIndexSchemaVersion = sourceIndexSchemaVersion.value,
        databaseFile = databaseFile,
        publishedAtEpochMillis = publishedAtEpochMillis,
        repositoryOverlayFile = repositoryOverlayFile,
    )
