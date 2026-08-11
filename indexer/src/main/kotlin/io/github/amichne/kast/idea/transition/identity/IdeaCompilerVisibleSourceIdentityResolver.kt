package io.github.amichne.kast.idea.transition

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.vfs.StandardFileSystems
import com.intellij.openapi.vfs.VfsUtilCore
import io.github.amichne.kast.api.client.WorkspaceIdentity
import io.github.amichne.kast.api.validation.FileHashing
import io.github.amichne.kast.idea.IdeaWorkspaceModuleIdentity
import io.github.amichne.kast.idea.SemanticPathContentIdentity
import io.github.amichne.kast.indexstore.api.index.FileContentHash
import java.nio.file.Files
import java.nio.file.Path

@JvmInline
internal value class CompilerVisibleSourceIdentity private constructor(val value: String) {
    companion object {
        fun hash(records: Iterable<String>): CompilerVisibleSourceIdentity =
            CompilerVisibleSourceIdentity(FileHashing.sha256(records.joinToString("\n")))
    }
}

@JvmInline
internal value class CompilerSourceRootAuthorities private constructor(val roots: Set<Path>) {
    companion object {
        /**
         * Proof transition: `Project -> CompilerSourceRootAuthorities`.
         *
         * Establishes the complete set of local compiler source-root authorities
         * configured in IntelliJ module models, including roots whose directories
         * do not yet exist and therefore have no `VirtualFile`. Non-local VFS
         * protocols are outside this filesystem authority. Raw [Path] values may
         * be extracted only at the [WorkspaceVfsObservationScope] boundary.
         */
        fun from(project: Project): CompilerSourceRootAuthorities =
            CompilerSourceRootAuthorities(
                ApplicationManager.getApplication().runReadAction<Set<Path>> {
                    ModuleManager.getInstance(project).modules
                        .asSequence()
                        .filterNot(Module::isDisposed)
                        .flatMap { module ->
                            ModuleRootManager.getInstance(module).sourceRootUrls.asSequence()
                        }
                        .mapNotNull(::localConfiguredSourceRootPath)
                        .toCollection(linkedSetOf())
                },
            )
    }
}

internal object IdeaCompilerVisibleSourceIdentityResolver {
    fun resolve(
        project: Project,
        workspaceIdentity: WorkspaceIdentity,
        modules: List<Module>,
        isCancelled: () -> Boolean = { false },
    ): CompilerVisibleSourceIdentity {
        val candidates = modules.asSequence()
            .filterNot(Module::isDisposed)
            .sortedBy(Module::getName)
            .flatMap { module -> candidates(workspaceIdentity, module, isCancelled) }
            .distinct()
            .sortedWith(
                compareBy(
                    CompilerVisibleSourceCandidate::stablePath,
                    { candidate -> candidate.ownerModule.value },
                    { candidate -> candidate.language.name },
                ),
            )
            .toList()
        return CompilerVisibleSourceIdentity.hash(
            candidates.map { candidate ->
                SemanticPathContentIdentity.requireActive(isCancelled)
                record(
                    candidate.stablePath,
                    candidate.ownerModule.value,
                    candidate.language.name,
                    hashFile(candidate.absolutePath, isCancelled).value,
                )
            },
        )
    }

    fun sourceRoots(project: Project): Set<Path> =
        ApplicationManager.getApplication().runReadAction<Set<Path>> {
            ModuleManager.getInstance(project).modules
                .asSequence()
                .filterNot(Module::isDisposed)
                .flatMap { module -> moduleSourceRoots(module).asSequence() }
                .toCollection(linkedSetOf())
        }

    private fun candidates(
        workspaceIdentity: WorkspaceIdentity,
        module: Module,
        isCancelled: () -> Boolean,
    ): Sequence<CompilerVisibleSourceCandidate> {
        val sourceRoots = moduleSourceRoots(module)
        if (sourceRoots.isEmpty()) return emptySequence()
        val owner = IdeaWorkspaceModuleIdentity.of(module.name)
        return sourceRoots.asSequence().flatMap { root ->
            Files.walk(root).use { paths ->
                paths.peek { SemanticPathContentIdentity.requireActive(isCancelled) }
                    .filter(Files::isRegularFile)
                    .map { path -> path.toAbsolutePath().normalize() }
                    .map { path -> path to sourceLanguage(path) }
                    .filter { (_, language) -> language != null }
                    .map { (path, language) ->
                        CompilerVisibleSourceCandidate(
                            absolutePath = path,
                            stablePath = stablePath(workspaceIdentity, path),
                            ownerModule = owner,
                            language = checkNotNull(language),
                        )
                    }.toList().asSequence()
            }
        }
    }

    private fun moduleSourceRoots(module: Module): List<Path> =
        ModuleRootManager.getInstance(module).sourceRoots
            .asSequence()
            .filter { root -> root.isValid && root.isDirectory }
            .map { root -> root.toNioPath().toAbsolutePath().normalize() }
            .toList()

    private fun sourceLanguage(path: Path): CompilerSourceLanguage? = when (path.fileName.toString().substringAfterLast('.')) {
        "kt", "kts" -> CompilerSourceLanguage.KOTLIN
        "java" -> CompilerSourceLanguage.JAVA
        else -> null
    }

    private fun hashFile(path: Path, isCancelled: () -> Boolean): FileContentHash =
        FileContentHash.parse(SemanticPathContentIdentity.file(path, isCancelled))

    private fun stablePath(workspaceIdentity: WorkspaceIdentity, path: Path): String =
        workspaceIdentity.relativizeIfContained(path)?.toString()?.replace('\\', '/')
            ?: "\$EXTERNAL/${path.toString().replace('\\', '/')}"

    private fun record(vararg fields: String): String = buildString {
        fields.forEach { field -> append(field.length).append(':').append(field) }
    }
}

private fun localConfiguredSourceRootPath(sourceRootUrl: String): Path? {
    val protocol = sourceRootUrl.substringBefore("://", missingDelimiterValue = "")
    if (protocol != StandardFileSystems.FILE_PROTOCOL) return null
    return Path.of(VfsUtilCore.urlToPath(sourceRootUrl)).toAbsolutePath().normalize()
}

private enum class CompilerSourceLanguage {
    KOTLIN,
    JAVA,
}

private data class CompilerVisibleSourceCandidate(
    val absolutePath: Path,
    val stablePath: String,
    val ownerModule: IdeaWorkspaceModuleIdentity,
    val language: CompilerSourceLanguage,
)
