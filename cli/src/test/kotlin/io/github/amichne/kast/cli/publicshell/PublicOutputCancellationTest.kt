package io.github.amichne.kast.cli.publicshell

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.CleanupMode
import org.junit.jupiter.api.io.TempDir

/** Real stdout backpressure exercises the production writer without a semantic host. */
class PublicOutputCancellationTest {
    @TempDir(cleanup = CleanupMode.ON_SUCCESS) lateinit var temporary: Path

    @Test
    fun `SIGINT settles a proven blocked metadata writer without downstream reads`() {
        assertDrainedControl()
        val child = startMetadata("blocked")
        try {
            awaitBlockedWrite(child)
            sendInterrupt(child)
            val settledWithoutDrain = child.waitFor(5, TimeUnit.SECONDS)
            if (!settledWithoutDrain) {
                val afterSignal = threadDump(child, "after-signal")
                assertTrue(isBlockedWriter(afterSignal), "Signal fixture lost its blocked-write precondition")
                val releasedBytes = drainChild(child).size
                assertEquals(130, child.exitValue(), "Reader release must expose the accepted SIGINT status")
                Files.writeString(temporary.resolve("reader-release.txt"), "exit=130\nbytes=$releasedBytes\n")
            }
            assertTrue(
                settledWithoutDrain,
                "SIGINT left the proven native write and worker join blocked. Reader release produced exit130. " +
                    "Evidence: $temporary",
            )
            assertEquals(130, child.exitValue())
        } finally {
            stopOwnedChild(child)
        }
    }

    @Test
    fun `closed stdout consumer selects IO failure in the real metadata child`() {
        val child = startMetadata("closed-consumer")
        try {
            child.inputStream.close()
            assertTrue(child.waitFor(20, TimeUnit.SECONDS), "Closed-consumer child did not settle")
            assertEquals(5, child.exitValue())
        } finally {
            stopOwnedChild(child)
        }
    }

    private fun assertDrainedControl() {
        val child = startMetadata("control")
        try {
            val output = drainChild(child)
            assertEquals(0, child.exitValue(), "Unsignalled metadata control failed")
            assertTrue(output.isNotEmpty(), "Metadata control emitted no output")
            Files.writeString(temporary.resolve("control.txt"), "exit=0\nbytes=${output.size}\n")
        } finally {
            stopOwnedChild(child)
        }
    }

    private fun startMetadata(label: String): Process =
        ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin/java").toString(),
                "-cp",
                System.getProperty("java.class.path"),
                KastPublicCliMain::class.java.name,
                "describe",
                "query",
                "--section",
                "output",
            )
            .directory(temporary.toFile())
            .redirectError(temporary.resolve("$label.stderr").toFile())
            .apply {
                environment().clear()
                environment()["HOME"] = temporary.toString()
            }
            .start()
            .also { it.outputStream.close() }

    private fun awaitBlockedWrite(child: Process) {
        repeat(100) { index ->
            assertTrue(child.isAlive, "Metadata child ended before the blocked-write precondition")
            if (isBlockedWriter(threadDump(child, "before-signal-$index"))) return
        }
        error("Fixture could not establish the native-write and worker-join precondition: $temporary")
    }

    private fun isBlockedWriter(dump: String): Boolean {
        val worker = dump.substringAfter("\"kast-public-query\"", "").substringBefore("\n\n")
        val nativeWrite = worker.contains("writeBytes") || worker.contains("write0")
        return nativeWrite &&
            worker.contains("Native Method") &&
            worker.contains("PublicInvocationOwner.publish") &&
            dump.contains("PublicCliInvocationKt.joinPublicWorker")
    }

    private fun threadDump(child: Process, label: String): String {
        val path = temporary.resolve("$label.txt")
        val command =
            ProcessBuilder(
                    Path.of(System.getProperty("java.home"), "bin/jcmd").toString(),
                    child.pid().toString(),
                    "Thread.print",
                )
                .redirectErrorStream(true)
                .redirectOutput(path.toFile())
                .start()
        try {
            assertTrue(command.waitFor(15, TimeUnit.SECONDS), "jcmd fixture did not settle")
            assertEquals(0, command.exitValue(), Files.readString(path))
            return Files.readString(path)
        } finally {
            stopOwnedChild(command)
        }
    }

    private fun sendInterrupt(child: Process) {
        val command = ProcessBuilder("/bin/kill", "-s", "INT", child.pid().toString()).start()
        try {
            assertTrue(command.waitFor(5, TimeUnit.SECONDS), "Signal fixture did not settle")
            assertEquals(0, command.exitValue())
        } finally {
            stopOwnedChild(command)
        }
    }

    private fun drainChild(child: Process): ByteArray {
        val executor = Executors.newSingleThreadExecutor()
        try {
            val bytes = executor.submit(Callable { child.inputStream.readAllBytes() })
            assertTrue(child.waitFor(20, TimeUnit.SECONDS), "Drained metadata child did not settle")
            return bytes.get(5, TimeUnit.SECONDS)
        } finally {
            stopOwnedChild(child)
            executor.shutdownNow()
        }
    }

    private fun stopOwnedChild(child: Process) {
        if (child.isAlive) child.destroyForcibly().waitFor()
    }
}
