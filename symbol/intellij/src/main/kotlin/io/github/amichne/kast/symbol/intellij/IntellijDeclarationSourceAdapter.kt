package io.github.amichne.kast.symbol.intellij

import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.PsiManager
import io.github.amichne.kast.kernel.ReadLimitParameter
import io.github.amichne.kast.kernel.ReadLimits
import io.github.amichne.kast.symbol.contract.SemanticFilePartition
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryBatch
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryBlockCause
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryByteCount
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryElapsedNanoseconds
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryFileIdentity
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryOutcome
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryProgress
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryQualification
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryQualifications
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryRequest
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryTimings
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryWorkCount
import io.github.amichne.kast.symbol.contract.SymbolLibraryPolicy
import io.github.amichne.kast.symbol.contract.SymbolSearchScope
import io.github.amichne.kast.workspace.intellij.read.IntellijReadObservation
import java.nio.file.Path
import org.jetbrains.kotlin.idea.KotlinFileType
import org.jetbrains.kotlin.psi.KtFile

/** Bounded VFS observations preserve native scope authority while detaching only admitted unread partitions. */
internal class IntellijDeclarationSourceAdapter(
    private val project: Project,
    private val scope: CompiledIntellijSearchScope,
    private val request: SymbolDiscoveryRequest,
    private val limits: ReadLimits,
) {
    private val admission = IntellijDeclarationSourceAdmission(scope, request)

    fun initialPartitions(): List<SemanticFilePartition> {
        val paths =
            when (val selected = scope.scope) {
                is SymbolSearchScope.ExactFile -> listOf(Path.of(selected.file.value))
                else ->
                    scope.sourceRoots
                        .map { Path.of(it.sourceRoot.value) }
                        .distinct()
                        .sortedBy { it.toString() }
                        .filter(admission::relevantDirectory)
            }
        val rootPaths = paths.toSet()
        return paths
            .filter { selected -> generateSequence(selected.parent) { it.parent }.none { it in rootPaths } }
            .map { path ->
                val identity = admission.identity(path, "file://$path")
                if (scope.scope is SymbolSearchScope.ExactFile) SemanticFilePartition.File(identity)
                else SemanticFilePartition.Directory(identity)
            }
            .sortedBy { it.orderingPath }
    }

    fun observe(partition: SemanticFilePartition): IntellijDeclarationPartitionObservation {
        val url =
            when (val identity = partition.location) {
                is SymbolDiscoveryFileIdentity.Workspace -> "file://${identity.path.value}"
                is SymbolDiscoveryFileIdentity.External -> identity.url.value
            }
        val native =
            VirtualFileManager.getInstance().findFileByUrl(url)
                ?: return rejected(SymbolDiscoveryBlockCause.PARTITION_UNAVAILABLE)
        if (!native.isValid || native.canonicalPath != native.path)
            return rejected(SymbolDiscoveryBlockCause.PARTITION_UNAVAILABLE)
        return when (partition) {
            is SemanticFilePartition.Directory ->
                if (native.isDirectory) directory(native) else rejected(SymbolDiscoveryBlockCause.PARTITION_UNAVAILABLE)
            is SemanticFilePartition.File -> file(native)
        }
    }

    private fun directory(native: VirtualFile): IntellijDeclarationPartitionObservation {
        val children = native.children
        if (children.size > limits[ReadLimitParameter.DISCOVERY_FILES].value)
            return rejected(SymbolDiscoveryBlockCause.PARTITION_CAPACITY)
        val detached = mutableListOf<SemanticFilePartition>()
        var bytes = PARTITION_OBSERVATION_BINDING_BYTES
        for (child in children) {
            ProgressManager.checkCanceled()
            val partition = admission.detach(child)
            if (partition != null) {
                val added = partition.detachedBytes()
                if (added > limits[ReadLimitParameter.QUERY_CHECKPOINT_BYTES].value - bytes)
                    return rejected(SymbolDiscoveryBlockCause.RETENTION_LIMIT)
                detached += partition
                bytes += added
            }
        }
        return IntellijDeclarationPartitionObservation.Directory(detached.sortedBy { it.orderingPath })
    }

    private fun file(native: VirtualFile): IntellijDeclarationPartitionObservation {
        if (native.isDirectory) return rejected(SymbolDiscoveryBlockCause.PARTITION_UNAVAILABLE)
        if (native.fileType != KotlinFileType.INSTANCE || !scope.nativeScope.contains(native))
            return IntellijDeclarationPartitionObservation.OutsideUniverse
        val file =
            PsiManager.getInstance(project).findFile(native) as? KtFile
                ?: return rejected(SymbolDiscoveryBlockCause.PROVIDER_UNAVAILABLE)
        return IntellijDeclarationPartitionObservation.Source(file)
    }

    private fun rejected(cause: SymbolDiscoveryBlockCause): IntellijDeclarationPartitionObservation.Rejected =
        IntellijDeclarationPartitionObservation.Rejected(cause)
}

internal fun discoverIncrementalScopedDeclarations(
    project: Project,
    scope: CompiledIntellijSearchScope,
    request: SymbolDiscoveryRequest,
    limits: ReadLimits,
    allowance: IntellijDeclarationDiscoveryAllowance,
    observation: IntellijReadObservation,
): IntellijNativeDiscoveryExecution {
    if ((scope.scope as? SymbolSearchScope.Workspace)?.libraries == SymbolLibraryPolicy.INCLUDE)
        return unsupportedDeclarationLibraries(request)
    val adapter = IntellijDeclarationSourceAdapter(project, scope, request, limits)
    return IntellijIncrementalDeclarationDiscovery(
            scope,
            request,
            limits,
            allowance,
            adapter::initialPartitions,
            adapter::observe,
            environment = { project.discoveryEnvironmentState() },
            cancellationCheck = ProgressManager::checkCanceled,
            observation = observation,
        )
        .execute()
}

private fun unsupportedDeclarationLibraries(request: SymbolDiscoveryRequest): IntellijNativeDiscoveryExecution =
    IntellijNativeDiscoveryExecution.Produced(
        SymbolDiscoveryOutcome.Qualified(
            SymbolDiscoveryBatch.create(
                    request,
                    emptyList(),
                    SymbolDiscoveryByteCount.parse(0).discoveryRefined(),
                    SymbolDiscoveryWorkCount.Zero,
                    SymbolDiscoveryTimings(
                        SymbolDiscoveryElapsedNanoseconds.Zero,
                        SymbolDiscoveryElapsedNanoseconds.Zero,
                    ),
                )
                .discoveryRefined(),
            SymbolDiscoveryQualifications.from(setOf(SymbolDiscoveryQualification.UNSUPPORTED_ITEM)).discoveryRefined(),
            SymbolDiscoveryProgress.Blocked(SymbolDiscoveryBlockCause.PROVIDER_UNAVAILABLE),
        )
    )

private const val PARTITION_OBSERVATION_BINDING_BYTES = 768L
