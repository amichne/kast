package io.github.amichne.kast.change.apply.service

import io.github.amichne.kast.change.apply.spi.AddDeclarationApplyExecutor
import io.github.amichne.kast.change.apply.spi.AddDeclarationApplyPreconditionFailure
import io.github.amichne.kast.change.apply.spi.AddDeclarationApplyResult
import io.github.amichne.kast.change.contract.AddDeclarationApplyObservation
import io.github.amichne.kast.change.contract.AddDeclarationUndoAvailability
import io.github.amichne.kast.change.contract.ClosedAddDeclarationApplyFailure
import io.github.amichne.kast.change.contract.AddDeclarationKind
import io.github.amichne.kast.change.contract.AddDeclarationMutationProgress
import io.github.amichne.kast.change.contract.AddDeclarationPlanId
import io.github.amichne.kast.change.contract.AddDeclarationPlanningEvidence
import io.github.amichne.kast.change.contract.AddDeclarationRevalidationObservation
import io.github.amichne.kast.change.contract.AddDeclarationSourceOwner
import io.github.amichne.kast.change.contract.AddDeclarationSourceProvenance
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
import io.github.amichne.kast.change.journal.contract.AddDeclarationApplyJournal
import io.github.amichne.kast.change.journal.contract.AddDeclarationPlanJournalFailure
import io.github.amichne.kast.change.journal.contract.ApproveAddDeclarationPlan
import io.github.amichne.kast.change.journal.contract.ApproveAddDeclarationPlanResult
import io.github.amichne.kast.change.journal.contract.AppliedUnverifiedAddDeclaration
import io.github.amichne.kast.change.journal.contract.BeginAddDeclarationApply
import io.github.amichne.kast.change.journal.contract.BeginAddDeclarationApplyResult
import io.github.amichne.kast.change.journal.contract.CompleteAddDeclarationApply
import io.github.amichne.kast.change.journal.contract.CompleteAddDeclarationApplyResult
import io.github.amichne.kast.change.journal.contract.LoadAddDeclarationPlanResult
import io.github.amichne.kast.change.journal.contract.PersistedAddDeclarationPlan
import io.github.amichne.kast.change.journal.contract.PrepareAddDeclarationRecovery
import io.github.amichne.kast.change.journal.contract.PrepareAddDeclarationRecoveryResult
import io.github.amichne.kast.change.journal.contract.RawAddDeclarationPlanApprovalEvidence
import io.github.amichne.kast.change.journal.contract.RecoveryPreparedAddDeclaration
import io.github.amichne.kast.change.journal.contract.StoreAddDeclarationPlanResult
import io.github.amichne.kast.change.recovery.contract.DurableAddDeclarationRecovery
import io.github.amichne.kast.change.recovery.contract.PreparedAddDeclarationRecovery
import io.github.amichne.kast.change.journal.contract.JournaledAddDeclarationRecovery
import io.github.amichne.kast.kernel.EvidenceGeneration
import io.github.amichne.kast.kernel.Refinement
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertInstanceOf

class AddDeclarationApplyServiceTest {
    @Test
    fun `exact recovery applies and durably closes as applied unverified`() = runTest {
        val fixture = fixture()
        val journal = ApplyJournal(fixture.recovery.record)
        val service = AddDeclarationApplicationService(journal) { command ->
            assertEquals(fixture.recovery.prepared, command.preparedRecovery)
            AddDeclarationApplyResult.Applied(observation(fixture.plan, setOf(TARGET)))
        }

        val applied = assertInstanceOf<ApplyRecoveryPreparedAddDeclarationResult.AppliedUnverified>(
            service.apply(fixture.recovery),
        )

        assertEquals(fixture.plan, applied.record.plan)
        assertEquals(AddDeclarationMutationProgress.BEGUN, applied.record.mutationProgress)
        assertEquals(listOf("begin", "complete"), journal.events)
    }

    @Test
    fun `begin CAS rejection performs no physical apply`() = runTest {
        val fixture = fixture()
        val executorCalled = AtomicBoolean(false)
        val journal = ApplyJournal(fixture.recovery.record, rejectBegin = true)
        val service = AddDeclarationApplicationService(journal) {
            executorCalled.set(true)
            error("executor must not run")
        }

        val rejected = assertInstanceOf<
            ApplyRecoveryPreparedAddDeclarationResult.RejectedBeforeAdmission,
        >(service.apply(fixture.recovery))

        assertInstanceOf<AddDeclarationPreApplyFailure.BeginPersistence>(rejected.failure)
        assertFalse(executorCalled.get())
        assertEquals(listOf("begin"), journal.events)
    }

    @Test
    fun `undeclared document after physical write retains admitted recovery and cannot close`() = runTest {
        val fixture = fixture()
        val journal = ApplyJournal(fixture.recovery.record)
        val service = AddDeclarationApplicationService(journal) {
            AddDeclarationApplyResult.Applied(
                observation(fixture.plan, setOf(TARGET, "$ROOT/Other.kt")),
            )
        }

        val recovery = assertInstanceOf<
            ApplyRecoveryPreparedAddDeclarationResult.RecoveryRequiredAfterMutation,
        >(
            service.apply(fixture.recovery),
        )
        val closure = assertInstanceOf<AddDeclarationRecoveryRequiredFailure.Closure>(recovery.failure)

        assertEquals(ClosedAddDeclarationApplyFailure.WRITE_SET_MISMATCH, closure.failure)
        assertEquals(AddDeclarationMutationProgress.BEGUN, recovery.physicalProgress)
        assertEquals(listOf("begin"), journal.events)
    }

    @Test
    fun `physical precondition rejection remains exact while durable v3 requires recovery`() = runTest {
        val fixture = fixture()
        val journal = ApplyJournal(fixture.recovery.record)
        val service = AddDeclarationApplicationService(journal) {
            AddDeclarationApplyResult.RejectedBeforeMutation(
                AddDeclarationApplyPreconditionFailure.TARGET_PREIMAGE_MISMATCH,
            )
        }

        val recovery = assertInstanceOf<
            ApplyRecoveryPreparedAddDeclarationResult.RecoveryRequiredBeforeMutation,
        >(
            service.apply(fixture.recovery),
        )

        assertInstanceOf<AddDeclarationRecoveryRequiredFailure.PhysicalBeforeMutation>(recovery.failure)
        assertEquals(AddDeclarationMutationProgress.NOT_BEGUN, recovery.physicalProgress)
        assertEquals(AddDeclarationMutationProgress.MAY_HAVE_BEGUN, recovery.admitted.mutationProgress)
    }

    @Test
    fun `completion CAS failure after write retains recovery authority`() = runTest {
        val fixture = fixture()
        val journal = ApplyJournal(fixture.recovery.record, rejectComplete = true)
        val service = AddDeclarationApplicationService(journal) {
            AddDeclarationApplyResult.Applied(observation(fixture.plan, setOf(TARGET)))
        }

        val recovery = assertInstanceOf<
            ApplyRecoveryPreparedAddDeclarationResult.RecoveryRequiredAfterMutation,
        >(
            service.apply(fixture.recovery),
        )

        assertInstanceOf<AddDeclarationRecoveryRequiredFailure.CompletionPersistence>(recovery.failure)
        assertEquals(AddDeclarationMutationProgress.BEGUN, recovery.physicalProgress)
        assertEquals(listOf("begin", "complete"), journal.events)
    }

    private fun observation(
        plan: PlannedAddDeclaration,
        paths: Set<String>,
    ): AddDeclarationApplyObservation = AddDeclarationApplyObservation.observe(
        plan = plan,
        changedDocumentPaths = paths,
        afterImage = plan.expectedFile.postimage,
        undoAvailability = AddDeclarationUndoAvailability.UNAVAILABLE,
    ).refined()

    private class ApplyJournal(
        private val prepared: RecoveryPreparedAddDeclaration,
        private val rejectBegin: Boolean = false,
        private val rejectComplete: Boolean = false,
    ) : AddDeclarationApplyJournal {
        val events = mutableListOf<String>()

        override fun beginApply(command: BeginAddDeclarationApply): BeginAddDeclarationApplyResult {
            events += "begin"
            if (rejectBegin || command.recoveryPrepared != prepared) {
                return BeginAddDeclarationApplyResult.Rejected(
                    AddDeclarationPlanJournalFailure.StorageUnavailable,
                )
            }
            val admitted = when (
                val result = io.github.amichne.kast.change.journal.contract
                    .ApplyAdmittedAddDeclaration.begin(command)
            ) {
                is Refinement.Refined -> result.value
                is Refinement.Rejected -> error("valid begin fixture was rejected: ${result.failure}")
            }
            return BeginAddDeclarationApplyResult.Begun(
                admitted,
            )
        }

        override fun completeApply(
            command: CompleteAddDeclarationApply,
        ): CompleteAddDeclarationApplyResult {
            events += "complete"
            if (rejectComplete) {
                return CompleteAddDeclarationApplyResult.Rejected(
                    AddDeclarationPlanJournalFailure.StorageUnavailable,
                )
            }
            val completed = when (val result = AppliedUnverifiedAddDeclaration.complete(command)) {
                is Refinement.Refined -> result.value
                is Refinement.Rejected -> error("valid completion fixture was rejected: ${result.failure}")
            }
            return CompleteAddDeclarationApplyResult.Completed(completed)
        }

        override fun store(plan: PlannedAddDeclaration): StoreAddDeclarationPlanResult =
            error("not used")

        override fun load(planId: AddDeclarationPlanId): LoadAddDeclarationPlanResult =
            error("not used")

        override fun approve(command: ApproveAddDeclarationPlan): ApproveAddDeclarationPlanResult =
            error("not used")

        override fun prepareRecovery(
            command: PrepareAddDeclarationRecovery,
        ): PrepareAddDeclarationRecoveryResult = error("not used")
    }

    private fun fixture(declaration: String = "fun added(): Int = 1"): ApplyFixture {
        val plan = plan(declaration)
        val revalidated = revalidated(plan)
        val durable = DurableAddDeclarationRecovery.fromPreparedMaterial(revalidated.recovery)
        val recovery = PreparedAddDeclarationRecovery.admit(revalidated, durable).refined()
        val approved = approved(plan)
        val record = RecoveryPreparedAddDeclaration.prepare(
            PrepareAddDeclarationRecovery.admit(approved, revalidated).refined(),
        ).refined()
        return ApplyFixture(plan, JournaledAddDeclarationRecovery.admit(recovery, record).refined())
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

    private fun revalidated(plan: PlannedAddDeclaration): RevalidatedAddDeclaration =
        RevalidatedAddDeclaration.admit(
            plan,
            AddDeclarationRevalidationObservation.observe(
                generation = EvidenceGeneration.parse(plan.generation.value).refined(),
                target = plan.target,
                currentFile = plan.expectedFile.preimage,
                provenance = AddDeclarationSourceProvenance.AUTHORED,
                writability = AddDeclarationTargetWritability.WRITABLE,
            ).refined(),
        ).refined()

    private fun plan(declaration: String): PlannedAddDeclaration {
        val before = "package sample\n".toByteArray()
        val after = "package sample\n\n$declaration\n".toByteArray()
        val intent = RawAddDeclarationPlanRequest(
            workspaceRoot = ROOT,
            targetPath = TARGET,
            expectedCurrentSha256 = hash(before),
            proposedDeclaration = declaration,
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
                expectedFile = ExpectedFileProof.admit(target, exact(before), exact(after)).refined(),
                declaredWriteSet = DeclaredWriteSet.admit(listOf(target.targetPath)).refined(),
                expectedSemanticDelta = ExpectedAddDeclarationDelta.admit(
                    packageName = "sample",
                    declarationName = declaration.substringAfter("fun ").substringBefore('('),
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
        hash(bytes),
        Base64.getEncoder().encodeToString(bytes),
    ).refined()

    private fun hash(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString(separator = "") { byte -> "%02x".format(byte) }

    private fun <T, F> Refinement<T, F>.refined(): T =
        assertInstanceOf<Refinement.Refined<T>>(this).value

    private data class ApplyFixture(
        val plan: PlannedAddDeclaration,
        val recovery: JournaledAddDeclarationRecovery,
    )

    private companion object {
        const val ROOT = "/workspace/kast"
        const val TARGET = "$ROOT/indexer/src/main/kotlin/sample/Target.kt"
    }
}
