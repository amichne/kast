package io.github.amichne.kast.change.contract

import io.github.amichne.kast.kernel.EvidenceGeneration
import io.github.amichne.kast.kernel.Refinement
import kotlinx.serialization.Serializable

enum class AddDeclarationPlanningEvidenceFailure {
    INTENT_TARGET_MISMATCH,
    EXPECTED_FILE_TARGET_MISMATCH,
    WRITE_SET_NOT_EXACT_TARGET,
    VERIFICATION_GENERATION_MISMATCH,
    COMPILER_CONTEXT_GENERATION_MISMATCH,
    COMPILER_CONTEXT_TARGET_MISSING,
    COMPILER_CONTEXT_TARGET_HASH_MISMATCH,
}

@Serializable
@ConsistentCopyVisibility
data class AddDeclarationPlanningEvidence private constructor(
    val intent: AddDeclarationIntent,
    val generation: AddDeclarationGeneration,
    val target: AddDeclarationTargetCapability,
    val expectedFile: ExpectedFileProof,
    val declaredWriteSet: DeclaredWriteSet,
    val expectedSemanticDelta: ExpectedAddDeclarationDelta,
    val verification: AddDeclarationVerificationContract,
    val compilerContext: ExpectedAddDeclarationCompilerContext,
    val compilerEvidence: DetachedCompilerEvidence,
) {
    companion object {
        /**
         * Proof transition:
         * Detached planning facts to Refinement of AddDeclarationPlanningEvidence or
         * AddDeclarationPlanningEvidenceFailure.
         *
         * Establishes one coherent add-declaration evidence aggregate for the same intent, target,
         * singleton write set, and G0 verification contract.
         * AddDeclarationPlanningEvidenceFailure is the closed expected failure. Raw evidence is
         * extracted only by the IntelliJ planning adapter.
         */
        fun admit(
            intent: AddDeclarationIntent,
            generation: EvidenceGeneration,
            target: AddDeclarationTargetCapability,
            expectedFile: ExpectedFileProof,
            declaredWriteSet: DeclaredWriteSet,
            expectedSemanticDelta: ExpectedAddDeclarationDelta,
            verification: AddDeclarationVerificationContract,
            compilerContext: ExpectedAddDeclarationCompilerContext,
            compilerEvidence: DetachedCompilerEvidence,
        ): Refinement<AddDeclarationPlanningEvidence, AddDeclarationPlanningEvidenceFailure> {
            if (
                target.workspaceRoot != intent.workspaceRoot ||
                target.targetPath != intent.targetPath ||
                target.expectedCurrentSha256 != intent.expectedCurrentSha256
            ) {
                return Refinement.Rejected(AddDeclarationPlanningEvidenceFailure.INTENT_TARGET_MISMATCH)
            }
            if (expectedFile.targetPath != target.targetPath) {
                return Refinement.Rejected(AddDeclarationPlanningEvidenceFailure.EXPECTED_FILE_TARGET_MISMATCH)
            }
            if (declaredWriteSet.paths != listOf(target.targetPath)) {
                return Refinement.Rejected(AddDeclarationPlanningEvidenceFailure.WRITE_SET_NOT_EXACT_TARGET)
            }
            val exactGeneration = AddDeclarationGeneration.of(generation)
            if (verification.requiredGeneration != exactGeneration) {
                return Refinement.Rejected(
                    AddDeclarationPlanningEvidenceFailure.VERIFICATION_GENERATION_MISMATCH,
                )
            }
            if (compilerContext.generation != exactGeneration) {
                return Refinement.Rejected(
                    AddDeclarationPlanningEvidenceFailure.COMPILER_CONTEXT_GENERATION_MISMATCH,
                )
            }
            val targetContext = compilerContext.contextFiles.singleOrNull {
                it.path == target.targetPath.value
            } ?: return Refinement.Rejected(
                AddDeclarationPlanningEvidenceFailure.COMPILER_CONTEXT_TARGET_MISSING,
            )
            if (targetContext.sha256 != expectedFile.preimage.sha256) {
                return Refinement.Rejected(
                    AddDeclarationPlanningEvidenceFailure.COMPILER_CONTEXT_TARGET_HASH_MISMATCH,
                )
            }
            return Refinement.Refined(
                AddDeclarationPlanningEvidence(
                    intent = intent,
                    generation = exactGeneration,
                    target = target,
                    expectedFile = expectedFile,
                    declaredWriteSet = declaredWriteSet,
                    expectedSemanticDelta = expectedSemanticDelta,
                    verification = verification,
                    compilerContext = compilerContext,
                    compilerEvidence = compilerEvidence,
                ),
            )
        }
    }
}

@Serializable
@JvmInline
value class ChangePlanId private constructor(val value: String) {
    companion object {
        /**
         * Proof transition: `String -> Refinement<ChangePlanId, ChangePlanIdFailure>`.
         *
         * Establishes an opaque canonical lowercase SHA-256 plan identity. The closed expected
         * failure is `ChangePlanIdFailure.INVALID`; raw extraction is permitted only at
         * transport and durable-journal boundaries.
         */
        fun parse(
            raw: String,
        ): Refinement<ChangePlanId, ChangePlanIdFailure> =
            if (Regex("[0-9a-f]{64}").matches(raw)) {
                Refinement.Refined(ChangePlanId(raw))
            } else {
                Refinement.Rejected(ChangePlanIdFailure.INVALID)
            }

        internal fun fromCanonicalIdentity(value: String): ChangePlanId =
            ChangePlanId(sha256Hex(value.toByteArray()))
    }
}

enum class ChangePlanIdFailure {
    INVALID,
}

typealias AddDeclarationPlanId = ChangePlanId
typealias AddDeclarationPlanIdFailure = ChangePlanIdFailure

@Serializable
@ConsistentCopyVisibility
data class PlannedAddDeclaration private constructor(
    val planId: AddDeclarationPlanId,
    val intent: AddDeclarationIntent,
    val generation: AddDeclarationGeneration,
    val target: AddDeclarationTargetCapability,
    val expectedFile: ExpectedFileProof,
    val declaredWriteSet: DeclaredWriteSet,
    val expectedSemanticDelta: ExpectedAddDeclarationDelta,
    val verification: AddDeclarationVerificationContract,
    val compilerContext: ExpectedAddDeclarationCompilerContext,
    val compilerEvidence: DetachedCompilerEvidence,
) {
    companion object {
        /**
         * Proof transition:
         * AddDeclarationPlanningEvidence to PlannedAddDeclaration.
         *
         * Establishes a detached operation-specific plan with canonical identity derived from every
         * G0 input and proof fact. There is no expected failure because the evidence aggregate
         * already carries all invariants. Raw compiler JSON may be extracted only at the named
         * compatibility transport boundary.
         */
        fun issue(evidence: AddDeclarationPlanningEvidence): PlannedAddDeclaration {
            val material = PlanIdentityMaterial.from(evidence)
            return PlannedAddDeclaration(
                planId = AddDeclarationPlanId.fromCanonicalIdentity(
                    AddDeclarationPlanCodec.encodeIdentity(material),
                ),
                intent = evidence.intent,
                generation = evidence.generation,
                target = evidence.target,
                expectedFile = evidence.expectedFile,
                declaredWriteSet = evidence.declaredWriteSet,
                expectedSemanticDelta = evidence.expectedSemanticDelta,
                verification = evidence.verification,
                compilerContext = evidence.compilerContext,
                compilerEvidence = evidence.compilerEvidence,
            )
        }
    }

    internal fun identityMaterial(): PlanIdentityMaterial = PlanIdentityMaterial(
        intent = intent,
        generation = generation,
        target = target,
        expectedFile = expectedFile,
        declaredWriteSet = declaredWriteSet,
        expectedSemanticDelta = expectedSemanticDelta,
        verification = verification,
        compilerContext = compilerContext,
        compilerEvidence = compilerEvidence,
    )
}

@Serializable
internal data class PlanIdentityMaterial(
    val intent: AddDeclarationIntent,
    val generation: AddDeclarationGeneration,
    val target: AddDeclarationTargetCapability,
    val expectedFile: ExpectedFileProof,
    val declaredWriteSet: DeclaredWriteSet,
    val expectedSemanticDelta: ExpectedAddDeclarationDelta,
    val verification: AddDeclarationVerificationContract,
    val compilerContext: ExpectedAddDeclarationCompilerContext,
    val compilerEvidence: DetachedCompilerEvidence,
) {
    companion object {
        fun from(evidence: AddDeclarationPlanningEvidence): PlanIdentityMaterial =
            PlanIdentityMaterial(
                intent = evidence.intent,
                generation = evidence.generation,
                target = evidence.target,
                expectedFile = evidence.expectedFile,
                declaredWriteSet = evidence.declaredWriteSet,
                expectedSemanticDelta = evidence.expectedSemanticDelta,
                verification = evidence.verification,
                compilerContext = evidence.compilerContext,
                compilerEvidence = evidence.compilerEvidence,
            )
    }
}
