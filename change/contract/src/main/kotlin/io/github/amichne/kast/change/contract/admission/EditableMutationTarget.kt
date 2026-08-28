package io.github.amichne.kast.change.contract

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.symbol.contract.ExactDeclarationTextRange
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryFileIdentity
import io.github.amichne.kast.symbol.contract.SymbolSelector
import io.github.amichne.kast.workspace.contract.GradleSourceSetOwner
import io.github.amichne.kast.workspace.contract.PublishedWorkspace
import io.github.amichne.kast.workspace.contract.SemanticReadLease
import io.github.amichne.kast.workspace.contract.SourceRoot
import io.github.amichne.kast.workspace.contract.SourceRootProvenance
import io.github.amichne.kast.workspace.contract.WorkspaceSourceContentHash
import io.github.amichne.kast.workspace.contract.WorkspaceStateIdentity
import java.nio.file.Path

/** Detached exact content observation for the semantic target selected at one read lease. */
data class ObservedMutationTargetState(
    val lease: SemanticReadLease,
    val file: SymbolDiscoveryFileIdentity,
    val content: WorkspaceSourceContentHash,
)

/** Weaker admission input assembled from published, compiler, ownership, and content evidence. */
data class MutationTargetObservation(
    val workspace: PublishedWorkspace,
    val selector: SymbolSelector,
    val expectedOwner: GradleSourceSetOwner,
    val observedState: ObservedMutationTargetState,
)

/** Finite reasons that an observed semantic target cannot enter mutation planning. */
enum class MutationTargetAdmissionFailure {
    GENERATED_SOURCE_ROOT,
    UNKNOWN_SOURCE_ROOT,
    ESCAPED_TARGET,
    AMBIGUOUS_OWNERSHIP,
    STALE_STATE,
    WRONG_OWNER,
}

/**
 * Exact authored declaration capability eligible to enter later mutation planning.
 *
 * This value carries proof only about the target. It provides no filesystem, document, PSI,
 * persistence, or source-write operation.
 */
class EditableMutationTarget private constructor(
    val lease: SemanticReadLease,
    val workspaceState: WorkspaceStateIdentity,
    val file: SymbolDiscoveryFileIdentity.Workspace,
    val content: WorkspaceSourceContentHash,
    val sourceRoot: SourceRoot,
    val selector: SymbolSelector,
) {
    val owner: GradleSourceSetOwner
        get() = sourceRoot.owner

    val range: ExactDeclarationTextRange
        get() = selector.range

    companion object {
        internal fun restore(
            lease: SemanticReadLease,
            workspaceState: WorkspaceStateIdentity,
            file: SymbolDiscoveryFileIdentity.Workspace,
            content: WorkspaceSourceContentHash,
            sourceRoot: SourceRoot,
            selector: SymbolSelector,
        ): Refinement<EditableMutationTarget, MutationTargetAdmissionFailure> {
            if (selector.lease != lease || selector.file != file) {
                return Refinement.Rejected(MutationTargetAdmissionFailure.STALE_STATE)
            }
            val targetPath = runCatching { Path.of(file.path.value) }.getOrNull()
                ?: return Refinement.Rejected(MutationTargetAdmissionFailure.ESCAPED_TARGET)
            val rootPath = runCatching { Path.of(lease.workspaceRoot.value) }.getOrNull()
                ?: return Refinement.Rejected(MutationTargetAdmissionFailure.ESCAPED_TARGET)
            val sourcePath = rootPath.resolve(sourceRoot.location.value).normalize()
            if (targetPath == sourcePath || !targetPath.startsWith(sourcePath)) {
                return Refinement.Rejected(MutationTargetAdmissionFailure.ESCAPED_TARGET)
            }
            when (sourceRoot.provenance) {
                SourceRootProvenance.Authored -> Unit
                SourceRootProvenance.Generated -> return Refinement.Rejected(
                    MutationTargetAdmissionFailure.GENERATED_SOURCE_ROOT,
                )
                is SourceRootProvenance.Unknown -> return Refinement.Rejected(
                    MutationTargetAdmissionFailure.UNKNOWN_SOURCE_ROOT,
                )
            }
            return Refinement.Refined(
                EditableMutationTarget(
                    lease,
                    workspaceState,
                    file,
                    content,
                    sourceRoot,
                    selector,
                ),
            )
        }

        /**
         * Proof transition: `MutationTargetObservation -> Refinement<EditableMutationTarget,
         * MutationTargetAdmissionFailure>`.
         *
         * Establishes that one compiler-grounded declaration and exact content observation share
         * the published root and generation, lie strictly within one uniquely owned authored
         * Gradle source root, and match the required owner. [MutationTargetAdmissionFailure] is the
         * closed expected failure. Detached paths are interpreted only inside this pure admission
         * transition; raw source access is permitted only at a later physical observation or
         * source-write boundary that consumes the returned capability.
         */
        fun admit(
            observation: MutationTargetObservation,
        ): Refinement<EditableMutationTarget, MutationTargetAdmissionFailure> {
            val workspace = observation.workspace
            val selector = observation.selector
            val state = observation.observedState
            if (
                selector.lease != workspace.readLease ||
                state.lease != workspace.readLease ||
                state.file != selector.file
            ) {
                return Refinement.Rejected(MutationTargetAdmissionFailure.STALE_STATE)
            }
            val file = when (val selectedFile = selector.file) {
                is SymbolDiscoveryFileIdentity.Workspace -> selectedFile
                is SymbolDiscoveryFileIdentity.External -> return Refinement.Rejected(
                    MutationTargetAdmissionFailure.ESCAPED_TARGET,
                )
            }
            val targetPath = Path.of(file.path.value)
            val workspacePath = Path.of(workspace.root.value)
            val containingRoots = workspace.sourceRoots
                .filter { sourceRoot ->
                    val sourcePath = workspacePath.resolve(sourceRoot.location.value).normalize()
                    targetPath != sourcePath && targetPath.startsWith(sourcePath)
                }
                .distinct()
            if (containingRoots.isEmpty()) {
                return Refinement.Rejected(MutationTargetAdmissionFailure.ESCAPED_TARGET)
            }
            if (containingRoots.size > 1) {
                return Refinement.Rejected(MutationTargetAdmissionFailure.AMBIGUOUS_OWNERSHIP)
            }
            val sourceRoot = containingRoots.single()
            if (sourceRoot.owner != observation.expectedOwner) {
                return Refinement.Rejected(MutationTargetAdmissionFailure.WRONG_OWNER)
            }
            when (sourceRoot.provenance) {
                SourceRootProvenance.Authored -> Unit
                SourceRootProvenance.Generated -> return Refinement.Rejected(
                    MutationTargetAdmissionFailure.GENERATED_SOURCE_ROOT,
                )
                is SourceRootProvenance.Unknown -> return Refinement.Rejected(
                    MutationTargetAdmissionFailure.UNKNOWN_SOURCE_ROOT,
                )
            }
            return Refinement.Refined(
                EditableMutationTarget(
                    lease = workspace.readLease,
                    workspaceState = workspace.sourceState,
                    file = file,
                    content = state.content,
                    sourceRoot = sourceRoot,
                    selector = selector,
                ),
            )
        }
    }
}
