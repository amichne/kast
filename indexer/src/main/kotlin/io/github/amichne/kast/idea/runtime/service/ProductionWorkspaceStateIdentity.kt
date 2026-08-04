package io.github.amichne.kast.idea

import com.intellij.openapi.project.Project
import io.github.amichne.kast.api.client.KastConfig
import io.github.amichne.kast.api.client.WorkspaceIdentity
import io.github.amichne.kast.api.validation.FileHashing
import io.github.amichne.kast.idea.transition.BuildSemanticInputIdentity
import io.github.amichne.kast.idea.transition.IdeaSemanticEnvironmentIdentityResolver
import io.github.amichne.kast.idea.transition.WorkspaceStateIdentity
import io.github.amichne.kast.idea.transition.WorkspaceStateIdentityResolver
import java.nio.file.Path

internal fun productionWorkspaceStateIdentity(
    project: Project,
    workspaceRoot: Path,
    workspaceIdentity: WorkspaceIdentity,
    liveConfig: KastConfig,
    admittedContentIdentity: AdmittedWorkspaceContentIdentity,
    gradleModelIdentity: String,
    buildSemanticInputIdentity: BuildSemanticInputIdentity,
    isCancelled: () -> Boolean,
): WorkspaceStateIdentity = WorkspaceStateIdentityResolver(
    workspaceRoot = workspaceRoot,
    admittedContentIdentity = { admittedContentIdentity },
    semanticEnvironmentIdentity = {
        buildString {
            append(IdeaSemanticEnvironmentIdentityResolver.resolve(project, workspaceIdentity, isCancelled))
            append("\ngradle-model:").append(gradleModelIdentity)
        }
    },
    indexingScopeIdentity = {
        FileHashing.sha256(
            buildString {
                append(liveConfig.indexing)
                append("\n.kastignore:")
                append(SemanticPathContentIdentity.resolve(workspaceRoot.resolve(".kastignore"), isCancelled))
            },
        )
    },
    buildSemanticInputIdentity = { buildSemanticInputIdentity },
).resolve()
