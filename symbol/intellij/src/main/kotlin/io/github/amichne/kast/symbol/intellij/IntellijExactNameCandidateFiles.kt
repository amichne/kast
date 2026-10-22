package io.github.amichne.kast.symbol.intellij

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.SingleRootFileViewProvider
import com.intellij.psi.search.GlobalSearchScope
import io.github.amichne.kast.kernel.ReadLimitParameter
import io.github.amichne.kast.kernel.ReadLimits
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryBlockCause
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryQualification
import io.github.amichne.kast.workspace.intellij.read.IntellijReadCounter
import io.github.amichne.kast.workspace.intellij.read.IntellijReadSearch
import io.github.amichne.kast.workspace.intellij.read.search
import org.jetbrains.kotlin.idea.KotlinFileType

internal enum class ExactNameCandidateFileFailure {
    STOPPED,
    PROVIDER_FAILURE,
}

/** Only a completed index read produces this request-local exclusion evidence. */
internal class IntellijExactNameCandidateFiles
private constructor(
    private val paths: Set<String>,
    val retainedBytes: Long,
) {
    fun includes(file: VirtualFile): Boolean =
        !file.isValid || file.path in paths || SingleRootFileViewProvider.isTooLargeForIntelligence(file)

    companion object {
        fun collect(
            name: IntellijIndexedDeclarationName,
            scope: GlobalSearchScope,
            indexes: IntellijDeclarationIndexes,
            limits: ReadLimits,
            callbacks: ScopedDeclarationCallbacks,
            resources: io.github.amichne.kast.kernel.ResourceBudget,
        ): Refinement<IntellijExactNameCandidateFiles, ExactNameCandidateFileFailure> =
            Collector(scope, limits, callbacks, resources).collect(name, indexes)
    }

    private class Collector(
        private val scope: GlobalSearchScope,
        private val limits: ReadLimits,
        private val callbacks: ScopedDeclarationCallbacks,
        private val resources: io.github.amichne.kast.kernel.ResourceBudget,
    ) {
        private val paths = linkedSetOf<String>()
        private var bytes = 0L
        private var halted = false

        fun collect(
            name: IntellijIndexedDeclarationName,
            indexes: IntellijDeclarationIndexes,
        ): Refinement<IntellijExactNameCandidateFiles, ExactNameCandidateFileFailure> {
            val complete =
                callbacks.observation.search(IntellijReadSearch.SCOPED_FILES) { search ->
                    indexes.candidateFiles(name, scope) { file ->
                        search.callbackEntered()
                        callbacks.observation.count(IntellijReadCounter.DISCOVERY_FILE_ENTRIES_SUPPLIED)
                        accept(file)
                    }
                }
            return when {
                halted -> Refinement.Rejected(ExactNameCandidateFileFailure.STOPPED)
                !complete -> Refinement.Rejected(ExactNameCandidateFileFailure.PROVIDER_FAILURE)
                else -> Refinement.Refined(IntellijExactNameCandidateFiles(paths.toSet(), bytes))
            }
        }

        private fun accept(file: VirtualFile): Boolean {
            if (halted) return false
            if (!callbacks.observe()) return halt()
            if (file.fileType != KotlinFileType.INSTANCE || !scope.contains(file)) return true
            if (file.path in paths) return true
            val added = FILE_BINDING_BYTES + TEXT_UNIT_BYTES * file.path.length
            if (
                resources is io.github.amichne.kast.kernel.ResourceBudget.Bounded &&
                    paths.size >= limits[ReadLimitParameter.DISCOVERY_FILES].value
            )
                return reject(
                    SymbolDiscoveryBlockCause.PARTITION_CAPACITY,
                    SymbolDiscoveryQualification.WORK_LIMIT_REACHED,
                )
            if (
                resources is io.github.amichne.kast.kernel.ResourceBudget.Bounded &&
                    added > limits[ReadLimitParameter.QUERY_CHECKPOINT_BYTES].value - bytes
            )
                return reject(
                    SymbolDiscoveryBlockCause.RETENTION_LIMIT,
                    SymbolDiscoveryQualification.BYTE_LIMIT_REACHED,
                )
            if (!callbacks.consume()) return halt()
            paths += file.path
            bytes += added
            callbacks.observation.count(IntellijReadCounter.DISCOVERY_BYTES_CHARGED, amount = added.toInt())
            return true
        }

        private fun halt(): Boolean {
            halted = true
            return false
        }

        private fun reject(cause: SymbolDiscoveryBlockCause, qualification: SymbolDiscoveryQualification): Boolean {
            callbacks.qualify(qualification)
            callbacks.block(cause)
            return halt()
        }
    }
}

/** The bounded VFS inventory preserves failures for files that the word index excludes. */
internal fun collectExactNameCandidateDeclarations(
    project: Project,
    plan: IntellijDirectoryDiscoveryPlan,
    name: IntellijIndexedDeclarationName,
    indexes: IntellijDeclarationIndexes,
    limits: ReadLimits,
    callbacks: ScopedDeclarationCallbacks,
    allowance: IntellijDeclarationDiscoveryAllowance = IntellijDeclarationDiscoveryAllowance(plan.request),
    partitions: IntellijDeclarationPartitionFiles = NativeIntellijDeclarationPartitionFiles,
): Boolean {
    val candidates =
        when (
            val result =
                IntellijExactNameCandidateFiles.collect(
                    name,
                    plan.scope.nativeScope,
                    indexes,
                    limits,
                    callbacks,
                    plan.request.budget.resources,
                )
        ) {
            is Refinement.Refined -> result.value
            is Refinement.Rejected -> {
                when (result.failure) {
                    ExactNameCandidateFileFailure.STOPPED -> Unit
                    ExactNameCandidateFileFailure.PROVIDER_FAILURE ->
                        callbacks.qualify(SymbolDiscoveryQualification.PROVIDER_FAILURE)
                }
                return false
            }
        }
    return collectDirectoryKotlinDeclarations(
        project,
        plan,
        limits,
        allowance,
        callbacks,
        partitions,
        includeSource = candidates::includes,
        retainedBytes = candidates.retainedBytes,
    )
}

private const val FILE_BINDING_BYTES = 768L
private const val TEXT_UNIT_BYTES = 2L
