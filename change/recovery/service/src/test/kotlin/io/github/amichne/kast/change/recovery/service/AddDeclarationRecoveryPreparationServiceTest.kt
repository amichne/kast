package io.github.amichne.kast.change.recovery.service

import io.github.amichne.kast.change.contract.AddDeclarationKind
import io.github.amichne.kast.change.contract.AddDeclarationPlanId
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
import io.github.amichne.kast.change.contract.ExpectedAddDeclarationCompilerContext
import io.github.amichne.kast.change.contract.ExpectedFileProof
import io.github.amichne.kast.change.contract.PlannedAddDeclaration
import io.github.amichne.kast.change.contract.RawAddDeclarationPlanRequest
import io.github.amichne.kast.change.contract.RevalidatedAddDeclaration
import io.github.amichne.kast.change.journal.contract.AddDeclarationPlanJournal
import io.github.amichne.kast.change.journal.contract.ApproveAddDeclarationPlan
import io.github.amichne.kast.change.journal.contract.ApproveAddDeclarationPlanResult
import io.github.amichne.kast.change.journal.contract.LoadAddDeclarationPlanResult
import io.github.amichne.kast.change.journal.contract.PersistedAddDeclarationPlan
import io.github.amichne.kast.change.journal.contract.PrepareAddDeclarationRecovery
import io.github.amichne.kast.change.journal.contract.PrepareAddDeclarationRecoveryResult
import io.github.amichne.kast.change.journal.contract.RawAddDeclarationPlanApprovalEvidence
import io.github.amichne.kast.change.journal.contract.RecoveryPreparedAddDeclaration
import io.github.amichne.kast.change.journal.contract.StoreAddDeclarationPlanResult
import io.github.amichne.kast.change.recovery.contract.DurableAddDeclarationRecovery
import io.github.amichne.kast.change.recovery.contract.DurableAddDeclarationRecoveryFailure
import io.github.amichne.kast.change.recovery.spi.AddDeclarationRecoveryPreparer
import io.github.amichne.kast.change.recovery.spi.DurableAddDeclarationRecoveryResult
import io.github.amichne.kast.kernel.EvidenceGeneration
import io.github.amichne.kast.kernel.Refinement
import java.security.MessageDigest
import java.util.Base64
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertInstanceOf

class AddDeclarationRecoveryPreparationServiceTest {
    @Test
    fun `durable artifact precedes journal transition and yields stronger recovery proof`() {
        val plan = plan()
        val approved = approved(plan)
        val events = mutableListOf<String>()
        val service = AddDeclarationRecoveryPreparationService(
            journal = RecordingJournal(events),
            preparer = RecordingPreparer(events),
        )

        val prepared = assertInstanceOf<PrepareApprovedAddDeclarationRecoveryResult.Prepared>(
            service.prepare(approved, revalidated(plan)),
        ).recovery

        assertEquals(listOf("artifact", "journal"), events)
        assertEquals(plan, prepared.revalidated.plan)
        assertEquals(plan.expectedFile.preimage, prepared.record.recovery.beforeImage)
        assertEquals(plan.expectedFile.preimage, prepared.durableRecovery.material.beforeImage)
    }

    @Test
    fun `mismatched approved and revalidated plans fail before any effect`() {
        val events = mutableListOf<String>()
        val service = AddDeclarationRecoveryPreparationService(
            journal = RecordingJournal(events),
            preparer = RecordingPreparer(events),
        )

        val rejected = assertInstanceOf<PrepareApprovedAddDeclarationRecoveryResult.Rejected>(
            service.prepare(approved(plan()), revalidated(plan("fun other(): Int = 2"))),
        )

        assertInstanceOf<AddDeclarationRecoveryPreparationServiceFailure.Admission>(rejected.failure)
        assertTrue(events.isEmpty())
    }

    @Test
    fun `durable artifact failure prevents journal transition`() {
        val events = mutableListOf<String>()
        val plan = plan()
        val service = AddDeclarationRecoveryPreparationService(
            journal = RecordingJournal(events),
            preparer = AddDeclarationRecoveryPreparer {
                events += "artifact-rejected"
                DurableAddDeclarationRecoveryResult.Rejected(
                    DurableAddDeclarationRecoveryFailure.STORAGE_UNAVAILABLE,
                )
            },
        )

        val rejected = assertInstanceOf<PrepareApprovedAddDeclarationRecoveryResult.Rejected>(
            service.prepare(approved(plan), revalidated(plan)),
        )

        assertInstanceOf<AddDeclarationRecoveryPreparationServiceFailure.Durable>(rejected.failure)
        assertEquals(listOf("artifact-rejected"), events)
    }

    private class RecordingPreparer(
        private val events: MutableList<String>,
    ) : AddDeclarationRecoveryPreparer {
        override fun prepare(
            material: io.github.amichne.kast.change.contract.AddDeclarationRecoveryMaterial,
        ): DurableAddDeclarationRecoveryResult {
            events += "artifact"
            return DurableAddDeclarationRecoveryResult.Prepared(
                DurableAddDeclarationRecovery.fromPreparedMaterial(material),
            )
        }
    }

    private inner class RecordingJournal(
        private val events: MutableList<String>,
    ) : AddDeclarationPlanJournal {
        override fun store(plan: PlannedAddDeclaration): StoreAddDeclarationPlanResult =
            error("Storage is outside this focused recovery proof")

        override fun load(planId: AddDeclarationPlanId): LoadAddDeclarationPlanResult =
            LoadAddDeclarationPlanResult.NotFound(planId)

        override fun approve(command: ApproveAddDeclarationPlan): ApproveAddDeclarationPlanResult =
            error("Approval is outside this focused recovery proof")

        override fun prepareRecovery(
            command: PrepareAddDeclarationRecovery,
        ): PrepareAddDeclarationRecoveryResult {
            events += "journal"
            return PrepareAddDeclarationRecoveryResult.Prepared(
                RecoveryPreparedAddDeclaration.prepare(command).refined(),
            )
        }
    }

    private fun approved(plan: PlannedAddDeclaration): PersistedAddDeclarationPlan.Approved {
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
        return PersistedAddDeclarationPlan.approve(awaiting, command).refined()
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

    private fun plan(
        proposedDeclaration: String = "fun added(): Int = 1",
    ): PlannedAddDeclaration {
        val before = "package sample\n".toByteArray()
        val after = "package sample\n\nfun added(): Int = 1\n".toByteArray()
        val intent = RawAddDeclarationPlanRequest(
            workspaceRoot = ROOT,
            targetPath = TARGET,
            expectedCurrentSha256 = hash(before),
            proposedDeclaration = proposedDeclaration,
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
        return PlannedAddDeclaration.issue(
            AddDeclarationPlanningEvidence.admit(
                intent = intent,
                generation = generation,
                target = target,
                expectedFile = ExpectedFileProof.admit(
                    target,
                    exact(before),
                    exact(after),
                ).refined(),
                declaredWriteSet = DeclaredWriteSet.admit(listOf(target.targetPath)).refined(),
                expectedSemanticDelta = ExpectedAddDeclarationDelta.admit(
                    packageName = "sample",
                    declarationName = "added",
                    declarationKind = AddDeclarationKind.FUNCTION,
                ).refined(),
                verification = AddDeclarationVerificationContract.forGeneration(generation),
                compilerContext = ExpectedAddDeclarationCompilerContext.admitSingleSource(
                    generation,
                    "3".repeat(64),
                    "4".repeat(64),
                    target.targetPath.value,
                    hash(before),
                    0,
                ).refined(),
                compilerEvidence = DetachedCompilerEvidence.admit("{\"complete\":true}").refined(),
            ).refined(),
        )
    }

    private fun exact(bytes: ByteArray): ExactFileContentProof = ExactFileContentProof.admit(
        sha256 = hash(bytes),
        contentBase64 = Base64.getEncoder().encodeToString(bytes),
    ).refined()

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
