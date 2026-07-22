package io.github.amichne.kast.api.client

import io.github.amichne.kast.api.client.fields.defaultConfigSocketDir
import io.github.amichne.kast.api.contract.NormalizedPath
import io.github.amichne.kast.api.validation.FileHashing
import java.nio.file.Files
import java.nio.file.Path

@JvmInline
value class WorkspaceId(val value: String) {
    override fun toString(): String = value
}

@JvmInline
value class GradleSettingsFileHash(val value: String) {
    override fun toString(): String = value
}

data class GradleRootIdentity(
    val root: NormalizedPath,
    val settingsFile: NormalizedPath,
    val settingsFileHash: GradleSettingsFileHash,
)

data class WorkspaceIdentity(
    val workspaceRoot: NormalizedPath,
    val canonicalWorkspaceRoot: NormalizedPath,
    val workspaceId: WorkspaceId,
    val canonicalWorkspaceId: WorkspaceId,
    val repositoryDataDirectory: NormalizedPath?,
    val workspaceDataDirectory: NormalizedPath,
    val workspaceCacheDirectory: NormalizedPath,
    val sourceIndexDatabasePath: NormalizedPath,
    val defaultSocketPath: NormalizedPath,
    val descriptorDirectory: NormalizedPath,
    val gradleRoot: GradleRootIdentity?,
) {
    val workspaceRootPath: Path
        get() = workspaceRoot.toJavaPath()

    val canonicalWorkspaceRootPath: Path
        get() = canonicalWorkspaceRoot.toJavaPath()

    val workspaceDataDirectoryPath: Path
        get() = workspaceDataDirectory.toJavaPath()

    val repositoryDataDirectoryPath: Path?
        get() = repositoryDataDirectory?.toJavaPath()

    val workspaceCacheDirectoryPath: Path
        get() = workspaceCacheDirectory.toJavaPath()

    val sourceIndexDatabaseFile: Path
        get() = sourceIndexDatabasePath.toJavaPath()

    val defaultSocketFile: Path
        get() = defaultSocketPath.toJavaPath()

    val descriptorDirectoryFile: Path
        get() = descriptorDirectory.toJavaPath()

    fun contains(path: Path): Boolean {
        val canonicalPath = NormalizedPath.of(path).toJavaPath()
        return canonicalPath == canonicalWorkspaceRootPath ||
            canonicalPath.startsWith(canonicalWorkspaceRootPath)
    }

    fun contains(rawPath: String): Boolean =
        runCatching { contains(Path.of(rawPath)) }.getOrDefault(false)

    fun relativizeIfContained(path: Path): Path? {
        val canonicalPath = NormalizedPath.of(path).toJavaPath()
        return if (canonicalPath == canonicalWorkspaceRootPath || canonicalPath.startsWith(canonicalWorkspaceRootPath)) {
            canonicalWorkspaceRootPath.relativize(canonicalPath)
        } else {
            null
        }
    }

    fun traceDetails(): Map<String, Any?> = mapOf(
        "workspaceId" to workspaceId.value,
        "canonicalWorkspaceId" to canonicalWorkspaceId.value,
        "workspaceRoot" to workspaceRoot.value,
        "canonicalWorkspaceRoot" to canonicalWorkspaceRoot.value,
        "repositoryDataDirectory" to repositoryDataDirectory?.value,
        "workspaceDataDirectory" to workspaceDataDirectory.value,
        "workspaceCacheDirectory" to workspaceCacheDirectory.value,
        "sourceIndexDatabasePath" to sourceIndexDatabasePath.value,
        "defaultSocketPath" to defaultSocketPath.value,
        "descriptorDirectory" to descriptorDirectory.value,
        "gradleRoot" to gradleRoot?.root?.value,
        "gradleSettingsFile" to gradleRoot?.settingsFile?.value,
        "gradleSettingsFileHash" to gradleRoot?.settingsFileHash?.value,
    )

    companion object {
        fun fromWorkspaceRoot(
            workspaceRoot: Path,
            resolver: WorkspaceDirectoryResolver = WorkspaceDirectoryResolver(),
            descriptorDirectory: Path = defaultDescriptorDirectory(),
        ): WorkspaceIdentity {
            val normalizedWorkspaceRoot = NormalizedPath.ofAbsolute(workspaceRoot)
            val canonicalWorkspaceRoot = NormalizedPath.of(normalizedWorkspaceRoot.toJavaPath())
            val workspaceId = WorkspaceId(resolver.workspaceHash(normalizedWorkspaceRoot.toJavaPath()))
            val canonicalWorkspaceId = WorkspaceId(resolver.workspaceHash(canonicalWorkspaceRoot.toJavaPath()))
            val workspaceDataDirectory = resolver.workspaceDataDirectory(normalizedWorkspaceRoot.toJavaPath())
            val workspaceCacheDirectory = resolver.workspaceCacheDirectory(normalizedWorkspaceRoot.toJavaPath())
            return WorkspaceIdentity(
                workspaceRoot = normalizedWorkspaceRoot,
                canonicalWorkspaceRoot = canonicalWorkspaceRoot,
                workspaceId = workspaceId,
                canonicalWorkspaceId = canonicalWorkspaceId,
                repositoryDataDirectory = resolver.repositoryDataDirectory(normalizedWorkspaceRoot.toJavaPath())
                    ?.let(NormalizedPath::ofAbsolute),
                workspaceDataDirectory = NormalizedPath.ofAbsolute(workspaceDataDirectory),
                workspaceCacheDirectory = NormalizedPath.ofAbsolute(workspaceCacheDirectory),
                sourceIndexDatabasePath = NormalizedPath.ofAbsolute(resolver.workspaceDatabasePath(normalizedWorkspaceRoot.toJavaPath())),
                defaultSocketPath = NormalizedPath.ofAbsolute(socketPathForWorkspaceId(workspaceId)),
                descriptorDirectory = NormalizedPath.ofAbsolute(descriptorDirectory),
                gradleRoot = gradleRootIdentity(canonicalWorkspaceRoot.toJavaPath()),
            )
        }

        private fun socketPathForWorkspaceId(workspaceId: WorkspaceId): Path = Path.of(
            defaultConfigSocketDir(),
            "kast-${workspaceId.value}.sock",
        ).toAbsolutePath().normalize()

        private fun gradleRootIdentity(canonicalWorkspaceRoot: Path): GradleRootIdentity? {
            val settingsFile = generateSequence(canonicalWorkspaceRoot) { path -> path.parent }
                .flatMap { root ->
                    sequenceOf(root.resolve("settings.gradle.kts"), root.resolve("settings.gradle"))
                }
                .firstOrNull(Files::isRegularFile)
                ?: return null
            return GradleRootIdentity(
                root = NormalizedPath.of(settingsFile.parent),
                settingsFile = NormalizedPath.of(settingsFile),
                settingsFileHash = GradleSettingsFileHash(FileHashing.sha256(Files.readString(settingsFile))),
            )
        }
    }
}
