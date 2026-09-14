package io.github.amichne.kast.appserver.provider

import io.github.amichne.kast.appserver.BrokerOperationalLimits
import io.github.amichne.kast.appserver.core.CanonicalBrokerDirectory
import io.github.amichne.kast.kernel.Refinement
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class KastSchemaOutputBudgetTest {
    @Test
    fun `schema output admits larger contracts while retaining the combined stream boundary`(@TempDir root: Path) =
        runBlocking {
            val executable = root.resolve("schema-output")
            Files.writeString(
                executable,
                "#!/bin/sh\ncat \"\$1\"\nif [ \"\$2\" = extra ]; then printf x >&2; fi\n",
            )
            Files.setPosixFilePermissions(executable, PosixFilePermissions.fromString("rwx------"))
            val output = root.resolve("output")
            suspend fun execute(size: Int, extra: Boolean): BrokerProcessExecution {
                Files.writeString(output, "x".repeat(size))
                val request =
                    BrokerProcessRequest.admit(
                        (BrokerExecutable.admit(executable) as Refinement.Refined).value,
                        listOf(output.toString(), if (extra) "extra" else "quiet"),
                        checkNotNull(CanonicalBrokerDirectory.admit(root.toRealPath())),
                        maximumOutputBytes = BrokerOperationalLimits.maximumKastSchemaBytes,
                        timeoutMillis = 5_000,
                    ) as Refinement.Refined
                return JdkBrokerProcessExecutor.execute(request.value)
            }
            for (size in listOf(600 * 1_024, BrokerOperationalLimits.maximumKastSchemaBytes)) {
                val result = assertInstanceOf(BrokerProcessExecution.Completed::class.java, execute(size, false))
                assertEquals(0, result.exitCode)
                assertEquals(size, result.stdout.length)
                assertEquals("", result.stderr)
            }
            for (extra in listOf(false, true)) {
                val size = BrokerOperationalLimits.maximumKastSchemaBytes + if (extra) 0 else 1
                assertEquals(
                    BrokerProcessExecution.Rejected(BrokerProcessFailure.OUTPUT_LIMIT),
                    execute(size, extra),
                )
            }
        }
}
