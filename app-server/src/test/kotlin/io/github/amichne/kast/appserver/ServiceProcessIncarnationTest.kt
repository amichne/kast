package io.github.amichne.kast.appserver

import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir

class ServiceProcessIncarnationTest {
    @Test
    fun `an exited process that no parent reaped is exited`(@TempDir processes: Path) {
        val current = checkNotNull(ServiceProcessIncarnation.capture(ProcessHandle.current()))
        val stat = Files.createDirectory(processes.resolve(current.pid.toString())).resolve("stat")
        assertEquals(SemanticPeerIncarnation.LIVE, current.observe(processes))
        for ((line, expected) in
            listOf(
                "${current.pid} (kast) Z 1 1 1" to SemanticPeerIncarnation.EXITED,
                // The command is the only field that can hold a parenthesis or a space.
                "${current.pid} (a) Z (b) S 1 1 1" to SemanticPeerIncarnation.LIVE,
                "${current.pid} (kast) S 1 1 1" to SemanticPeerIncarnation.LIVE,
                "${current.pid} (kast) D 1 1 1" to SemanticPeerIncarnation.LIVE,
                "Z" to SemanticPeerIncarnation.LIVE,
                "" to SemanticPeerIncarnation.LIVE,
            )) {
            Files.writeString(stat, line)
            assertEquals(expected, current.observe(processes), line)
        }
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    fun `a real unreaped child is exited`() {
        // The shell never waits for its exited child, so the child stays unreaped while the shell sleeps.
        val parent = ProcessBuilder("/bin/sh", "-c", "sleep 0 & echo $!; exec sleep 30").start()
        try {
            val child = parent.inputReader().readLine().toLong()
            val deadline = System.nanoTime() + 10_000_000_000L
            while (stateOf(child) != 'Z' && System.nanoTime() < deadline) Thread.onSpinWait()
            val handle = ProcessHandle.of(child).orElseThrow()
            val incarnation = checkNotNull(ServiceProcessIncarnation.capture(handle))
            assertEquals(SemanticPeerIncarnation.EXITED, incarnation.observe())
        } finally {
            parent.destroyForcibly().waitFor()
        }
    }

    private fun stateOf(pid: Long): Char? =
        Files.readString(Path.of("/proc/$pid/stat")).substringAfterLast(") ").firstOrNull()
}
