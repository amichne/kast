package io.github.amichne.kast.topology.intellij

import com.intellij.openapi.vfs.VirtualFile
import io.github.amichne.kast.kernel.ReadLimitParameter
import io.github.amichne.kast.kernel.ReadLimits
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.workspace.contract.WorkspaceSourceContentHash
import io.github.amichne.kast.workspace.intellij.read.IntellijReadCounter

/** Each uncaptured exact identity needs at least one stream read, even an empty file's EOF. */
@JvmInline
internal value class SemanticHashReadMinimum private constructor(val calls: Int) {
    companion object {
        fun fromMissingFiles(files: Set<SemanticNativeFileIdentity>) = SemanticHashReadMinimum(files.size)
    }
}

/** Metadata and native leaves are retained only for this synchronous native read. */
internal object SemanticNativeSdkFilePlan {
    private sealed interface Tree {
        data class Complete(val hash: WorkspaceSourceContentHash) : Tree

        data class Fresh(val admission: SemanticNativeFileMemo.TreeAdmission, val leaves: List<VirtualFile>) : Tree

        data class Repeated(val identity: SemanticNativeFileIdentity) : Tree
    }

    fun capture(
        roots: List<VirtualFile>,
        digest: SemanticInputDigest,
        files: SemanticNativeFiles,
        memo: SemanticNativeFileMemo,
        limits: ReadLimits,
        budget: DependencyCaptureBudget,
    ): SemanticCapture<Unit> {
        if (roots.isEmpty()) return captureRejected(SemanticDependencyCaptureFailure.COMPILER_CONFIGURATION_UNAVAILABLE)
        if (roots.size > limits[ReadLimitParameter.MODEL_CLASSPATH_ENTRIES_PER_MODULE].value)
            return captureRejected(SemanticDependencyCaptureFailure.CAPACITY_EXCEEDED)
        return when (val prepared = Preparation(files, memo, limits, budget).roots(roots)) {
            is Refinement.Rejected -> prepared
            is Refinement.Refined -> hashRoots(roots, prepared.value, digest, files, memo, budget)
        }
    }

    private class Preparation(
        private val files: SemanticNativeFiles,
        private val memo: SemanticNativeFileMemo,
        private val limits: ReadLimits,
        private val budget: DependencyCaptureBudget,
    ) {
        private val seenRoots = mutableSetOf<SemanticNativeFileIdentity>()
        private val missingFiles = mutableSetOf<SemanticNativeFileIdentity>()
        private var retainedLeaves = 0

        fun roots(roots: List<VirtualFile>): SemanticCapture<List<Tree>> =
            try {
                prepareRoots(roots)
            } finally {
                budget.observation.count(IntellijReadCounter.DEPENDENCY_SDK_PLANNED_FILES, amount = retainedLeaves)
                budget.observation.count(
                    IntellijReadCounter.DEPENDENCY_SDK_MINIMUM_HASH_READS,
                    amount = missingFiles.size,
                )
            }

        private fun prepareRoots(roots: List<VirtualFile>): SemanticCapture<List<Tree>> {
            val trees = ArrayList<Tree>()
            for (root in roots) {
                when (val prepared = root(root)) {
                    is Refinement.Rejected -> return prepared
                    is Refinement.Refined -> trees.add(prepared.value)
                }
            }
            when (val remaining = budget.requireHashReads(SemanticHashReadMinimum.fromMissingFiles(missingFiles))) {
                is Refinement.Rejected -> return remaining
                is Refinement.Refined -> Unit
            }
            budget.observation.count(IntellijReadCounter.DEPENDENCY_SDK_FILE_PLANS_COMPLETED)
            return Refinement.Refined(trees)
        }

        private fun root(root: VirtualFile): SemanticCapture<Tree> {
            val identity = SemanticNativeFileIdentity(root.url)
            if (!seenRoots.add(identity)) return Refinement.Refined(Tree.Repeated(identity))
            return when (val admitted = memo.prepareTree(identity, budget, seenRoots)) {
                is Refinement.Rejected -> admitted
                is Refinement.Refined ->
                    when (val preparation = admitted.value) {
                        is SemanticNativeFileMemo.TreePreparation.Complete ->
                            Refinement.Refined(Tree.Complete(preparation.hash))
                        is SemanticNativeFileMemo.TreeAdmission -> fresh(root, preparation)
                    }
            }
        }

        private fun fresh(root: VirtualFile, admission: SemanticNativeFileMemo.TreeAdmission): SemanticCapture<Tree> {
            val leaves = ArrayList<VirtualFile>()
            return when (val observed = files.walkMetadata(root) { file -> leaf(file, leaves) }) {
                is Refinement.Rejected -> observed
                is Refinement.Refined -> Refinement.Refined(Tree.Fresh(admission, leaves))
            }
        }

        private fun leaf(file: VirtualFile, leaves: MutableList<VirtualFile>): SemanticCapture<Unit> {
            if (retainedLeaves >= limits[ReadLimitParameter.DISCOVERY_FILES].value)
                return captureRejected(SemanticDependencyCaptureFailure.CAPACITY_EXCEEDED)
            retainedLeaves++
            leaves.add(file)
            val identity = SemanticNativeFileIdentity(file.url)
            when (val presence = memo.hashPresence(identity)) {
                is Refinement.Rejected -> return presence
                is Refinement.Refined ->
                    if (presence.value == SemanticNativeFileMemo.HashPresence.Missing) missingFiles.add(identity)
            }
            val minimum = SemanticHashReadMinimum.fromMissingFiles(missingFiles)
            return when (val capacity = memo.admitHashMinimum(minimum)) {
                is Refinement.Rejected -> capacity
                is Refinement.Refined -> budget.requireHashReads(minimum)
            }
        }
    }

    private fun hashRoots(
        roots: List<VirtualFile>,
        trees: List<Tree>,
        digest: SemanticInputDigest,
        files: SemanticNativeFiles,
        memo: SemanticNativeFileMemo,
        budget: DependencyCaptureBudget,
    ): SemanticCapture<Unit> {
        digest.text(roots.size.toString())
        for ((index, tree) in trees.withIndex()) {
            digest.text("ROOT")
            digest.text(roots[index].url)
            when (val captured = hashTree(tree, files, memo, budget)) {
                is Refinement.Rejected -> return captured
                is Refinement.Refined -> digest.text(captured.value.value)
            }
        }
        return Refinement.Refined(Unit)
    }

    private fun hashTree(
        tree: Tree,
        files: SemanticNativeFiles,
        memo: SemanticNativeFileMemo,
        budget: DependencyCaptureBudget,
    ): SemanticCapture<WorkspaceSourceContentHash> =
        when (tree) {
            is Tree.Complete ->
                when (val current = budget.current()) {
                    is Refinement.Rejected -> current
                    is Refinement.Refined -> Refinement.Refined(tree.hash)
                }
            is Tree.Fresh -> tree.admission.capture { hashLeaves(tree.leaves, files) }
            is Tree.Repeated ->
                memo.tree(tree.identity, budget) {
                    captureRejected(SemanticDependencyCaptureFailure.INPUT_UNAVAILABLE)
                }
        }

    private fun hashLeaves(
        leaves: List<VirtualFile>,
        files: SemanticNativeFiles,
    ): SemanticCapture<WorkspaceSourceContentHash> {
        val digest = SemanticInputDigest()
        for (file in leaves) {
            digest.text("FILE")
            digest.text(file.url)
            when (val hash = files.hashClean(file)) {
                is Refinement.Rejected -> return hash
                is Refinement.Refined -> digest.text(hash.value.value)
            }
        }
        return Refinement.Refined(digest.finish())
    }
}
