package io.github.amichne.kast.appserver.runtime

import io.github.amichne.kast.appserver.core.BrokerThreadId
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class SharedTaskSessionsTest {
    @Test fun `ordinary turn completion cannot discharge reconciliation`() {
        val sessions = SharedTaskSessions()
        val controller = ClientConnectionId.fresh()
        val thread = checkNotNull(BrokerThreadId.admit("thread-1"))
        sessions.connect(controller)
        sessions.attach(thread, controller)
        sessions.begin(thread, "turn-1")
        sessions.requireReconciliation(thread)
        sessions.finish(thread, "other-turn")
        assertEquals(ControlResult.Rejected(ControlFailure.RECONCILIATION_REQUIRED), sessions.authorize(thread, controller))
        sessions.finish(thread, "turn-1")
        assertEquals(ControlResult.Rejected(ControlFailure.RECONCILIATION_REQUIRED), sessions.authorize(thread, controller))
        sessions.reconcile(thread, "turn-1", true)
        assertEquals(ControlResult.Accepted, sessions.authorize(thread, controller))
    }
    @Test fun `completed turn retains disconnected controller until approval resolves`() {
        val sessions = SharedTaskSessions()
        val controller = ClientConnectionId.fresh()
        val observer = ClientConnectionId.fresh()
        val thread = checkNotNull(BrokerThreadId.admit("thread-1"))
        sessions.connect(controller)
        sessions.connect(observer)
        sessions.attach(thread, controller)
        sessions.attach(thread, observer)
        sessions.begin(thread, "turn-1")
        sessions.pending(thread, "approval-1")
        sessions.disconnect(controller)

        sessions.finish(thread, "turn-1")

        assertTrue(sessions.hasWork(controller))
        assertEquals(ControlResult.Rejected(ControlFailure.TASK_BUSY), sessions.claim(thread, observer))
        sessions.resolved(thread, "approval-1")
        assertFalse(sessions.hasWork(controller))
        assertEquals(ControlResult.Accepted, sessions.claim(thread, observer))
    }
    @Test fun `lost upstream requires matching terminal history before a new controller`() {
        val tasks = SharedTaskSessions(); val a = ClientConnectionId.fresh(); val b = ClientConnectionId.fresh()
        val thread = checkNotNull(BrokerThreadId.admit("thread-1"))
        tasks.connect(a); tasks.connect(b); tasks.attach(thread,a); tasks.attach(thread,b)
        tasks.begin(thread,"turn-1"); tasks.upstreamLost(a)
        assertEquals(ControlResult.Rejected(ControlFailure.RECONCILIATION_REQUIRED),tasks.claim(thread,b))
        tasks.reconcile(thread,"old-turn",true)
        assertEquals(ControlResult.Rejected(ControlFailure.RECONCILIATION_REQUIRED),tasks.claim(thread,b))
        tasks.reconcile(thread,"turn-1",true)
        assertEquals(ControlResult.Accepted,tasks.claim(thread,b))
    }
    @Test fun `two observers share a task with one explicit controller`() {
        val sessions = SharedTaskSessions()
        val a = ClientConnectionId.fresh(); val b = ClientConnectionId.fresh()
        val thread = checkNotNull(BrokerThreadId.admit("thread-1"))
        sessions.connect(a); sessions.connect(b)
        sessions.attach(thread, a); sessions.attach(thread, b)
        assertEquals(setOf(a, b), sessions.observers(thread))
        assertEquals(ControlResult.Rejected(ControlFailure.NOT_CONTROLLER), sessions.authorize(thread, b))
        assertEquals(ControlResult.Rejected(ControlFailure.CONTROLLER_CONFLICT), sessions.claim(thread, b))
        assertEquals(ControlResult.Accepted, sessions.release(thread, a))
        assertEquals(ControlResult.Accepted, sessions.claim(thread, b))
    }
    @Test fun `disconnect retains active ownership until terminal evidence`() {
        val sessions = SharedTaskSessions()
        val a = ClientConnectionId.fresh(); val b = ClientConnectionId.fresh()
        val thread = checkNotNull(BrokerThreadId.admit("thread-1"))
        sessions.connect(a); sessions.connect(b); sessions.attach(thread,a); sessions.attach(thread,b)
        sessions.begin(thread,"turn-1"); sessions.disconnect(a)
        assertTrue(sessions.hasWork(a))
        assertEquals(ControlResult.Rejected(ControlFailure.TASK_BUSY), sessions.claim(thread,b))
        sessions.finish(thread)
        assertFalse(sessions.hasWork(a))
        assertEquals(ControlResult.Accepted,sessions.claim(thread,b))
    }
    @Test fun `pending approval prevents a handoff even before turn notification`() {
        val sessions = SharedTaskSessions(); val a = ClientConnectionId.fresh()
        val thread = checkNotNull(BrokerThreadId.admit("thread-1"))
        sessions.connect(a); sessions.attach(thread,a); sessions.pending(thread,"request-1")
        assertEquals(ControlResult.Rejected(ControlFailure.TASK_BUSY),sessions.release(thread,a))
        sessions.resolved(thread,"request-1")
        assertEquals(ControlResult.Accepted,sessions.release(thread,a))
    }
}
