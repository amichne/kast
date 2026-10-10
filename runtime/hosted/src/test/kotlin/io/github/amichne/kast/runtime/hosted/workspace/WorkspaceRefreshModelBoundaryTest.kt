package io.github.amichne.kast.runtime.hosted.workspace

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.WorkspaceRefreshEffect
import io.github.amichne.kast.runtime.hosted.HostedGradleRevision
import io.github.amichne.kast.runtime.hosted.lifecycle.WorkspacePreparationAction
import io.github.amichne.kast.runtime.hosted.lifecycle.WorkspaceReadinessPreparation
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import io.github.amichne.kast.workspace.contract.WorkspaceCapabilityReadiness
import io.github.amichne.kast.workspace.contract.WorkspaceReadinessFixture
import io.github.amichne.kast.workspace.contract.WorkspaceReadinessNextAction
import io.github.amichne.kast.workspace.contract.WorkspaceReadinessReason
import io.github.amichne.kast.workspace.intellij.read.hosted.HostedQueryRejectionDocument
import io.github.amichne.kast.workspace.intellij.read.hosted.HostedReadRecovery
import io.github.amichne.kast.workspace.intellij.read.hosted.HostedReadinessDocument
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Real revision tickets, readiness composition and service transitions with detached fake native observations. */
class WorkspaceRefreshModelBoundaryTest {
    @Test
    fun `legacy description only downgrades pending inputs and preserves other native rejections`() {
        val port = ModelPort()
        val service = WorkspaceRefreshService(port, { 0L })
        port.changeInput()
        val downgraded =
            assertInstanceOf(
                HostedReadinessDocument.Unavailable::class.java,
                port.models.legacyReadiness(HostedReadinessDocument.AdmissionReady),
            )
        assertEquals(HostedReadRecovery.GradleModel.Required, downgraded.rejection.recovery)
        assertEquals(JsonPrimitive("GRADLE_MODEL_INCOMPLETE"), downgraded.rejection.detail)
        val nativeRejected =
            HostedReadinessDocument.Unavailable(
                HostedQueryRejectionDocument(
                    failure = "PROJECT_ADMISSION_REJECTED",
                    detail = JsonPrimitive("DUMB_MODE"),
                    stage = "PROJECT_ADMISSION",
                    recovery = HostedReadRecovery.Indexing.Required,
                )
            )
        assertSame(nativeRejected, port.models.legacyReadiness(nativeRejected))
        service.submit(id(1), WorkspaceRefreshEffect.GRADLE_MODEL_RELOAD)
        port.finish(WorkspaceRefreshEffectResult.SUCCEEDED)
        assertEquals(WorkspaceRefreshStatus.Complete, service.status(id(1)))
        assertSame(
            HostedReadinessDocument.AdmissionReady,
            port.models.legacyReadiness(HostedReadinessDocument.AdmissionReady),
        )
    }

    @Test
    fun `old successful model observation cannot satisfy a pending build input revision`() {
        val port = ModelPort()
        port.changeInput()
        val observed = assertInstanceOf(WorkspaceCapabilityReadiness.Unavailable::class.java, port.readiness())
        assertEquals(WorkspaceReadinessReason.MODEL_INCOMPLETE, observed.reason)
        assertEquals(WorkspaceReadinessNextAction.REFRESH_MODEL, observed.nextAction)
        assertEquals(WorkspacePreparationAction.ReloadModel, WorkspaceReadinessPreparation().observe(observed))
        repeat(3) { port.readiness() }
        assertTrue(port.models.needsModelReload())
    }

    @Test
    fun `covered model refresh settles then freshly observed model admits a read without extra reload`() {
        val port = ModelPort()
        val service = WorkspaceRefreshService(port, { 0L })
        port.changeInput()
        service.submit(id(1), WorkspaceRefreshEffect.GRADLE_MODEL_RELOAD)
        assertTrue(port.models.needsModelReload())
        port.finish(WorkspaceRefreshEffectResult.SUCCEEDED)
        assertEquals(WorkspaceRefreshStatus.Complete, service.status(id(1)))
        assertEquals(false, port.models.needsModelReload())
        assertInstanceOf(WorkspaceCapabilityReadiness.Ready::class.java, port.readiness())
        assertEquals(WorkspacePreparationAction.Ready, WorkspaceReadinessPreparation().observe(port.readiness()))
        val read = mutableListOf<WorkspaceRefreshStatus>()
        service.refreshForRead(read::add)
        port.finish(WorkspaceRefreshEffectResult.SUCCEEDED)
        assertEquals(listOf(WorkspaceRefreshStatus.Complete), read)
        assertEquals(
            listOf(WorkspaceRefreshEffect.GRADLE_MODEL_RELOAD, WorkspaceRefreshEffect.FILE_REFRESH),
            port.effects,
        )
    }

    @Test
    fun `successful settlement still waits for a current native preparation observation`() {
        val port = ModelPort()
        val service = WorkspaceRefreshService(port, { 0L })
        port.changeInput()
        service.submit(id(1), WorkspaceRefreshEffect.GRADLE_MODEL_RELOAD)
        port.nativeReady = false
        port.finish(WorkspaceRefreshEffectResult.SUCCEEDED)
        assertEquals(false, port.models.needsModelReload())
        assertEquals(WorkspaceRefreshStatus.Pending(WorkspaceRefreshStage.ADMISSION), service.status(id(1)))
        port.nativeReady = true
        assertEquals(WorkspaceRefreshStatus.Complete, service.status(id(1)))
        assertEquals(1, port.effects.size)
    }

    @Test
    fun `successful import only covers its captured inputs and later changes require another owned import`() {
        val port = ModelPort()
        val service = WorkspaceRefreshService(port, { 0L })
        port.changeInput()
        service.submit(id(1), WorkspaceRefreshEffect.GRADLE_MODEL_RELOAD)
        port.changeInput()
        port.finish(WorkspaceRefreshEffectResult.SUCCEEDED)
        assertTrue(port.models.needsModelReload())
        assertEquals(WorkspaceRefreshStatus.Pending(WorkspaceRefreshStage.ADMISSION), service.status(id(1)))
        service.submit(id(2), WorkspaceRefreshEffect.GRADLE_MODEL_RELOAD)
        port.finish(WorkspaceRefreshEffectResult.SUCCEEDED)
        assertEquals(WorkspaceRefreshStatus.Complete, service.status(id(2)))
        assertEquals(false, port.models.needsModelReload())
    }

    @Test
    fun `existing import with unknown start coverage cannot acknowledge new inputs and later owned import recovers`() {
        val port = ModelPort().apply { existingImport = true }
        var now = 0L
        val service = WorkspaceRefreshService(port, { now }, pendingTimeoutNanos = 10)
        port.changeInput()
        service.submit(id(1), WorkspaceRefreshEffect.GRADLE_MODEL_RELOAD)
        port.finish(WorkspaceRefreshEffectResult.SUCCEEDED)
        assertTrue(port.models.needsModelReload())
        assertEquals(WorkspaceRefreshStatus.Pending(WorkspaceRefreshStage.ADMISSION), service.status(id(1)))
        now = 10
        assertEquals(WorkspaceRefreshStatus.Failed(WorkspaceRefreshFailure.DEADLINE_EXCEEDED), service.status(id(1)))
        port.existingImport = false
        service.submit(id(2), WorkspaceRefreshEffect.GRADLE_MODEL_RELOAD)
        port.finish(WorkspaceRefreshEffectResult.SUCCEEDED)
        assertEquals(WorkspaceRefreshStatus.Complete, service.status(id(2)))
        assertEquals(false, port.models.needsModelReload())
        assertEquals(2, port.effects.size)
    }

    @Test
    fun `failure cancellation and retirement cannot discharge input revisions or allow a stale success`() {
        for (result in
            listOf(
                WorkspaceRefreshEffectResult.FAILED,
                WorkspaceRefreshEffectResult.CANCELLED,
                WorkspaceRefreshEffectResult.DISPOSED,
            )) {
            val revision = HostedGradleRevision()
            revision.changed()
            val ticket = revision.beginOwnedImport()
            ticket.settled(WorkspaceRefreshEffectResult.RETIRED)
            assertTrue(revision.pending())
            ticket.settled(result)
            ticket.settled(WorkspaceRefreshEffectResult.SUCCEEDED)
            assertTrue(revision.pending())
        }
    }

    private fun id(value: Int) = (WorkspaceRefreshRequestId.parse("model-$value") as Refinement.Refined).value
}

private class ModelPort : WorkspaceRefreshPort {
    private val revision = HostedGradleRevision()
    val models = WorkspaceRefreshModelBoundary(revision)
    private val facts =
        WorkspaceReadinessFixture(
            (CanonicalWorkspaceRoot.fromCanonicalPath(java.nio.file.Path.of("/workspace")) as Refinement.Refined).value
        )
    val effects = mutableListOf<WorkspaceRefreshEffect>()
    private val callbacks = ArrayDeque<(WorkspaceRefreshEffectResult) -> Unit>()
    var existingImport = false
    var nativeReady = true

    override fun readiness(): WorkspaceCapabilityReadiness =
        models.readiness(
            if (nativeReady) facts.ready()
            else
                WorkspaceCapabilityReadiness.Pending(
                    facts.identity,
                    WorkspaceReadinessReason.INDEXING,
                    WorkspaceReadinessNextAction.OBSERVE_AGAIN,
                )
        )

    override fun start(effect: WorkspaceRefreshEffect, complete: (WorkspaceRefreshEffectResult) -> Unit) {
        effects += effect
        val ticket =
            if (effect == WorkspaceRefreshEffect.GRADLE_MODEL_RELOAD && !existingImport) models.beginOwnedImport()
            else null
        callbacks += { outcome ->
            ticket?.settled(outcome)
            complete(outcome)
        }
    }

    override fun startIncremental(complete: (WorkspaceRefreshEffectResult) -> Unit) =
        start(WorkspaceRefreshEffect.FILE_REFRESH, complete)

    fun changeInput() {
        revision.changed()
        facts.advance()
    }

    fun finish(outcome: WorkspaceRefreshEffectResult) = callbacks.removeFirst()(outcome)
}
