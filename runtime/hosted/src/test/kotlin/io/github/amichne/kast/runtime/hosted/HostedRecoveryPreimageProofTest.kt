package io.github.amichne.kast.runtime.hosted

import io.github.amichne.kast.change.contract.LiveChangeBasis
import io.github.amichne.kast.change.recovery.RecoveryDocumentObservation
import io.github.amichne.kast.change.recovery.RecoverySourceObservation
import io.github.amichne.kast.evidence.contract.AppliedRecoveryWriteSet
import io.github.amichne.kast.evidence.contract.MutationPlanBinding
import io.github.amichne.kast.evidence.contract.MutationRecoveryPreparation
import io.github.amichne.kast.evidence.contract.MutationRecoveryRecord
import io.github.amichne.kast.evidence.contract.PlannedRecoveryWrite
import io.github.amichne.kast.evidence.contract.RecoveryPreimage
import io.github.amichne.kast.evidence.contract.RecoverySourcePath
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.workspace.contract.IdeReadEpochRevision
import io.github.amichne.kast.workspace.contract.IdeReadHostLifetime
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertInstanceOf

class HostedRecoveryPreimageProofTest {
    private val fixture = HostedApprovalFixture()
    private val plan = fixture.plan
    private val before = plan.basis.observation.reference
    private val source = RecoverySourcePath.parse(plan.target.file.path.value).approvalRefined()
    private val preimage = RecoveryPreimage.fromBoundary("package sample\nfun service() = 1\n".toByteArray())
    private val preparation =
        MutationRecoveryPreparation.admit(
                MutationPlanBinding.parse(plan.planId.value).approvalRefined(),
                listOf(PlannedRecoveryWrite(source, preimage)),
            )
            .approvalRefined()
    private val prepared = MutationRecoveryRecord.prepare(preparation)
    private val applied =
        MutationRecoveryRecord.recordApplied(
                prepared,
                AppliedRecoveryWriteSet.admit(preparation.plannedWrites, listOf(source)).approvalRefined(),
            )
            .approvalRefined()
    private val after =
        LiveChangeBasis.observe(
                before.copy(epoch = IdeReadEpochRevision.parse(before.epoch.value + 1).approvalRefined()),
                plan.basis.observation.model,
            )
            .approvalRefined()
    private val observed =
        RecoverySourceObservation(source, preimage, RecoveryDocumentObservation.SavedAndCommitted(preimage))

    @Test
    fun `exact completed rollback retains actual newer reference`() {
        val expected = HostedRecoveryExpectation(plan, before, applied)
        val proof = HostedRecoveryPreimageProof.admit(expected, after, observed).approvalRefined()
        assertSame(after, proof.after)
        assertEquals(before.epoch.value + 1, proof.after.reference.epoch.value)
    }

    @Test
    fun `rolled back journal cannot substitute for current document correlation`() {
        val expected = HostedRecoveryExpectation(plan, before, MutationRecoveryRecord.rolledBack(applied))
        val dirty = observed.copy(document = RecoveryDocumentObservation.DirtyOrUncommitted)
        assertFailure(
            failure = HostedRecoveryProofFailure.DOCUMENT_NOT_READY,
            expected = expected,
            basis = after,
            actual = dirty,
        )
        val divergent =
            observed.copy(
                document =
                    RecoveryDocumentObservation.SavedAndCommitted(
                        RecoveryPreimage.fromBoundary("racing document edit".toByteArray())
                    )
            )
        assertFailure(
            failure = HostedRecoveryProofFailure.DOCUMENT_PREIMAGE_MISMATCH,
            expected = expected,
            basis = after,
            actual = divergent,
        )
        assertSame(after, HostedRecoveryPreimageProof.admit(expected, after, observed).approvalRefined().after)
    }

    @Test
    fun `matching disk alone never completes unavailable or unloaded document proof`() {
        val expected = HostedRecoveryExpectation(plan, before, prepared)
        for (document in listOf(RecoveryDocumentObservation.Unavailable, RecoveryDocumentObservation.NotLoaded)) {
            assertFailure(
                failure = HostedRecoveryProofFailure.DOCUMENT_NOT_READY,
                expected = expected,
                basis = after,
                actual = observed.copy(document = document),
            )
        }
        val diverged = observed.copy(savedContent = RecoveryPreimage.fromBoundary("racing disk edit".toByteArray()))
        assertFailure(
            failure = HostedRecoveryProofFailure.PHYSICAL_PREIMAGE_MISMATCH,
            expected = expected,
            basis = after,
            actual = diverged,
        )
    }

    @Test
    fun `recovery owner change or epoch regression rejects historical completion`() {
        val expected = HostedRecoveryExpectation(plan, before, applied)
        val foreign =
            LiveChangeBasis.observe(
                    after.reference.copy(host = IdeReadHostLifetime.fromBoundary(UUID(0, 99))),
                    after.model,
                )
                .approvalRefined()
        assertFailure(
            failure = HostedRecoveryProofFailure.OWNER_CHANGED,
            expected = expected,
            basis = foreign,
            actual = observed,
        )
        val regressed =
            LiveChangeBasis.observe(
                    after.reference.copy(epoch = IdeReadEpochRevision.parse(before.epoch.value - 1).approvalRefined()),
                    after.model,
                )
                .approvalRefined()
        assertFailure(
            failure = HostedRecoveryProofFailure.REFERENCE_REGRESSED,
            expected = expected,
            basis = regressed,
            actual = observed,
        )
    }

    private fun assertFailure(
        failure: HostedRecoveryProofFailure,
        expected: HostedRecoveryExpectation,
        basis: LiveChangeBasis,
        actual: RecoverySourceObservation,
    ) {
        assertEquals(
            failure,
            assertInstanceOf<Refinement.Rejected<HostedRecoveryProofFailure>>(
                    HostedRecoveryPreimageProof.admit(expected, basis, actual)
                )
                .failure,
        )
    }
}
