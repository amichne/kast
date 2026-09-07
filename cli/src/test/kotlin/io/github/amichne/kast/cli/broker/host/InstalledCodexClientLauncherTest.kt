package io.github.amichne.kast.cli.broker.host

import io.github.amichne.kast.cli.broker.BrokerServiceLaunchCommand
import io.github.amichne.kast.cli.broker.PersistentBrokerServiceAdmission
import io.github.amichne.kast.cli.broker.PersistentBrokerServiceHost
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
    fun `desktop selects the standard local App Server without substituting the Codex executable`(
        @TempDir temporary: Path,
    ) {
        val bin = Files.createDirectory(temporary.resolve("bin"))
        val home = Files.createDirectory(temporary.resolve("home")).toRealPath()
        val kast = executable(bin.resolve("kast"))
        val upstream = executable(bin.resolve("codex"))
        val capture = temporary.resolve("desktop-environment")
        val desktop = bin.resolve("Codex Desktop")
        Files.writeString(
            desktop,
            "#!/bin/sh\nprintf '%s\\n%s\\n%s\\n' \"\${CODEX_CLI_PATH:-}\" " +
                "\"\${CODEX_APP_SERVER_USE_LOCAL_DAEMON:-}\" \"\${CODEX_HOME:-}\" > \"$capture\"\n",
        )
        Files.setPosixFilePermissions(desktop, PosixFilePermissions.fromString("rwx------"))
        val launcher = InstalledCodexClientLauncher(
            kast,
            home,
            mapOf(
                "PATH" to bin.toString(),
                "CODEX_EXECUTABLE" to upstream.toString(),
                "KAST_CODEX_DESKTOP_EXECUTABLE" to desktop.toString(),
            ),
            serviceHost = ReadyServiceHost(),
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
            listOf("", "1", home.resolve(".codex").toString()),
            Files.readAllLines(capture),
        )
    }

    @Test
    fun `desktop launch receives the standard Codex home after ensuring the service`(
        @TempDir temporary: Path,
    ) {
        val bin = Files.createDirectory(temporary.resolve("bin"))
        val home = Files.createDirectory(temporary.resolve("home")).toRealPath()
        val kast = executable(bin.resolve("kast"))
        val upstream = executable(bin.resolve("codex"))
        val desktop = executable(bin.resolve("Codex Desktop"))
        val processLauncher = CapturedProcessLauncher()
        val serviceHost = ReadyServiceHost()
        val launcher = InstalledCodexClientLauncher(
            kast,
            home,
            mapOf(
                "PATH" to bin.toString(),
                "CODEX_EXECUTABLE" to upstream.toString(),
                "KAST_CODEX_DESKTOP_EXECUTABLE" to desktop.toString(),
            ),
            processLauncher,
            serviceHost,
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
        assertEquals(home.resolve(".codex"), request.codexHome)
        assertEquals(home.resolve(".codex"), serviceHost.command.codexHome)
    }

    @Test
    fun `cli launch delegates to real Codex through the persistent App Server`(
        @TempDir temporary: Path,
    ) {
        val bin = Files.createDirectory(temporary.resolve("bin"))
        val home = Files.createDirectory(temporary.resolve("home")).toRealPath()
        val kast = executable(bin.resolve("kast"))
        val upstream = executable(bin.resolve("codex"))
        val processLauncher = CapturedProcessLauncher(exitCode = 17)
        val serviceHost = ReadyServiceHost()
        val launcher = InstalledCodexClientLauncher(
            kast,
            home,
            mapOf("PATH" to bin.toString()),
            processLauncher,
            serviceHost,
        )

        assertEquals(
            CodexClientLaunchRun.Completed(17),
            launcher.launch(CodexClientLaunch.Cli),
        )
        val request = assertInstanceOf(
            CodexClientProcessRequest.Cli::class.java,
            processLauncher.request,
        )
        assertEquals(upstream, request.upstream.path)
        assertEquals(home.resolve(".codex/app-server-control/app-server-control.sock"), request.publicSocket)
        assertEquals(home.resolve(".codex"), request.codexHome)
        assertEquals(request.publicSocket, serviceHost.command.publicSocket)
    }

    @Test
    fun `interrupted CLI launch destroys its owned real Codex process`(
        @TempDir temporary: Path,
    ) {
        val bin = Files.createDirectory(temporary.resolve("bin"))
        val home = Files.createDirectory(temporary.resolve("home")).toRealPath()
        val kast = executable(bin.resolve("kast"))
        val pidFile = temporary.resolve("codex.pid")
        val codex = bin.resolve("codex")
        Files.writeString(
            codex,
            "#!/bin/sh\nprintf '%s' \"\$\$\" > '$pidFile'\nexec sleep 30\n",
        )
        Files.setPosixFilePermissions(codex, PosixFilePermissions.fromString("rwx------"))
        val launcher = InstalledCodexClientLauncher(
            kast,
            home,
            mapOf("PATH" to bin.toString()),
            serviceHost = ReadyServiceHost(),
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

    @Test
    fun `disabled App Server fails before any process is launched`(
        @TempDir temporary: Path,
    ) {
        val bin = Files.createDirectory(temporary.resolve("bin"))
        val home = Files.createDirectory(temporary.resolve("home")).toRealPath()
        val kast = executable(bin.resolve("kast"))
        executable(bin.resolve("codex"))
        val processLauncher = CapturedProcessLauncher()

        assertEquals(
            CodexClientLaunchRun.Rejected(CodexClientLaunchFailure.APP_SERVER_DISABLED),
            InstalledCodexClientLauncher(
                kast,
                home,
                mapOf(
                    "PATH" to bin.toString(),
                    "KAST_ENABLE_APP_SERVER" to "0",
                ),
                processLauncher,
                ReadyServiceHost(),
            ).launch(CodexClientLaunch.Cli),
        )
        assertFalse(processLauncher.wasCalled)
    }

    private class CapturedProcessLauncher(
        private val exitCode: Int = 0,
    ) : CodexClientProcessLauncher {
        lateinit var request: CodexClientProcessRequest
        val wasCalled: Boolean get() = this::request.isInitialized

        override fun launch(request: CodexClientProcessRequest): CodexClientLaunchRun {
            this.request = request
            return CodexClientLaunchRun.Completed(exitCode)
        }
    }

    private class ReadyServiceHost(
        private val admission: PersistentBrokerServiceAdmission =
            PersistentBrokerServiceAdmission.Ready,
    ) : PersistentBrokerServiceHost {
        lateinit var command: BrokerServiceLaunchCommand

        override fun ensure(command: BrokerServiceLaunchCommand): PersistentBrokerServiceAdmission {
            this.command = command
            return admission
        }
    }

    private fun executable(path: Path): Path {
        Files.writeString(path, "#!/bin/sh\nexit 0\n")
        Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rwx------"))
        return path.toRealPath()
    }
}
