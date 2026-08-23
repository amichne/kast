package io.github.amichne.kast.workspace.intellij

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.externalSystem.model.DataNode
import com.intellij.openapi.externalSystem.model.ExternalProjectInfo
import com.intellij.openapi.externalSystem.model.ProjectKeys
import com.intellij.openapi.externalSystem.model.project.ContentRootData
import com.intellij.openapi.externalSystem.model.project.ExternalSystemSourceType
import com.intellij.openapi.externalSystem.model.project.ModuleData
import com.intellij.openapi.externalSystem.service.project.ProjectDataManager
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ModuleRootManager
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import io.github.amichne.kast.workspace.contract.WorkspaceSourceContentHash
import io.github.amichne.kast.workspace.contract.WorkspaceSourcePath
import io.github.amichne.kast.workspace.contract.WorkspaceSourceRootBoundary
import io.github.amichne.kast.workspace.contract.WorkspaceSourceRootKind
import io.github.amichne.kast.workspace.contract.WorkspaceSourceRootProvenance
import io.github.amichne.kast.workspace.contract.WorkspaceStateIdentity
import org.jetbrains.plugins.gradle.model.data.GradleSourceSetData
import org.jetbrains.plugins.gradle.util.GradleConstants
import org.jetbrains.plugins.gradle.util.gradlePathOrNull
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.security.MessageDigest

/** Exact detached values consumed by composition's installed Gradle-model projection. */
class InstalledGradleModelCapture internal constructor(
    val root: CanonicalWorkspaceRoot,
    val sourceRoots: List<WorkspaceSourceRootBoundary>,
    val identity: WorkspaceStateIdentity,
    private val identityBoundary: InstalledGradleSemanticIdentityBoundary,
) {
    /**
     * Proof transition: `InstalledGradleModelCapture -> Refinement<WorkspaceStateIdentity,
     * InstalledGradleModelCaptureFailure>`.
     *
     * Establishes the current source-content identity under the same detached Gradle ownership,
     * module, SDK, and classpath evidence proven by this capture. The closed expected failure is
     * [InstalledGradleModelCaptureFailure]. Raw filesystem reads remain inside this physical
     * identity boundary.
     */
    fun captureCurrentSemanticIdentity(): Refinement<
        WorkspaceStateIdentity,
        InstalledGradleModelCaptureFailure,
        > {
        val currentContents = when (val captured = captureSourceContentIdentities(
            Path.of(root.value),
            sourceRoots,
        )) {
            is InstalledSourceContentIdentityCapture.Captured -> captured.contents
            is InstalledSourceContentIdentityCapture.Rejected -> return Refinement.Rejected(
                InstalledGradleModelCaptureFailure.SOURCE_STATE_UNAVAILABLE,
            )
        }
        return when (val derived = deriveInstalledGradleSemanticIdentity(
            identityBoundary.copy(sourceContents = currentContents),
        )) {
            is Refinement.Refined -> derived
            is Refinement.Rejected -> Refinement.Rejected(
                InstalledGradleModelCaptureFailure.IDENTITIES_UNAVAILABLE,
            )
        }
    }
}

enum class InstalledGradleModelCaptureFailure {
    ROOT_UNAVAILABLE,
    EXTERNAL_PROJECT_UNAVAILABLE,
    EXTERNAL_PROJECT_INCOMPLETE,
    SOURCE_ROOTS_UNAVAILABLE,
    SOURCE_STATE_UNAVAILABLE,
    IDENTITIES_UNAVAILABLE,
}

/**
 * Proof transition: `(Project, Path) -> Refinement<InstalledGradleModelCapture,
 * InstalledGradleModelCaptureFailure>`.
 *
 * A refined result establishes a complete successful external-project model, non-empty exact
 * Gradle source-set ownership, exact source-content state, and one canonical semantic identity.
 * [InstalledGradleModelCaptureFailure] closes every expected missing proof. Live project, module,
 * DataNode, source-root, SDK, order-entry, and import-timestamp values do not leave this read
 * action.
 */
internal fun captureInstalledGradleModel(
    project: Project,
    workspaceRoot: Path,
): Refinement<InstalledGradleModelCapture, InstalledGradleModelCaptureFailure> =
    ReadAction.nonBlocking<Refinement<InstalledGradleModelCapture, InstalledGradleModelCaptureFailure>> model@{
        val root = when (val admitted = CanonicalWorkspaceRoot.fromCanonicalPath(workspaceRoot)) {
            is Refinement.Refined -> admitted.value
            is Refinement.Rejected -> return@model Refinement.Rejected(
                InstalledGradleModelCaptureFailure.ROOT_UNAVAILABLE,
            )
        }
        val projectData = ProjectDataManager.getInstance()
        val projects = projectData.getExternalProjectsData(project, GradleConstants.SYSTEM_ID)
            .filter { info -> Path.of(info.externalProjectPath).toAbsolutePath().normalize() == workspaceRoot }
        projects.forEach { info -> projectData.ensureTheDataIsReadyToUse(info.externalProjectStructure) }
        if (projects.isEmpty()) {
            return@model Refinement.Rejected(
                InstalledGradleModelCaptureFailure.EXTERNAL_PROJECT_UNAVAILABLE,
            )
        }
        if (projects.any { !it.isComplete() }) {
            return@model Refinement.Rejected(
                InstalledGradleModelCaptureFailure.EXTERNAL_PROJECT_INCOMPLETE,
            )
        }
        val boundaries = projects.flatMap { info -> info.sourceRootBoundaries() }
            .distinct()
            .sortedWith(compareBy({ it.sourceRoot.toString() }, { it.ideaModuleName }))
        if (boundaries.isEmpty()) {
            return@model Refinement.Rejected(
                InstalledGradleModelCaptureFailure.SOURCE_ROOTS_UNAVAILABLE,
            )
        }
        val sourceIdentities = when (val captured = captureSourceContentIdentities(
            workspaceRoot,
            boundaries,
        )) {
            is InstalledSourceContentIdentityCapture.Captured -> captured.contents
            is InstalledSourceContentIdentityCapture.Rejected -> return@model Refinement.Rejected(
                InstalledGradleModelCaptureFailure.SOURCE_STATE_UNAVAILABLE,
            )
        }
        val identityBoundary = InstalledGradleSemanticIdentityBoundary(
            root = root,
            sourceRoots = boundaries,
            sourceContents = sourceIdentities,
            externalProjectPaths = projects.map { info ->
                Path.of(info.externalProjectPath).toAbsolutePath().normalize()
            },
            modules = ModuleManager.getInstance(project).modules
                .filterNot { module -> module.isDisposed }
                .map { module ->
                    val roots = ModuleRootManager.getInstance(module)
                    val sdk = roots.sdk?.let { installed ->
                        InstalledSdkSemanticIdentity.Present(
                            installed.versionString?.takeIf(String::isNotBlank)
                                ?.let(InstalledSdkVersion::Known)
                            ?: InstalledSdkVersion.Unknown,
                        )
                    } ?: InstalledSdkSemanticIdentity.Absent
                    InstalledModuleSemanticIdentity(
                        module.name,
                        sdk,
                        roots.orderEntries().classes().urls
                            .map(::InstalledClasspathEntrySemanticIdentity),
                    )
                },
        )
        val identity = when (val derived = deriveInstalledGradleSemanticIdentity(identityBoundary)) {
            is Refinement.Refined -> derived.value
            is Refinement.Rejected -> return@model Refinement.Rejected(
                InstalledGradleModelCaptureFailure.IDENTITIES_UNAVAILABLE,
            )
        }
        Refinement.Refined(
            InstalledGradleModelCapture(root, boundaries, identity, identityBoundary),
        )
    }.inSmartMode(project).executeSynchronously()

private enum class InstalledSourceContentIdentityFailure {
    OUTSIDE_WORKSPACE,
    SYMBOLIC_LINK,
    NOT_DIRECTORY,
    UNSUPPORTED_ENTRY,
    IO_UNAVAILABLE,
    ACCESS_DENIED,
    IDENTITY_REJECTED,
}

private sealed interface InstalledSourceContentIdentityCapture {
    data class Captured(
        val contents: List<InstalledSourceContentIdentity>,
    ) : InstalledSourceContentIdentityCapture

    data class Rejected(
        val failure: InstalledSourceContentIdentityFailure,
    ) : InstalledSourceContentIdentityCapture
}

/**
 * Proof transition: `(Path, List<WorkspaceSourceRootBoundary>) ->
 * InstalledSourceContentIdentityCapture`.
 *
 * Captured establishes typed deterministic content identities for every regular entry beneath each
 * workspace-contained, non-symlinked source root, including explicit present and missing root
 * states. [InstalledSourceContentIdentityFailure] closes containment, kind, link, I/O, and access
 * failures. Raw paths and digest text remain inside this physical identity-capture boundary.
 */
private fun captureSourceContentIdentities(
    workspaceRoot: Path,
    sourceRoots: List<WorkspaceSourceRootBoundary>,
): InstalledSourceContentIdentityCapture = try {
    val contents = mutableListOf<InstalledSourceContentIdentity>()
    for (root in sourceRoots.map(WorkspaceSourceRootBoundary::sourceRoot).distinct().sorted()) {
        if (!root.startsWith(workspaceRoot)) {
            return InstalledSourceContentIdentityCapture.Rejected(
                InstalledSourceContentIdentityFailure.OUTSIDE_WORKSPACE,
            )
        }
        if (Files.isSymbolicLink(root)) {
            return InstalledSourceContentIdentityCapture.Rejected(
                InstalledSourceContentIdentityFailure.SYMBOLIC_LINK,
            )
        }
        val relativeRoot = when (
            val admitted = WorkspaceSourcePath.parse(
                workspaceRoot.relativize(root).portablePath(),
            )
        ) {
            is Refinement.Refined -> admitted.value
            is Refinement.Rejected -> return InstalledSourceContentIdentityCapture.Rejected(
                InstalledSourceContentIdentityFailure.IDENTITY_REJECTED,
            )
        }
        if (Files.notExists(root, LinkOption.NOFOLLOW_LINKS)) {
            contents += InstalledSourceContentIdentity.Root(
                relativeRoot,
                InstalledSourceRootState.Missing,
            )
            continue
        }
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            return InstalledSourceContentIdentityCapture.Rejected(
                InstalledSourceContentIdentityFailure.NOT_DIRECTORY,
            )
        }
        contents += InstalledSourceContentIdentity.Root(
            relativeRoot,
            InstalledSourceRootState.Present,
        )
        Files.walk(root).use { paths ->
            val iterator = paths.sorted().iterator()
            while (iterator.hasNext()) {
                val path = iterator.next()
                if (Files.isSymbolicLink(path)) {
                    return InstalledSourceContentIdentityCapture.Rejected(
                        InstalledSourceContentIdentityFailure.SYMBOLIC_LINK,
                    )
                }
                when {
                    Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) -> Unit
                    Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) -> {
                        val relativePath = when (
                            val admitted = WorkspaceSourcePath.parse(
                                workspaceRoot.relativize(path).portablePath(),
                            )
                        ) {
                            is Refinement.Refined -> admitted.value
                            is Refinement.Rejected ->
                                return InstalledSourceContentIdentityCapture.Rejected(
                                    InstalledSourceContentIdentityFailure.IDENTITY_REJECTED,
                                )
                        }
                        val hash = when (val admitted = WorkspaceSourceContentHash.parse(path.sha256())) {
                            is Refinement.Refined -> admitted.value
                            is Refinement.Rejected ->
                                return InstalledSourceContentIdentityCapture.Rejected(
                                    InstalledSourceContentIdentityFailure.IDENTITY_REJECTED,
                                )
                        }
                        contents += InstalledSourceContentIdentity.File(relativePath, hash)
                    }
                    else -> return InstalledSourceContentIdentityCapture.Rejected(
                        InstalledSourceContentIdentityFailure.UNSUPPORTED_ENTRY,
                    )
                }
            }
        }
    }
    InstalledSourceContentIdentityCapture.Captured(contents.distinct())
} catch (_: IOException) {
    InstalledSourceContentIdentityCapture.Rejected(
        InstalledSourceContentIdentityFailure.IO_UNAVAILABLE,
    )
} catch (_: SecurityException) {
    InstalledSourceContentIdentityCapture.Rejected(
        InstalledSourceContentIdentityFailure.ACCESS_DENIED,
    )
}

private fun Path.sha256(): String {
    val digest = MessageDigest.getInstance("SHA-256")
    Files.newInputStream(this).use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
    }
    return digest.digest().joinToString("") { byte ->
        (byte.toInt() and 0xff).toString(16).padStart(2, '0')
    }
}

private fun Path.portablePath(): String = joinToString("/") { segment -> segment.toString() }

private fun ExternalProjectInfo.isComplete(): Boolean =
    externalProjectStructure?.let { structure ->
        structure.isReady &&
        lastSuccessfulImportTimestamp > 0 &&
        lastSuccessfulImportTimestamp >= lastImportTimestamp
    } == true

private fun ExternalProjectInfo.sourceRootBoundaries(): List<WorkspaceSourceRootBoundary> {
    val structure = externalProjectStructure ?: return emptyList()
    val boundaries = mutableListOf<WorkspaceSourceRootBoundary>()
    structure.visit { node ->
        val sourceSet = node.data as? GradleSourceSetData ?: return@visit
        boundaries += sourceSet.sourceRootBoundaries(node)
    }
    return boundaries
}

private fun GradleSourceSetData.sourceRootBoundaries(
    node: DataNode<*>,
): List<WorkspaceSourceRootBoundary> {
    val projectPath = (node.parent?.data as? ModuleData)?.gradlePathOrNull
                      ?: return emptyList()
    val sourceSetName = externalName.substringAfterLast(':')
    val buildRoot = Path.of(linkedExternalProjectPath).toAbsolutePath().normalize()
    val boundaries = mutableListOf<WorkspaceSourceRootBoundary>()
    node.visit { child ->
        if (child.key != ProjectKeys.CONTENT_ROOT) return@visit
        val content = child.data as? ContentRootData ?: return@visit
        ExternalSystemSourceType.entries.forEach { type ->
            val kind = type.sourceKind() ?: return@forEach
            content.getPaths(type).forEach { sourceRoot ->
                boundaries += WorkspaceSourceRootBoundary(
                    internalName,
                    buildRoot,
                    projectPath,
                    sourceSetName,
                    Path.of(sourceRoot.path).toAbsolutePath().normalize(),
                    kind,
                    if (type.isGenerated) {
                        WorkspaceSourceRootProvenance.GENERATED
                    } else {
                        WorkspaceSourceRootProvenance.AUTHORED
                    },
                )
            }
        }
    }
    return boundaries
}

private fun ExternalSystemSourceType.sourceKind(): WorkspaceSourceRootKind? = when (this) {
    ExternalSystemSourceType.SOURCE,
    ExternalSystemSourceType.SOURCE_GENERATED,
        -> WorkspaceSourceRootKind.PRODUCTION
    ExternalSystemSourceType.TEST,
    ExternalSystemSourceType.TEST_GENERATED,
        -> WorkspaceSourceRootKind.TEST
    ExternalSystemSourceType.EXCLUDED,
    ExternalSystemSourceType.RESOURCE,
    ExternalSystemSourceType.TEST_RESOURCE,
    ExternalSystemSourceType.RESOURCE_GENERATED,
    ExternalSystemSourceType.TEST_RESOURCE_GENERATED,
        -> null
}
