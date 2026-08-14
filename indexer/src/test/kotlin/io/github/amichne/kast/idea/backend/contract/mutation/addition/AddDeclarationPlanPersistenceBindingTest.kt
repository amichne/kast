package io.github.amichne.kast.idea

import com.intellij.testFramework.junit5.TestApplication
import io.github.amichne.kast.api.contract.query.AddDeclarationPlanQuery
import io.github.amichne.kast.api.contract.result.AdditionTargetPath
import io.github.amichne.kast.api.contract.result.AdditionTargetPreimageSha256
import io.github.amichne.kast.api.protocol.AddDeclarationPlanPersistenceException
import io.github.amichne.kast.change.contract.AddDeclarationPlanId
import io.github.amichne.kast.change.contract.PlannedAddDeclaration
import io.github.amichne.kast.change.journal.contract.AddDeclarationPlanJournal
import io.github.amichne.kast.change.journal.contract.AddDeclarationPlanJournalFailure
import io.github.amichne.kast.change.journal.contract.ApproveAddDeclarationPlan
import io.github.amichne.kast.change.journal.contract.ApproveAddDeclarationPlanResult
import io.github.amichne.kast.change.journal.contract.LoadAddDeclarationPlanResult
import io.github.amichne.kast.change.journal.contract.PersistedAddDeclarationPlan
import io.github.amichne.kast.change.journal.contract.PrepareAddDeclarationRecovery
import io.github.amichne.kast.change.journal.contract.PrepareAddDeclarationRecoveryResult
import io.github.amichne.kast.change.journal.contract.StoreAddDeclarationPlanResult
import io.github.amichne.kast.change.plan.service.AddDeclarationPlanPersistenceService
import io.github.amichne.kast.api.validation.FileHashing
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

@TestApplication
internal class AddDeclarationPlanPersistenceBindingTest : ExactAdditionPlanningTestSupport() {
    @Test
    fun `public plan persists the published generation after the semantic read lease is released`() = runBlocking {
        ensureProjectReady()
        val target = Path.of(sampleFile.virtualFile.path).toAbsolutePath().normalize()
        val sourceRoot = sourceRoot()
        val workspaceRoot = commonWorkspaceRoot(sourceRoot.toString(), target.toString())
        val activeReads = AtomicInteger()
        val journal = ObservingJournal(activeReads)
        val backend = backend(
            workspaceRoot = workspaceRoot,
            psiGeneration = { 47L },
            workspaceSemanticReadAuthority = TestWorkspaceSemanticReadAuthority(
                onReadOpened = activeReads::incrementAndGet,
                onReadClosed = activeReads::decrementAndGet,
            ),
            workspaceModelReader = model(workspaceRoot, sourceRoot),
            addDeclarationPlanPersistenceService = AddDeclarationPlanPersistenceService(journal),
        )
        val before = Files.readAllBytes(target)

        val result = backend.planAddDeclaration(
            AddDeclarationPlanQuery(
                targetPath = AdditionTargetPath.parse(target.toString()),
                expectedCurrentSha256 = AdditionTargetPreimageSha256.of(FileHashing.sha256(before)),
                proposedDeclaration = "class DurablePlannerDeclaration",
            ),
        )

        assertFalse(journal.observedActiveRead)
        assertEquals(0, activeReads.get())
        assertEquals(1L, journal.storedPlan?.generation?.value)
        assertEquals(1L, journal.storedPlan?.compilerContext?.generation?.value)
        assertEquals(result.proof.targetPath.value, journal.storedPlan?.target?.targetPath?.value)
        assertArrayEquals(before, Files.readAllBytes(target))
    }

    @Test
    fun `journal rejection is a closed public persistence failure`() = runBlocking {
        ensureProjectReady()
        val target = Path.of(sampleFile.virtualFile.path).toAbsolutePath().normalize()
        val sourceRoot = sourceRoot()
        val workspaceRoot = commonWorkspaceRoot(sourceRoot.toString(), target.toString())
        val backend = backend(
            workspaceRoot = workspaceRoot,
            workspaceModelReader = model(workspaceRoot, sourceRoot),
            addDeclarationPlanPersistenceService = AddDeclarationPlanPersistenceService(
                RejectingJournal,
            ),
        )
        val before = Files.readAllBytes(target)

        val failure = assertThrows(AddDeclarationPlanPersistenceException::class.java) {
            runBlocking {
                backend.planAddDeclaration(
                    AddDeclarationPlanQuery(
                        targetPath = AdditionTargetPath.parse(target.toString()),
                        expectedCurrentSha256 =
                            AdditionTargetPreimageSha256.of(FileHashing.sha256(before)),
                        proposedDeclaration = "class RejectedDurablePlannerDeclaration",
                    ),
                )
            }
        }

        assertEquals("STORAGE_UNAVAILABLE", failure.failure.name)
    }

    private class ObservingJournal(
        private val activeReads: AtomicInteger,
    ) : AddDeclarationPlanJournal {
        var observedActiveRead: Boolean = false
            private set
        var storedPlan: PlannedAddDeclaration? = null
            private set

        override fun store(plan: PlannedAddDeclaration): StoreAddDeclarationPlanResult {
            observedActiveRead = activeReads.get() != 0
            storedPlan = plan
            return StoreAddDeclarationPlanResult.Stored(
                PersistedAddDeclarationPlan.awaitingApproval(plan),
            )
        }

        override fun load(planId: AddDeclarationPlanId): LoadAddDeclarationPlanResult =
            LoadAddDeclarationPlanResult.NotFound(planId)

        override fun approve(command: ApproveAddDeclarationPlan): ApproveAddDeclarationPlanResult =
            error("Approval is outside the KIP-032 composition proof")

        override fun prepareRecovery(
            command: PrepareAddDeclarationRecovery,
        ): PrepareAddDeclarationRecoveryResult =
            error("Recovery is outside the KIP-033 composition proof")
    }

    private data object RejectingJournal : AddDeclarationPlanJournal {
        override fun store(plan: PlannedAddDeclaration): StoreAddDeclarationPlanResult =
            StoreAddDeclarationPlanResult.Rejected(
                AddDeclarationPlanJournalFailure.StorageUnavailable,
            )

        override fun load(planId: AddDeclarationPlanId): LoadAddDeclarationPlanResult =
            LoadAddDeclarationPlanResult.NotFound(planId)

        override fun approve(command: ApproveAddDeclarationPlan): ApproveAddDeclarationPlanResult =
            error("Approval is outside the KIP-032 composition proof")

        override fun prepareRecovery(
            command: PrepareAddDeclarationRecovery,
        ): PrepareAddDeclarationRecoveryResult =
            error("Recovery is outside the KIP-033 composition proof")
    }
}
