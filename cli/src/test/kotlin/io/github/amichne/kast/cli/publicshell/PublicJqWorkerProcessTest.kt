package io.github.amichne.kast.cli.publicshell

import io.github.amichne.kast.kernel.Refinement
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Real child processes are necessary here: thread interruption alone cannot stop an embedded evaluator. */
open class PublicJqWorkerProcessTest {
    protected open fun runtimeArguments(): List<String> =
        (PublicJqWorkerCommand.current() as Refinement.Refined).value.arguments

    @Test
    fun `a real embedded worker runs with an empty environment and no jq on PATH`() {
        val result = PublicJqProcess(Path.of("/"), emptyMap()).execute(request("map(. + 1)", "[1,2]"))
        assertEquals(PublicJqCapture(0, "[2,3]"), (result as Refinement.Refined).value)
    }

    @Test
    fun `deadline kills and reaps the actual evaluating process`() {
        val child = AtomicReference<Process>()
        val process = PublicJqProcess(Path.of("/"), emptyMap()) { builder -> builder.start().also(child::set) }
        val result = process.execute(request("reduce range(0;1000000000) as \$i (0; . + \$i)", timeout = "500"))
        assertEquals(PublicJqFailure.TIME_LIMIT, (result as Refinement.Rejected).failure.failure)
        assertNotNull(child.get())
        assertFalse(child.get().isAlive)
        assertEquals(0, Thread.getAllStackTraces().keys.count { it.name == "kast-jq-pipe" && it.isAlive })
    }

    @Test
    fun `cancellation joins the real owned child before returning`() {
        val started = CountDownLatch(1)
        val child = AtomicReference<Process>()
        val failure = AtomicReference<Throwable>()
        val runner =
            PublicJqProcess(Path.of("/"), emptyMap()) { builder ->
                builder.start().also {
                    child.set(it)
                    started.countDown()
                }
            }
        val owner = Thread {
            try {
                runner.execute(request("reduce range(0;1000000000) as \$i (0; . + \$i)"))
            } catch (observed: InterruptedException) {
                failure.set(observed)
            }
        }
        owner.start()
        try {
            assertTrue(started.await(10, TimeUnit.SECONDS))
            owner.interrupt()
            owner.join(10_000)
            assertFalse(owner.isAlive)
            assertTrue(failure.get() is InterruptedException)
            assertFalse(child.get().isAlive)
        } finally {
            owner.interrupt()
            child.get()?.let { if (it.isAlive) it.destroyForcibly().waitFor() }
            owner.join(10_000)
        }
    }

    private fun request(program: String, input: String = "null", timeout: String = "5000"): PublicJqProcessRequest {
        val command =
            (parsePublicCliArguments(
                    listOf("query", "plan", "--name", "Order", "--jq", program, "--jq-timeout-ms", timeout)
                )
                    as Refinement.Refined)
                .value
        val limits = (command.projection as PublicOutputProjection.Jq).options.limits
        return PublicJqProcessRequest(
            runtimeArguments() + listOf("FILTER", limits.bytes.toString(), program),
            input,
            PublicJqStage.FILTER,
            limits,
        )
    }
}
