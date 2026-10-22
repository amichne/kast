package io.github.amichne.kast.symbol.intellij

import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import io.github.amichne.kast.kernel.ReadLimitParameter
import io.github.amichne.kast.kernel.ReadLimits
import io.github.amichne.kast.symbol.contract.SemanticFilePartition
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryBatch
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryBlockCause
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryByteCount
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryElapsedNanoseconds
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryOutcome
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryProgress
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryQualification
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryQualifications
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryRequest
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryTimings
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryWorkCount
import io.github.amichne.kast.symbol.contract.SymbolLibraryPolicy
import io.github.amichne.kast.symbol.contract.SymbolSearchScope
import io.github.amichne.kast.workspace.intellij.read.IntellijReadCall
import io.github.amichne.kast.workspace.intellij.read.IntellijReadContributor
import io.github.amichne.kast.workspace.intellij.read.IntellijReadCounter
import io.github.amichne.kast.workspace.intellij.read.IntellijReadObservation
import io.github.amichne.kast.workspace.intellij.read.IntellijReadTermination
import io.github.amichne.kast.workspace.intellij.read.call
import java.nio.file.Path
import org.jetbrains.kotlin.idea.KotlinFileType
import org.jetbrains.kotlin.psi.KtFile

/** Bounded VFS observations preserve native scope authority while detaching only admitted unread partitions. */
internal class IntellijDeclarationSourceAdapter
private constructor(
    private val project: Project,
    private val scope: CompiledIntellijSearchScope,
    private val request: SymbolDiscoveryRequest,
    private val limits: ReadLimits,
    private val allowance: IntellijDeclarationDiscoveryAllowance = IntellijDeclarationDiscoveryAllowance(request),
    private val observation: IntellijReadObservation = IntellijReadObservation.None,
    private val plan: IntellijDirectoryDiscoveryPlan.Prepared? = null,
    private val consume: () -> Boolean = { true },
    private val partitions: IntellijDeclarationPartitionFiles = NativeIntellijDeclarationPartitionFiles,
    private val includeSource: (VirtualFile) -> Boolean = { true },
) {
    constructor(
        project: Project,
        scope: CompiledIntellijSearchScope,
        request: SymbolDiscoveryRequest,
        limits: ReadLimits,
        allowance: IntellijDeclarationDiscoveryAllowance = IntellijDeclarationDiscoveryAllowance(request),
        observation: IntellijReadObservation = IntellijReadObservation.None,
    ) : this(
        project,
        scope,
        request,
        limits,
        allowance,
        observation,
        null,
        { true },
        NativeIntellijDeclarationPartitionFiles,
    )

    constructor(
        project: Project,
        plan: IntellijDirectoryDiscoveryPlan.Prepared,
        limits: ReadLimits,
        allowance: IntellijDeclarationDiscoveryAllowance,
        observation: IntellijReadObservation,
        consume: () -> Boolean,
        partitions: IntellijDeclarationPartitionFiles,
        includeSource: (VirtualFile) -> Boolean = { true },
    ) : this(
        project,
        plan.selection.scope,
        plan.selection.request,
        limits,
        allowance,
        observation,
        plan,
        consume,
        partitions,
        includeSource,
    )

    private val admission = IntellijDeclarationSourceAdmission(scope, request, plan)

    fun initialPartitions(): IntellijDeclarationInitialInventory {
        if (expired()) return initialTimeLimit()
        return when (val selected = scope.scope) {
            is SymbolSearchScope.ExactFile -> {
                val path = Path.of(selected.file.value)
                if (plan != null && !plan.selection.admitsFile(path))
                    return IntellijDeclarationInitialInventory.Complete(emptyList())
                IntellijDeclarationInitialInventory.Complete(
                    listOf(SemanticFilePartition.File(admission.identity(path, "file://$path")))
                )
            }
            is SymbolSearchScope.Module,
            is SymbolSearchScope.SourceSet,
            is SymbolSearchScope.GradleProject,
            is SymbolSearchScope.Workspace -> sourceRootPartitions()
        }
    }

    private fun sourceRootPartitions(): IntellijDeclarationInitialInventory {
        val paths = sortedSetOf<Path>(compareBy(Path::toString))
        if (plan != null) observation.count(IntellijReadCounter.DISCOVERY_DIRECTORY_PATH_CONVERSIONS)
        for (root in
            plan?.sourceRoots?.asSequence() ?: scope.sourceRoots.asSequence().map { Path.of(it.sourceRoot.value) }) {
            if (expired()) return initialTimeLimit()
            if (plan != null && !consume())
                return IntellijDeclarationInitialInventory.Rejected(
                    SymbolDiscoveryBlockCause.INSUFFICIENT_EXECUTION_GRANT
                )
            observation.count(IntellijReadCounter.DISCOVERY_PLAN_ROOT_ENTRIES)
            val path = if (plan == null) root else plan.selection.intersection(root) ?: continue
            if (admission.relevantDirectory(path)) paths.add(path)
        }
        return detachRootPartitions(paths)
    }

    private fun detachRootPartitions(paths: Set<Path>): IntellijDeclarationInitialInventory {
        val partitions = mutableListOf<SemanticFilePartition>()
        for (path in paths) {
            if (expired()) return initialTimeLimit()
            if (generateSequence(path.parent) { it.parent }.any { it in paths }) continue
            val identity = admission.identity(path, "file://$path")
            partitions += SemanticFilePartition.Directory(identity)
        }
        if (expired()) return initialTimeLimit()
        return IntellijDeclarationInitialInventory.Complete(partitions)
    }

    fun observe(partition: SemanticFilePartition): IntellijDeclarationPartitionObservation {
        if (expired()) return timeLimit()
        observation.count(
            IntellijReadCounter.DISCOVERY_PARTITIONS_OBSERVED,
            IntellijReadContributor.SCOPED_DECLARATIONS,
        )
        val result = observeNative(partition)
        return when (result) {
            is IntellijDeclarationPartitionObservation.Directory,
            is IntellijDeclarationPartitionObservation.Source,
            IntellijDeclarationPartitionObservation.OutsideUniverse -> {
                if (expired()) return timeLimit()
                observation.count(
                    IntellijReadCounter.DISCOVERY_PARTITIONS_ACCEPTED,
                    IntellijReadContributor.SCOPED_DECLARATIONS,
                )
                result
            }
            is IntellijDeclarationPartitionObservation.Rejected,
            IntellijDeclarationPartitionObservation.TimeLimit -> result
        }
    }

    private fun observeNative(partition: SemanticFilePartition): IntellijDeclarationPartitionObservation {
        val native = observation.call(IntellijReadCall.VFS_FIND_FILE) { partitions.find(partition) }
        if (expired()) return timeLimit()
        if (native == null)
            return rejected(
                SymbolDiscoveryBlockCause.PARTITION_UNAVAILABLE,
                IntellijReadTermination.DISCOVERY_PARTITION_NOT_FOUND,
            )
        if (!native.isValid || native.path != partition.orderingPath)
            return rejected(
                SymbolDiscoveryBlockCause.PARTITION_UNAVAILABLE,
                IntellijReadTermination.DISCOVERY_PARTITION_INVALID,
            )
        if (native.canonicalPath != native.path)
            return rejected(
                SymbolDiscoveryBlockCause.PARTITION_UNAVAILABLE,
                IntellijReadTermination.DISCOVERY_PARTITION_NON_CANONICAL,
            )
        return when (partition) {
            is SemanticFilePartition.Directory ->
                if (native.isDirectory) directory(native)
                else
                    rejected(
                        SymbolDiscoveryBlockCause.PARTITION_UNAVAILABLE,
                        IntellijReadTermination.DISCOVERY_PARTITION_KIND_MISMATCH,
                    )
            is SemanticFilePartition.File -> file(native)
        }
    }

    private fun directory(native: VirtualFile): IntellijDeclarationPartitionObservation {
        val children = observation.call(IntellijReadCall.VFS_CHILDREN) { partitions.children(native) }
        val detached = mutableListOf<SemanticFilePartition>()
        var bytes = PARTITION_OBSERVATION_BINDING_BYTES
        val eligible =
            children
                .asSequence()
                .takeWhile { !expired() }
                .onEach { observation.count(IntellijReadCounter.DISCOVERY_DIRECTORY_CHILD_ENTRIES) }
                .filter { it.isDirectory || includeSource(it) }
        for (child in eligible) {
            if (!consume())
                return IntellijDeclarationPartitionObservation.Rejected(
                    SymbolDiscoveryBlockCause.INSUFFICIENT_EXECUTION_GRANT
                )
            val partition = admission.detach(child) ?: continue
            if (
                request.budget.resources is io.github.amichne.kast.kernel.ResourceBudget.Bounded &&
                    detached.size >= limits[ReadLimitParameter.DISCOVERY_FILES].value
            )
                return rejected(
                    SymbolDiscoveryBlockCause.PARTITION_CAPACITY,
                    IntellijReadTermination.DISCOVERY_PARTITION_CAPACITY,
                )
            val added = partition.detachedBytes()
            if (
                request.budget.resources is io.github.amichne.kast.kernel.ResourceBudget.Bounded &&
                    added > limits[ReadLimitParameter.QUERY_CHECKPOINT_BYTES].value - bytes
            )
                return rejected(SymbolDiscoveryBlockCause.RETENTION_LIMIT, IntellijReadTermination.RETENTION_LIMIT)
            detached += partition
            bytes += added
        }
        return if (expired()) timeLimit()
        else IntellijDeclarationPartitionObservation.Directory(detached.sortedBy { it.orderingPath })
    }

    private fun file(native: VirtualFile): IntellijDeclarationPartitionObservation {
        if (native.isDirectory)
            return rejected(
                SymbolDiscoveryBlockCause.PARTITION_UNAVAILABLE,
                IntellijReadTermination.DISCOVERY_PARTITION_KIND_MISMATCH,
            )
        if (native.fileType != KotlinFileType.INSTANCE || !scope.nativeScope.contains(native))
            return IntellijDeclarationPartitionObservation.OutsideUniverse
        if (!includeSource(native)) return IntellijDeclarationPartitionObservation.OutsideUniverse
        val file =
            observation.call(IntellijReadCall.PSI_FIND_FILE) { PsiManager.getInstance(project).findFile(native) }
                as? KtFile
                ?: return rejected(
                    SymbolDiscoveryBlockCause.PROVIDER_UNAVAILABLE,
                    IntellijReadTermination.DISCOVERY_SOURCE_UNAVAILABLE,
                )
        return IntellijDeclarationPartitionObservation.Source(file)
    }

    private fun expired(): Boolean {
        ProgressManager.checkCanceled()
        return allowance.expired()
    }

    private fun timeLimit(): IntellijDeclarationPartitionObservation.TimeLimit {
        observation.terminated(IntellijReadTermination.TIME_LIMIT, IntellijReadContributor.SCOPED_DECLARATIONS)
        return IntellijDeclarationPartitionObservation.TimeLimit
    }

    private fun initialTimeLimit(): IntellijDeclarationInitialInventory.TimeLimit {
        observation.terminated(IntellijReadTermination.TIME_LIMIT, IntellijReadContributor.SCOPED_DECLARATIONS)
        return IntellijDeclarationInitialInventory.TimeLimit
    }

    private fun rejected(
        cause: SymbolDiscoveryBlockCause,
        reason: IntellijReadTermination,
    ): IntellijDeclarationPartitionObservation.Rejected {
        observation.terminated(reason, IntellijReadContributor.SCOPED_DECLARATIONS)
        return IntellijDeclarationPartitionObservation.Rejected(cause)
    }
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
    val adapter = IntellijDeclarationSourceAdapter(project, scope, request, limits, allowance, observation)
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
