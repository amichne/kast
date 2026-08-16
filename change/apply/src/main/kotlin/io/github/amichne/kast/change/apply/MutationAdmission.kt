package io.github.amichne.kast.change.apply

import io.github.amichne.kast.change.contract.AddDeclarationPlannedEdit
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.workspace.contract.SourceRoot
import io.github.amichne.kast.workspace.contract.SourceRootProvenance
import io.github.amichne.kast.workspace.contract.WorkspaceSourceContentHash
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat

/** Finite reasons current repository state cannot become source-mutation authority. */
enum class MutationAdmissionFailure {
    WRONG_ROOT,
    STALE_GENERATION,
    STALE_SOURCE_STATE,
    SOURCE_CONTENT_CHANGED,
    OUT_OF_SCOPE,
    GENERATED_TARGET,
    UNKNOWN_TARGET_PROVENANCE,
    WRONG_SOURCE_ROOT_OWNER,
    TARGET_READ_ONLY,
    UNPLANNED_WRITE_SET,
    ANCHOR_OUT_OF_BOUNDS,
    SOURCE_HASH_UNREPRESENTABLE,
}

internal class ExactAdmittedSourceWrite(
    val preimageText: String,
    val postimageText: String,
    val insertionOffset: Int,
    val insertionText: String,
    val postimageContent: WorkspaceSourceContentHash,
)

/** Pure candidate carrying every current-state proof except durable pre-write recovery. */
internal class AdmittedMutation(
    val request: AddDeclarationApplyRequest,
    val observation: ObservedMutationSource,
    val sourceRoot: SourceRoot,
    val write: ExactAdmittedSourceWrite,
)

/** Pure KCS-017 admission from a detached plan and current source observation. */
internal class MutationAdmissionService {
    /**
     * Proof transition: `(AddDeclarationApplyRequest, ObservedMutationSource) -> Refinement<
     * AdmittedMutation, MutationAdmissionFailure>`.
     *
     * Establishes one exact root, generation, source state, content image, uniquely owned authored
     * source root, writable target, singleton caller scope, singleton planned insertion, and exact
     * derived postimage. [MutationAdmissionFailure] is the closed expected failure. Raw source
     * extraction is prohibited here and remains confined to the physical source boundary.
     */
    fun admit(
        request: AddDeclarationApplyRequest,
        observed: ObservedMutationSource,
    ): Refinement<AdmittedMutation, MutationAdmissionFailure> {
        val plan = request.plan
        val workspace = request.workspace
        if (
            plan.sourceSnapshot.lease.workspaceRoot != workspace.root ||
            plan.target.lease.workspaceRoot != workspace.root ||
            request.writeScope.root != workspace.root
        ) {
            return rejected(MutationAdmissionFailure.WRONG_ROOT)
        }
        if (
            plan.sourceSnapshot.lease.generation != workspace.generation ||
            plan.target.lease.generation != workspace.generation
        ) {
            return rejected(MutationAdmissionFailure.STALE_GENERATION)
        }
        if (
            plan.sourceSnapshot.workspaceState != workspace.sourceState ||
            plan.target.workspaceState != workspace.sourceState
        ) {
            return rejected(MutationAdmissionFailure.STALE_SOURCE_STATE)
        }
        val currentRoot = when (val root = currentSourceRoot(request)) {
            is Refinement.Refined -> root.value
            is Refinement.Rejected -> return root
        }
        when (currentRoot.provenance) {
            SourceRootProvenance.Authored -> Unit
            SourceRootProvenance.Generated ->
                return rejected(MutationAdmissionFailure.GENERATED_TARGET)
            is SourceRootProvenance.Unknown ->
                return rejected(MutationAdmissionFailure.UNKNOWN_TARGET_PROVENANCE)
        }
        if (currentRoot.owner != plan.target.owner) {
            return rejected(MutationAdmissionFailure.WRONG_SOURCE_ROOT_OWNER)
        }
        if (plan.target.file !in request.writeScope.sources) {
            return rejected(MutationAdmissionFailure.OUT_OF_SCOPE)
        }
        if (request.writeScope.sources != setOf(plan.target.file)) {
            return rejected(MutationAdmissionFailure.UNPLANNED_WRITE_SET)
        }
        if (observed.source != plan.target.file) {
            return rejected(MutationAdmissionFailure.UNPLANNED_WRITE_SET)
        }
        if (observed.content != plan.sourceSnapshot.content) {
            return rejected(MutationAdmissionFailure.SOURCE_CONTENT_CHANGED)
        }
        if (observed.access != SourceWriteAccess.Writable) {
            return rejected(MutationAdmissionFailure.TARGET_READ_ONLY)
        }
        val edit = when {
            plan.plannedEdits.size != 1 ->
                return rejected(MutationAdmissionFailure.UNPLANNED_WRITE_SET)
            plan.plannedEdits.single() is AddDeclarationPlannedEdit.InsertAfterDeclaration ->
                plan.plannedEdits.single() as AddDeclarationPlannedEdit.InsertAfterDeclaration
            else -> return rejected(MutationAdmissionFailure.UNPLANNED_WRITE_SET)
        }
        if (edit.file != plan.target.file || edit.anchor != plan.target.range) {
            return rejected(MutationAdmissionFailure.UNPLANNED_WRITE_SET)
        }
        if (edit.anchor.endExclusive > observed.text.length) {
            return rejected(MutationAdmissionFailure.ANCHOR_OUT_OF_BOUNDS)
        }
        val insertion = "\n\n${edit.declaration.value}"
        val postimage = observed.text.substring(0, edit.anchor.endExclusive) + insertion +
            observed.text.substring(edit.anchor.endExclusive)
        val postimageContent = when (val parsed = WorkspaceSourceContentHash.parse(
            sha256(postimage.toByteArray(StandardCharsets.UTF_8)),
        )) {
            is Refinement.Refined -> parsed.value
            is Refinement.Rejected -> return rejected(
                MutationAdmissionFailure.SOURCE_HASH_UNREPRESENTABLE,
            )
        }
        return Refinement.Refined(
            AdmittedMutation(
                request,
                observed,
                currentRoot,
                ExactAdmittedSourceWrite(
                    observed.text,
                    postimage,
                    edit.anchor.endExclusive,
                    insertion,
                    postimageContent,
                ),
            ),
        )
    }

    /**
     * Proof transition: `AddDeclarationApplyRequest -> Refinement<SourceRoot,
     * MutationAdmissionFailure>`.
     *
     * Establishes one current source root at the plan's exact modeled location and proves that the
     * target remains strictly contained by it. [MutationAdmissionFailure] closes ambiguous,
     * absent, or escaped ownership. Raw path interpretation is confined to this pure admission
     * boundary and no path handle leaves it.
     */
    private fun currentSourceRoot(
        request: AddDeclarationApplyRequest,
    ): Refinement<SourceRoot, MutationAdmissionFailure> {
        val plan = request.plan
        val matching = request.workspace.sourceRoots.filter { root ->
            root.location == plan.target.sourceRoot.location
        }
        if (matching.size != 1) {
            return rejected(MutationAdmissionFailure.WRONG_SOURCE_ROOT_OWNER)
        }
        val root = matching.single()
        val workspacePath = Path.of(request.workspace.root.value)
        val sourcePath = workspacePath.resolve(root.location.value).normalize()
        val targetPath = Path.of(plan.target.file.path.value)
        if (targetPath == sourcePath || !targetPath.startsWith(sourcePath)) {
            return rejected(MutationAdmissionFailure.WRONG_SOURCE_ROOT_OWNER)
        }
        return Refinement.Refined(root)
    }

    private fun rejected(
        failure: MutationAdmissionFailure,
    ): Refinement.Rejected<MutationAdmissionFailure> = Refinement.Rejected(failure)

    private fun sha256(bytes: ByteArray): String = HexFormat.of().formatHex(
        MessageDigest.getInstance("SHA-256").digest(bytes),
    )
}
