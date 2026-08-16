package io.github.amichne.kast.runtime.composition.platform

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import io.github.amichne.kast.workspace.contract.GradleSourceRootEvidence
import io.github.amichne.kast.workspace.contract.ImportedWorkspaceModelState
import io.github.amichne.kast.workspace.contract.SourceRoot
import io.github.amichne.kast.workspace.contract.SourceRootProvenance
import io.github.amichne.kast.workspace.contract.WorkspaceSearchScopeModel
import io.github.amichne.kast.workspace.contract.WorkspaceSearchScopeModelCompilation
import io.github.amichne.kast.workspace.contract.WorkspaceSearchScopeModelFailure
import io.github.amichne.kast.workspace.contract.WorkspaceSourceRootBoundary
import io.github.amichne.kast.workspace.contract.WorkspaceSourceRootProvenance
import io.github.amichne.kast.workspace.contract.WorkspaceStateIdentity
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.security.MessageDigest

/** Raw live-model projection consumed once at the installed IntelliJ boundary. */
internal data class InstalledGradleModelBoundary(
    val root: CanonicalWorkspaceRoot,
    val importedModelComplete: Boolean,
    val sourceRoots: List<WorkspaceSourceRootBoundary>,
    val identityFields: List<String>,
)

sealed interface InstalledGradleModelFailure {
    data object IncompleteBoundary : InstalledGradleModelFailure

    data class ScopeRejected(
        val failures: Set<WorkspaceSearchScopeModelFailure>,
    ) : InstalledGradleModelFailure

    data object SourceRootOutsideWorkspace : InstalledGradleModelFailure

    data object SourceRootRejected : InstalledGradleModelFailure

    data object StateIdentityRejected : InstalledGradleModelFailure

    data object ModelRejected : InstalledGradleModelFailure
}

/**
 * Proof transition: `InstalledGradleModelBoundary -> InstalledGradleModelRead`.
 *
 * Captured establishes a complete coherent semantic scope, identical typed publication roots,
 * and deterministic state identity over explicit model, classpath, and source-content fields.
 * [InstalledGradleModelRead.Unavailable] closes every incomplete or inconsistent boundary state.
 * Raw Gradle strings and paths may enter only through [InstalledGradleModelBoundary].
 */
internal fun projectInstalledGradleModel(
    boundary: InstalledGradleModelBoundary,
): InstalledGradleModelRead {
    if (
        !boundary.importedModelComplete ||
        boundary.identityFields.isEmpty() ||
        boundary.identityFields.any(String::isBlank)
    ) {
        return InstalledGradleModelRead.Unavailable(
            InstalledGradleModelFailure.IncompleteBoundary,
        )
    }
    val scope = when (val compiled = WorkspaceSearchScopeModel.compile(
        boundary.root,
        ImportedWorkspaceModelState.COMPLETE,
        boundary.sourceRoots,
    )) {
        is WorkspaceSearchScopeModelCompilation.Compiled -> compiled
        is WorkspaceSearchScopeModelCompilation.Rejected ->
            return InstalledGradleModelRead.Unavailable(
                InstalledGradleModelFailure.ScopeRejected(compiled.failures),
            )
    }
    val roots = boundary.sourceRoots.map { sourceRoot ->
        val evidence = sourceRoot.publicationEvidence(boundary.root)
            ?: return InstalledGradleModelRead.Unavailable(
                InstalledGradleModelFailure.SourceRootOutsideWorkspace,
            )
        when (val admitted = SourceRoot.admit(evidence)) {
            is Refinement.Refined -> admitted.value
            is Refinement.Rejected -> return InstalledGradleModelRead.Unavailable(
                InstalledGradleModelFailure.SourceRootRejected,
            )
        }
    }.distinct()
    val state = when (val parsed = WorkspaceStateIdentity.parse(
        modelIdentity(boundary),
    )) {
        is Refinement.Refined -> parsed.value
        is Refinement.Rejected -> return InstalledGradleModelRead.Unavailable(
            InstalledGradleModelFailure.StateIdentityRejected,
        )
    }
    return when (val model = InstalledGradleWorkspaceModel.admit(
        boundary.root,
        state,
        roots,
        scope,
    )) {
        is Refinement.Refined -> InstalledGradleModelRead.Captured(model.value)
        is Refinement.Rejected -> InstalledGradleModelRead.Unavailable(
            InstalledGradleModelFailure.ModelRejected,
        )
    }
}

private fun WorkspaceSourceRootBoundary.publicationEvidence(
    root: CanonicalWorkspaceRoot,
): GradleSourceRootEvidence? {
    val rootPath = Path.of(root.value)
    if (!linkedBuildRoot.startsWith(rootPath) || !sourceRoot.startsWith(rootPath)) return null
    val buildRoot = rootPath.relativize(linkedBuildRoot).portableRelative()
    val location = rootPath.relativize(sourceRoot).portableRelative()
    return GradleSourceRootEvidence(
        ideaModuleName,
        buildRoot,
        gradleProjectPath,
        sourceSetName,
        location,
        when (provenance) {
            WorkspaceSourceRootProvenance.AUTHORED -> SourceRootProvenance.Authored
            WorkspaceSourceRootProvenance.GENERATED -> SourceRootProvenance.Generated
            WorkspaceSourceRootProvenance.UNKNOWN -> return null
        },
    )
}

private fun Path.portableRelative(): String =
    joinToString("/") { it.toString() }.ifEmpty { "." }

private fun modelIdentity(boundary: InstalledGradleModelBoundary): String {
    val canonical = buildString {
        appendField(boundary.root.value)
        boundary.sourceRoots.sortedBy { it.sourceRoot.toString() }.forEach { sourceRoot ->
            appendField(sourceRoot.ideaModuleName)
            appendField(sourceRoot.linkedBuildRoot.toString())
            appendField(sourceRoot.gradleProjectPath)
            appendField(sourceRoot.sourceSetName)
            appendField(sourceRoot.sourceRoot.toString())
            appendField(sourceRoot.sourceKind.name)
            appendField(sourceRoot.provenance.name)
        }
        boundary.identityFields.sorted().forEach { identity -> appendField(identity) }
    }
    return MessageDigest.getInstance("SHA-256")
        .digest(canonical.toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { byte ->
            (byte.toInt() and 0xff).toString(16).padStart(2, '0')
        }
}

private fun StringBuilder.appendField(value: String) {
    append(value.toByteArray(StandardCharsets.UTF_8).size)
    append(':')
    append(value)
}
