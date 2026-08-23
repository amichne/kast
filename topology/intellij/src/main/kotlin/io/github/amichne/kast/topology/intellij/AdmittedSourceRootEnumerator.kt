package io.github.amichne.kast.topology.intellij

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.topology.contract.TopologyCandidateEnumeration
import io.github.amichne.kast.topology.contract.TopologyCandidateEnumerationFailure
import io.github.amichne.kast.topology.contract.TopologyCandidateEnumerator
import io.github.amichne.kast.topology.contract.TopologyCandidateSet
import io.github.amichne.kast.topology.contract.TopologySourceFile
import io.github.amichne.kast.workspace.contract.PublishedWorkspace
import io.github.amichne.kast.workspace.contract.SourceRoot
import io.github.amichne.kast.workspace.contract.WorkspaceSourceContentHash
import io.github.amichne.kast.workspace.contract.WorkspaceSourcePath
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.security.MessageDigest

/**
 * Physical candidate enumerator whose only search authorities are published source roots.
 *
 * It never receives a Gradle model, module index, repository scanner, import trigger, or project
 * root walk capability. Each content digest is detached before admission.
 */
class AdmittedSourceRootEnumerator : TopologyCandidateEnumerator {
    /**
     * Proof transition: `PublishedWorkspace -> TopologyCandidateEnumeration`.
     *
     * Complete establishes exactly the regular `.kt` and `.kts` files found beneath the
     * publication's typed source roots, with unique ownership and SHA-256 content identity.
     * [TopologyCandidateEnumerationFailure] is the closed expected failure. Raw filesystem paths
     * and bytes are consumed only in this physical adapter.
     */
    override fun enumerate(workspace: PublishedWorkspace): TopologyCandidateEnumeration {
        val workspaceRoot = Path.of(workspace.root.value)
        val boundaries = mutableListOf<EnumeratedSource>()
        try {
            workspace.sourceRoots.forEach { sourceRoot ->
                val physicalRoot = workspaceRoot.resolve(sourceRoot.location.value).normalize()
                if (!physicalRoot.startsWith(workspaceRoot)) {
                    return rejected(TopologyCandidateEnumerationFailure.SOURCE_ROOT_UNAVAILABLE)
                }
                if (Files.notExists(physicalRoot, LinkOption.NOFOLLOW_LINKS)) {
                    return@forEach
                }
                if (!Files.isDirectory(physicalRoot, LinkOption.NOFOLLOW_LINKS)) {
                    return rejected(TopologyCandidateEnumerationFailure.SOURCE_ROOT_UNAVAILABLE)
                }
                Files.walk(physicalRoot).use { paths ->
                    paths.filter { path ->
                        Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) && path.isKotlinSource()
                    }.forEach { path ->
                        boundaries += EnumeratedSource(
                            sourceRoot,
                            workspaceRoot.relativize(path).normalize(),
                            Files.readAllBytes(path),
                        )
                    }
                }
            }
        } catch (_: IOException) {
            return rejected(TopologyCandidateEnumerationFailure.SOURCE_CONTENT_UNAVAILABLE)
        } catch (_: SecurityException) {
            return rejected(TopologyCandidateEnumerationFailure.SOURCE_CONTENT_UNAVAILABLE)
        }
        if (boundaries.groupBy(EnumeratedSource::relativePath).any { it.value.size > 1 }) {
            return rejected(TopologyCandidateEnumerationFailure.AMBIGUOUS_SOURCE_ROOT_OWNER)
        }
        val files = boundaries.map { boundary ->
            when (val admitted = boundary.admit(workspace)) {
                is Refinement.Refined -> admitted.value
                is Refinement.Rejected -> return rejected(admitted.failure)
            }
        }
        return when (val candidates = TopologyCandidateSet.admit(workspace, files)) {
            is Refinement.Refined -> TopologyCandidateEnumeration.Complete(candidates.value)
            is Refinement.Rejected ->
                rejected(TopologyCandidateEnumerationFailure.CANDIDATE_REJECTED)
        }
    }
}

private data class EnumeratedSource(
    val sourceRoot: SourceRoot,
    val relativePath: Path,
    val content: ByteArray,
) {
    /**
     * Proof transition: `(EnumeratedSource, PublishedWorkspace) ->
     * Refinement<TopologySourceFile, TopologyCandidateEnumerationFailure>`.
     *
     * The refined file carries parsed workspace-relative path and content-hash evidence bound to
     * its published source root. [TopologyCandidateEnumerationFailure] is the closed expected
     * failure. Raw path text and bytes are extracted only by this filesystem adapter.
     */
    fun admit(
        workspace: PublishedWorkspace,
    ): Refinement<TopologySourceFile, TopologyCandidateEnumerationFailure> {
        val path = when (val parsed = WorkspaceSourcePath.parse(relativePath.toString())) {
            is Refinement.Refined -> parsed.value
            is Refinement.Rejected -> return candidateRejected()
        }
        val digest = MessageDigest.getInstance("SHA-256").digest(content)
            .joinToString("") { byte ->
                (byte.toInt() and 0xff).toString(16).padStart(2, '0')
            }
        val hash = when (val parsed = WorkspaceSourceContentHash.parse(digest)) {
            is Refinement.Refined -> parsed.value
            is Refinement.Rejected -> return candidateRejected()
        }
        return when (val admitted = TopologySourceFile.admit(workspace, sourceRoot, path, hash)) {
            is Refinement.Refined -> Refinement.Refined(admitted.value)
            is Refinement.Rejected -> candidateRejected()
        }
    }
}

private fun candidateRejected(): Refinement.Rejected<TopologyCandidateEnumerationFailure> =
    Refinement.Rejected(TopologyCandidateEnumerationFailure.CANDIDATE_REJECTED)

private fun Path.isKotlinSource(): Boolean {
    val name = fileName.toString()
    return name.endsWith(".kt") || name.endsWith(".kts")
}

private fun rejected(
    failure: TopologyCandidateEnumerationFailure,
): TopologyCandidateEnumeration.Rejected = TopologyCandidateEnumeration.Rejected(failure)
