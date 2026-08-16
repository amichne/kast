package io.github.amichne.kast.change.contract

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryFileIdentity
import io.github.amichne.kast.workspace.contract.GradleSourceSetOwner
import io.github.amichne.kast.workspace.contract.PublishedWorkspace
import io.github.amichne.kast.workspace.contract.SemanticReadLease
import io.github.amichne.kast.workspace.contract.SourceRoot
import io.github.amichne.kast.workspace.contract.SourceRootProvenance
import io.github.amichne.kast.workspace.contract.WorkspaceStateIdentity
import java.nio.file.Path

enum class KotlinFileSourceTextFailure {
    EMPTY,
    NUL_CHARACTER,
    MISSING_FINAL_NEWLINE,
}

/** Complete Kotlin file source admitted before semantic parsing by the IntelliJ boundary. */
@JvmInline
value class KotlinFileSourceText private constructor(
    val value: String,
) {
    companion object {
        /**
         * Proof transition: `String -> Refinement<KotlinFileSourceText,
         * KotlinFileSourceTextFailure>`.
         *
         * Establishes non-empty, NUL-free whole-file text with a final line terminator.
         * [KotlinFileSourceTextFailure] is the closed expected failure. Raw text may enter only at
         * the change-intent boundary and leave only at the authority-bound IntelliJ file-creation
         * boundary.
         */
        fun parse(raw: String): Refinement<KotlinFileSourceText, KotlinFileSourceTextFailure> =
            when {
                raw.isEmpty() -> Refinement.Rejected(KotlinFileSourceTextFailure.EMPTY)
                '\u0000' in raw -> Refinement.Rejected(KotlinFileSourceTextFailure.NUL_CHARACTER)
                !raw.endsWith('\n') -> Refinement.Rejected(
                    KotlinFileSourceTextFailure.MISSING_FINAL_NEWLINE,
                )
                else -> Refinement.Refined(KotlinFileSourceText(raw))
            }
    }
}

data class AddFileTargetObservation(
    val workspace: PublishedWorkspace,
    val file: SymbolDiscoveryFileIdentity.Workspace,
    val expectedOwner: GradleSourceSetOwner,
)

enum class AddFileTargetAdmissionFailure {
    NON_KOTLIN_FILE,
    ESCAPED_TARGET,
    AMBIGUOUS_OWNERSHIP,
    WRONG_OWNER,
    GENERATED_SOURCE_ROOT,
    UNKNOWN_SOURCE_ROOT,
}

/** Exact authored source-root location eligible for absent-file admission during apply. */
class CreatableKotlinFileTarget private constructor(
    val lease: SemanticReadLease,
    val workspaceState: WorkspaceStateIdentity,
    val file: SymbolDiscoveryFileIdentity.Workspace,
    val sourceRoot: SourceRoot,
) {
    companion object {
        /**
         * Proof transition: `AddFileTargetObservation -> Refinement<
         * CreatableKotlinFileTarget, AddFileTargetAdmissionFailure>`.
         *
         * Establishes a canonical `.kt` path strictly inside one uniquely owned authored Gradle
         * source root at the published generation. [AddFileTargetAdmissionFailure] closes escaped,
         * ambiguous, generated, unknown, or wrongly owned locations. Physical absence remains an
         * apply-boundary proof and raw path extraction is permitted only there.
         */
        fun admit(
            observation: AddFileTargetObservation,
        ): Refinement<CreatableKotlinFileTarget, AddFileTargetAdmissionFailure> {
            val target = Path.of(observation.file.path.value)
            if (!target.fileName.toString().endsWith(".kt")) {
                return Refinement.Rejected(AddFileTargetAdmissionFailure.NON_KOTLIN_FILE)
            }
            val workspace = observation.workspace
            val workspacePath = Path.of(workspace.root.value)
            if (target == workspacePath || !target.startsWith(workspacePath)) {
                return Refinement.Rejected(AddFileTargetAdmissionFailure.ESCAPED_TARGET)
            }
            val containing = workspace.sourceRoots.filter { root ->
                val sourcePath = workspacePath.resolve(root.location.value).normalize()
                target != sourcePath && target.startsWith(sourcePath)
            }.distinct()
            if (containing.isEmpty()) {
                return Refinement.Rejected(AddFileTargetAdmissionFailure.ESCAPED_TARGET)
            }
            if (containing.size > 1) {
                return Refinement.Rejected(AddFileTargetAdmissionFailure.AMBIGUOUS_OWNERSHIP)
            }
            val sourceRoot = containing.single()
            if (sourceRoot.owner != observation.expectedOwner) {
                return Refinement.Rejected(AddFileTargetAdmissionFailure.WRONG_OWNER)
            }
            when (sourceRoot.provenance) {
                SourceRootProvenance.Authored -> Unit
                SourceRootProvenance.Generated -> return Refinement.Rejected(
                    AddFileTargetAdmissionFailure.GENERATED_SOURCE_ROOT,
                )
                is SourceRootProvenance.Unknown -> return Refinement.Rejected(
                    AddFileTargetAdmissionFailure.UNKNOWN_SOURCE_ROOT,
                )
            }
            return Refinement.Refined(
                CreatableKotlinFileTarget(
                    workspace.readLease,
                    workspace.sourceState,
                    observation.file,
                    sourceRoot,
                ),
            )
        }
    }
}

data class AddFilePlanRequest(
    val target: CreatableKotlinFileTarget,
    val content: KotlinFileSourceText,
)

enum class AddFileObligation : ChangeVerificationObligation {
    TARGET_ABSENT_AT_G0,
    GENERATION_UNCHANGED,
    OWNER_AND_PROVENANCE_UNCHANGED,
    DECLARED_WRITE_SET_CLOSED,
    EXPECTED_POSTIMAGE_OBSERVED,
    FILE_IDENTITY_CREATED,
    UNRELATED_CODE_PRESERVED,
    COMPILER_DIAGNOSTICS_CLEAR,
    RESULT_GENERATION_PUBLISHED,
}

/** Pure deterministic AddFile plan. */
class AddFileChangePlan private constructor(
    override val planId: ChangePlanId,
    override val intent: ChangeIntent.AddFile,
    val target: CreatableKotlinFileTarget,
    override val writes: PlannedMutationWriteSet,
    val requiredVerification: List<AddFileObligation>,
) : ChangePlan {
    override val priorLease: SemanticReadLease = target.lease
    override val workspaceState: WorkspaceStateIdentity = target.workspaceState

    companion object {
        /**
         * Proof transition: `AddFilePlanRequest -> AddFileChangePlan`.
         *
         * Establishes a deterministic detached singleton plan from an absent precondition to the
         * exact whole-file Kotlin postimage and exhaustive AddFile obligations. There is no
         * expected failure because both request members already carry their invariants. Raw file
         * text may leave only after separate mutation admission.
         */
        fun issue(request: AddFilePlanRequest): AddFileChangePlan {
            val intent = ChangeIntent.AddFile(request.target, request.content)
            val writes = PlannedMutationWriteSet.singleton(
                PlannedMutationWrite(
                    request.target.file,
                    request.target.sourceRoot,
                    PlannedSourcePrecondition.Absent,
                    listOf(SourceTextMutation.CreateFile(request.content)),
                ),
            )
            val canonical = buildString {
                appendPlanningField("ADD_FILE")
                appendPlanningField(request.target.lease.workspaceRoot.value)
                appendPlanningField(request.target.lease.generation.value.toString())
                appendPlanningField(request.target.workspaceState.value)
                appendPlanningField(request.target.file.path.value)
                appendPlanningField(request.target.sourceRoot.owner.module.value)
                appendPlanningField(request.target.sourceRoot.owner.project.projectPath.value)
                appendPlanningField(request.target.sourceRoot.owner.sourceSet.value)
                appendPlanningField(request.content.value)
                AddFileObligation.entries.forEach { appendPlanningField(it.name) }
            }
            return AddFileChangePlan(
                ChangePlanId.fromCanonicalIdentity(canonical),
                intent,
                request.target,
                writes,
                AddFileObligation.entries,
            )
        }
    }
}

sealed interface AddFilePlanResult {
    data class Planned(
        val plan: AddFileChangePlan,
    ) : AddFilePlanResult
}

fun interface AddFilePlanOperations {
    /**
     * Proof transition: `AddFilePlanRequest -> AddFilePlanResult`.
     *
     * Planned carries one deterministic semantic file-creation plan. No source-write or platform
     * capability crosses this pure planning boundary.
     */
    fun plan(request: AddFilePlanRequest): AddFilePlanResult
}
