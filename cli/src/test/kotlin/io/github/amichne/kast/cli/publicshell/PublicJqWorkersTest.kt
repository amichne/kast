package io.github.amichne.kast.cli.publicshell

import io.github.amichne.kast.kernel.Refinement
import java.io.IOException
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/** Admission the pool applies before it starts or reuses a worker. */
class PublicJqWorkersTest {
    @Test
    fun `input limit and invalid text reject before any worker starts`() {
        val workers = PublicJqWorkers(start = { error("no worker may start") })
        for ((input, expected) in
            listOf("too large" to PublicJqFailure.BYTE_LIMIT, "\uD800" to PublicJqFailure.OUTPUT_INVALID)) {
            val result = workers.execute(request(input = input, bytes = "8")) as Refinement.Rejected
            assertEquals(expected, result.failure.failure, input)
        }
    }

    @Test
    fun `an already cancelled caller starts no worker`() {
        val workers = PublicJqWorkers(start = { error("no worker may start after cancellation") })
        Thread.currentThread().interrupt()
        try {
            assertThrows(InterruptedException::class.java) { workers.execute(request()) }
        } finally {
            Thread.interrupted()
        }
    }

    @Test
    fun `a worker starts with no environment at the root and a launch failure stays private`() {
        val workers =
            PublicJqWorkers(
                start = { builder ->
                    assertEquals(emptyMap<String, String>(), builder.environment())
                    assertEquals(Path.of("/").toFile(), builder.directory())
                    assertEquals("--internal-entrypoint=jq", builder.command().last())
                    throw IOException("private launch failure")
                }
            )
        val missing = workers.execute(request()) as Refinement.Rejected
        assertEquals(PublicJqFailure.ENGINE_UNAVAILABLE, missing.failure.failure)
        assertFalse(missing.failure.prepared().text.contains("private launch failure"))
    }

    private fun request(input: String = "", bytes: String = "16777216"): PublicJqProcessRequest {
        val command =
            (parsePublicCliArguments(listOf("query", "plan", "--name", "Order", "--jq", ".", "--jq-max-bytes", bytes))
                    as Refinement.Refined)
                .value
        val options = (command.projection as PublicOutputProjection.Jq).options
        return PublicJqProcessRequest(PublicJqStage.FILTER, options.workerArgument, input, options.limits)
    }
}
