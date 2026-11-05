package io.github.amichne.kast.cli.publicshell

import io.github.amichne.kast.kernel.Refinement
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PublicJqProcessTest {
    @Test
    fun `argv environment and private stdin are bound without a shell`() {
        val child = ObservedJqChild("null\n".toByteArray())
        val program = "\"\$(not-a-command)\""
        val request = request(program, "private input")
        val runner =
            PublicJqProcess(Path.of("/"), mapOf("ONLY" to "value")) { builder ->
                assertEquals(listOf("/trusted/jq", "-c", "--", program), builder.command())
                assertEquals(mapOf("ONLY" to "value"), builder.environment())
                assertEquals(Path.of("/").toFile(), builder.directory())
                child
            }
        assertEquals(Refinement.Refined(PublicJqCapture(0, "null\n")), runner.execute(request))
        assertEquals("private input", child.written.toString(Charsets.UTF_8))
        child.assertSettled()
    }

    @Test
    fun `stdout and stderr byte limits stop and reap the owned child`() {
        for ((child, expected) in
            listOf(
                ObservedJqChild("oversized".toByteArray()) to PublicJqFailure.BYTE_LIMIT,
                ObservedJqChild(byteArrayOf(), ByteArray(32769)) to PublicJqFailure.STDERR_LIMIT,
            )) {
            val result = PublicJqProcess(Path.of("/"), emptyMap()) { child }.execute(request(bytes = "1"))
            assertEquals(expected, (result as Refinement.Rejected).failure.failure)
            assertTrue(child.destroyed)
            child.assertSettled()
        }
    }

    @Test
    fun `input limit rejects before starting a child`() {
        val runner = PublicJqProcess(Path.of("/"), emptyMap()) { error("start forbidden") }
        val result = runner.execute(request(input = "too large", bytes = "1")) as Refinement.Rejected
        assertEquals(PublicJqFailure.BYTE_LIMIT, result.failure.failure)
    }

    @Test
    fun `invalid Unicode input rejects before starting a child`() {
        val runner = PublicJqProcess(Path.of("/"), emptyMap()) { error("invalid Unicode must not start a child") }
        val result = runner.execute(request(input = "\uD800"))
        assertEquals(PublicJqFailure.OUTPUT_INVALID, (result as Refinement.Rejected).failure.failure)
    }

    @Test
    fun `an already cancelled worker starts no child`() {
        val runner = PublicJqProcess(Path.of("/"), emptyMap()) { error("start forbidden after cancellation") }
        Thread.currentThread().interrupt()
        try {
            assertThrows(InterruptedException::class.java) { runner.execute(request()) }
        } finally {
            Thread.interrupted()
        }
    }

    @Test
    fun `reported deadline expiration kills and reaps without returning output`() {
        val child = ObservedJqChild("1\n".toByteArray(), wait = ChildWait.EXPIRED)
        val result = PublicJqProcess(Path.of("/"), emptyMap()) { child }.execute(request()) as Refinement.Rejected
        assertEquals(PublicJqFailure.TIME_LIMIT, result.failure.failure)
        assertEquals(PublicJqStage.FILTER, result.failure.stage)
        assertEquals(5000, result.failure.timeoutMillis)
        assertTrue(child.destroyed)
        child.assertSettled()
    }

    @Test
    fun `cancellation propagates after owned child and pipes settle`() {
        val child = ObservedJqChild(byteArrayOf(), wait = ChildWait.INTERRUPTED)
        val runner = PublicJqProcess(Path.of("/"), emptyMap()) { child }
        assertThrows(InterruptedException::class.java) { runner.execute(request()) }
        assertTrue(child.destroyed)
        child.assertSettled()
    }

    @Test
    fun `invalid UTF8 and start failures retain their finite boundary causes`() {
        val child = ObservedJqChild(byteArrayOf(0xc3.toByte()))
        val result = PublicJqProcess(Path.of("/"), emptyMap()) { child }.execute(request()) as Refinement.Rejected
        assertEquals(PublicJqFailure.OUTPUT_INVALID, result.failure.failure)
        child.assertSettled()
        val missing =
            PublicJqProcess(Path.of("/"), emptyMap()) { throw IOException("private launch failure") }.execute(request())
                as Refinement.Rejected
        assertEquals(PublicJqFailure.ENGINE_UNAVAILABLE, missing.failure.failure)
        assertFalse(missing.failure.prepared().text.contains("private launch failure"))
    }

    private fun request(program: String = ".", input: String = "", bytes: String = "16777216"): PublicJqProcessRequest {
        val command =
            (parsePublicCliArguments(
                    listOf(
                        "query",
                        "plan",
                        "--name",
                        "Order",
                        "--jq",
                        program,
                        "--jq-max-bytes",
                        bytes,
                    )
                )
                    as Refinement.Refined)
                .value
        val limits = (command.projection as PublicOutputProjection.Jq).options.limits
        return PublicJqProcessRequest(listOf("/trusted/jq", "-c", "--", program), input, PublicJqStage.FILTER, limits)
    }
}

private enum class ChildWait {
    SETTLED,
    EXPIRED,
    INTERRUPTED,
}

private class ObservedJqChild(
    stdout: ByteArray,
    stderr: ByteArray = byteArrayOf(),
    private val wait: ChildWait = ChildWait.SETTLED,
) : Process() {
    @Volatile private var alive = true
    @Volatile
    var destroyed = false
        private set

    private var reaped = false
    private var outClosed = false
    private var errClosed = false
    private var inClosed = false
    val written =
        object : ByteArrayOutputStream() {
            override fun close() {
                inClosed = true
                super.close()
            }
        }
    private val output =
        object : ByteArrayInputStream(stdout) {
            override fun close() {
                outClosed = true
                super.close()
            }
        }
    private val error =
        object : ByteArrayInputStream(stderr) {
            override fun close() {
                errClosed = true
                super.close()
            }
        }

    override fun getOutputStream() = written

    override fun getInputStream() = output

    override fun getErrorStream() = error

    override fun waitFor(timeout: Long, unit: TimeUnit): Boolean =
        when (wait) {
            ChildWait.SETTLED -> {
                alive = false
                true
            }
            ChildWait.EXPIRED -> false
            ChildWait.INTERRUPTED -> throw InterruptedException("controlled cancellation")
        }

    override fun waitFor(): Int {
        check(!alive)
        reaped = true
        return 0
    }

    override fun exitValue(): Int {
        check(!alive)
        return 0
    }

    override fun isAlive() = alive

    override fun destroy() {
        destroyed = true
        alive = false
    }

    override fun destroyForcibly(): Process {
        destroy()
        return this
    }

    fun assertSettled() {
        assertFalse(alive)
        assertTrue(reaped)
        assertTrue(inClosed && outClosed && errClosed)
    }
}
