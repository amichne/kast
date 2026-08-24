package io.github.amichne.kast.change.contract

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.symbol.contract.ExactDeclarationTextRange
import io.github.amichne.kast.symbol.contract.CompilerSymbolKind
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryFileIdentity
import io.github.amichne.kast.workspace.contract.SemanticReadLease
import io.github.amichne.kast.workspace.contract.WorkspaceSourceContentHash
import io.github.amichne.kast.workspace.contract.WorkspaceStateIdentity

/** Strong whole-request input whose required semantic evidence is complete and exact. */
class AdmittedAddDeclarationPlanInput private constructor(
    val target: EditableMutationTarget,
    val declaration: AddDeclarationSourceText,
    val expectedSemanticDelta: ExpectedAddDeclarationDelta,
    val evidence: CompleteAddDeclarationPlanningEvidence,
) {
    companion object {
        /**
         * Proof transition: `AddDeclarationPlanRequest -> Refinement<
         * AdmittedAddDeclarationPlanInput, AddDeclarationPlanningFailure>`.
         *
         * Establishes an exact editable target, canonical declaration, expected semantic delta,
         * and complete normalized relation, traversal, and diagnostic evidence for the same
         * target lease. [AddDeclarationPlanningFailure] is the closed expected failure. Raw source
         * text and semantic observations must already have crossed their typed boundaries; this
         * pure transition permits no raw extraction.
         */
        fun admit(
            request: AddDeclarationPlanRequest,
        ): Refinement<AdmittedAddDeclarationPlanInput, AddDeclarationPlanningFailure> = when (
            val evidence = CompleteAddDeclarationPlanningEvidence.admit(request)
        ) {
            is Refinement.Refined -> Refinement.Refined(
                AdmittedAddDeclarationPlanInput(
                    request.target,
                    request.declaration,
                    request.expectedSemanticDelta,
                    evidence.value,
                ),
            )
            is Refinement.Rejected -> evidence
        }
    }
}

/** Exact immutable source preimage and published state captured by an admitted target. */
class AddDeclarationSourceSnapshot private constructor(
    val lease: SemanticReadLease,
    val workspaceState: WorkspaceStateIdentity,
    val file: SymbolDiscoveryFileIdentity.Workspace,
    val content: WorkspaceSourceContentHash,
) {
    companion object {
        internal fun from(target: EditableMutationTarget): AddDeclarationSourceSnapshot =
            AddDeclarationSourceSnapshot(
                target.lease,
                target.workspaceState,
                target.file,
                target.content,
            )
    }
}

/** Closed planned edit families; KCS-015 permits exactly one declaration insertion. */
sealed interface AddDeclarationPlannedEdit {
    @ConsistentCopyVisibility
    data class InsertAfterDeclaration internal constructor(
        val file: SymbolDiscoveryFileIdentity.Workspace,
        val anchor: ExactDeclarationTextRange,
        val declaration: AddDeclarationSourceText,
    ) : AddDeclarationPlannedEdit

    @ConsistentCopyVisibility
    data class InsertIntoClassBody internal constructor(
        val file: SymbolDiscoveryFileIdentity.Workspace,
        val anchor: ExactDeclarationTextRange,
        val declaration: AddDeclarationSourceText,
    ) : AddDeclarationPlannedEdit
}

/**
 * Pure deterministic AddDeclaration plan.
 *
 * This value contains no write method or effect capability. Later admission must bind it to exact
 * current repository state before any physical adapter can act.
 */
class AddDeclarationChangePlan private constructor(
    override val planId: AddDeclarationPlanId,
    val sourceSnapshot: AddDeclarationSourceSnapshot,
    val target: EditableMutationTarget,
    val evidence: CompleteAddDeclarationPlanningEvidence,
    val plannedEdits: List<AddDeclarationPlannedEdit>,
    val expectedSemanticDelta: ExpectedAddDeclarationDelta,
    val requiredVerification: AddDeclarationVerificationContract,
) : ChangePlan {
    override val intent: ChangeIntent = ChangeIntent.AddDeclaration(
        target,
        when (val edit = plannedEdits.single()) {
            is AddDeclarationPlannedEdit.InsertAfterDeclaration -> edit.declaration
            is AddDeclarationPlannedEdit.InsertIntoClassBody -> edit.declaration
        },
        expectedSemanticDelta,
    )
    override val priorLease: SemanticReadLease
        get() = sourceSnapshot.lease
    override val workspaceState: WorkspaceStateIdentity
        get() = sourceSnapshot.workspaceState
    override val writes: PlannedMutationWriteSet = PlannedMutationWriteSet.singleton(
        PlannedMutationWrite(
            target.file,
            target.sourceRoot,
            PlannedSourcePrecondition.Existing(sourceSnapshot.content),
            listOf(
                when (val edit = plannedEdits.single()) {
                    is AddDeclarationPlannedEdit.InsertAfterDeclaration ->
                        SourceTextMutation.InsertAfterDeclaration(edit.anchor, edit.declaration)
                    is AddDeclarationPlannedEdit.InsertIntoClassBody ->
                        SourceTextMutation.InsertIntoClassBody(edit.anchor, edit.declaration)
                },
            ),
        ),
    )

    companion object {
        /**
         * Proof transition: `AdmittedAddDeclarationPlanInput -> AddDeclarationChangePlan`.
         *
         * Establishes a detached plan whose identity covers the exact source snapshot, target,
         * canonical insertion, normalized complete evidence, expected semantic delta, and every
         * closed verification obligation. There is no expected failure because the input already
         * carries complete planning proof. Raw declaration extraction is permitted only at a
         * later mutation adapter after separate mutation-authority admission.
         */
        fun issue(
            input: AdmittedAddDeclarationPlanInput,
        ): AddDeclarationChangePlan {
            val verification = AddDeclarationVerificationContract.forGeneration(
                input.target.lease.generation,
            )
            val edit = if (input.target.selector.kind == CompilerSymbolKind.CLASSLIKE) {
                AddDeclarationPlannedEdit.InsertIntoClassBody(
                    input.target.file,
                    input.target.range,
                    input.declaration,
                )
            } else {
                AddDeclarationPlannedEdit.InsertAfterDeclaration(
                    input.target.file,
                    input.target.range,
                    input.declaration,
                )
            }
            val canonical = buildString {
                appendTarget(input.target)
                appendPlanningField(input.declaration.value)
                appendPlanningField(input.expectedSemanticDelta.packageName)
                appendPlanningField(input.expectedSemanticDelta.declarationName)
                appendPlanningField(input.expectedSemanticDelta.declarationKind.name)
                appendPlanningField(input.evidence.fingerprint.value)
                verification.obligations.forEach { obligation ->
                    appendPlanningField(obligation.name)
                }
            }
            return AddDeclarationChangePlan(
                planId = AddDeclarationPlanId.fromCanonicalIdentity(canonical),
                sourceSnapshot = AddDeclarationSourceSnapshot.from(input.target),
                target = input.target,
                evidence = input.evidence,
                plannedEdits = listOf(edit),
                expectedSemanticDelta = input.expectedSemanticDelta,
                requiredVerification = verification,
            )
        }

        private fun StringBuilder.appendTarget(target: EditableMutationTarget) {
            appendPlanningField(target.lease.workspaceRoot.value)
            appendPlanningField(target.lease.generation.value.toString())
            appendPlanningField(target.workspaceState.value)
            appendPlanningField(target.file.path.value)
            appendPlanningField(target.content.value)
            appendPlanningField(target.sourceRoot.location.value)
            appendPlanningField(target.owner.module.value)
            appendPlanningField(target.owner.project.buildRoot.value)
            appendPlanningField(target.owner.project.projectPath.value)
            appendPlanningField(target.owner.sourceSet.value)
            appendPlanningField(target.selector.fingerprint.value)
            appendPlanningField(target.range.startInclusive.toString())
            appendPlanningField(target.range.endExclusive.toString())
        }
    }
}

sealed interface AddDeclarationPlanResult {
    data class Planned(
        val plan: AddDeclarationChangePlan,
    ) : AddDeclarationPlanResult

    data class Rejected(
        val failure: AddDeclarationPlanningFailure,
    ) : AddDeclarationPlanResult
}

/** Public pure `change.plan` boundary for the AddDeclaration intent. */
fun interface AddDeclarationPlanOperations {
    /**
     * Proof transition: `AddDeclarationPlanRequest -> AddDeclarationPlanResult`.
     *
     * Produces a deterministic plan only when the target and all required detached evidence refine
     * to [AdmittedAddDeclarationPlanInput]. [AddDeclarationPlanningFailure] is the closed expected
     * failure. Raw source and compiler values remain outside this pure operation boundary.
     */
    fun plan(request: AddDeclarationPlanRequest): AddDeclarationPlanResult
}
