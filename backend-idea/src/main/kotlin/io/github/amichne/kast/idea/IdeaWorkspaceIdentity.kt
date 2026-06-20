package io.github.amichne.kast.idea

import com.intellij.openapi.project.Project
import io.github.amichne.kast.api.client.WorkspaceDirectoryResolver
import io.github.amichne.kast.api.contract.NormalizedPath
import io.github.amichne.kast.api.protocol.ValidationException
import java.nio.file.Path

internal class IdeaWorkspaceIdentity private constructor(
    val workspaceRoot: NormalizedPath,
    val canonicalWorkspaceRoot: NormalizedPath,
    val workspaceId: String,
    val ideaProjectName: String,
    val ideaProjectBasePath: String,
) {
    val workspaceRootPath: Path
        get() = workspaceRoot.toJavaPath()

    val canonicalWorkspaceRootPath: Path
        get() = canonicalWorkspaceRoot.toJavaPath()

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
        val isWorkspaceFile = canonicalTarget == canonicalWorkspaceRootPath ||
            canonicalTarget.startsWith(canonicalWorkspaceRootPath)
        if (!isWorkspaceFile) {
            throw ValidationException(
                message = "Kast IDEA edits must target files inside the active workspace",
                details = mapOf(
                    "filePath" to rawFilePath,
                    "canonicalFilePath" to canonicalTarget.toString(),
                    "workspaceRoot" to workspaceRoot.value,
                    "canonicalWorkspaceRoot" to canonicalWorkspaceRoot.value,
                    "workspaceId" to workspaceId,
                    "mutation" to mutation.wireName,
                    "ideaProjectName" to ideaProjectName,
                    "ideaProjectBasePath" to ideaProjectBasePath,
                ),
            )
        }
        val relativePath = canonicalWorkspaceRootPath.relativize(canonicalTarget).toString()
        return IdeaWorkspaceFilePath(
            normalizedPath = normalizedPath,
            canonicalPath = NormalizedPath.ofAbsolute(canonicalTarget),
            relativePath = relativePath,
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
        private val resolver = WorkspaceDirectoryResolver()

        fun fromProject(project: Project, workspaceRoot: Path): IdeaWorkspaceIdentity {
            val basePath = project.basePath ?: throw ValidationException(
                message = "Kast IDEA edits require a project with a workspace root",
                details = mapOf("ideaProjectName" to project.name),
            )
            val normalizedWorkspaceRoot = NormalizedPath.ofAbsolute(workspaceRoot)
            val canonicalWorkspaceRoot = NormalizedPath.of(normalizedWorkspaceRoot.toJavaPath())
            return IdeaWorkspaceIdentity(
                workspaceRoot = normalizedWorkspaceRoot,
                canonicalWorkspaceRoot = canonicalWorkspaceRoot,
                workspaceId = resolver.workspaceHash(normalizedWorkspaceRoot.toJavaPath()),
                ideaProjectName = project.name,
                ideaProjectBasePath = basePath,
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
