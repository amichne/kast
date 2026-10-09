package io.github.amichne.kast.topology.intellij

import io.github.amichne.kast.kernel.ReadLimitParameter
import io.github.amichne.kast.kernel.ReadLimits
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.workspace.contract.WorkspaceSourceContentHash
import io.github.amichne.kast.workspace.intellij.read.IntellijReadCounter

/** Exact VFS identity supplied by the native boundary; no URL normalization or alias inference. */
@JvmInline internal value class SemanticNativeFileIdentity(val url: String)

/** Only complete file/tree hashes are shared; one native attempt owns this bounded memo and closes it. */
internal class SemanticNativeFileMemo(private val limits: ReadLimits = ReadLimits.Default) {
    private sealed interface State {
        class Active : State {
            val hashes = mutableMapOf<SemanticNativeFileIdentity, WorkspaceSourceContentHash>()
            val trees = mutableMapOf<SemanticNativeFileIdentity, WorkspaceSourceContentHash>()
        }

        data object Closed : State
    }

    private var state: State = State.Active()

    fun hash(
        identity: SemanticNativeFileIdentity,
        budget: DependencyCaptureBudget,
        read: () -> SemanticCapture<WorkspaceSourceContentHash>,
    ): SemanticCapture<WorkspaceSourceContentHash> = observe(identity, budget, Kind.FILE, read)

    fun tree(
        identity: SemanticNativeFileIdentity,
        budget: DependencyCaptureBudget,
        read: () -> SemanticCapture<WorkspaceSourceContentHash>,
    ): SemanticCapture<WorkspaceSourceContentHash> = observe(identity, budget, Kind.TREE, read)

    private fun observe(
        identity: SemanticNativeFileIdentity,
        budget: DependencyCaptureBudget,
        kind: Kind,
        read: () -> SemanticCapture<WorkspaceSourceContentHash>,
    ): SemanticCapture<WorkspaceSourceContentHash> {
        val active =
            when (val selected = state) {
                State.Closed -> return captureRejected(SemanticDependencyCaptureFailure.READ_CAPTURE_ENDED)
                is State.Active -> selected
            }
        when (val current = budget.current()) {
            is Refinement.Rejected -> return current
            is Refinement.Refined -> Unit
        }
        if (identity.url.length > limits[ReadLimitParameter.MODEL_CLASSPATH_URL_CHARACTERS].value)
            return captureRejected(SemanticDependencyCaptureFailure.CAPACITY_EXCEEDED)
        val memo =
            when (kind) {
                Kind.FILE -> active.hashes
                Kind.TREE -> active.trees
            }
        memo[identity]?.let {
            budget.observation.count(
                when (kind) {
                    Kind.FILE -> IntellijReadCounter.DEPENDENCY_HASH_MEMO_HITS
                    Kind.TREE -> IntellijReadCounter.DEPENDENCY_TREE_MEMO_HITS
                }
            )
            return Refinement.Refined(it)
        }
        if (memo.size >= limits[ReadLimitParameter.DISCOVERY_FILES].value)
            return captureRejected(SemanticDependencyCaptureFailure.CAPACITY_EXCEEDED)
        return when (val observed = read()) {
            is Refinement.Rejected -> observed
            is Refinement.Refined -> observed.also { memo[identity] = it.value }
        }
    }

    fun finishNativeRead() {
        state = State.Closed
    }

    private enum class Kind {
        FILE,
        TREE,
    }
}
