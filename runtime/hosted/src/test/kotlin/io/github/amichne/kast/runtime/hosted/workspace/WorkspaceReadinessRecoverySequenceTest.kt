package io.github.amichne.kast.runtime.hosted.workspace

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.WorkspaceRefreshEffect
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import io.github.amichne.kast.workspace.contract.WorkspaceCapabilityReadiness
import io.github.amichne.kast.workspace.contract.WorkspaceEpochValidation
import io.github.amichne.kast.workspace.contract.WorkspaceExecutionCertainty
import io.github.amichne.kast.workspace.contract.WorkspaceExecutionDisposition
import io.github.amichne.kast.workspace.contract.WorkspaceExecutionSettlement
import io.github.amichne.kast.workspace.contract.WorkspaceReadinessFixture
import io.github.amichne.kast.workspace.contract.WorkspaceReadinessNextAction
import io.github.amichne.kast.workspace.contract.WorkspaceReadinessReason
import io.github.amichne.kast.workspace.contract.WorkspaceRequestEffect
import io.github.amichne.kast.workspace.contract.validateWorkspaceEpoch
import io.github.amichne.kast.workspace.contract.workspaceExecutionDisposition
import java.nio.file.Path
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

/** Synthetic native observations exercise the real owners and rules; this does not assert incident causality. */
class WorkspaceReadinessRecoverySequenceTest {
    @Test
    fun `screenshot sequence recovers a fresh read without discarding imported M1`() = runTest {
        val port = Port()
        val service = WorkspaceRefreshService(port, { testScheduler.currentTime * 1_000_000 })
        val m0 = assertInstanceOf(WorkspaceCapabilityReadiness.Ready::class.java, port.readiness())

        // A model-relevant change requires an authorized import; retained M0 is still historical evidence.
        port.modelChanged()
        assertSame(WorkspaceEpochValidation.Stale, validateWorkspaceEpoch(m0.epoch, port.currentEpoch()))
        assertEquals(
            WorkspaceRefreshStatus.Pending(WorkspaceRefreshStage.EFFECT),
            service.submit(id(), WorkspaceRefreshEffect.GRADLE_MODEL_RELOAD),
        )
        assertEquals(listOf(WorkspaceRefreshEffect.GRADLE_MODEL_RELOAD), port.effects)
        assertSame(WorkspaceEpochValidation.Stale, validateWorkspaceEpoch(m0.epoch, port.currentEpoch()))

        port.importAppliedAndSettled()
        assertSame(WorkspaceRefreshStatus.Complete, service.status(id()))
        val m1 = assertInstanceOf(WorkspaceCapabilityReadiness.Ready::class.java, port.readiness())
        assertInstanceOf(
            WorkspaceEpochValidation.Current::class.java,
            validateWorkspaceEpoch(m1.epoch, port.currentEpoch()),
        )

        // The provider is still running after read cancellation. Lost reply changes no native model fact.
        assertReadCancellationAndLostReplyAwaitProvider()

        // Observation remains reachable independently of the execution disposition and refresh lane.
        assertSame(WorkspaceRefreshStatus.Complete, service.status(id()))
        assertInstanceOf(WorkspaceCapabilityReadiness.Ready::class.java, port.readiness())
        assertInstanceOf(
            WorkspaceEpochValidation.Current::class.java,
            validateWorkspaceEpoch(m1.epoch, port.currentEpoch()),
        )
        assertSame(
            WorkspaceEpochValidation.Stale,
            validateWorkspaceEpoch(m0.epoch, port.currentEpoch()),
        )

        assertRetiredProviderReobservesReadAuthority()
        assertInstanceOf(WorkspaceCapabilityReadiness.Ready::class.java, port.readiness())
        assertInstanceOf(
            WorkspaceEpochValidation.Current::class.java,
            validateWorkspaceEpoch(m1.epoch, port.currentEpoch()),
        )
        assertEquals(listOf(WorkspaceRefreshEffect.GRADLE_MODEL_RELOAD), port.effects)

        // Native model usability cannot reconcile an uncertain mutation.
        assertUncertainMutationRemainsFenced()
    }

    private fun assertReadCancellationAndLostReplyAwaitProvider() {
        val cancelled =
            workspaceExecutionDisposition(
                WorkspaceRequestEffect.READ,
                WorkspaceExecutionCertainty.UNCERTAIN,
                WorkspaceExecutionSettlement.PROVIDER_RUNNING,
            )
        assertEquals(WorkspaceExecutionDisposition.AWAIT_PROVIDER, cancelled)
        val lostReply =
            workspaceExecutionDisposition(
                WorkspaceRequestEffect.READ,
                WorkspaceExecutionCertainty.UNCERTAIN,
                WorkspaceExecutionSettlement.PROVIDER_RUNNING,
            )
        assertEquals(cancelled, lostReply)
    }

    private fun assertRetiredProviderReobservesReadAuthority() {
        assertEquals(
            WorkspaceExecutionDisposition.REOBSERVE_NATIVE_AUTHORITY,
            workspaceExecutionDisposition(
                WorkspaceRequestEffect.READ,
                WorkspaceExecutionCertainty.UNCERTAIN,
                WorkspaceExecutionSettlement.PROVIDER_TERMINATED,
            ),
        )
    }

    private fun assertUncertainMutationRemainsFenced() {
        assertEquals(
            WorkspaceExecutionDisposition.RECONCILE_MUTATION,
            workspaceExecutionDisposition(
                WorkspaceRequestEffect.MUTATION,
                WorkspaceExecutionCertainty.UNCERTAIN,
                WorkspaceExecutionSettlement.PROVIDER_TERMINATED,
            ),
        )
    }

    @Test
    fun `refresh callback alone cannot publish a newly imported preparation observation`() {
        val port = Port()
        val service = WorkspaceRefreshService(port)
        port.modelChanged()
        service.submit(id(), WorkspaceRefreshEffect.GRADLE_MODEL_RELOAD)
        port.completeEffect(WorkspaceRefreshEffectResult.SUCCEEDED)
        assertEquals(WorkspaceRefreshStatus.Pending(WorkspaceRefreshStage.ADMISSION), service.status(id()))
        port.ready = true
        assertSame(WorkspaceRefreshStatus.Complete, service.status(id()))
        assertEquals(1, port.effects.size)
    }

    private class Port : WorkspaceRefreshPort {
        val facts =
            WorkspaceReadinessFixture(
                (CanonicalWorkspaceRoot.fromCanonicalPath(Path.of("/workspace")) as Refinement.Refined).value
            )
        val effects = mutableListOf<WorkspaceRefreshEffect>()
        private val callbacks = ArrayDeque<(WorkspaceRefreshEffectResult) -> Unit>()
        var ready = true

        override fun start(effect: WorkspaceRefreshEffect, complete: (WorkspaceRefreshEffectResult) -> Unit) {
            effects += effect
            callbacks += complete
        }

        override fun startIncremental(complete: (WorkspaceRefreshEffectResult) -> Unit) =
            start(WorkspaceRefreshEffect.FILE_REFRESH, complete)

        override fun readiness(): WorkspaceCapabilityReadiness =
            if (ready) facts.ready()
            else
                WorkspaceCapabilityReadiness.Pending(
                    facts.identity,
                    WorkspaceReadinessReason.NATIVE_WORK,
                    WorkspaceReadinessNextAction.OBSERVE_SETTLEMENT,
                )

        fun currentEpoch() = facts.observe()

        fun modelChanged() {
            ready = false
            facts.advance()
        }

        fun importAppliedAndSettled() {
            ready = true
            completeEffect(WorkspaceRefreshEffectResult.SUCCEEDED)
        }

        fun completeEffect(result: WorkspaceRefreshEffectResult) = callbacks.removeFirst()(result)
    }

    private fun id() = (WorkspaceRefreshRequestId.parse("screenshot-import") as Refinement.Refined).value
}
