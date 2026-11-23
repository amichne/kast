package io.github.amichne.kast.distribution.cli

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir

/**
 * The Python installer's management_lock takes fcntl.lockf on management.lock. Linux keeps flock and fcntl record locks
 * independent, so only a record-lock holder in another process proves that the Python and Kotlin owners exclude each
 * other.
 */
class ManagementFileLockTest {
    @TempDir lateinit var temporary: Path

    @Test
    @Timeout(30)
    fun `a python lockf holder rejects the registration lock until it releases`() {
        val root = Files.createDirectory(temporary.resolve("kast"))
        val lock = Files.createFile(root.resolve("management.lock"))
        val holder =
            ProcessBuilder(
                    "python3",
                    "-c",
                    "import fcntl,sys; f=open(sys.argv[1],'r+b'); fcntl.lockf(f,fcntl.LOCK_EX); " +
                        "print('locked',flush=True); sys.stdin.read()",
                    lock.toString(),
                )
                .redirectError(ProcessBuilder.Redirect.INHERIT)
                .start()
        try {
            assertEquals("locked", holder.inputReader().readLine())
            var entered = false
            val rejected =
                assertThrows(ManagementRejected::class.java) { withRegistrationLock(root) { entered = true } }
            assertEquals(ManagementRejected("registration-lock", "another operation is active"), rejected)
            assertEquals(false, entered)
        } finally {
            holder.outputStream.close()
            holder.waitFor(10, TimeUnit.SECONDS)
            holder.destroyForcibly()
        }
        assertEquals(0, holder.waitFor())
        assertEquals("acquired", withRegistrationLock(root) { "acquired" })
    }
}
