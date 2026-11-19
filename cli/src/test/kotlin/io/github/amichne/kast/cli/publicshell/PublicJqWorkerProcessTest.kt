package io.github.amichne.kast.cli.publicshell

import io.github.amichne.kast.kernel.Refinement
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Real worker processes are necessary here: thread interruption alone cannot stop an embedded evaluator. */
class PublicJqWorkerProcessTest {
    private val started = mutableListOf<Process>()
    private val workers =
        PublicJqWorkers(start = { builder -> builder.start().also { synchronized(started) { started += it } } })

    @AfterEach
    fun close() {
        workers.close()
        synchronized(started) { started.forEach { if (it.isAlive) it.destroyForcibly().waitFor() } }
    }

    @Test
    fun `a warm worker serves consecutive requests from one process`() {
        val first = workers.execute(request("map(. + 1)", Json.encodeToString(listOf(1, 2))))
        val second = workers.execute(request(".[0]", Json.encodeToString(listOf(7))))
        assertEquals(PublicJqCapture(0, Json.encodeToString(listOf(2, 3))), (first as Refinement.Refined).value)
        assertEquals(PublicJqCapture(0, "7"), (second as Refinement.Refined).value)
        assertEquals(1, started.size)
        assertTrue(started.single().isAlive)
    }

    @Test
    fun `a worker that exceeds the diagnostic limit stops before its reply`() {
        val noisy =
            PublicJqWorkers(
                start = { builder ->
                    builder.command(
                        builder.command().take(1) +
                            listOf("-cp", System.getProperty("java.class.path"), NoisyWorker::class.java.name)
                    )
                    builder.start().also { started += it }
                }
            )
        noisy.use {
            val result = it.execute(request(".", timeout = "3000"))
            assertEquals(PublicJqFailure.STDERR_LIMIT, (result as Refinement.Rejected).failure.failure)
            assertFalse(started.single().isAlive)
        }
    }

    private object NoisyWorker {
        @JvmStatic
        fun main(args: Array<String>) {
            JqFrames.readRequest(java.io.DataInputStream(System.`in`))
            System.err.write(ByteArray(PublicJqLimits.STDERR_BYTES + 1))
            System.err.flush()
            System.`in`.read()
        }
    }

    @Test
    fun `worker protocol preserves UTF-8 when the runtime text output encoding is ASCII`() {
        val ascii =
            PublicJqWorkers(
                start = { builder ->
                    val command = builder.command()
                    builder.command(listOf(command.first(), "-Dstdout.encoding=US-ASCII") + command.drop(1))
                    builder.start().also { synchronized(started) { started += it } }
                }
            )
        val input = Json.encodeToString("Ω𐀀😀􏿿")
        try {
            for (program in listOf(".", "explode | implode", input)) {
                val result = ascii.execute(request(program, input))
                assertEquals(PublicJqCapture(0, input), (result as Refinement.Refined).value, program)
            }
        } finally {
            ascii.close()
        }
    }

    @Test
    fun `a malformed encoded program is rejected and its worker is replaced`() {
        val oversized = Base64.getEncoder().encodeToString(ByteArray(MAX_PROGRAM_BYTES + 1) { 32 })
        val invalidUtf8 = Base64.getEncoder().encodeToString(byteArrayOf(0xFF.toByte()))
        for (argument in listOf("?", "", "AA==", invalidUtf8, oversized)) {
            val result = workers.execute(request(".").copy(program = argument))
            assertEquals(
                PublicJqCapture(PublicJqWorkerExit.INVALID_REQUEST.code, ""),
                (result as Refinement.Refined).value,
                argument,
            )
        }
        assertEquals(PublicJqCapture(0, "1"), (workers.execute(request(".", "1")) as Refinement.Refined).value)
    }

    @Test
    fun `a runaway evaluation is killed at its deadline and the next request gets a new worker`() {
        // Neither program allocates or yields, so only killing the process stops them.
        for (program in listOf("range(2147483647) | empty", "reduce range(0;1000000000) as \$i (0; . + \$i)")) {
            val before = started.size
            val result = workers.execute(request(program, timeout = "500"))
            assertEquals(PublicJqFailure.TIME_LIMIT, (result as Refinement.Rejected).failure.failure, program)
            assertFalse(started.last().isAlive, program)
            assertEquals(before + 1, started.size)
        }
        assertEquals(PublicJqCapture(0, "2"), (workers.execute(request(". + 1", "1")) as Refinement.Refined).value)
        assertTrue(started.last().isAlive)
    }

    @Test
    fun `an exhausted heap is reported and its worker is not reused`() {
        val result = workers.execute(request("[range(1000000000)] | length", timeout = "60000"))
        assertEquals(
            PublicJqCapture(PublicJqWorkerExit.RESOURCE_LIMIT.code, ""),
            (result as Refinement.Refined).value,
        )
        val exhausted = started.single()
        assertTrue(exhausted.waitFor(10, TimeUnit.SECONDS))
        assertEquals(PublicJqCapture(0, "1"), (workers.execute(request(".", "1")) as Refinement.Refined).value)
        assertNotEquals(exhausted, started.last())
    }

    @Test
    fun `cancellation kills the evaluating worker before returning`() {
        val evaluating = CountDownLatch(1)
        val failure = AtomicReference<Throwable>()
        val cancelled =
            PublicJqWorkers(
                start = { builder ->
                    builder.start().also {
                        synchronized(started) { started += it }
                        evaluating.countDown()
                    }
                }
            )
        val owner = Thread {
            try {
                cancelled.execute(request("reduce range(0;1000000000) as \$i (0; . + \$i)"))
            } catch (observed: InterruptedException) {
                failure.set(observed)
            }
        }
        owner.start()
        try {
            assertTrue(evaluating.await(10, TimeUnit.SECONDS))
            owner.interrupt()
            owner.join(10_000)
            assertFalse(owner.isAlive)
            assertTrue(failure.get() is InterruptedException)
            assertFalse(started.single().isAlive)
        } finally {
            owner.interrupt()
            owner.join(10_000)
            cancelled.close()
        }
    }

    private fun request(program: String, input: String = "null", timeout: String = "5000"): PublicJqProcessRequest {
        val command =
            (parsePublicCliArguments(
                    listOf("query", "plan", "--name", "Order", "--jq", program, "--jq-timeout-ms", timeout)
                )
                    as Refinement.Refined)
                .value
        val options = (command.projection as PublicOutputProjection.Jq).options
        return PublicJqProcessRequest(PublicJqStage.FILTER, options.workerArgument, input, options.limits)
    }
}
