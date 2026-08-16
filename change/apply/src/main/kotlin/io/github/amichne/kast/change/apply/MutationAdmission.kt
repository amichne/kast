package io.github.amichne.kast.change.apply

import io.github.amichne.kast.change.contract.SourceTextMutation
import io.github.amichne.kast.change.contract.PlannedSourcePrecondition
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
    EMPTY_MUTATION_SET,
    MUTATION_OVERLAP,
    MUTATION_OUT_OF_BOUNDS,
    MUTATION_PREIMAGE_MISMATCH,
    MUTATION_KIND_MISMATCH,
    SOURCE_PRECONDITION_MISMATCH,
    SOURCE_HASH_UNREPRESENTABLE,
}

internal class ExactAdmittedSourceWrite(
    val source: io.github.amichne.kast.symbol.contract.SymbolDiscoveryFileIdentity.Workspace,
    val preimage: ObservedMutationPrecondition,
    val postimage: DerivedMutationPostimage,
)

/** Exact derived postimage that preserves the successful mutation and content-hash proof. */
internal class DerivedMutationPostimage private constructor(
    internal val text: String,
    val content: WorkspaceSourceContentHash,
    mutations: List<SourceTextMutation>,
) {
    val mutations: List<SourceTextMutation> = mutations.toList()

    companion object {
        /**
         * Proof transition: `(ObservedMutationPrecondition, List<SourceTextMutation>) ->
         * Refinement<DerivedMutationPostimage, MutationAdmissionFailure>`.
         *
         * Establishes either a non-overlapping existing-source mutation set whose expected text
         * matches the preimage or one whole-file creation against proven absence, plus the
         * SHA-256 identity of the deterministic postimage.
         * [MutationAdmissionFailure] closes invalid mutation sets and unrepresentable content.
         * Raw source text may enter from [ObservedMutationSource] and leave only through the
         * authority-bound IntelliJ mutation boundary.
         */
        fun derive(
            preimage: ObservedMutationPrecondition,
            mutations: List<SourceTextMutation>,
        ): Refinement<DerivedMutationPostimage, MutationAdmissionFailure> {
            if (mutations.isEmpty()) {
                return Refinement.Rejected(MutationAdmissionFailure.EMPTY_MUTATION_SET)
            }
            return when (preimage) {
                is ObservedMutationSource -> deriveExisting(preimage, mutations)
                is ObservedAbsentMutationSource -> deriveCreatedFile(mutations)
            }
        }

        /**
         * Proof transition: `(ObservedMutationSource, List<SourceTextMutation>) -> Refinement<
         * DerivedMutationPostimage, MutationAdmissionFailure>`.
         *
         * Establishes non-overlapping in-file mutations whose expected text matches the exact
         * existing preimage and retains their deterministic SHA-256 postimage.
         * [MutationAdmissionFailure] closes mixed mutation kinds, invalid ranges, overlap, and
         * preimage mismatch. Raw text leaves only through the returned stronger postimage.
         */
        private fun deriveExisting(
            preimage: ObservedMutationSource,
            mutations: List<SourceTextMutation>,
        ): Refinement<DerivedMutationPostimage, MutationAdmissionFailure> {
            if (mutations.any { it is SourceTextMutation.CreateFile }) {
                return Refinement.Rejected(MutationAdmissionFailure.MUTATION_KIND_MISMATCH)
            }
            val rangedMutations = mutations.map { mutation ->
                val range = when (mutation) {
                    is SourceTextMutation.CreateFile -> return Refinement.Rejected(
                        MutationAdmissionFailure.MUTATION_KIND_MISMATCH,
                    )
                    is SourceTextMutation.InsertAfterDeclaration -> MutationRange(
                        mutation.anchor.endExclusive,
                        mutation.anchor.endExclusive,
                    )
                    is SourceTextMutation.Replace -> MutationRange(
                        mutation.range.startInclusive,
                        mutation.range.endExclusive,
                    )
                    is SourceTextMutation.ReplaceDeclaration -> MutationRange(
                        mutation.range.startInclusive,
                        mutation.range.endExclusive,
                    )
                }
                mutation to range
            }
            val ranges = rangedMutations.map { it.second }.sortedBy(MutationRange::startInclusive)
            if (ranges.zipWithNext().any { (left, right) ->
                    left.startInclusive == right.startInclusive ||
                        left.endExclusive > right.startInclusive
                }
            ) {
                return Refinement.Rejected(MutationAdmissionFailure.MUTATION_OVERLAP)
            }
            if (ranges.any {
                        it.startInclusive < 0 ||
                        it.endExclusive < it.startInclusive ||
                        it.endExclusive > preimage.text.length
                }
            ) {
                return Refinement.Rejected(MutationAdmissionFailure.MUTATION_OUT_OF_BOUNDS)
            }
            if (mutations.any { mutation ->
                    when (mutation) {
                        is SourceTextMutation.Replace -> preimage.text.substring(
                            mutation.range.startInclusive,
                            mutation.range.endExclusive,
                        ) != mutation.expected.value
                        is SourceTextMutation.ReplaceDeclaration -> preimage.text.substring(
                            mutation.range.startInclusive,
                            mutation.range.endExclusive,
                        ) != mutation.expected.value
                        else -> false
                    }
                }
            ) {
                return Refinement.Rejected(
                    MutationAdmissionFailure.MUTATION_PREIMAGE_MISMATCH,
                )
            }
            var result = preimage.text
            rangedMutations.sortedByDescending { it.second.startInclusive }.forEach { pair ->
                val mutation = pair.first
                result = when (mutation) {
                    is SourceTextMutation.CreateFile -> return Refinement.Rejected(
                        MutationAdmissionFailure.MUTATION_KIND_MISMATCH,
                    )
                    is SourceTextMutation.InsertAfterDeclaration -> {
                        val offset = mutation.anchor.endExclusive
                        result.substring(0, offset) + "\n\n${mutation.declaration.value}" +
                            result.substring(offset)
                    }
                    is SourceTextMutation.Replace -> result.substring(
                        0,
                        mutation.range.startInclusive,
                    ) + mutation.replacement.value + result.substring(mutation.range.endExclusive)
                    is SourceTextMutation.ReplaceDeclaration -> result.substring(
                        0,
                        mutation.range.startInclusive,
                    ) + mutation.replacement.value + result.substring(mutation.range.endExclusive)
                }
            }
            val content = when (val parsed = WorkspaceSourceContentHash.parse(
                sha256(result.toByteArray(StandardCharsets.UTF_8)),
            )) {
                is Refinement.Refined -> parsed.value
                is Refinement.Rejected -> return Refinement.Rejected(
                    MutationAdmissionFailure.SOURCE_HASH_UNREPRESENTABLE,
                )
            }
            return Refinement.Refined(DerivedMutationPostimage(result, content, mutations))
        }

        /**
         * Proof transition: `List<SourceTextMutation> -> Refinement<
         * DerivedMutationPostimage, MutationAdmissionFailure>` under an admitted absent source.
         *
         * Establishes exactly one whole-file creation and its SHA-256 postimage identity.
         * [MutationAdmissionFailure] closes mixed, missing, or repeated mutation kinds. Raw source
         * text leaves only through the returned stronger postimage.
         */
        private fun deriveCreatedFile(
            mutations: List<SourceTextMutation>,
        ): Refinement<DerivedMutationPostimage, MutationAdmissionFailure> {
            val create = mutations.singleOrNull() as? SourceTextMutation.CreateFile
                ?: return Refinement.Rejected(MutationAdmissionFailure.MUTATION_KIND_MISMATCH)
            val result = create.content.value
            val content = when (val parsed = WorkspaceSourceContentHash.parse(
                sha256(result.toByteArray(StandardCharsets.UTF_8)),
            )) {
                is Refinement.Refined -> parsed.value
                is Refinement.Rejected -> return Refinement.Rejected(
                    MutationAdmissionFailure.SOURCE_HASH_UNREPRESENTABLE,
                )
            }
            return Refinement.Refined(DerivedMutationPostimage(result, content, mutations))
        }

        private fun sha256(bytes: ByteArray): String = HexFormat.of().formatHex(
            MessageDigest.getInstance("SHA-256").digest(bytes),
        )
    }
}

private data class MutationRange(
    val startInclusive: Int,
    val endExclusive: Int,
)

/** Pure candidate carrying every current-state proof except durable pre-write recovery. */
internal class AdmittedMutation(
    val request: AddDeclarationApplyRequest,
    val observation: ObservedMutationPrecondition,
    val sourceRoot: SourceRoot,
    val write: ExactAdmittedSourceWrite,
)

/** Pure KCS-017 admission from a detached plan and current source observation. */
internal class MutationAdmissionService {
    /**
     * Proof transition: `(ChangeApplyRequest, ObservedMutationPrecondition) -> Refinement<
     * AdmittedMutation, MutationAdmissionFailure>`.
     *
     * Establishes one exact root, generation, source state, content image, uniquely owned authored
     * source root, writable target, exact caller scope, planned semantic transformations, and exact
     * derived postimage. [MutationAdmissionFailure] is the closed expected failure. Raw source
     * extraction is prohibited here and remains confined to the physical source boundary.
     */
    fun admit(
        request: AddDeclarationApplyRequest,
        observed: ObservedMutationPrecondition,
    ): Refinement<AdmittedMutation, MutationAdmissionFailure> {
        val plan = request.plan
        val workspace = request.workspace
        if (
            plan.priorLease.workspaceRoot != workspace.root ||
            request.writeScope.root != workspace.root
        ) {
            return rejected(MutationAdmissionFailure.WRONG_ROOT)
        }
        if (
            plan.priorLease.generation != workspace.generation
        ) {
            return rejected(MutationAdmissionFailure.STALE_GENERATION)
        }
        if (
            plan.workspaceState != workspace.sourceState
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
        val plannedWrite = plan.writes.entries.singleOrNull()
            ?: return rejected(MutationAdmissionFailure.UNPLANNED_WRITE_SET)
        if (currentRoot.owner != plannedWrite.sourceRoot.owner) {
            return rejected(MutationAdmissionFailure.WRONG_SOURCE_ROOT_OWNER)
        }
        if (plannedWrite.source !in request.writeScope.sources) {
            return rejected(MutationAdmissionFailure.OUT_OF_SCOPE)
        }
        if (request.writeScope.sources != setOf(plannedWrite.source)) {
            return rejected(MutationAdmissionFailure.UNPLANNED_WRITE_SET)
        }
        if (observed.source != plannedWrite.source) {
            return rejected(MutationAdmissionFailure.UNPLANNED_WRITE_SET)
        }
        when (val expected = plannedWrite.precondition) {
            is PlannedSourcePrecondition.Existing -> {
                if (observed !is ObservedMutationSource) {
                    return rejected(MutationAdmissionFailure.SOURCE_PRECONDITION_MISMATCH)
                }
                if (observed.content != expected.content) {
                    return rejected(MutationAdmissionFailure.SOURCE_CONTENT_CHANGED)
                }
            }
            PlannedSourcePrecondition.Absent -> if (observed !is ObservedAbsentMutationSource) {
                return rejected(MutationAdmissionFailure.SOURCE_PRECONDITION_MISMATCH)
            }
        }
        if (observed.access != SourceWriteAccess.Writable) {
            return rejected(MutationAdmissionFailure.TARGET_READ_ONLY)
        }
        val postimage = when (val derived = DerivedMutationPostimage.derive(
            observed,
            plannedWrite.mutations,
        )) {
            is Refinement.Refined -> derived.value
            is Refinement.Rejected -> return derived
        }
        return Refinement.Refined(
            AdmittedMutation(
                request,
                observed,
                currentRoot,
                ExactAdmittedSourceWrite(
                    plannedWrite.source,
                    observed,
                    postimage,
                ),
            ),
        )
    }

    /**
     * Proof transition: `ChangeApplyRequest -> Refinement<SourceRoot,
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
        val plannedWrite = plan.writes.entries.singleOrNull()
            ?: return rejected(MutationAdmissionFailure.UNPLANNED_WRITE_SET)
        val matching = request.workspace.sourceRoots.filter { root ->
            root.location == plannedWrite.sourceRoot.location
        }
        if (matching.size != 1) {
            return rejected(MutationAdmissionFailure.WRONG_SOURCE_ROOT_OWNER)
        }
        val root = matching.single()
        val workspacePath = Path.of(request.workspace.root.value)
        val sourcePath = workspacePath.resolve(root.location.value).normalize()
        val targetPath = Path.of(plannedWrite.source.path.value)
        if (targetPath == sourcePath || !targetPath.startsWith(sourcePath)) {
            return rejected(MutationAdmissionFailure.WRONG_SOURCE_ROOT_OWNER)
        }
        return Refinement.Refined(root)
    }

    private fun rejected(
        failure: MutationAdmissionFailure,
    ): Refinement.Rejected<MutationAdmissionFailure> = Refinement.Rejected(failure)

}
