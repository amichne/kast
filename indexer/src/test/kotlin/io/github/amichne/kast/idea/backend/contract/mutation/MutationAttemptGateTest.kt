package io.github.amichne.kast.idea

import io.github.amichne.kast.api.contract.MutationAttemptId
import io.github.amichne.kast.api.protocol.ConflictException
import io.github.amichne.kast.idea.backend.mutation.MutationAttemptGate
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

internal class MutationAttemptGateTest {
    private val oldAttempt = MutationAttemptId.parse("123e4567-e89b-42d3-a456-426614174000")
    private val newAttempt = MutationAttemptId.parse("123e4567-e89b-42d3-a456-426614174001")

    @Test
    fun `old running write finishes before a new scratch inspection admits its attempt`() = runBlocking {
        val gate = MutationAttemptGate()
        gate.inspectAndAdmit(oldAttempt) { Unit }
        val writeEntered = CompletableDeferred<Unit>()
        val releaseWrite = CompletableDeferred<Unit>()
        val inspectEntered = CompletableDeferred<Unit>()
        val oldWrite = async(start = CoroutineStart.UNDISPATCHED) {
            gate.write(oldAttempt) {
                writeEntered.complete(Unit)
                releaseWrite.await()
                "old-finished"
            }
        }
        writeEntered.await()
        val inspection = async(start = CoroutineStart.UNDISPATCHED) {
            gate.inspectAndAdmit(newAttempt) {
                inspectEntered.complete(Unit)
            }
        }
        yield()

        assertFalse(inspectEntered.isCompleted)
        releaseWrite.complete(Unit)
        assertEquals("old-finished", oldWrite.await())
        inspection.await()
        assertTrue(inspectEntered.isCompleted)
    }

    @Test
    fun `old queued write rejects before its action after a new attempt is admitted`() = runBlocking {
        val gate = MutationAttemptGate()
        gate.inspectAndAdmit(oldAttempt) { Unit }
        val inspectEntered = CompletableDeferred<Unit>()
        val releaseInspect = CompletableDeferred<Unit>()
        val filesystemCalled = AtomicBoolean(false)
        val inspection = async(start = CoroutineStart.UNDISPATCHED) {
            gate.inspectAndAdmit(newAttempt) {
                inspectEntered.complete(Unit)
                releaseInspect.await()
            }
        }
        inspectEntered.await()
        val oldWrite = async(start = CoroutineStart.UNDISPATCHED) {
            runCatching {
                gate.write(oldAttempt) {
                    filesystemCalled.set(true)
                }
            }.exceptionOrNull()
        }
        yield()
        assertFalse(filesystemCalled.get())

        releaseInspect.complete(Unit)
        inspection.await()
        assertInstanceOf(ConflictException::class.java, oldWrite.await())
        assertFalse(filesystemCalled.get())
    }
}
