package io.github.amichne.kast.idea

import com.intellij.openapi.project.Project
import io.github.amichne.kast.api.client.WorkspaceIdentity
import io.github.amichne.kast.api.validation.FileHashing
import io.github.amichne.kast.api.contract.NormalizedPath
import io.github.amichne.kast.api.protocol.ValidationException
import java.nio.file.Path

@JvmInline
internal value class IdeaProjectLocationHash(val value: String) {
    override fun toString(): String = value
}

internal data class IdeaProjectIdentity(
    val name: String,
    val basePath: NormalizedPath,
    val locationHash: IdeaProjectLocationHash,
)

internal data class IdeaWorkspaceIdentity(
    val workspaceIdentity: WorkspaceIdentity,
    val ideaProject: IdeaProjectIdentity,
) {
    val workspaceRootPath: Path
        get() = workspaceIdentity.workspaceRootPath

    val canonicalWorkspaceRootPath: Path
        get() = workspaceIdentity.canonicalWorkspaceRootPath

    fun traceDetails(): Map<String, Any?> = workspaceIdentity.traceDetails() + mapOf(
        "ideaProjectName" to ideaProject.name,
        "ideaProjectBasePath" to ideaProject.basePath.value,
        "ideaProjectLocationHash" to ideaProject.locationHash.value,
    )

    fun requireEditablePath(
        rawFilePath: String,
        mutation: IdeaWorkspaceMutation,
    ): IdeaWorkspaceFilePath {
        val normalizedPath = NormalizedPath.parse(rawFilePath)
        val canonicalTarget = when (mutation) {
            IdeaWorkspaceMutation.CREATE_FILE -> canonicalCreateTarget(normalizedPath)
            IdeaWorkspaceMutation.DELETE_FILE,
            IdeaWorkspaceMutation.TEXT_EDIT -> NormalizedPath.of(normalizedPath.toJavaPath()).toJavaPath()
        }
        val relativePath = workspaceIdentity.relativizeIfContained(canonicalTarget)
        if (relativePath == null) {
            throw ValidationException(
                message = "Kast IDEA edits must target files inside the active workspace",
                details = mapOf(
                    "filePath" to rawFilePath,
                    "canonicalFilePath" to canonicalTarget.toString(),
                    "workspaceRoot" to workspaceIdentity.workspaceRoot.value,
                    "canonicalWorkspaceRoot" to workspaceIdentity.canonicalWorkspaceRoot.value,
                    "workspaceId" to workspaceIdentity.workspaceId.value,
                    "canonicalWorkspaceId" to workspaceIdentity.canonicalWorkspaceId.value,
                    "mutation" to mutation.wireName,
                    "ideaProjectName" to ideaProject.name,
                    "ideaProjectBasePath" to ideaProject.basePath.value,
                    "ideaProjectLocationHash" to ideaProject.locationHash.value,
                ),
            )
        }
        return IdeaWorkspaceFilePath(
            normalizedPath = normalizedPath,
            canonicalPath = NormalizedPath.ofAbsolute(canonicalTarget),
            relativePath = relativePath.toString(),
        )
    }

    private fun canonicalCreateTarget(normalizedPath: NormalizedPath): Path {
        val target = normalizedPath.toJavaPath()
        val parent = target.parent ?: throw ValidationException(
            message = "Create file operations require a parent directory",
            details = mapOf("filePath" to normalizedPath.value),
        )
        return NormalizedPath.of(parent)
            .toJavaPath()
            .resolve(target.fileName)
            .normalize()
    }

    companion object {
        fun fromProject(
            project: Project,
            workspaceRoot: Path,
            descriptorDirectory: Path? = null,
        ): IdeaWorkspaceIdentity {
            val basePath = project.basePath ?: throw ValidationException(
                message = "Kast IDEA edits require a project with a workspace root",
                details = mapOf("ideaProjectName" to project.name),
            )
            return IdeaWorkspaceIdentity(
                workspaceIdentity = if (descriptorDirectory == null) {
                    WorkspaceIdentity.fromWorkspaceRoot(workspaceRoot)
                } else {
                    WorkspaceIdentity.fromWorkspaceRoot(workspaceRoot, descriptorDirectory = descriptorDirectory)
                },
                ideaProject = IdeaProjectIdentity(
                    name = project.name,
                    basePath = NormalizedPath.of(Path.of(basePath)),
                    locationHash = IdeaProjectLocationHash(FileHashing.sha256(Path.of(basePath).toAbsolutePath().normalize().toString()).take(12)),
                ),
            )
        }
    }
}

internal data class IdeaWorkspaceFilePath(
    val normalizedPath: NormalizedPath,
    val canonicalPath: NormalizedPath,
    val relativePath: String,
)

internal enum class IdeaWorkspaceMutation(val wireName: String) {
    TEXT_EDIT("text_edit"),
    CREATE_FILE("create_file"),
    DELETE_FILE("delete_file"),
}
