package io.github.amichne.kast.workspace.intellij.read.hosted

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import io.github.amichne.kast.workspace.contract.IdeReadHostLifetime
import io.github.amichne.kast.workspace.contract.ProjectReadEpoch
import io.github.amichne.kast.workspace.contract.ProjectReadEpochObservation
import io.github.amichne.kast.workspace.contract.ProjectReadEpochObservationFailure
import io.github.amichne.kast.workspace.contract.WorkspaceCapability
import io.github.amichne.kast.workspace.contract.WorkspaceCapabilityReadiness
import io.github.amichne.kast.workspace.contract.WorkspaceEpochValidation
import io.github.amichne.kast.workspace.contract.WorkspaceModelIdentity
import io.github.amichne.kast.workspace.contract.WorkspaceReadOperationIdentity
import io.github.amichne.kast.workspace.contract.WorkspaceReadinessDetail
import io.github.amichne.kast.workspace.contract.WorkspaceReadinessNextAction
import io.github.amichne.kast.workspace.contract.WorkspaceReadinessReason
import io.github.amichne.kast.workspace.contract.observeWorkspaceReadSettlement
import io.github.amichne.kast.workspace.contract.validateWorkspaceEpoch
import io.github.amichne.kast.workspace.intellij.read.ExistingProjectAdmissionFailure
import io.github.amichne.kast.workspace.intellij.read.ExistingProjectValidation
import java.nio.file.Path
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

class HostedWorkspaceReadinessTest {
    private val identity =
        WorkspaceModelIdentity(
            (CanonicalWorkspaceRoot.fromCanonicalPath(Path.of("/detached-project")) as Refinement.Refined).value,
            IdeReadHostLifetime.fromBoundary(UUID(0, 1)),
        )

    @Test
    fun `unsettled read reports its identity and retains model without granting preparation readiness`() {
        val source = ProjectReadEpoch.Source.create { Refinement.Refined(1) }
        val ready =
            observeWorkspaceReadiness(identity, ExistingProjectValidation.Validated, source::observe)
                as WorkspaceCapabilityReadiness.Ready
        val lifetime = HostedQueryLifetime()
        val operation = WorkspaceReadOperationIdentity.Traced(UUID(0, 2))
        val permit = (lifetime.begin(lifetime.endpoint, identity = operation) as HostedQueryAdmission.Admitted).permit
        val pending =
            assertInstanceOf(
                WorkspaceCapabilityReadiness.Pending::class.java,
                observeWorkspaceReadSettlement(ready, lifetime.settlement()),
            )
        assertEquals(WorkspaceReadinessReason.NATIVE_WORK, pending.reason)
        assertEquals(WorkspaceReadinessNextAction.OBSERVE_SETTLEMENT, pending.nextAction)
        val detail = assertInstanceOf(WorkspaceReadinessDetail.UnsettledReads::class.java, pending.detail)
        assertSame(ready, detail.retainedObservation)
        assertEquals(listOf(operation), detail.operations)
        lifetime.complete(permit)
        // Quiescence alone is not freshness: the caller supplies a new native observation to the rule.
        val current = observeWorkspaceReadiness(identity, ExistingProjectValidation.Validated, source::observe)
        assertSame(current, observeWorkspaceReadSettlement(current, lifetime.settlement()))
    }

    @Test
    fun `preparation requires both native model policy and a current epoch`() {
        val source = ProjectReadEpoch.Source.create { Refinement.Refined(1) }
        var observations = 0
        val cold =
            observeWorkspaceReadiness(
                identity,
                ExistingProjectValidation.Rejected(ExistingProjectAdmissionFailure.GradleModelUnavailable),
            ) {
                observations++
                source.observe()
            }
        assertEquals(0, observations)
        assertEquals(
            WorkspaceCapabilityReadiness.Unavailable(
                identity,
                WorkspaceReadinessReason.MODEL_UNAVAILABLE,
                WorkspaceReadinessNextAction.REFRESH_MODEL,
            ),
            cold,
        )
        val current =
            observeWorkspaceReadiness(identity, ExistingProjectValidation.Validated) {
                observations++
                source.observe()
            }
        val ready = assertInstanceOf(WorkspaceCapabilityReadiness.Ready::class.java, current)
        assertEquals(1, observations)
        assertEquals(identity, ready.identity)
        assertEquals(WorkspaceCapability.MODEL_PREPARATION, ready.capability)
        assertInstanceOf(
            WorkspaceEpochValidation.Current::class.java,
            validateWorkspaceEpoch(ready.epoch, source.observe()),
        )
    }

    @Test
    fun `successful preparation callback cannot replace stale or unavailable epoch evidence`() {
        var signal = 1
        val source = ProjectReadEpoch.Source.create { Refinement.Refined(signal) }
        val retained =
            observeWorkspaceReadiness(identity, ExistingProjectValidation.Validated, source::observe)
                as WorkspaceCapabilityReadiness.Ready
        signal++
        assertSame(WorkspaceEpochValidation.Stale, validateWorkspaceEpoch(retained.epoch, source.observe()))
        val latest =
            observeWorkspaceReadiness(identity, ExistingProjectValidation.Validated, source::observe)
                as WorkspaceCapabilityReadiness.Ready
        assertInstanceOf(
            WorkspaceEpochValidation.Current::class.java,
            validateWorkspaceEpoch(latest.epoch, source.observe()),
        )

        val unavailable =
            observeWorkspaceReadiness(identity, ExistingProjectValidation.Validated) {
                ProjectReadEpochObservation.Rejected(ProjectReadEpochObservationFailure.GradleModelAmbiguous)
            }
        assertEquals(
            WorkspaceCapabilityReadiness.Unavailable(
                identity,
                WorkspaceReadinessReason.EPOCH_UNAVAILABLE,
                WorkspaceReadinessNextAction.OBSERVE_AGAIN,
                WorkspaceReadinessDetail.EpochRejected(ProjectReadEpochObservationFailure.GradleModelAmbiguous),
            ),
            unavailable,
        )
    }

    @Test
    fun `disposal indexing and preemption retain their supported next action`() {
        val failures =
            listOf(
                ProjectReadEpochObservationFailure.ProjectDisposed,
                ProjectReadEpochObservationFailure.DumbMode,
                ProjectReadEpochObservationFailure.ReadPreempted,
            )
        failures.forEach { failure ->
            val actual =
                observeWorkspaceReadiness(identity, ExistingProjectValidation.Validated) {
                    ProjectReadEpochObservation.Rejected(failure)
                }
            val expected =
                when (failure) {
                    ProjectReadEpochObservationFailure.ProjectDisposed ->
                        WorkspaceCapabilityReadiness.Blocked(
                            identity,
                            WorkspaceReadinessReason.PROJECT_DISPOSED,
                            WorkspaceReadinessNextAction.REOPEN_PROJECT,
                            WorkspaceReadinessDetail.EpochRejected(failure),
                        )
                    ProjectReadEpochObservationFailure.DumbMode ->
                        WorkspaceCapabilityReadiness.Pending(
                            identity,
                            WorkspaceReadinessReason.INDEXING,
                            WorkspaceReadinessNextAction.OBSERVE_AGAIN,
                            WorkspaceReadinessDetail.EpochRejected(failure),
                        )
                    else ->
                        WorkspaceCapabilityReadiness.Pending(
                            identity,
                            WorkspaceReadinessReason.NATIVE_WORK,
                            WorkspaceReadinessNextAction.OBSERVE_SETTLEMENT,
                            WorkspaceReadinessDetail.EpochRejected(failure),
                        )
                }
            assertEquals(expected, actual)
        }
    }

    @Test
    fun `compiler and project selection failures cannot become model preparation success`() {
        val compiler = workspaceReadinessRejected(identity, ExistingProjectAdmissionFailure.K2Unavailable)
        assertEquals(
            WorkspaceCapabilityReadiness.Blocked(
                identity,
                WorkspaceReadinessReason.COMPILER_UNAVAILABLE,
                WorkspaceReadinessNextAction.CHECK_CONFIGURATION,
            ),
            compiler,
        )
        val retired = workspaceReadinessRejected(identity, ExistingProjectAdmissionFailure.ProjectDisposed)
        assertEquals(
            WorkspaceCapabilityReadiness.Blocked(
                identity,
                WorkspaceReadinessReason.PROJECT_DISPOSED,
                WorkspaceReadinessNextAction.REOPEN_PROJECT,
            ),
            retired,
        )
        val mismatch = workspaceReadinessRejected(identity, ExistingProjectAdmissionFailure.RetainedAuthorityMismatch)
        assertEquals(
            WorkspaceCapabilityReadiness.Blocked(
                identity,
                WorkspaceReadinessReason.PROJECT_IDENTITY_MISMATCH,
                WorkspaceReadinessNextAction.SELECT_PROJECT,
            ),
            mismatch,
        )
    }
}
