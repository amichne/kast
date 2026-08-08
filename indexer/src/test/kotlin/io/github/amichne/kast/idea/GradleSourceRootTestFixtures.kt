package io.github.amichne.kast.idea

import com.intellij.openapi.externalSystem.model.project.ExternalSystemSourceType
import java.nio.file.Path

internal fun authoredGradleSourceRoot(path: Path): IdeaGradleProjectLoadBridge.GradleSourceRoot =
    IdeaGradleProjectLoadBridge.classifySourceRoot(path, listOf(ExternalSystemSourceType.SOURCE))

internal fun generatedGradleSourceRoot(path: Path): IdeaGradleProjectLoadBridge.GradleSourceRoot =
    IdeaGradleProjectLoadBridge.classifySourceRoot(path, listOf(ExternalSystemSourceType.SOURCE_GENERATED))

internal fun unknownGradleSourceRoot(path: Path): IdeaGradleProjectLoadBridge.GradleSourceRoot =
    IdeaGradleProjectLoadBridge.classifySourceRoot(path, emptyList())
