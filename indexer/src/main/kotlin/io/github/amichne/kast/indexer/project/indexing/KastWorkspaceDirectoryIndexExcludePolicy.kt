package io.github.amichne.kast.indexer.project.indexing

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.impl.DirectoryIndexExcludePolicy
import com.intellij.openapi.vfs.VfsUtilCore
import io.github.amichne.kast.idea.KastStructuredTrace
import java.nio.file.Path

/**
 * Supplies exact, project-root-relative exclusions before IntelliJ constructs
 * its first workspace index iterators.
 */
internal class KastWorkspaceDirectoryIndexExcludePolicy(
    project: Project,
    workspaceRoot: KastProjectWorkspaceRoot,
) : DirectoryIndexExcludePolicy {
    private val exclusions = KastWorkspaceIndexingExclusions.forWorkspace(workspaceRoot)

    init {
        KastStructuredTrace.event(
            eventName = "idea.indexing.exclusions_resolved",
            project = project,
            workspaceRoot = workspaceRoot.pathForFilesystemBoundary,
            outcome = "available",
            detail = mapOf("excludeUrls" to exclusions.urls),
        )
    }

    /**
     * Proof transition: captured `Project -> String[]` at the IntelliJ
     * `DirectoryIndexExcludePolicy` boundary.
     *
     * Each returned URL is an exact, normalized child of the project root. A
     * policy instance can only be created from an admitted
     * [KastProjectWorkspaceRoot]. Raw URL extraction is permitted only by
     * IntelliJ's directory-index extension boundary.
     */
    override fun getExcludeUrlsForProject(): Array<String> = exclusions.urls.toTypedArray()
}

/**
 * Project-lifetime authority to install the exclusion policy before project
 * initialization and indexing begin.
 */
internal class KastWorkspaceDirectoryIndexExclusionAdmission private constructor(
    private val workspaceRoot: KastProjectWorkspaceRoot,
) {
    /**
     * Effectful transition:
     * `Project -> KastWorkspaceDirectoryIndexExclusionInstallation`.
     *
     * Establishes that the exact admitted workspace exclusions are registered
     * only for this project and owned by [lifetime], or returns the closed
     * [KastWorkspaceDirectoryIndexExclusionInstallation.Unsupported] result
     * when an isolated host does not expose the project extension point. Raw
     * extension registration is permitted only at this initialization boundary.
     */
    fun install(
        project: Project,
        lifetime: Disposable,
    ): KastWorkspaceDirectoryIndexExclusionInstallation {
        @Suppress("UnstableApiUsage")
        val extensionPoint = project.extensionArea
            .getExtensionPointIfRegistered<DirectoryIndexExcludePolicy>(
                DirectoryIndexExcludePolicy.EP_NAME.name,
            )
            ?: return KastWorkspaceDirectoryIndexExclusionInstallation.Unsupported
        extensionPoint.registerExtension(
            KastWorkspaceDirectoryIndexExcludePolicy(project, workspaceRoot),
            lifetime,
        )
        return KastWorkspaceDirectoryIndexExclusionInstallation.Installed
    }

    /** Installs the policy for exactly the lifetime of [project]. */
    @Suppress("UnstableApiUsage")
    fun installForProjectLifetime(project: Project) {
        when (install(project, project.service<KastWorkspaceDirectoryIndexExclusionLifetime>())) {
            KastWorkspaceDirectoryIndexExclusionInstallation.Installed -> Unit
            KastWorkspaceDirectoryIndexExclusionInstallation.Unsupported ->
                KastStructuredTrace.event(
                    eventName = "idea.indexing.exclusions_unavailable",
                    project = project,
                    workspaceRoot = workspaceRoot.pathForFilesystemBoundary,
                    outcome = "unsupported",
                    detail = mapOf(
                        "extensionPoint" to DirectoryIndexExcludePolicy.EP_NAME.name,
                    ),
                )
        }
    }

    companion object {
        /**
         * Proof transition: `Path -> KastWorkspaceDirectoryIndexExclusionAdmission`.
         *
         * Establishes an absolute, normalized workspace root whose exclusion
         * authority may be installed before IntelliJ initializes that project.
         * Raw path extraction is permitted only while forming IntelliJ VFS URLs.
         */
        fun fromWorkspaceRoot(workspaceRoot: Path): KastWorkspaceDirectoryIndexExclusionAdmission =
            KastWorkspaceDirectoryIndexExclusionAdmission(
                KastProjectWorkspaceRoot.fromWorkspaceRoot(workspaceRoot),
            )
    }
}

/** Closed result of project-scoped directory-index exclusion installation. */
internal sealed interface KastWorkspaceDirectoryIndexExclusionInstallation {
    data object Installed : KastWorkspaceDirectoryIndexExclusionInstallation

    data object Unsupported : KastWorkspaceDirectoryIndexExclusionInstallation
}

@Service(Service.Level.PROJECT)
internal class KastWorkspaceDirectoryIndexExclusionLifetime : Disposable {
    override fun dispose() = Unit
}

@JvmInline
internal value class KastProjectWorkspaceRoot private constructor(
    val pathForFilesystemBoundary: Path,
) {
    companion object {
        /**
         * Proof transition: `Path -> KastProjectWorkspaceRoot`.
         *
         * Establishes that the admitted workspace path is absolute and
         * normalized. Raw path extraction is permitted only while forming
         * IntelliJ VFS URLs and trace evidence.
         */
        fun fromWorkspaceRoot(workspaceRoot: Path): KastProjectWorkspaceRoot =
            KastProjectWorkspaceRoot(workspaceRoot.toAbsolutePath().normalize())
    }
}

private enum class KastNonSemanticWorkspaceRoot(
    val directoryName: String,
) {
    GRADLE_AND_KAST_CACHE(".gradle"),
    INTELLIJ_PLATFORM_CACHE(".intellijPlatform"),
    KAST_WORKSPACE_CACHE(".kast"),
    RUN_CONFIGURATION_STATE(".run"),
    PYTHON_VIRTUAL_ENVIRONMENT(".venv"),
    GENERATED_SITE("site"),
    NODE_MODULES("node_modules"),
    RUST_TARGET("target"),
}

private class KastWorkspaceIndexingExclusions private constructor(
    val urls: List<String>,
) {
    companion object {
        /**
         * Proof transition: `KastProjectWorkspaceRoot -> KastWorkspaceIndexingExclusions`.
         *
         * Establishes exact VFS URLs for the finite set of proven
         * non-semantic root children. It does not inspect `.gitignore` and
         * does not exclude broad build trees whose nested generated source
         * status requires the imported model. Raw path and URL extraction is
         * permitted only at this IntelliJ VFS boundary.
         */
        fun forWorkspace(root: KastProjectWorkspaceRoot): KastWorkspaceIndexingExclusions =
            KastWorkspaceIndexingExclusions(
                KastNonSemanticWorkspaceRoot.entries.map { exclusion ->
                    VfsUtilCore.pathToUrl(
                        root.pathForFilesystemBoundary.resolve(exclusion.directoryName).toString(),
                    )
                },
            )

    }
}
