package io.github.amichne.kast.change.journal.contract

import io.github.amichne.kast.change.contract.AddDeclarationKind
import io.github.amichne.kast.change.contract.AddDeclarationMutationProgress
import io.github.amichne.kast.change.contract.AddDeclarationPlanningEvidence
import io.github.amichne.kast.change.contract.AddDeclarationRevalidationObservation
import io.github.amichne.kast.change.contract.AddDeclarationSourceProvenance
import io.github.amichne.kast.change.contract.AddDeclarationSourceOwner
import io.github.amichne.kast.change.contract.AddDeclarationTargetCapability
import io.github.amichne.kast.change.contract.AddDeclarationTargetWritability
import io.github.amichne.kast.change.contract.AddDeclarationVerificationContract
import io.github.amichne.kast.change.contract.DeclaredWriteSet
import io.github.amichne.kast.change.contract.DetachedCompilerEvidence
import io.github.amichne.kast.change.contract.ExactFileContentProof
import io.github.amichne.kast.change.contract.ExpectedAddDeclarationDelta
import io.github.amichne.kast.change.contract.ExpectedFileProof
import io.github.amichne.kast.change.contract.PlannedAddDeclaration
import io.github.amichne.kast.change.contract.RawAddDeclarationPlanRequest
import io.github.amichne.kast.change.contract.RevalidatedAddDeclaration
import io.github.amichne.kast.kernel.EvidenceGeneration
import io.github.amichne.kast.kernel.Refinement
import java.security.MessageDigest
import java.util.Base64
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertInstanceOf

class AddDeclarationPlanLifecycleTest {
    @Test
    fun `approved revalidation prepares exact recovery at one next version before mutation`() {
        val plan = plan()
        val awaiting = PersistedAddDeclarationPlan.awaitingApproval(plan)
        val approved = PersistedAddDeclarationPlan.approve(
            awaiting,
            approval(awaiting),
        ).refined()
        val command = PrepareAddDeclarationRecovery.admit(
            revalidated = revalidated(plan),
            expectedVersion = approved.version,
        ).refined()

        val prepared = RecoveryPreparedAddDeclaration.prepare(approved, command).refined()

        assertEquals(AddDeclarationPlanStage.RECOVERY_PREPARED, prepared.stage)
        assertEquals(approved.version, prepared.priorVersion)
        assertEquals(approved.version.next().refined(), prepared.version)
        assertEquals(plan.expectedFile.preimage, prepared.recovery.beforeImage)
        assertEquals(AddDeclarationMutationProgress.NOT_BEGUN, prepared.mutationProgress)
    }

    @Test
    fun `recovery preparation rejects a stale approved version before mutation`() {
        val plan = plan()
        val awaiting = PersistedAddDeclarationPlan.awaitingApproval(plan)
        val approved = PersistedAddDeclarationPlan.approve(
            awaiting,
            approval(awaiting),
        ).refined()
        val stale = PrepareAddDeclarationRecovery.admit(
            revalidated = revalidated(plan),
            expectedVersion = awaiting.version,
        ).refined()

        val rejection = assertInstanceOf<
            Refinement.Rejected<AddDeclarationRecoveryPreparationRejection>,
            >(RecoveryPreparedAddDeclaration.prepare(approved, stale)).failure

        assertEquals(
            AddDeclarationRecoveryPreparationFailure.PRIOR_VERSION_MISMATCH,
            rejection.failure,
        )
        assertEquals(AddDeclarationMutationProgress.NOT_BEGUN, rejection.mutationProgress)
    }

    @Test
    fun `explicit PlanId-bound approval advances exactly one prior state version`() {
        val plan = plan()
        val awaiting = PersistedAddDeclarationPlan.awaitingApproval(plan)
        val evidence = RawAddDeclarationPlanApprovalEvidence(
            planId = plan.planId.value,
            approvedBy = "agent:operator",
            evidenceSha256 = "a".repeat(64),
        ).refine().refined()
        val command = ApproveAddDeclarationPlan.admit(
            planId = plan.planId,
            expectedVersion = awaiting.version,
            evidence = evidence,
        ).refined()
        val approved = PersistedAddDeclarationPlan.approve(awaiting, command).refined()

        assertEquals(AddDeclarationPlanStage.AWAITING_APPROVAL, awaiting.stage)
        assertEquals(AddDeclarationPlanStage.APPROVED, approved.stage)
        assertEquals(awaiting.version.next().refined(), approved.version)
        assertEquals(evidence, approved.approvalEvidence)
    }

    @Test
    fun `approval evidence for another plan is a closed rejection`() {
        val awaiting = PersistedAddDeclarationPlan.awaitingApproval(plan())
        val evidence = RawAddDeclarationPlanApprovalEvidence(
            planId = "0".repeat(64),
            approvedBy = "agent:operator",
            evidenceSha256 = "b".repeat(64),
        ).refine().refined()

        assertEquals(
            AddDeclarationPlanApprovalFailure.PLAN_ID_MISMATCH,
            assertInstanceOf<Refinement.Rejected<AddDeclarationPlanApprovalFailure>>(
                ApproveAddDeclarationPlan.admit(
                    planId = awaiting.plan.planId,
                    expectedVersion = awaiting.version,
                    evidence = evidence,
                ),
            ).failure,
        )
    }

    @Test
    fun `approval command rejects an exhausted prior version`() {
        val plan = plan()
        val evidence = RawAddDeclarationPlanApprovalEvidence(
            planId = plan.planId.value,
            approvedBy = "agent:operator",
            evidenceSha256 = "c".repeat(64),
        ).refine().refined()

        assertEquals(
            AddDeclarationPlanApprovalFailure.VERSION_EXHAUSTED,
            assertInstanceOf<Refinement.Rejected<AddDeclarationPlanApprovalFailure>>(
                ApproveAddDeclarationPlan.admit(
                    planId = plan.planId,
                    expectedVersion = AddDeclarationPlanStateVersion.parse(Long.MAX_VALUE).refined(),
                    evidence = evidence,
                ),
            ).failure,
        )
    }

    private fun approval(
        awaiting: PersistedAddDeclarationPlan.AwaitingApproval,
    ): ApproveAddDeclarationPlan {
        val evidence = RawAddDeclarationPlanApprovalEvidence(
            planId = awaiting.plan.planId.value,
            approvedBy = "agent:operator",
            evidenceSha256 = "a".repeat(64),
        ).refine().refined()
        return ApproveAddDeclarationPlan.admit(
            planId = awaiting.plan.planId,
            expectedVersion = awaiting.version,
            evidence = evidence,
        ).refined()
    }

    private fun revalidated(plan: PlannedAddDeclaration): RevalidatedAddDeclaration {
        val observation = AddDeclarationRevalidationObservation.observe(
            generation = EvidenceGeneration.parse(plan.generation.value).refined(),
            target = plan.target,
            currentFile = plan.expectedFile.preimage,
            provenance = AddDeclarationSourceProvenance.AUTHORED,
            writability = AddDeclarationTargetWritability.WRITABLE,
        ).refined()
        return RevalidatedAddDeclaration.admit(plan, observation).refined()
    }

    private fun plan(): PlannedAddDeclaration {
        val before = "package sample\n".toByteArray()
        val after = "package sample\n\nfun added(): Int = 1\n".toByteArray()
        val intent = RawAddDeclarationPlanRequest(
            workspaceRoot = ROOT,
            targetPath = TARGET,
            expectedCurrentSha256 = hash(before),
            proposedDeclaration = "fun added(): Int = 1",
        ).refine().refined()
        val owner = AddDeclarationSourceOwner.admit(
            sourceRoot = "$ROOT/indexer/src/main/kotlin",
            ideaModuleName = "kast.indexer.main",
            gradleBuildRoot = ROOT,
            gradleProjectPath = ":indexer",
            sourceSetName = "main",
        ).refined()
        val target = AddDeclarationTargetCapability.admit(intent, owner).refined()
        val generation = EvidenceGeneration.parse(7).refined()
        val evidence = AddDeclarationPlanningEvidence.admit(
            intent = intent,
            generation = generation,
            target = target,
            expectedFile = ExpectedFileProof.admit(
                target,
                ExactFileContentProof.admit(hash(before), Base64.getEncoder().encodeToString(before)).refined(),
                ExactFileContentProof.admit(hash(after), Base64.getEncoder().encodeToString(after)).refined(),
            ).refined(),
            declaredWriteSet = DeclaredWriteSet.admit(listOf(target.targetPath)).refined(),
            expectedSemanticDelta = ExpectedAddDeclarationDelta.admit(
                packageName = "sample",
                declarationName = "added",
                declarationKind = AddDeclarationKind.FUNCTION,
                collisionSignature = "2".repeat(64),
            ).refined(),
            verification = AddDeclarationVerificationContract.forGeneration(generation),
            compilerEvidence = DetachedCompilerEvidence.admit("{\"complete\":true}").refined(),
        ).refined()
        return PlannedAddDeclaration.issue(evidence)
    }

    private fun hash(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString(separator = "") { byte -> "%02x".format(byte) }

    private fun <T, F> Refinement<T, F>.refined(): T =
        assertInstanceOf<Refinement.Refined<T>>(this).value

    private companion object {
        const val ROOT = "/workspace/kast"
        const val TARGET = "$ROOT/indexer/src/main/kotlin/sample/Target.kt"
    }
}
