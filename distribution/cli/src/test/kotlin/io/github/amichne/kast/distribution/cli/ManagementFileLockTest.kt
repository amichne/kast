package io.github.amichne.kast.distribution.cli

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir

/**
 * The Python installer and Kotlin management commands must exclude each other on management.lock. Linux keeps flock and
 * fcntl record locks independent, so a child holding Python's production management_lock must reject Kotlin's
 * FileChannel lock; this fails on Linux if management_lock returns to flock. macOS lets the two families conflict, so
 * there it proves only the cross-language rejection.
 */
class ManagementFileLockTest {
    @TempDir lateinit var temporary: Path

    @Test
    @Timeout(30)
    fun `python management_lock rejects the registration lock until it releases`() {
        val installation = Path.of("../../packaging/internal/installation").toAbsolutePath().normalize()
        assertTrue(
            Files.isRegularFile(installation.resolve("managed_installation.py")),
            "managed_installation.py is absent under $installation",
        )
        // management_lock admits only a physical root, and macOS temporary paths pass through /var.
        val root = Files.createDirectory(temporary.resolve("kast")).toRealPath()
        val holder =
            try {
                ProcessBuilder(
                        "python3",
                        "-I",
                        "-B",
                        "-c",
                        "import sys; sys.path.insert(0, sys.argv[1]); from pathlib import Path; " +
                            "import managed_installation as install\n" +
                            "with install.management_lock(Path(sys.argv[2])):\n" +
                            "    print('locked', flush=True); sys.stdin.read()",
                        installation.toString(),
                        root.toString(),
                    )
                    .redirectError(ProcessBuilder.Redirect.INHERIT)
                    .start()
            } catch (failure: IOException) {
                fail<Process>("python3 must be on PATH to hold the production management_lock", failure)
            }
        try {
            assertEquals("locked", holder.inputReader().readLine())
            var entered = false
            val rejected =
                assertThrows(ManagementRejected::class.java) { withRegistrationLock(root) { entered = true } }
            assertEquals(ManagementRejected("registration-lock", "another operation is active"), rejected)
            assertFalse(entered)
        } finally {
            holder.outputStream.close()
            holder.waitFor(10, TimeUnit.SECONDS)
            holder.destroyForcibly()
        }
        assertEquals(0, holder.waitFor())
        assertEquals("acquired", withRegistrationLock(root) { "acquired" })
    }
}
