package io.github.amichne.kast.appserver.runtime

import io.github.amichne.kast.appserver.core.BrokerInvocationContext
import io.github.amichne.kast.kernel.Refinement
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class ExactPlanApprovalTest {
    @Test
    fun `identity admission does not create approval`(@TempDir root: Path) {
        assertInstanceOf(Refinement.Refined::class.java, subject(root, "a".repeat(64), "b".repeat(64)))
        assertEquals(
            Refinement.Rejected(PlanApprovalFailure.INVALID_PLAN_IDENTITY),
            subject(root, "approved", "b".repeat(64)),
        )
        assertEquals(
            Refinement.Rejected(PlanApprovalFailure.INVALID_CHALLENGE),
            subject(root, "a".repeat(64), "true"),
        )
    }

    @Test
    fun `only controller accept proves the exact pending challenge and invocation`(@TempDir root: Path) {
        val pending = pending(root)
        val result = pending.respond(pending.controller, Json.parseToJsonElement("""{"decision":"accept"}"""))
        val resolved = assertInstanceOf(ExactPlanApprovalResolution.Resolved::class.java, result)
        val approved = assertInstanceOf(ExactPlanApprovalOutcome.Approved::class.java, resolved.outcome).proof
        assertSame(pending.subject, approved.subject)
        assertSame(pending.invocation, approved.invocation)
        assertEquals(pending.controller, approved.controller)
        assertEquals(pending.lease, approved.lease)
        assertEquals(
            ExactPlanApprovalResolution.Rejected(PlanApprovalFailure.ALREADY_RESOLVED),
            pending.respond(pending.controller, Json.parseToJsonElement("""{"decision":"accept"}""")),
        )
    }

    @Test
    fun `observer reply cannot spend the controllers pending request`(@TempDir root: Path) {
        val pending = pending(root)
        val accept = Json.parseToJsonElement("""{"decision":"accept"}""")
        assertEquals(
            ExactPlanApprovalResolution.Rejected(PlanApprovalFailure.NOT_RESPONSIBLE_CONTROLLER),
            pending.respond(ClientConnectionId.fresh(), accept),
        )
        assertInstanceOf(
            ExactPlanApprovalOutcome.Approved::class.java,
            resolved(pending.respond(pending.controller, accept)),
        )
    }

    @Test
    fun `reacquired controller identity cannot revive a stale lease`(@TempDir root: Path) {
        val tasks = SharedTaskSessions()
        val pending = pending(root, tasks)
        tasks.release(pending.invocation.threadId, pending.controller)
        tasks.claim(pending.invocation.threadId, pending.controller)
        val accept = Json.parseToJsonElement("""{"decision":"accept"}""")
        assertEquals(
            ExactPlanApprovalOutcome.Rejected(PlanApprovalFailure.CONTROLLER_LEASE_CHANGED),
            resolved(pending.respond(pending.controller, accept)),
        )
        assertEquals(
            ExactPlanApprovalResolution.Rejected(PlanApprovalFailure.ALREADY_RESOLVED),
            pending.respond(pending.controller, accept),
        )
    }

    @Test
    fun `native non-accept decisions terminate without granting`(@TempDir root: Path) {
        mapOf(
                "decline" to ExactPlanApprovalOutcome.Terminated(PlanApprovalTermination.DECLINED),
                "cancel" to ExactPlanApprovalOutcome.Terminated(PlanApprovalTermination.CANCELLED),
                "acceptForSession" to
                    ExactPlanApprovalOutcome.Rejected(PlanApprovalFailure.SESSION_APPROVAL_UNSUPPORTED),
            )
            .forEach { (decision, expected) ->
                val pending = pending(root)
                assertEquals(
                    expected,
                    resolved(
                        pending.respond(pending.controller, Json.parseToJsonElement("""{"decision":"$decision"}"""))
                    ),
                )
                assertEquals(
                    ExactPlanApprovalResolution.Rejected(PlanApprovalFailure.ALREADY_RESOLVED),
                    pending.terminate(PlanApprovalTermination.CANCELLED),
                )
            }
    }

    @Test
    fun `malformed and caller supplied approval flags cannot grant`(@TempDir root: Path) {
        listOf(
                "null",
                "true",
                "[]",
                "{}",
                """{"approved":true}""",
                """{"decision":true}""",
                """{"decision":"unknown"}""",
                """{"decision":"accept","planIdentity":"changed"}""",
            )
            .forEach { raw ->
                val pending = pending(root)
                assertEquals(
                    ExactPlanApprovalOutcome.Rejected(PlanApprovalFailure.MALFORMED_RESPONSE),
                    resolved(pending.respond(pending.controller, Json.parseToJsonElement(raw))),
                )
                assertEquals(
                    ExactPlanApprovalResolution.Rejected(PlanApprovalFailure.ALREADY_RESOLVED),
                    pending.respond(pending.controller, Json.parseToJsonElement("""{"decision":"accept"}""")),
                )
            }
    }

    @Test
    fun `disconnect cancellation timeout and owner retirement cannot later become approvals`(@TempDir root: Path) {
        PlanApprovalTermination.entries.forEach { reason ->
            val pending = pending(root)
            assertEquals(ExactPlanApprovalOutcome.Terminated(reason), resolved(pending.terminate(reason)))
            assertEquals(
                ExactPlanApprovalResolution.Rejected(PlanApprovalFailure.ALREADY_RESOLVED),
                pending.respond(pending.controller, Json.parseToJsonElement("""{"decision":"accept"}""")),
            )
        }
    }

    @Test
    fun `concurrent accept and disconnect produce exactly one terminal outcome`(@TempDir root: Path) {
        val pending = pending(root)
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val accepted =
                executor.submit<ExactPlanApprovalResolution> {
                    start.await()
                    pending.respond(pending.controller, Json.parseToJsonElement("""{"decision":"accept"}"""))
                }
            val disconnected =
                executor.submit<ExactPlanApprovalResolution> {
                    start.await()
                    pending.terminate(PlanApprovalTermination.DISCONNECTED)
                }
            start.countDown()
            val outcomes = listOf(accepted.get(5, TimeUnit.SECONDS), disconnected.get(5, TimeUnit.SECONDS))
            assertEquals(1, outcomes.count { it is ExactPlanApprovalResolution.Resolved })
            assertEquals(
                1,
                outcomes.count { it == ExactPlanApprovalResolution.Rejected(PlanApprovalFailure.ALREADY_RESOLVED) },
            )
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `task reconciliation blocks both new requests and pending acceptance`(@TempDir root: Path) {
        val tasks = SharedTaskSessions()
        val pending = pending(root, tasks)
        tasks.requireReconciliation(pending.invocation.threadId)
        assertEquals(
            Refinement.Rejected(ControlFailure.RECONCILIATION_REQUIRED),
            PendingExactPlanApproval.open(pending.subject, pending.invocation, tasks),
        )
        assertEquals(
            ExactPlanApprovalOutcome.ControlRejected(ControlFailure.RECONCILIATION_REQUIRED),
            resolved(pending.respond(pending.controller, Json.parseToJsonElement("""{"decision":"accept"}"""))),
        )
    }

    @Test
    fun `missing and unclaimed tasks cannot issue pending approvals`(@TempDir root: Path) {
        val tasks = SharedTaskSessions()
        val pending = pending(root, tasks)
        assertEquals(
            Refinement.Rejected(ControlFailure.TASK_UNKNOWN),
            PendingExactPlanApproval.open(pending.subject, pending.invocation, SharedTaskSessions()),
        )
        tasks.release(pending.invocation.threadId, pending.controller)
        assertEquals(
            Refinement.Rejected(ControlFailure.NOT_CONTROLLER),
            PendingExactPlanApproval.open(pending.subject, pending.invocation, tasks),
        )
    }

    private fun resolved(result: ExactPlanApprovalResolution): ExactPlanApprovalOutcome =
        assertInstanceOf(ExactPlanApprovalResolution.Resolved::class.java, result).outcome

    private fun pending(root: Path, tasks: SharedTaskSessions = SharedTaskSessions()): PendingExactPlanApproval {
        val invocation =
            (BrokerInvocationContext.admit(
                    threadId = "thread-1",
                    turnId = "turn-1",
                    callId = "call-1",
                    workingDirectory = root.toRealPath(),
                ) as Refinement.Refined)
                .value
        val controller = ClientConnectionId.fresh()
        tasks.connect(controller)
        tasks.attach(invocation.threadId, controller)
        return (PendingExactPlanApproval.open(
                subject = (subject(root, "a".repeat(64), "b".repeat(64)) as Refinement.Refined).value,
                invocation = invocation,
                tasks = tasks,
            ) as Refinement.Refined)
            .value
    }

    private fun subject(root: Path, plan: String, challenge: String) =
        ExactPlanApprovalSubject.admit(
            planIdentity = plan,
            hostedChallenge = challenge,
            root =
                checkNotNull(io.github.amichne.kast.appserver.core.CanonicalBrokerDirectory.admit(root.toRealPath())),
            host = (HostedApprovalOwnerId.admit("11111111-1111-1111-1111-111111111111") as Refinement.Refined).value,
        )
}
