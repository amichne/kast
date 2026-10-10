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
    sealed interface HashPresence {
        data object Complete : HashPresence

        data object Missing : HashPresence
    }

    sealed interface TreePreparation {
        data class Complete(val hash: WorkspaceSourceContentHash) : TreePreparation
    }

    /** One admission precedes metadata work; completion may consume the last permitted metadata step. */
    class TreeAdmission
    private constructor(
        private val owner: SemanticNativeFileMemo,
        private val identity: SemanticNativeFileIdentity,
    ) : TreePreparation {
        private enum class Use {
            AVAILABLE,
            CONSUMED,
        }

        private var use = Use.AVAILABLE

        fun capture(
            read: () -> SemanticCapture<WorkspaceSourceContentHash>
        ): SemanticCapture<WorkspaceSourceContentHash> {
            if (use == Use.CONSUMED)
                return captureRejected(SemanticDependencyCaptureFailure.FILE_CAPTURE_ADMISSION_CONSUMED)
            use = Use.CONSUMED
            if (owner.state is State.Closed) return captureRejected(SemanticDependencyCaptureFailure.READ_CAPTURE_ENDED)
            return when (val observed = read()) {
                is Refinement.Rejected -> observed
                is Refinement.Refined -> {
                    val active =
                        when (val state = owner.state) {
                            State.Closed -> return captureRejected(SemanticDependencyCaptureFailure.READ_CAPTURE_ENDED)
                            is State.Active -> state
                        }
                    if (
                        identity !in active.trees &&
                            active.trees.size >= owner.limits[ReadLimitParameter.DISCOVERY_FILES].value
                    )
                        return captureRejected(SemanticDependencyCaptureFailure.CAPACITY_EXCEEDED)
                    active.trees[identity] = observed.value
                    observed
                }
            }
        }

        companion object {
            fun admit(
                owner: SemanticNativeFileMemo,
                identity: SemanticNativeFileIdentity,
                budget: DependencyCaptureBudget,
                plannedRoots: Set<SemanticNativeFileIdentity>,
            ): SemanticCapture<TreePreparation> {
                val active =
                    when (val state = owner.state) {
                        State.Closed -> return captureRejected(SemanticDependencyCaptureFailure.READ_CAPTURE_ENDED)
                        is State.Active -> state
                    }
                when (val current = budget.current()) {
                    is Refinement.Rejected -> return current
                    is Refinement.Refined -> Unit
                }
                if (identity.url.length > owner.limits[ReadLimitParameter.MODEL_CLASSPATH_URL_CHARACTERS].value)
                    return captureRejected(SemanticDependencyCaptureFailure.CAPACITY_EXCEEDED)
                active.trees[identity]?.let {
                    budget.observation.count(IntellijReadCounter.DEPENDENCY_TREE_MEMO_HITS)
                    return Refinement.Refined(TreePreparation.Complete(it))
                }
                if (
                    active.trees.size.toLong() + plannedRoots.count { it !in active.trees } >
                        owner.limits[ReadLimitParameter.DISCOVERY_FILES].value
                )
                    return captureRejected(SemanticDependencyCaptureFailure.CAPACITY_EXCEEDED)
                return Refinement.Refined(TreeAdmission(owner, identity))
            }
        }
    }

    private sealed interface State {
        class Active : State {
            val hashes = mutableMapOf<SemanticNativeFileIdentity, WorkspaceSourceContentHash>()
            val trees = mutableMapOf<SemanticNativeFileIdentity, WorkspaceSourceContentHash>()
        }

        data object Closed : State
    }

    private var state: State = State.Active()

    fun prepareTree(
        identity: SemanticNativeFileIdentity,
        budget: DependencyCaptureBudget,
        plannedRoots: Set<SemanticNativeFileIdentity> = setOf(identity),
    ) = TreeAdmission.admit(this, identity, budget, plannedRoots)

    fun admitHashMinimum(minimum: SemanticHashReadMinimum): SemanticCapture<Unit> =
        when (val active = state) {
            State.Closed -> captureRejected(SemanticDependencyCaptureFailure.READ_CAPTURE_ENDED)
            is State.Active ->
                if (active.hashes.size.toLong() + minimum.calls > limits[ReadLimitParameter.DISCOVERY_FILES].value)
                    captureRejected(SemanticDependencyCaptureFailure.CAPACITY_EXCEEDED)
                else Refinement.Refined(Unit)
        }

    /** Consults complete, read-owned proof only; a missing hash will require at least one actual read. */
    fun hashPresence(identity: SemanticNativeFileIdentity): SemanticCapture<HashPresence> =
        when (val active = state) {
            State.Closed -> captureRejected(SemanticDependencyCaptureFailure.READ_CAPTURE_ENDED)
            is State.Active ->
                Refinement.Refined(if (identity in active.hashes) HashPresence.Complete else HashPresence.Missing)
        }

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
