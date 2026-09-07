package io.github.amichne.kast.cli.broker

import io.github.amichne.kast.kernel.Refinement
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions

class CodexHostExecutableTest {
    @Test
    fun `upstream executable is distinct from the Desktop facade`(
        @TempDir temporary: Path,
    ) {
        val facadePath = executable(temporary.resolve("kast-codex"))
        val upstreamPath = executable(temporary.resolve("codex"))
        val facades = DesktopFacadeExecutables.resolve(facadePath, null)

        assertEquals(
            upstreamPath,
            refined(UpstreamCodexExecutable.admit(upstreamPath, facades)).path,
        )
        assertEquals(
            Refinement.Rejected(CodexHostExecutableFailure.RECURSIVE_FACADE),
            UpstreamCodexExecutable.admit(facadePath, facades),
        )
    }

    @Test
    fun `hard linked facade identity is rejected as recursive`(
        @TempDir temporary: Path,
    ) {
        val facadePath = executable(temporary.resolve("kast-codex"))
        val alias = Files.createLink(temporary.resolve("codex"), facadePath)
        val facades = DesktopFacadeExecutables.resolve(facadePath, null)

        assertEquals(
            Refinement.Rejected(CodexHostExecutableFailure.RECURSIVE_FACADE),
            UpstreamCodexExecutable.admit(alias, facades),
        )
    }

    @Test
    fun `real Codex override wins without consulting facade substitution`(
        @TempDir temporary: Path,
    ) {
        val bin = Files.createDirectory(temporary.resolve("bin"))
        val userHome = Files.createDirectory(temporary.resolve("home")).toRealPath()
        val kast = executable(bin.resolve("kast"))
        val facade = executable(bin.resolve("kast-codex"))
        val upstream = executable(bin.resolve("codex"))
        val resolution = BrokerServiceLaunchCommand.resolve(
            kast,
            userHome,
            mapOf(
                "PATH" to "/usr/bin:/bin",
                "CODEX_CLI_PATH" to facade.toString(),
                "KAST_REAL_CODEX_EXECUTABLE" to upstream.toString(),
            ),
        )

        val command = assertInstanceOf(
            BrokerServiceLaunchCommandResolution.Resolved::class.java,
            resolution,
        ).command
        assertEquals(upstream, command.codex.path)
    }

    @Test
    fun `installed sibling remains a recursion guard when configured facade differs`(
        @TempDir temporary: Path,
    ) {
        val bin = Files.createDirectory(temporary.resolve("bin"))
        val userHome = Files.createDirectory(temporary.resolve("home")).toRealPath()
        val kast = executable(bin.resolve("kast"))
        val installedFacade = executable(bin.resolve("kast-codex"))
        val configuredFacade = executable(bin.resolve("configured-codex-facade"))

        assertEquals(
            BrokerServiceLaunchCommandResolution.Rejected(
                PersistentBrokerServiceFailure.CODEX_EXECUTABLE_UNAVAILABLE,
            ),
            BrokerServiceLaunchCommand.resolve(
                kast,
                userHome,
                mapOf(
                    "PATH" to "/usr/bin:/bin",
                    "CODEX_CLI_PATH" to configuredFacade.toString(),
                    "KAST_REAL_CODEX_EXECUTABLE" to installedFacade.toString(),
                ),
            ),
        )
    }

    private fun executable(path: Path): Path {
        Files.writeString(path, "#!/bin/sh\nexit 0\n")
        Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rwx------"))
        return path.toRealPath()
    }

    private fun <T, E> refined(refinement: Refinement<T, E>): T =
        (refinement as Refinement.Refined).value
}
