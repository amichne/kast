package io.github.amichne.kast.topology.intellij

import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.vfs.VfsUtil
import io.github.amichne.kast.topology.contract.TopologyCandidateEnumeration
import io.github.amichne.kast.topology.contract.TopologyCandidateEnumerationFailure
import io.github.amichne.kast.topology.contract.TopologyCandidateEnumerator
import io.github.amichne.kast.topology.contract.TopologyExtractionFailure
import io.github.amichne.kast.topology.contract.TopologyExtractionRequest
import io.github.amichne.kast.topology.contract.TopologyFileExtraction
import io.github.amichne.kast.workspace.contract.PublishedWorkspace
import io.github.amichne.kast.workspace.contract.SourceRoot
import java.nio.file.Path
import java.util.concurrent.CancellationException

internal enum class TopologySourceRootVfsSynchronizationFailure {
    INVALID_SOURCE_ROOT_SCOPE,
    REFRESH_UNAVAILABLE,
}

internal sealed interface TopologySourceRootVfsSynchronization {
    data object Synchronized : TopologySourceRootVfsSynchronization

    data class Rejected(
        val failure: TopologySourceRootVfsSynchronizationFailure,
    ) : TopologySourceRootVfsSynchronization
}

/** Explicit IntelliJ VFS effect restricted to already admitted workspace source roots. */
internal fun interface TopologySourceRootVfsSynchronizer {
    fun synchronize(
        workspace: PublishedWorkspace,
        sourceRoots: List<SourceRoot>,
    ): TopologySourceRootVfsSynchronization
}

/**
 * Synchronously observes external writes below the exact admitted roots before filesystem hashing
 * or a VFS-mismatch retry. It never saves/reloads a document or commits PSI.
 */
internal data object InstalledTopologySourceRootVfsSynchronizer :
    TopologySourceRootVfsSynchronizer {
    override fun synchronize(
        workspace: PublishedWorkspace,
        sourceRoots: List<SourceRoot>,
    ): TopologySourceRootVfsSynchronization = try {
        val workspaceRoot = Path.of(workspace.root.value).toAbsolutePath().normalize()
        val roots = sourceRoots.map { sourceRoot ->
            workspaceRoot.resolve(sourceRoot.location.value).normalize()
        }
        if (roots.any { root -> !root.startsWith(workspaceRoot) }) {
            return TopologySourceRootVfsSynchronization.Rejected(
                TopologySourceRootVfsSynchronizationFailure.INVALID_SOURCE_ROOT_SCOPE,
            )
        }
        if (roots.isNotEmpty()) {
            VfsUtil.markDirtyAndRefresh(
                false,
                true,
                true,
                *roots.map(Path::toFile).toTypedArray(),
            )
        }
        TopologySourceRootVfsSynchronization.Synchronized
    } catch (cancelled: ProcessCanceledException) {
        throw cancelled
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: RuntimeException) {
        TopologySourceRootVfsSynchronization.Rejected(
            TopologySourceRootVfsSynchronizationFailure.REFRESH_UNAVAILABLE,
        )
    }
}

/** Candidate enumerator that establishes VFS observation before disk bytes are hashed. */
internal class SourceRootSynchronizedTopologyCandidateEnumerator(
    private val synchronizer: TopologySourceRootVfsSynchronizer,
    private val delegate: TopologyCandidateEnumerator,
) : TopologyCandidateEnumerator {
    override fun enumerate(workspace: PublishedWorkspace): TopologyCandidateEnumeration =
        when (val synchronization = synchronizer.synchronize(workspace, workspace.sourceRoots)) {
            TopologySourceRootVfsSynchronization.Synchronized -> delegate.enumerate(workspace)
            is TopologySourceRootVfsSynchronization.Rejected -> TopologyCandidateEnumeration.Rejected(
                when (synchronization.failure) {
                    TopologySourceRootVfsSynchronizationFailure.INVALID_SOURCE_ROOT_SCOPE ->
                        TopologyCandidateEnumerationFailure.SOURCE_ROOT_UNAVAILABLE
                    TopologySourceRootVfsSynchronizationFailure.REFRESH_UNAVAILABLE ->
                        TopologyCandidateEnumerationFailure.SOURCE_CONTENT_UNAVAILABLE
                },
            )
        }
}

/** Production candidate boundary: synchronize admitted roots, then enumerate only those roots. */
fun intellijSynchronizedTopologyCandidateEnumerator(): TopologyCandidateEnumerator =
    TopologyCandidateEnumerator { workspace ->
        SourceRootSynchronizedTopologyCandidateEnumerator(
            InstalledTopologySourceRootVfsSynchronizer,
            AdmittedSourceRootEnumerator(),
        ).enumerate(workspace)
    }

/** One retry is permitted only when live VFS bytes disagree with admitted disk evidence. */
internal class TopologyVfsMismatchRetrier(
    private val synchronizer: TopologySourceRootVfsSynchronizer,
) {
    suspend fun extract(
        workspace: PublishedWorkspace,
        request: TopologyExtractionRequest,
        attempt: suspend () -> TopologyFileExtraction,
    ): TopologyFileExtraction {
        val first = attempt()
        if (
            first !is TopologyFileExtraction.Failed ||
            first.failure != TopologyExtractionFailure.VFS_CONTENT_MISMATCH ||
            first.file !in request.candidates.files
        ) {
            return first
        }
        return when (
            synchronizer.synchronize(workspace, listOf(first.file.sourceRoot))
        ) {
            TopologySourceRootVfsSynchronization.Synchronized -> attempt()
            is TopologySourceRootVfsSynchronization.Rejected -> first
        }
    }
}
