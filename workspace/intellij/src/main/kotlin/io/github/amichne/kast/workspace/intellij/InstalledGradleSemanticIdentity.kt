package io.github.amichne.kast.workspace.intellij

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import io.github.amichne.kast.workspace.contract.WorkspaceSourceContentHash
import io.github.amichne.kast.workspace.contract.WorkspaceSourcePath
import io.github.amichne.kast.workspace.contract.WorkspaceSourceRootBoundary
import io.github.amichne.kast.workspace.contract.WorkspaceStateIdentity
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.security.MessageDigest

/** Raw detached semantic facts extracted from one complete live Gradle model. */
internal data class InstalledGradleSemanticIdentityBoundary(
    val root: CanonicalWorkspaceRoot,
    val sourceRoots: List<WorkspaceSourceRootBoundary>,
    val sourceContents: List<InstalledSourceContentIdentity>,
    val externalProjectPaths: List<Path>,
    val modules: List<InstalledModuleSemanticIdentity>,
)

internal enum class InstalledSourceRootState {
    Present,
    Missing,
}

internal sealed interface InstalledSourceContentIdentity {
    data class Root(
        val path: WorkspaceSourcePath,
        val state: InstalledSourceRootState,
    ) : InstalledSourceContentIdentity

    data class File(
        val path: WorkspaceSourcePath,
        val hash: WorkspaceSourceContentHash,
    ) : InstalledSourceContentIdentity
}

internal data class InstalledModuleSemanticIdentity(
    val name: String,
    val sdk: InstalledSdkSemanticIdentity,
    val classpath: List<InstalledClasspathEntrySemanticIdentity>,
)

internal sealed interface InstalledSdkSemanticIdentity {
    data object Absent : InstalledSdkSemanticIdentity

    data class Present(
        val version: InstalledSdkVersion,
    ) : InstalledSdkSemanticIdentity
}

internal sealed interface InstalledSdkVersion {
    data object Unknown : InstalledSdkVersion

    data class Known(
        val value: String,
    ) : InstalledSdkVersion
}

internal data class InstalledClasspathEntrySemanticIdentity(
    val url: String,
)

internal enum class InstalledGradleSemanticIdentityFailure {
    INCOMPLETE_SEMANTIC_INPUT,
    INVALID_PROJECT_PATH,
    INVALID_SOURCE_ROOT,
    INVALID_MODULE,
    STATE_IDENTITY_REJECTED,
}

/**
 * Proof transition: `InstalledGradleSemanticIdentityBoundary -> Refinement<
 * WorkspaceStateIdentity, InstalledGradleSemanticIdentityFailure>`.
 *
 * Establishes a versioned, order-independent SHA-256 identity over source content, exact Gradle
 * ownership, compiler SDK state, and dependency entries. Import timestamps and other operational
 * freshness data are absent from the input type. Raw live-model strings may enter only through
 * [InstalledGradleSemanticIdentityBoundary].
 */
internal fun deriveInstalledGradleSemanticIdentity(
    boundary: InstalledGradleSemanticIdentityBoundary,
): Refinement<WorkspaceStateIdentity, InstalledGradleSemanticIdentityFailure> {
    if (
        boundary.sourceRoots.isEmpty() ||
        boundary.sourceContents.isEmpty() ||
        boundary.externalProjectPaths.isEmpty() ||
        boundary.modules.isEmpty()
    ) {
        return Refinement.Rejected(
            InstalledGradleSemanticIdentityFailure.INCOMPLETE_SEMANTIC_INPUT,
        )
    }
    val root = Path.of(boundary.root.value)
    if (boundary.externalProjectPaths.any { path ->
            !path.isAbsolute || path.normalize() != path || !path.startsWith(root)
        }
    ) {
        return Refinement.Rejected(InstalledGradleSemanticIdentityFailure.INVALID_PROJECT_PATH)
    }
    if (boundary.sourceRoots.any { sourceRoot ->
            !sourceRoot.linkedBuildRoot.isAbsolute ||
            sourceRoot.linkedBuildRoot.normalize() != sourceRoot.linkedBuildRoot ||
            !sourceRoot.linkedBuildRoot.startsWith(root) ||
            !sourceRoot.sourceRoot.isAbsolute ||
            sourceRoot.sourceRoot.normalize() != sourceRoot.sourceRoot ||
            !sourceRoot.sourceRoot.startsWith(root)
        }
    ) {
        return Refinement.Rejected(InstalledGradleSemanticIdentityFailure.INVALID_SOURCE_ROOT)
    }
    if (boundary.modules.any { module ->
            module.name.isBlank() ||
            module.classpath.any { entry -> entry.url.isBlank() } ||
            when (val sdk = module.sdk) {
                InstalledSdkSemanticIdentity.Absent -> false
                is InstalledSdkSemanticIdentity.Present -> when (val version = sdk.version) {
                    InstalledSdkVersion.Unknown -> false
                    is InstalledSdkVersion.Known -> version.value.isBlank()
                }
            }
        }
    ) {
        return Refinement.Rejected(InstalledGradleSemanticIdentityFailure.INVALID_MODULE)
    }

    val canonical = buildString {
        appendField(IDENTITY_VERSION)
        appendField(boundary.root.value)
        boundary.sourceRoots.map { sourceRoot -> sourceRoot.canonicalIdentity(root) }
            .distinct()
            .sorted()
            .forEach { value -> appendField(value) }
        boundary.sourceContents.map(InstalledSourceContentIdentity::canonicalIdentity)
            .distinct()
            .sorted()
            .forEach { value -> appendField(value) }
        boundary.externalProjectPaths.map { path -> path.relativeTo(root) }
            .distinct()
            .sorted()
            .forEach { value -> appendRecord("project", value) }
        boundary.modules.map(InstalledModuleSemanticIdentity::canonicalIdentity)
            .distinct()
            .sorted()
            .forEach { value -> appendField(value) }
    }
    val digest = MessageDigest.getInstance("SHA-256")
        .digest(canonical.toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { byte ->
            (byte.toInt() and 0xff).toString(16).padStart(2, '0')
        }
    return when (val identity = WorkspaceStateIdentity.parse(digest)) {
        is Refinement.Refined -> identity
        is Refinement.Rejected -> Refinement.Rejected(
            InstalledGradleSemanticIdentityFailure.STATE_IDENTITY_REJECTED,
        )
    }
}

private fun Path.relativeTo(root: Path): String =
    root.relativize(this).joinToString("/") { segment -> segment.toString() }.ifEmpty { "." }

private fun WorkspaceSourceRootBoundary.canonicalIdentity(root: Path): String = buildString {
    appendRecord("source-root")
    appendField(ideaModuleName)
    appendField(linkedBuildRoot.relativeTo(root))
    appendField(gradleProjectPath)
    appendField(sourceSetName)
    appendField(sourceRoot.relativeTo(root))
    appendField(sourceKind.name)
    appendField(provenance.name)
}

private fun InstalledSourceContentIdentity.canonicalIdentity(): String = buildString {
    when (this@canonicalIdentity) {
        is InstalledSourceContentIdentity.Root -> {
            appendRecord("source-root-content")
            appendField(path.value)
            appendField(state.name)
        }
        is InstalledSourceContentIdentity.File -> {
            appendRecord("source-file-content")
            appendField(path.value)
            appendField(hash.value)
        }
    }
}

private fun InstalledModuleSemanticIdentity.canonicalIdentity(): String = buildString {
    appendRecord("module")
    appendField(name)
    when (sdk) {
        InstalledSdkSemanticIdentity.Absent -> appendRecord("sdk-absent")
        is InstalledSdkSemanticIdentity.Present -> {
            appendRecord("sdk-present")
            when (sdk.version) {
                InstalledSdkVersion.Unknown -> appendRecord("sdk-version-unknown")
                is InstalledSdkVersion.Known -> {
                    appendRecord("sdk-version-known")
                    appendField(sdk.version.value)
                }
            }
        }
    }
    classpath.map { entry -> buildString {
        appendRecord("classpath-entry")
        appendField(entry.url)
    } }.distinct().sorted().forEach { entry -> appendField(entry) }
}

private fun StringBuilder.appendRecord(tag: String) {
    appendField(tag)
}

private fun StringBuilder.appendRecord(tag: String, value: String) {
    appendField(tag)
    appendField(value)
}

private fun StringBuilder.appendField(value: String) {
    append(value.toByteArray(StandardCharsets.UTF_8).size)
    append(':')
    append(value)
}

private const val IDENTITY_VERSION = "kast-workspace-semantic-identity-v1"
