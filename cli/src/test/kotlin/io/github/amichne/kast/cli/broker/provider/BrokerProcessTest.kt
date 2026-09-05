package io.github.amichne.kast.cli.broker.provider

import io.github.amichne.kast.cli.broker.core.CanonicalBrokerDirectory
import io.github.amichne.kast.kernel.Refinement
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

class BrokerProcessTest {
    @Test
    fun `cancellation retires the wrapper and allows a subsequent execution`(
        @TempDir temporary: Path,
    ) = runBlocking {
        val pidFile = temporary.resolve("cancelled.pid")
        val executable = temporary.resolve("wrapper")
        Files.writeString(
            executable,
            "#!/bin/sh\nif [ \"\$1\" = wait ]; then printf '%s' \"\$\$\" > '$pidFile'; exec /bin/sleep 30; fi\nprintf 'complete'\n",
        )
        Files.setPosixFilePermissions(executable, PosixFilePermissions.fromString("rwx------"))
        fun request(argument: String) = BrokerProcessRequest.admit(
            BrokerExecutable.admit(executable).refinedValue(),
            listOf(argument),
            checkNotNull(CanonicalBrokerDirectory.admit(temporary.toRealPath())),
            maximumOutputBytes = 1_024,
            timeoutMillis = 60_000,
        ).refinedValue()
        val invocation = async { JdkBrokerProcessExecutor.execute(request("wait")) }
        withTimeout(5_000) {
            while (!Files.exists(pidFile)) delay(10)
        }
        invocation.cancelAndJoin()
        val pid = Files.readString(pidFile).toLong()
        assertFalse(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false))
        assertEquals(
            BrokerProcessExecution.Completed(0, "complete", ""),
            JdkBrokerProcessExecutor.execute(request("ready")),
        )
    }

    @Test
    fun `silent child is killed within the refined process deadline`(
        @TempDir temporary: Path,
    ) = runBlocking {
        val pidFile = temporary.resolve("child.pid")
        val executable = temporary.resolve("silent-child")
        Files.writeString(
            executable,
            "#!/bin/sh\nprintf '%s' \"\$\$\" > '${pidFile}'\nexec /bin/sleep 30\n",
        )
        Files.setPosixFilePermissions(executable, PosixFilePermissions.fromString("rwx------"))
        val request = BrokerProcessRequest.admit(
            BrokerExecutable.admit(executable).refinedValue(),
            emptyList(),
            checkNotNull(CanonicalBrokerDirectory.admit(temporary.toRealPath())),
            maximumOutputBytes = 1_024,
            timeoutMillis = 1_000,
        ).refinedValue()
        val started = TimeSource.Monotonic.markNow()

        val result = JdkBrokerProcessExecutor.execute(request)

        assertEquals(
            BrokerProcessExecution.Rejected(BrokerProcessFailure.TIMED_OUT),
            result,
        )
        assertTrue(started.elapsedNow() < 2.seconds)
        val pid = Files.readString(pidFile).toLong()
        assertFalse(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false))
    }

    private fun <Strong, Failure> Refinement<Strong, Failure>.refinedValue(): Strong = when (this) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> throw AssertionError("Expected refinement, received $failure")
    }
}
