package io.github.amichne.kast.change.contract

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.symbol.contract.CompilerGroundedSymbolEvidence
import io.github.amichne.kast.symbol.contract.CompilerSymbolKind
import io.github.amichne.kast.symbol.contract.ExactDeclarationTextRange
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryConstraints
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryFileIdentity
import io.github.amichne.kast.symbol.contract.SymbolSearchScope
import io.github.amichne.kast.symbol.contract.SymbolSelector
import io.github.amichne.kast.symbol.contract.SymbolSelectorFingerprint
import io.github.amichne.kast.symbol.contract.symbolSelectorFingerprint
import io.github.amichne.kast.workspace.contract.GradleSourceRootEvidence
import io.github.amichne.kast.workspace.contract.LiveSemanticReadAuthority
import io.github.amichne.kast.workspace.contract.ModelOwnedSourceRoot
import io.github.amichne.kast.workspace.contract.SemanticReadIdentity
import io.github.amichne.kast.workspace.contract.SourceRoot
import io.github.amichne.kast.workspace.contract.SourceRootProvenance
import io.github.amichne.kast.workspace.contract.WorkspaceSearchScopeModel
import io.github.amichne.kast.workspace.contract.WorkspaceSourceContentHash
import io.github.amichne.kast.workspace.contract.WorkspaceSourceRootProvenance
import java.nio.file.Path

/** Compiler facts retained after the request-local selector and live authority have expired. */
class PlannedDeclarationIdentity
private constructor(
    val file: SymbolDiscoveryFileIdentity.Workspace,
    val evidence: CompilerGroundedSymbolEvidence,
    val fingerprint: SymbolSelectorFingerprint,
    val scope: SymbolSearchScope,
    val constraints: SymbolDiscoveryConstraints,
) {
    val range: ExactDeclarationTextRange
        get() = evidence.range

    val kind: CompilerSymbolKind
        get() = evidence.kind

    internal companion object {
        fun capture(selector: SymbolSelector, file: SymbolDiscoveryFileIdentity.Workspace) =
            PlannedDeclarationIdentity(
                file = file,
                evidence = CompilerGroundedSymbolEvidence.fromSelector(selector),
                fingerprint = selector.fingerprint,
                scope = selector.scope,
                constraints = selector.constraints,
            )

        fun restore(
            basis: LiveChangeBasis,
            evidence: CompilerGroundedSymbolEvidence,
            fingerprint: SymbolSelectorFingerprint,
            scope: SymbolSearchScope,
            constraints: SymbolDiscoveryConstraints,
        ): Refinement<PlannedDeclarationIdentity, LiveAddDeclarationPlanDecodeFailure> {
            val file =
                evidence.file as? SymbolDiscoveryFileIdentity.Workspace
                    ?: return Refinement.Rejected(LiveAddDeclarationPlanDecodeFailure.TARGET_MISMATCH)
            if (
                symbolSelectorFingerprint(
                    identity = SemanticReadIdentity.Live(basis.reference),
                    scope = scope,
                    evidence = evidence,
                    constraints = constraints,
                ) != fingerprint
            ) {
                return Refinement.Rejected(LiveAddDeclarationPlanDecodeFailure.TARGET_MISMATCH)
            }
            return Refinement.Refined(
                PlannedDeclarationIdentity(
                    file = file,
                    evidence = evidence,
                    fingerprint = fingerprint,
                    scope = scope,
                    constraints = constraints,
                )
            )
        }
    }
}

/** Request-local input. Its authority is consumed while the original owner's read is admitted. */
data class LiveAddDeclarationPlanRequest(
    val authority: LiveSemanticReadAuthority,
    val model: WorkspaceSearchScopeModel,
    val selector: SymbolSelector,
    val content: WorkspaceSourceContentHash,
    val intent: InstalledAddDeclarationIntent,
    val evidence: AddDeclarationPlanningEvidenceInput,
)

sealed interface LiveAddDeclarationPlanningFailure {
    data class Target(val failure: MutationTargetAdmissionFailure) : LiveAddDeclarationPlanningFailure

    data class Basis(val failure: LiveChangeBasisFailure) : LiveAddDeclarationPlanningFailure

    data class Evidence(val failure: ChangePlanningFailure) : LiveAddDeclarationPlanningFailure
}

internal data class LivePlanningTarget(
    val basis: LiveChangeBasis,
    val target: PlannedDeclarationIdentity,
    val content: WorkspaceSourceContentHash,
)

internal data class LivePlannedDeclaration(
    val declaration: AddDeclarationSourceText,
    val expectedDelta: ExpectedAddDeclarationDelta,
)

internal data class LivePlanningEvidence(
    val evidence: DurableAddDeclarationPlanningEvidence,
    val verificationScope: LiveAddDeclarationVerificationScope,
)

/** Complete detached live planning proof; it holds no selector, read lease, PSI object, or write permission. */
class AdmittedLiveAddDeclarationPlanInput
private constructor(
    private val planningTarget: LivePlanningTarget,
    private val plannedDeclaration: LivePlannedDeclaration,
    private val planningEvidence: LivePlanningEvidence,
    val sourceRoot: SourceRoot,
    val modelOwner: ModelOwnedSourceRoot,
) {
    val basis
        get() = planningTarget.basis

    val target
        get() = planningTarget.target

    val content
        get() = planningTarget.content

    val declaration
        get() = plannedDeclaration.declaration

    val expectedDelta
        get() = plannedDeclaration.expectedDelta

    val evidence
        get() = planningEvidence.evidence

    val verificationScope
        get() = planningEvidence.verificationScope

    companion object {
        /** Restores historical proof only; source effects still require fresh original-owner admission. */
        internal fun restore(
            planningTarget: LivePlanningTarget,
            declaration: LivePlannedDeclaration,
            evidence: LivePlanningEvidence,
        ): Refinement<AdmittedLiveAddDeclarationPlanInput, LiveAddDeclarationPlanDecodeFailure> {
            val basis = planningTarget.basis
            val target = planningTarget.target
            when (
                val restoredTarget =
                    PlannedDeclarationIdentity.restore(
                        basis = basis,
                        evidence = target.evidence,
                        fingerprint = target.fingerprint,
                        scope = target.scope,
                        constraints = target.constraints,
                    )
            ) {
                is Refinement.Refined -> Unit
                is Refinement.Rejected -> return restoredTarget
            }
            val owner =
                when (val owned = admitOwner(target.file, basis.model)) {
                    is Refinement.Refined -> owned.value
                    is Refinement.Rejected ->
                        return Refinement.Rejected(LiveAddDeclarationPlanDecodeFailure.TARGET_MISMATCH)
                }
            val sourceRoot =
                when (val result = owner.authoredRoot(basis)) {
                    is Refinement.Refined -> result.value
                    is Refinement.Rejected ->
                        return Refinement.Rejected(LiveAddDeclarationPlanDecodeFailure.TARGET_MISMATCH)
                }
            val restoredEvidence =
                when (val result = evidence.restoreDurable()) {
                    is Refinement.Refined -> result.value
                    is Refinement.Rejected ->
                        return Refinement.Rejected(LiveAddDeclarationPlanDecodeFailure.EVIDENCE_INCOMPLETE)
                }
            return Refinement.Refined(
                AdmittedLiveAddDeclarationPlanInput(
                    planningTarget = planningTarget,
                    plannedDeclaration = declaration,
                    planningEvidence = LivePlanningEvidence(restoredEvidence, evidence.verificationScope),
                    sourceRoot = sourceRoot,
                    modelOwner = owner,
                )
            )
        }

        fun admit(
            request: LiveAddDeclarationPlanRequest
        ): Refinement<AdmittedLiveAddDeclarationPlanInput, LiveAddDeclarationPlanningFailure> {
            if (request.selector.lease !== request.authority) {
                return targetRejected(MutationTargetAdmissionFailure.STALE_STATE)
            }
            val basis =
                when (val result = LiveChangeBasis.observe(request.authority.reference, request.model)) {
                    is Refinement.Refined -> result.value
                    is Refinement.Rejected ->
                        return Refinement.Rejected(LiveAddDeclarationPlanningFailure.Basis(result.failure))
                }
            val file =
                when (val selected = request.selector.file) {
                    is SymbolDiscoveryFileIdentity.Workspace -> selected
                    is SymbolDiscoveryFileIdentity.External ->
                        return targetRejected(MutationTargetAdmissionFailure.ESCAPED_TARGET)
                }
            val owner =
                when (val owned = admitOwner(file, request.model)) {
                    is Refinement.Refined -> owned.value
                    is Refinement.Rejected -> return targetRejected(owned.failure)
                }
            val sourceRoot =
                when (val result = owner.authoredRoot(basis)) {
                    is Refinement.Refined -> result.value
                    is Refinement.Rejected -> return targetRejected(MutationTargetAdmissionFailure.UNKNOWN_SOURCE_ROOT)
                }
            val evidence =
                when (val admitted = admitEvidence(request, file)) {
                    is Refinement.Refined -> admitted.value
                    is Refinement.Rejected -> return admitted
                }
            return Refinement.Refined(
                AdmittedLiveAddDeclarationPlanInput(
                    planningTarget =
                        LivePlanningTarget(
                            basis,
                            PlannedDeclarationIdentity.capture(request.selector, file),
                            request.content,
                        ),
                    plannedDeclaration =
                        LivePlannedDeclaration(request.intent.declaration, request.intent.expectedDelta),
                    planningEvidence = evidence,
                    sourceRoot = sourceRoot,
                    modelOwner = owner,
                )
            )
        }

        private fun admitEvidence(
            request: LiveAddDeclarationPlanRequest,
            file: SymbolDiscoveryFileIdentity.Workspace,
        ): Refinement<LivePlanningEvidence, LiveAddDeclarationPlanningFailure> {
            val completeEvidence =
                when (val result = CompleteChangePlanningEvidence.admit(request.selector, file, request.evidence)) {
                    is Refinement.Refined -> result.value
                    is Refinement.Rejected ->
                        return Refinement.Rejected(LiveAddDeclarationPlanningFailure.Evidence(result.failure))
                }
            val verificationScope =
                when (val captured = LiveAddDeclarationVerificationScope.capture(completeEvidence)) {
                    is Refinement.Refined -> captured.value
                    is Refinement.Rejected ->
                        return Refinement.Rejected(LiveAddDeclarationPlanningFailure.Evidence(captured.failure))
                }
            return Refinement.Refined(
                LivePlanningEvidence(DurableAddDeclarationPlanningEvidence.from(completeEvidence), verificationScope)
            )
        }

        private fun LivePlanningEvidence.restoreDurable() =
            DurableAddDeclarationPlanningEvidence.restore(
                relations = evidence.relations,
                traversals = evidence.traversals,
                diagnostics = evidence.diagnostics,
                fingerprint = evidence.fingerprint,
                relationDigestSemantics = evidence.relationDigestSemantics,
            )

        private fun admitOwner(
            file: SymbolDiscoveryFileIdentity.Workspace,
            model: WorkspaceSearchScopeModel,
        ): Refinement<ModelOwnedSourceRoot, MutationTargetAdmissionFailure> {
            val targetPath = Path.of(file.path.value)
            val owners =
                model.sourceRoots.filter { owner ->
                    val sourcePath = Path.of(owner.sourceRoot.value)
                    targetPath != sourcePath && targetPath.startsWith(sourcePath)
                }
            if (owners.isEmpty()) return Refinement.Rejected(MutationTargetAdmissionFailure.ESCAPED_TARGET)
            if (owners.size != 1) return Refinement.Rejected(MutationTargetAdmissionFailure.AMBIGUOUS_OWNERSHIP)
            val owner = owners.single()
            when (owner.provenance) {
                WorkspaceSourceRootProvenance.AUTHORED -> Unit
                WorkspaceSourceRootProvenance.GENERATED ->
                    return Refinement.Rejected(MutationTargetAdmissionFailure.GENERATED_SOURCE_ROOT)
                WorkspaceSourceRootProvenance.UNKNOWN ->
                    return Refinement.Rejected(MutationTargetAdmissionFailure.UNKNOWN_SOURCE_ROOT)
            }
            return Refinement.Refined(owner)
        }

        private fun targetRejected(failure: MutationTargetAdmissionFailure) =
            Refinement.Rejected(LiveAddDeclarationPlanningFailure.Target(failure))

        private fun ModelOwnedSourceRoot.authoredRoot(basis: LiveChangeBasis) =
            SourceRoot.admit(
                GradleSourceRootEvidence(
                    ideaModuleName = module.value,
                    workspaceRelativeBuildRoot = project.buildRoot.value,
                    gradleProjectPath = project.projectPath.value,
                    sourceSetName = sourceSet.value,
                    workspaceRelativeSourceRoot =
                        Path.of(basis.reference.workspaceRoot.value)
                            .relativize(Path.of(sourceRoot.value))
                            .toString()
                            .ifEmpty { "." },
                    provenance = SourceRootProvenance.Authored,
                )
            )
    }
}
