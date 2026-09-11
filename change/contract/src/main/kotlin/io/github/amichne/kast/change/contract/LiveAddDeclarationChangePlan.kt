package io.github.amichne.kast.change.contract

import io.github.amichne.kast.kernel.Refinement

enum class LiveAddDeclarationPlanDecodeFailure {
    MALFORMED,
    VERSION_UNSUPPORTED,
    TARGET_MISMATCH,
    IDENTITY_MISMATCH,
    EVIDENCE_INCOMPLETE,
}

/** Immutable live plan. Historical evidence is deliberately separate from the legacy executable plan family. */
class LiveAddDeclarationChangePlan
private constructor(
    private val input: AdmittedLiveAddDeclarationPlanInput,
    val plannedEdit: AddDeclarationPlannedEdit,
    val writes: PlannedMutationWriteSet,
) {
    val planId = AddDeclarationPlanId.fromCanonicalIdentity(input.canonicalIdentity())
    val basis = ChangePlanningBasis.Live(input.basis)
    val target
        get() = input.target

    val content
        get() = input.content

    val declaration
        get() = input.declaration

    val expectedSemanticDelta
        get() = input.expectedDelta

    val evidence
        get() = input.evidence

    val verificationScope
        get() = input.verificationScope

    val requiredVerification: LiveAddDeclarationVerificationContract = LiveAddDeclarationVerificationContract.required

    companion object {
        internal fun restore(
            planId: AddDeclarationPlanId,
            input: AdmittedLiveAddDeclarationPlanInput,
        ): Refinement<LiveAddDeclarationChangePlan, LiveAddDeclarationPlanDecodeFailure> {
            val restored = issue(input)
            return if (restored.planId == planId) Refinement.Refined(restored)
            else Refinement.Rejected(LiveAddDeclarationPlanDecodeFailure.IDENTITY_MISMATCH)
        }

        fun issue(input: AdmittedLiveAddDeclarationPlanInput): LiveAddDeclarationChangePlan {
            val edit =
                planAddDeclarationInsertion(
                    file = input.target.file,
                    range = input.target.range,
                    kind = input.target.kind,
                    declaration = input.declaration,
                )
            val writes =
                PlannedMutationWriteSet.singleton(
                    PlannedMutationWrite(
                        source = input.target.file,
                        sourceRoot = input.sourceRoot,
                        precondition = PlannedSourcePrecondition.Existing(input.content),
                        mutations = listOf(edit.sourceMutation()),
                    )
                )
            return LiveAddDeclarationChangePlan(
                input = input,
                plannedEdit = edit,
                writes = writes,
            )
        }

        private fun AdmittedLiveAddDeclarationPlanInput.canonicalIdentity(): String = buildString {
            appendPlanningField("LIVE_ADD_DECLARATION_V1")
            appendPlanningField(basis.reference.workspaceRoot.value)
            appendPlanningField(basis.reference.version.toString())
            appendPlanningField(basis.reference.host.value.toString())
            appendPlanningField(basis.reference.epoch.value.toString())
            appendPlanningField(basis.reference.contentView.name)
            appendPlanningField(basis.model.sourceRoots.size.toString())
            basis.model.sourceRoots.forEach { root ->
                appendPlanningField(root.module.value)
                appendPlanningField(root.project.buildRoot.value)
                appendPlanningField(root.project.projectPath.value)
                appendPlanningField(root.sourceSet.value)
                appendPlanningField(root.sourceRoot.value)
                appendPlanningField(root.sourceKind.name)
                appendPlanningField(root.provenance.name)
            }
            appendPlanningField(target.file.path.value)
            appendPlanningField(target.fingerprint.value)
            appendPlanningField(content.value)
            appendPlanningField(declaration.value)
            appendPlanningField(expectedDelta.packageName)
            appendPlanningField(expectedDelta.declarationName)
            appendPlanningField(expectedDelta.declarationKind.name)
            appendPlanningField(evidence.fingerprint.value)
            appendPlanningField(LiveVerificationScopeCodec.canonicalIdentity(verificationScope))
            appendPlanningField(evidence.relationDigestSemantics.name)
            appendPlanningField(evidence.relations.size.toString())
            evidence.relations.forEach { relation ->
                appendPlanningField(relation.meaning.name)
                appendPlanningField(relation.stableDigest.value)
            }
            LiveAddDeclarationVerificationContract.required.semanticObligations.forEach { appendPlanningField(it.name) }
            LiveAddDeclarationVerificationContract.required.liveObligations.forEach { appendPlanningField(it.name) }
        }
    }
}

sealed interface LiveAddDeclarationPlanResult {
    data class Planned(val plan: LiveAddDeclarationChangePlan) : LiveAddDeclarationPlanResult

    data class Rejected(val failure: LiveAddDeclarationPlanningFailure) : LiveAddDeclarationPlanResult
}
