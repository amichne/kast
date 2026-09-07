package io.github.amichne.kast.cli.broker.host

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.atomic.AtomicReference

class InstalledCodexClientLauncherTest {
    @Test
    fun `desktop executable rejection retains a named failure`(
        @TempDir temporary: Path,
    ) {
        assertEquals(
            io.github.amichne.kast.kernel.Refinement.Rejected(
                CodexDesktopExecutableFailure.UNAVAILABLE,
            ),
            CodexDesktopExecutable.admit(temporary.resolve("missing-desktop")),
        )
    }

    @Test
    fun `desktop receives facade and upstream through only its process environment`(
        @TempDir temporary: Path,
    ) {
        val bin = Files.createDirectory(temporary.resolve("bin"))
        val home = Files.createDirectory(temporary.resolve("home")).toRealPath()
        val kast = executable(bin.resolve("kast"))
        val facade = executable(bin.resolve("kast-codex"))
        val upstream = executable(bin.resolve("codex"))
        val capture = temporary.resolve("desktop-environment")
        val desktop = bin.resolve("Codex Desktop")
        Files.writeString(
            desktop,
            "#!/bin/sh\nprintf '%s\\n%s\\n' \"\$CODEX_CLI_PATH\" " +
                "\"\$KAST_REAL_CODEX_EXECUTABLE\" > \"$capture\"\n",
        )
        Files.setPosixFilePermissions(desktop, PosixFilePermissions.fromString("rwx------"))
        val launcher = InstalledCodexClientLauncher(
            kast,
            home,
            mapOf(
                "PATH" to "/usr/bin:/bin",
                "KAST_REAL_CODEX_EXECUTABLE" to upstream.toString(),
                "KAST_CODEX_DESKTOP_EXECUTABLE" to desktop.toString(),
            ),
        )

        assertEquals(
            CodexClientLaunchRun.Completed(0),
            launcher.launch(CodexClientLaunch.Desktop),
        )
        for (attempt in 0..<100) {
            if (Files.exists(capture)) break
            Thread.sleep(10)
        }
        assertTrue(Files.exists(capture))
        assertEquals(
            listOf(facade.toString(), upstream.toString()),
            Files.readAllLines(capture),
        )
    }

    @Test
    fun `desktop launch receives process local facade and real Codex identities`(
        @TempDir temporary: Path,
    ) {
        val bin = Files.createDirectory(temporary.resolve("bin"))
        val home = Files.createDirectory(temporary.resolve("home")).toRealPath()
        val kast = executable(bin.resolve("kast"))
        val facade = executable(bin.resolve("kast-codex"))
        val upstream = executable(bin.resolve("codex"))
        val desktop = executable(bin.resolve("Codex Desktop"))
        val processLauncher = CapturedProcessLauncher()
        val launcher = InstalledCodexClientLauncher(
            kast,
            home,
            mapOf(
                "PATH" to "/usr/bin:/bin",
                "KAST_REAL_CODEX_EXECUTABLE" to upstream.toString(),
                "KAST_CODEX_DESKTOP_EXECUTABLE" to desktop.toString(),
            ),
            processLauncher,
        )

        assertEquals(
            CodexClientLaunchRun.Completed(0),
            launcher.launch(CodexClientLaunch.Desktop),
        )
        val request = assertInstanceOf(
            CodexClientProcessRequest.Desktop::class.java,
            processLauncher.request,
        )
        assertEquals(desktop, request.executable.path)
        assertEquals(facade, request.facade.path)
        assertEquals(upstream, request.upstream.path)
    }

    @Test
    fun `cli launch delegates to the installed facade and retains its exit code`(
        @TempDir temporary: Path,
    ) {
        val bin = Files.createDirectory(temporary.resolve("bin"))
        val home = Files.createDirectory(temporary.resolve("home")).toRealPath()
        val kast = executable(bin.resolve("kast"))
        val facade = executable(bin.resolve("kast-codex"))
        val processLauncher = CapturedProcessLauncher(exitCode = 17)
        val launcher = InstalledCodexClientLauncher(
            kast,
            home,
            mapOf("PATH" to "/usr/bin:/bin"),
            processLauncher,
        )

        assertEquals(
            CodexClientLaunchRun.Completed(17),
            launcher.launch(CodexClientLaunch.Cli),
        )
        assertEquals(
            facade,
            assertInstanceOf(
                CodexClientProcessRequest.Cli::class.java,
                processLauncher.request,
            ).facade.path,
        )
    }

    @Test
    fun `interrupted CLI launch destroys its owned facade process`(
        @TempDir temporary: Path,
    ) {
        val bin = Files.createDirectory(temporary.resolve("bin"))
        val home = Files.createDirectory(temporary.resolve("home")).toRealPath()
        val kast = executable(bin.resolve("kast"))
        val pidFile = temporary.resolve("facade.pid")
        val facade = bin.resolve("kast-codex")
        Files.writeString(
            facade,
            "#!/bin/sh\nprintf '%s' \"\$\$\" > '$pidFile'\nexec sleep 30\n",
        )
        Files.setPosixFilePermissions(facade, PosixFilePermissions.fromString("rwx------"))
        val launcher = InstalledCodexClientLauncher(
            kast,
            home,
            mapOf("PATH" to "/usr/bin:/bin"),
        )
        val result = AtomicReference<CodexClientLaunchRun>()
        val thread = Thread {
            result.set(launcher.launch(CodexClientLaunch.Cli))
        }
        thread.start()
        for (attempt in 0..<100) {
            if (Files.exists(pidFile)) break
            Thread.sleep(10)
        }
        assertTrue(Files.exists(pidFile))
        val pid = Files.readString(pidFile).toLong()

        thread.interrupt()
        thread.join(5_000)

        assertFalse(thread.isAlive)
        assertEquals(
            CodexClientLaunchRun.Rejected(CodexClientLaunchFailure.INTERRUPTED),
            result.get(),
        )
        assertFalse(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false))
    }

    private class CapturedProcessLauncher(
        private val exitCode: Int = 0,
    ) : CodexClientProcessLauncher {
        lateinit var request: CodexClientProcessRequest

        override fun launch(request: CodexClientProcessRequest): CodexClientLaunchRun {
            this.request = request
            return CodexClientLaunchRun.Completed(exitCode)
        }
    }

    private fun executable(path: Path): Path {
        Files.writeString(path, "#!/bin/sh\nexit 0\n")
        Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rwx------"))
        return path.toRealPath()
    }
}
