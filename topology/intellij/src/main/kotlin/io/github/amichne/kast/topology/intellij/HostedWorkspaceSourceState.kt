package io.github.amichne.kast.topology.intellij

import com.intellij.openapi.project.Project
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.IdeHostCompatibilityCandidate
import io.github.amichne.kast.protocol.contract.IdeHostCompatibilityPolicy
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import io.github.amichne.kast.workspace.contract.SourceRoot
import io.github.amichne.kast.workspace.contract.WorkspaceStateIdentity
import io.github.amichne.kast.workspace.intellij.read.AdmittedIdeProject
import io.github.amichne.kast.workspace.intellij.read.ExistingProjectAdmission
import io.github.amichne.kast.workspace.intellij.read.HostedProjectAdmissionFailure
import java.io.IOException
import java.io.UncheckedIOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat

sealed interface HostedWorkspaceSourceStateAdmissionFailure {
    data class ProjectRejected(
        val failure: HostedProjectAdmissionFailure,
    ) : HostedWorkspaceSourceStateAdmissionFailure

    data object SourceRootUnavailable : HostedWorkspaceSourceStateAdmissionFailure
    data object SourceContentUnavailable : HostedWorkspaceSourceStateAdmissionFailure
    data object AmbiguousSourceRootOwner : HostedWorkspaceSourceStateAdmissionFailure
    data object SourceStateRejected : HostedWorkspaceSourceStateAdmissionFailure
}

sealed interface HostedWorkspaceSourceStateAdmission {
    data class Admitted(
        val sourceState: WorkspaceStateIdentity,
    ) : HostedWorkspaceSourceStateAdmission

    data class Rejected(
        val failure: HostedWorkspaceSourceStateAdmissionFailure,
    ) : HostedWorkspaceSourceStateAdmission
}

/**
 * Verifies the already-open exact Project, then consumes physical source bytes inside this
 * topology adapter and returns only their detached exact-root identity.
 */
fun admitHostedWorkspaceSourceState(
    project: Project,
    root: CanonicalWorkspaceRoot,
    compatibilityCandidate: IdeHostCompatibilityCandidate,
    compatibilityPolicy: IdeHostCompatibilityPolicy,
    sourceRoots: List<SourceRoot>,
): HostedWorkspaceSourceStateAdmission {
    when (val admission = AdmittedIdeProject.admit(
        project,
        root,
        compatibilityCandidate,
        compatibilityPolicy,
    )) {
        is ExistingProjectAdmission.Admitted -> Unit
        is ExistingProjectAdmission.Rejected -> return rejected(
            HostedWorkspaceSourceStateAdmissionFailure.ProjectRejected(
                HostedProjectAdmissionFailure.ProjectRejected(admission.failure),
            ),
        )
    }
    return observeHostedWorkspaceSourceState(root, sourceRoots)
}

/** Physical source observation factored for deterministic exact-root tests. */
internal fun observeHostedWorkspaceSourceState(
    root: CanonicalWorkspaceRoot,
    sourceRoots: List<SourceRoot>,
): HostedWorkspaceSourceStateAdmission {
    val workspaceRoot = Path.of(root.value)
    val files = linkedMapOf<String, ByteArray>()
    try {
        sourceRoots.sortedBy { it.location.value }.forEach { sourceRoot ->
            val physicalRoot = workspaceRoot.resolve(sourceRoot.location.value).normalize()
            if (!physicalRoot.startsWith(workspaceRoot) || Files.isSymbolicLink(physicalRoot)) {
                return rejected(
                    HostedWorkspaceSourceStateAdmissionFailure.SourceRootUnavailable,
                )
            }
            if (Files.notExists(physicalRoot, LinkOption.NOFOLLOW_LINKS)) return@forEach
            if (!Files.isDirectory(physicalRoot, LinkOption.NOFOLLOW_LINKS)) {
                return rejected(
                    HostedWorkspaceSourceStateAdmissionFailure.SourceRootUnavailable,
                )
            }
            var ambiguousOwner = false
            Files.walk(physicalRoot).use { paths ->
                paths.filter { path ->
                    Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) && path.isKotlinSource()
                }.sorted().forEach { path ->
                    val relative = workspaceRoot.relativize(path).normalize().toString()
                    if (files.putIfAbsent(relative, Files.readAllBytes(path)) != null) {
                        ambiguousOwner = true
                    }
                }
            }
            if (ambiguousOwner) {
                return rejected(
                    HostedWorkspaceSourceStateAdmissionFailure.AmbiguousSourceRootOwner,
                )
            }
        }
    } catch (_: IOException) {
        return rejected(HostedWorkspaceSourceStateAdmissionFailure.SourceContentUnavailable)
    } catch (_: UncheckedIOException) {
        return rejected(HostedWorkspaceSourceStateAdmissionFailure.SourceContentUnavailable)
    } catch (_: SecurityException) {
        return rejected(HostedWorkspaceSourceStateAdmissionFailure.SourceContentUnavailable)
    }

    val digest = MessageDigest.getInstance("SHA-256")
    sourceRoots.sortedBy { it.location.value }.forEach { sourceRoot ->
        digest.update(sourceRoot.location.value.toByteArray(StandardCharsets.UTF_8))
        digest.update(0)
    }
    files.toSortedMap().forEach { (path, content) ->
        digest.update(path.toByteArray(StandardCharsets.UTF_8))
        digest.update(0)
        digest.update(MessageDigest.getInstance("SHA-256").digest(content))
    }
    return when (
        val parsed = WorkspaceStateIdentity.parse(HexFormat.of().formatHex(digest.digest()))
    ) {
        is Refinement.Refined -> HostedWorkspaceSourceStateAdmission.Admitted(parsed.value)
        is Refinement.Rejected -> rejected(
            HostedWorkspaceSourceStateAdmissionFailure.SourceStateRejected,
        )
    }
}

private fun Path.isKotlinSource(): Boolean = fileName.toString().let { name ->
    name.endsWith(".kt") || name.endsWith(".kts")
}

private fun rejected(
    failure: HostedWorkspaceSourceStateAdmissionFailure,
): HostedWorkspaceSourceStateAdmission.Rejected =
    HostedWorkspaceSourceStateAdmission.Rejected(failure)
