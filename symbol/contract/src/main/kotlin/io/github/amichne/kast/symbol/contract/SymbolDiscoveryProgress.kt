package io.github.amichne.kast.symbol.contract

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.workspace.contract.SemanticReadAuthority

/** Detached VFS locations retain file identity without retaining native VFS or PSI objects. */
sealed interface SemanticFilePartition {
    val location: SymbolDiscoveryFileIdentity
    val orderingPath: String
        get() =
            when (val identity = location) {
                is SymbolDiscoveryFileIdentity.Workspace -> identity.path.value
                is SymbolDiscoveryFileIdentity.External -> identity.url.value.removePrefix("file://")
            }

    data class Directory(override val location: SymbolDiscoveryFileIdentity) : SemanticFilePartition

    data class File(override val location: SymbolDiscoveryFileIdentity) : SemanticFilePartition
}

/** Absolute file path, then declaration source offset; independent of native iterator order. */
enum class SymbolDiscoveryProviderOrder {
    KOTLIN_FILE_SOURCE_V2
}

enum class SymbolDiscoveryBlockCause {
    PARTITION_UNAVAILABLE,
    PARTITION_CAPACITY,
    RETENTION_LIMIT,
    PROVIDER_UNAVAILABLE,
    QUALIFIED_PROVIDER,
    INSUFFICIENT_EXECUTION_GRANT,
    ITEM_BYTE_LIMIT,
    INPUT_REVISION_EXHAUSTED,
}

sealed interface SymbolDiscoveryProgress {
    data object Exhausted : SymbolDiscoveryProgress

    data class Resumable(val remainder: SymbolDiscoveryRemainder) : SymbolDiscoveryProgress

    data class Blocked(val cause: SymbolDiscoveryBlockCause) : SymbolDiscoveryProgress
}

enum class SymbolDiscoveryRemainderFailure {
    TARGET_UNSUPPORTED,
    FRONTIER_NOT_ORDERED_UNIQUE,
    INPUT_AUTHORITY_MISMATCH,
    NO_UNFINISHED_INPUT,
    NEGATIVE_REVISION,
    NO_INPUT_ADVANCEMENT,
    INVALID_COMPLETED_COUNT,
}

@JvmInline
value class SymbolDiscoveryInputRevision private constructor(val value: Long) {
    companion object {
        val Zero = SymbolDiscoveryInputRevision(0)

        fun parse(value: Long): Refinement<SymbolDiscoveryInputRevision, SymbolDiscoveryRemainderFailure> =
            if (value >= 0) Refinement.Refined(SymbolDiscoveryInputRevision(value))
            else Refinement.Rejected(SymbolDiscoveryRemainderFailure.NEGATIVE_REVISION)
    }
}

sealed interface SymbolDiscoveryActiveInput {
    data object Unopened : SymbolDiscoveryActiveInput

    data class Scanning(val file: SymbolDiscoveryFileIdentity.Workspace, val nextOffset: SymbolDiscoverySourceOffset) :
        SymbolDiscoveryActiveInput
}

/**
 * The checkpoint owns the unread, lexically ordered partition frontier and one active source position. Consuming a
 * directory replaces it with its bounded child snapshot. No complete global inventory is required, and removal of
 * consumed partitions preserves their next-child position without native replay.
 */
@ConsistentCopyVisibility
data class SymbolDiscoveryRemainder
private constructor(
    val authority: SemanticReadAuthority,
    val scope: SymbolSearchScope,
    val target: SymbolDiscoveryTarget.All,
    val constraints: SymbolDiscoveryConstraints,
    val providerOrder: SymbolDiscoveryProviderOrder,
    val frontier: List<SemanticFilePartition>,
    val active: SymbolDiscoveryActiveInput,
    val inputRevision: SymbolDiscoveryInputRevision,
    val discoveredFiles: SymbolDiscoveryWorkCount,
    val completedFiles: SymbolDiscoveryWorkCount,
    val permanentQualifications: Set<SymbolDiscoveryQualification>,
) {
    val nextOffset: SymbolDiscoverySourceOffset
        get() =
            when (val input = active) {
                SymbolDiscoveryActiveInput.Unopened ->
                    (SymbolDiscoverySourceOffset.parse(0) as Refinement.Refined).value
                is SymbolDiscoveryActiveInput.Scanning -> input.nextOffset
            }

    val retainedBytes: Long
        get() =
            768L +
                scope.detachedIdentityBytes() +
                2L * (authority.workspaceRoot.value.length + authority.identity.revisionKey.value.length) +
                constraints.fingerprintFields().sumOf { 48L + 2L * it.length } +
                frontier.sumOf { 160L + 2L * it.location.stableValue.length } +
                when (val input = active) {
                    SymbolDiscoveryActiveInput.Unopened -> 0L
                    is SymbolDiscoveryActiveInput.Scanning -> 160L + 2L * input.file.stableValue.length
                }

    fun matches(request: SymbolDiscoveryRequest): Boolean =
        authority == request.scope.lease &&
            scope == request.scope.scope &&
            target == request.target &&
            constraints == request.constraints &&
            providerOrder == SymbolDiscoveryProviderOrder.KOTLIN_FILE_SOURCE_V2

    fun advancesFrom(previous: SymbolDiscoveryRemainder): Boolean =
        authority == previous.authority &&
            scope == previous.scope &&
            target == previous.target &&
            constraints == previous.constraints &&
            providerOrder == previous.providerOrder &&
            inputRevision.value > previous.inputRevision.value &&
            discoveredFiles.value >= previous.discoveredFiles.value &&
            completedFiles.value >= previous.completedFiles.value &&
            permanentQualifications.containsAll(previous.permanentQualifications)

    companion object {
        fun fromPendingInput(
            request: SymbolDiscoveryRequest,
            frontier: List<SemanticFilePartition>,
            active: SymbolDiscoveryActiveInput,
            inputRevision: SymbolDiscoveryInputRevision,
            discoveredFiles: SymbolDiscoveryWorkCount = SymbolDiscoveryWorkCount.Zero,
            completedFiles: SymbolDiscoveryWorkCount = SymbolDiscoveryWorkCount.Zero,
            permanentQualifications: Set<SymbolDiscoveryQualification> = emptySet(),
        ): Refinement<SymbolDiscoveryRemainder, SymbolDiscoveryRemainderFailure> {
            if (inputRevision.value == 0L)
                return Refinement.Rejected(SymbolDiscoveryRemainderFailure.NO_INPUT_ADVANCEMENT)
            val target =
                request.target as? SymbolDiscoveryTarget.All
                    ?: return Refinement.Rejected(SymbolDiscoveryRemainderFailure.TARGET_UNSUPPORTED)
            if (target.kind == SymbolNameDiscoveryKind.FILE)
                return Refinement.Rejected(SymbolDiscoveryRemainderFailure.TARGET_UNSUPPORTED)
            if (!orderedUniqueFrontier(frontier, active))
                return Refinement.Rejected(SymbolDiscoveryRemainderFailure.FRONTIER_NOT_ORDERED_UNIQUE)
            if (!admittedFrontier(request.scope.lease, frontier, active))
                return Refinement.Rejected(SymbolDiscoveryRemainderFailure.INPUT_AUTHORITY_MISMATCH)
            if (!validCompletionCounts(active, discoveredFiles, completedFiles))
                return Refinement.Rejected(SymbolDiscoveryRemainderFailure.INVALID_COMPLETED_COUNT)
            if (frontier.isEmpty() && active == SymbolDiscoveryActiveInput.Unopened)
                return Refinement.Rejected(SymbolDiscoveryRemainderFailure.NO_UNFINISHED_INPUT)
            return Refinement.Refined(
                SymbolDiscoveryRemainder(
                    request.scope.lease,
                    request.scope.scope,
                    target,
                    request.constraints,
                    SymbolDiscoveryProviderOrder.KOTLIN_FILE_SOURCE_V2,
                    java.util.Collections.unmodifiableList(frontier.toList()),
                    active,
                    inputRevision,
                    discoveredFiles,
                    completedFiles,
                    java.util.Collections.unmodifiableSet(permanentQualifications.toSet()),
                )
            )
        }
    }
}

private fun SemanticFilePartition.admittedBy(authority: SemanticReadAuthority): Boolean =
    when (val identity = location) {
        is SymbolDiscoveryFileIdentity.Workspace ->
            CanonicalWorkspaceFilePath.fromCanonicalPath(
                authority.workspaceRoot,
                java.nio.file.Path.of(identity.path.value),
            ) is Refinement.Refined
        is SymbolDiscoveryFileIdentity.External ->
            this is SemanticFilePartition.Directory && identity.url.value == "file://${authority.workspaceRoot.value}"
    }

fun SymbolSearchScope.detachedIdentityBytes(): Long =
    when (this) {
        is SymbolSearchScope.ExactFile -> 2L * file.value.length
        is SymbolSearchScope.Module -> 2L * module.value.length
        is SymbolSearchScope.GradleProject -> 2L * (project.buildRoot.value.length + project.projectPath.value.length)
        is SymbolSearchScope.SourceSet ->
            2L * (project.buildRoot.value.length + project.projectPath.value.length + sourceSet.value.length)
        is SymbolSearchScope.Workspace -> 0L
    }

private fun orderedUniqueFrontier(frontier: List<SemanticFilePartition>, active: SymbolDiscoveryActiveInput): Boolean {
    val orderedPaths = frontier.map { it.orderingPath }
    if (orderedPaths != orderedPaths.distinct().sorted()) return false
    return active !is SymbolDiscoveryActiveInput.Scanning || frontier.none { it.location == active.file }
}

private fun admittedFrontier(
    authority: SemanticReadAuthority,
    frontier: List<SemanticFilePartition>,
    active: SymbolDiscoveryActiveInput,
): Boolean {
    if (frontier.any { !it.admittedBy(authority) }) return false
    return active !is SymbolDiscoveryActiveInput.Scanning ||
        SemanticFilePartition.File(active.file).admittedBy(authority)
}

private fun validCompletionCounts(
    active: SymbolDiscoveryActiveInput,
    discovered: SymbolDiscoveryWorkCount,
    completed: SymbolDiscoveryWorkCount,
): Boolean {
    if (completed.value > discovered.value) return false
    return when (active) {
        is SymbolDiscoveryActiveInput.Scanning -> discovered.value - completed.value == 1L
        SymbolDiscoveryActiveInput.Unopened -> discovered == completed
    }
}
