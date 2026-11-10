package io.github.amichne.kast.distribution.cli

import io.github.amichne.kast.kernel.Refinement
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class ContainerProcessTest {
    @TempDir lateinit var temporary: Path

    @Test
    fun `runtime capture reports the cause without publishing provider output`() {
        for ((case, expected) in
            listOf(
                "exact-limit" to (0 to ""),
                "oversized" to (1 to "OUTPUT_LIMIT_EXCEEDED\n"),
                "invalid-utf8" to (1 to "OUTPUT_ENCODING_REJECTED\n"),
                "nonzero" to (1 to "PROCESS_FAILED\n"),
            )) {
            val parent =
                ProcessBuilder(containerProcessFixtureCommand("capture", case))
                    .directory(temporary.toFile())
                    .apply { environment().clear() }
                    .start()
            try {
                assertTrue(parent.waitFor(10, TimeUnit.SECONDS), case)
                assertEquals(expected.first, parent.exitValue(), case)
                assertEquals(expected.second, parent.inputReader().readText(), case)
                assertEquals("", parent.errorReader().readText(), case)
            } finally {
                if (parent.isAlive) parent.destroyForcibly().waitFor(5, TimeUnit.SECONDS)
            }
        }
    }
}

internal object ContainerProcessFixture {
    @JvmStatic
    fun main(arguments: Array<String>) {
        when (arguments.singleOrNull()) {
            "exact-limit" -> print("x".repeat(1_048_576))
            "oversized" -> print("x".repeat(1_048_577))
            "invalid-utf8" -> {
                System.out.write(0xff)
                System.out.flush()
            }
            "nonzero" -> {
                println("synthetic confidential stdout")
                System.err.println("synthetic confidential stderr")
                exitProcess(7)
            }
            else -> {
                check(arguments.first() == "capture")
                capture(arguments[1])
            }
        }
    }

    private fun capture(case: String) {
        val process = NativeContainerProcess(Path.of("").toAbsolutePath(), emptyMap(), OwnedChildSession())
        when (val result = process.capture(containerProcessFixtureCommand(case))) {
            is Refinement.Refined -> Unit
            is Refinement.Rejected -> {
                println(result.failure.name)
                exitProcess(result.failure.exit)
            }
        }
    }
}

private fun containerProcessFixtureCommand(vararg arguments: String): List<String> =
    listOf(
        Path.of(System.getProperty("java.home"), "bin/java").toString(),
        "-cp",
        System.getProperty("java.class.path"),
        ContainerProcessFixture::class.java.name,
    ) + arguments
