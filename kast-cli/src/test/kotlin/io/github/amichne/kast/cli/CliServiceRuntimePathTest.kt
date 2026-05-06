package io.github.amichne.kast.cli

import io.github.amichne.kast.api.client.KastConfig
import io.github.amichne.kast.api.client.kastConfigHome
import io.github.amichne.kast.cli.options.DaemonStartOptions
import io.github.amichne.kast.cli.tty.CliFailure
import io.github.amichne.kast.cli.tty.CliOutput
import io.github.amichne.kast.cli.tty.CliService
import io.github.amichne.kast.cli.tty.defaultCliJson
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class CliServiceRuntimePathTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun daemonStartFindsStandaloneRuntimeLibsFromConfig() {
        val runtimeLibs = tempDir.resolve("runtime-libs")
        Files.createDirectories(runtimeLibs)
        Files.writeString(runtimeLibs.resolve("classpath.txt"), "standalone.jar\n")
        val defaults = KastConfig.defaults()
        val config = defaults.copy(
            backends = defaults.backends.copy(
                standalone = defaults.backends.standalone.copy(
                    runtimeLibsDir = io.github.amichne.kast.api.client.fields.StandaloneRuntimeLibsDir(
                        io.github.amichne.kast.api.client.fields.OptionalConfigString(runtimeLibs.toString()),
                    ),
                ),
            ),
        )

        val service = CliService(
            json = defaultCliJson(),
            configLoader = { config },
        )

        val output = service.daemonStart(
            DaemonStartOptions(
                standaloneArgs = listOf("--workspace-root=${tempDir.resolve("workspace")}"),
                workspaceRoot = tempDir.resolve("workspace"),
                runtimeLibsDir = null,
            ),
        )

        val command = (output as CliOutput.ExternalProcess).process.command
        assertTrue(command.contains("-cp"))
        assertEquals(
            runtimeLibs.resolve("standalone.jar").toString(),
            command[command.indexOf("-cp") + 1],
        )
    }

    @Test
    fun daemonStartUsesExplicitRuntimeLibsDirOverConfig() {
        val explicitRuntimeLibs = tempDir.resolve("explicit-runtime-libs")
        Files.createDirectories(explicitRuntimeLibs)
        Files.writeString(explicitRuntimeLibs.resolve("classpath.txt"), "explicit.jar\n")

        val service = CliService(
            json = defaultCliJson(),
            configLoader = { KastConfig.defaults() },
            envLookup = { null },
        )

        val output = service.daemonStart(
            DaemonStartOptions(
                standaloneArgs = listOf("--workspace-root=${tempDir.resolve("workspace")}"),
                workspaceRoot = tempDir.resolve("workspace"),
                runtimeLibsDir = explicitRuntimeLibs,
            ),
        )

        val command = (output as CliOutput.ExternalProcess).process.command
        assertTrue(command.contains("-cp"))
        assertEquals(
            explicitRuntimeLibs.resolve("explicit.jar").toString(),
            command[command.indexOf("-cp") + 1],
        )
    }

    @Test
    fun daemonStartThrowsWhenNoRuntimeLibsFound() {
        val service = CliService(
            json = defaultCliJson(),
            configLoader = { KastConfig.defaults() },
            envLookup = { null },
        )

        val failure = assertThrows<CliFailure> {
            service.daemonStart(
                DaemonStartOptions(
                    standaloneArgs = listOf("--workspace-root=${tempDir.resolve("workspace")}"),
                    workspaceRoot = tempDir.resolve("workspace"),
                    runtimeLibsDir = null,
                ),
            )
        }
        assertEquals("DAEMON_START_ERROR", failure.code)
        assertTrue(failure.message.contains("backends.standalone.runtimeLibsDir"))
    }

    @Test
    fun configInitWritesToKastConfigHomeFromEnvLookup() {
        val configHome = tempDir.resolve("custom-config-home")
        val service = CliService(
            json = defaultCliJson(),
            configLoader = { KastConfig.defaults() },
            envLookup = mapOf("KAST_CONFIG_HOME" to configHome.toString())::get,
        )

        service.configInit()

        assertTrue(
            Files.isRegularFile(configHome.resolve("config.toml")),
            "config.toml should be written to KAST_CONFIG_HOME",
        )
        assertEquals(
            configHome.toAbsolutePath().normalize(),
            kastConfigHome(mapOf("KAST_CONFIG_HOME" to configHome.toString())::get),
        )
    }
}
