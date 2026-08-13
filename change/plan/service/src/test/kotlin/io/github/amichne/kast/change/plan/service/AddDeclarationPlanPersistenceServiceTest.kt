package io.github.amichne.kast.change.plan.service

import io.github.amichne.kast.change.contract.AddDeclarationKind
import io.github.amichne.kast.change.contract.AddDeclarationPlanningEvidence
import io.github.amichne.kast.change.contract.AddDeclarationSourceOwner
import io.github.amichne.kast.change.contract.AddDeclarationTargetCapability
import io.github.amichne.kast.change.contract.AddDeclarationVerificationContract
import io.github.amichne.kast.change.contract.DeclaredWriteSet
import io.github.amichne.kast.change.contract.DetachedCompilerEvidence
import io.github.amichne.kast.change.contract.ExactFileContentProof
import io.github.amichne.kast.change.contract.ExpectedAddDeclarationDelta
import io.github.amichne.kast.change.contract.ExpectedFileProof
import io.github.amichne.kast.change.contract.PlannedAddDeclaration
import io.github.amichne.kast.change.contract.RawAddDeclarationPlanRequest
import io.github.amichne.kast.change.journal.contract.AddDeclarationPlanJournal
import io.github.amichne.kast.change.journal.contract.ApproveAddDeclarationPlan
import io.github.amichne.kast.change.journal.contract.ApproveAddDeclarationPlanResult
import io.github.amichne.kast.change.journal.contract.LoadAddDeclarationPlanResult
import io.github.amichne.kast.change.journal.contract.PersistedAddDeclarationPlan
import io.github.amichne.kast.change.journal.contract.StoreAddDeclarationPlanResult
import io.github.amichne.kast.change.plan.spi.AddDeclarationPlanner
import io.github.amichne.kast.change.plan.spi.AddDeclarationPlanningResult
import io.github.amichne.kast.kernel.EvidenceGeneration
import io.github.amichne.kast.kernel.Refinement
import java.security.MessageDigest
import java.util.Base64
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertInstanceOf

class AddDeclarationPlanPersistenceServiceTest {
    @Test
    fun `journal runs only after the planner releases its live resource`() = runTest {
        val plan = plan()
        var liveResourceHeld = false
        val planner = AddDeclarationPlanner {
            liveResourceHeld = true
            try {
                AddDeclarationPlanningResult.Planned(plan)
            } finally {
                liveResourceHeld = false
            }
        }
        var journalObservedReleased = false
        val journal = RecordingJournal { stored ->
            journalObservedReleased = !liveResourceHeld
            StoreAddDeclarationPlanResult.Stored(
                PersistedAddDeclarationPlan.awaitingApproval(stored),
            )
        }

        val result = AddDeclarationPlanPersistenceService(journal)
            .planAndPersist(planner, plan.intent)

        assertInstanceOf<PlanAndPersistAddDeclarationResult.Stored>(result)
        assertTrue(journalObservedReleased)
        assertFalse(liveResourceHeld)
        assertSame(plan, journal.storedPlan)
    }

    @Test
    fun `identical durable plan is returned as the existing journal record`() = runTest {
        val plan = plan()
        val existing = PersistedAddDeclarationPlan.awaitingApproval(plan)
        val journal = RecordingJournal { StoreAddDeclarationPlanResult.Existing(existing) }
        val planner = AddDeclarationPlanner { AddDeclarationPlanningResult.Planned(plan) }
        val service = AddDeclarationPlanPersistenceService(journal)

        val result = assertInstanceOf<PlanAndPersistAddDeclarationResult.Existing>(
            service.planAndPersist(planner, plan.intent),
        )

        assertSame(existing, result.record)
    }

    private class RecordingJournal(
        private val storeResult: (PlannedAddDeclaration) -> StoreAddDeclarationPlanResult,
    ) : AddDeclarationPlanJournal {
        var storedPlan: PlannedAddDeclaration? = null
            private set

        override fun store(plan: PlannedAddDeclaration): StoreAddDeclarationPlanResult {
            storedPlan = plan
            return storeResult(plan)
        }

        override fun load(
            planId: io.github.amichne.kast.change.contract.AddDeclarationPlanId,
        ): LoadAddDeclarationPlanResult = LoadAddDeclarationPlanResult.NotFound(planId)

        override fun approve(command: ApproveAddDeclarationPlan): ApproveAddDeclarationPlanResult =
            error("Approval is outside this focused planning proof")
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
        return PlannedAddDeclaration.issue(
            AddDeclarationPlanningEvidence.admit(
                intent = intent,
                generation = generation,
                target = target,
                expectedFile = ExpectedFileProof.admit(
                    target,
                    ExactFileContentProof.admit(
                        hash(before),
                        Base64.getEncoder().encodeToString(before),
                    ).refined(),
                    ExactFileContentProof.admit(
                        hash(after),
                        Base64.getEncoder().encodeToString(after),
                    ).refined(),
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
            ).refined(),
        )
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
