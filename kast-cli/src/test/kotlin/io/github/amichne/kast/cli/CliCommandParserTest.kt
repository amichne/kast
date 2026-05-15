package io.github.amichne.kast.cli

import io.github.amichne.kast.cli.options.BackendName
import io.github.amichne.kast.cli.tty.CliCommand
import io.github.amichne.kast.cli.tty.CliCommandParser
import io.github.amichne.kast.cli.tty.CliCompletionShell
import io.github.amichne.kast.cli.tty.CliFailure
import io.github.amichne.kast.cli.tty.MetricsSubcommand
import io.github.amichne.kast.cli.tty.defaultCliJson
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class CliCommandParserTest {
    private val parser = CliCommandParser(defaultCliJson())

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `no arguments opens help`() {
        val command = parser.parse(emptyArray())

        assertEquals(CliCommand.Help(), command)
    }

    @Test
    fun `namespace arguments open contextual help`() {
        val command = parser.parse(arrayOf("workspace"))

        assertEquals(CliCommand.Help(listOf("workspace")), command)
    }

    @Test
    fun `completion namespace opens contextual help`() {
        val command = parser.parse(arrayOf("completion"))

        assertEquals(CliCommand.Help(listOf("completion")), command)
    }

    @Test
    fun `scoped help flag keeps the command topic`() {
        val command = parser.parse(arrayOf("workspace", "status", "--help"))

        assertEquals(CliCommand.Help(listOf("workspace", "status")), command)
    }

    @Test
    fun `completion bash parses to completion command`() {
        val command = parser.parse(arrayOf("completion", "bash"))

        assertEquals(CliCommand.Completion(CliCompletionShell.BASH), command)
    }



    @Test
    fun `workspace ensure parses accept indexing`() {
        val command = parser.parse(
            arrayOf(
                "workspace",
                "ensure",
                "--workspace-root=$tempDir",
                "--accept-indexing=true",
            ),
        )

        assertTrue(command is CliCommand.WorkspaceEnsure)
        val ensureCommand = command as CliCommand.WorkspaceEnsure
        assertTrue(ensureCommand.options.acceptIndexing)
    }






    @Test
    fun `version flag returns version command`() {
        val command = parser.parse(arrayOf("--version"))

        assertSame(CliCommand.Version, command)
    }

    @Test
    fun `verify extension parses`() {
        val command = parser.parse(arrayOf("verify-extension"))

        assertSame(CliCommand.VerifyExtension, command)
    }



    @Test
    fun `self status parses`() {
        val command = parser.parse(arrayOf("self", "status"))

        assertSame(CliCommand.SelfStatus, command)
    }

    @Test
    fun `self doctor parses`() {
        val command = parser.parse(arrayOf("self", "doctor"))

        assertSame(CliCommand.SelfDoctor, command)
    }

    @Test
    fun `self uninstall parses`() {
        val command = parser.parse(arrayOf("self", "uninstall"))

        assertSame(CliCommand.SelfUninstall, command)
    }

    @Test
    fun `self upgrade parses`() {
        val command = parser.parse(arrayOf("self", "upgrade"))

        assertSame(CliCommand.SelfUpgrade, command)
    }
    @Test
    fun `smoke parses workspace root filters and format`() {
        val command = parser.parse(
            arrayOf(
                "smoke",
                "--workspace-root=$tempDir",
                "--file=CliCommandCatalog.kt",
                "--source-set=:kast-cli:test",
                "--symbol=KastCli",
                "--format=markdown",
            ),
        )

        assertTrue(command is CliCommand.Smoke)
        val smokeCommand = command as CliCommand.Smoke
        assertEquals(tempDir, smokeCommand.options.workspaceRoot)
        assertEquals("CliCommandCatalog.kt", smokeCommand.options.fileFilter)
        assertEquals(":kast-cli:test", smokeCommand.options.sourceSetFilter)
        assertEquals("KastCli", smokeCommand.options.symbolFilter)
        assertEquals(SmokeOutputFormat.MARKDOWN, smokeCommand.options.format)
    }

    @Test
    fun `gradle run parses task positional and extra args`() {
        val command = parser.parse(
            arrayOf(
                "gradle",
                "run",
                ":kast-cli:test",
                "--workspace-root=$tempDir",
                "--args=--stacktrace,--info",
            ),
        )

        assertTrue(command is CliCommand.GradleRun)
        val gradleCommand = command as CliCommand.GradleRun
        assertEquals(tempDir, gradleCommand.workspaceRoot)
        assertEquals(":kast-cli:test", gradleCommand.task)
        assertEquals(listOf("--stacktrace", "--info"), gradleCommand.extraArgs)
    }

    @Test
    fun `gradle run parses task option`() {
        val command = parser.parse(
            arrayOf(
                "gradle",
                "run",
                "--workspace-root=$tempDir",
                "--task=:index-store:test",
            ),
        )

        assertTrue(command is CliCommand.GradleRun)
        assertEquals(":index-store:test", (command as CliCommand.GradleRun).task)
    }

    @Test
    fun `smoke rejects dir alias`() {
        val failure = assertThrows<CliFailure> {
            parser.parse(
                arrayOf(
                    "smoke",
                    "--dir=$tempDir",
                ),
            )
        }

        assertEquals("CLI_USAGE", failure.code)
        assertTrue(failure.message.contains("--workspace-root"))
    }

    @Test
    fun `smoke rejects invalid format`() {
        val failure = assertThrows<CliFailure> {
            parser.parse(
                arrayOf(
                    "smoke",
                    "--format=html",
                ),
            )
        }

        assertEquals("CLI_USAGE", failure.code)
        assertTrue(failure.message.contains("json or markdown"))
    }

    @Test
    fun `install parses kast home defaults for releases and bin`() {
        val archivePath = tempDir.resolve("kast-portable.zip")

        val command = parser.parse(
            arrayOf(
                "install",
                "--archive=$archivePath",
            ),
        )

        assertTrue(command is CliCommand.Install)
        val installCommand = command as CliCommand.Install
        val home = Path.of(System.getProperty("user.home")).toAbsolutePath().normalize()
        assertEquals(home.resolve(".kast/releases"), installCommand.options.instancesRoot)
        assertEquals(home.resolve(".kast/bin"), installCommand.options.binDir)
    }

    @Test
    fun `install skill parses the primary name option`() {
        val command = parser.parse(
            arrayOf(
                "install",
                "skill",
                "--target-dir=$tempDir",
                "--name=kast-ci",
                "--yes=true",
            ),
        )

        assertTrue(command is CliCommand.InstallSkill)
        val installSkillCommand = command as CliCommand.InstallSkill
        assertEquals(tempDir, installSkillCommand.options.targetDir)
        assertEquals("kast-ci", installSkillCommand.options.name)
        assertTrue(installSkillCommand.options.force)
    }

    @Test
    fun `install skill accepts link-name as a compatibility alias`() {
        val command = parser.parse(
            arrayOf(
                "install",
                "skill",
                "--target-dir=$tempDir",
                "--link-name=kast-legacy",
            ),
        )

        assertTrue(command is CliCommand.InstallSkill)
        val installSkillCommand = command as CliCommand.InstallSkill
        assertEquals("kast-legacy", installSkillCommand.options.name)
    }

    @Test
    fun `install copilot extension parses target and force options`() {
        val command = parser.parse(
            arrayOf(
                "install",
                "copilot-extension",
                "--target-dir=$tempDir",
                "--yes=true",
                "--uninstall=true",
            ),
        )

        assertTrue(command is CliCommand.InstallCopilotExtension)
        val installCommand = command as CliCommand.InstallCopilotExtension
        assertEquals(tempDir, installCommand.options.targetDir)
        assertTrue(installCommand.options.force)
        assertTrue(installCommand.options.uninstall)
    }

    @Test
    fun `runtimeOptions accepts intellij backend name`() {
        val command = parser.parse(
            arrayOf(
                "workspace",
                "status",
                "--workspace-root=$tempDir",
                "--backend-name=intellij",
            ),
        )

        assertTrue(command is CliCommand.WorkspaceStatus)
        val statusCommand = command as CliCommand.WorkspaceStatus
        assertEquals(BackendName.INTELLIJ, statusCommand.options.backendName)
    }

    @Test
    fun `runtimeOptions accepts null backend name for auto-selection`() {
        val command = parser.parse(
            arrayOf(
                "workspace",
                "status",
                "--workspace-root=$tempDir",
            ),
        )

        assertTrue(command is CliCommand.WorkspaceStatus)
        val statusCommand = command as CliCommand.WorkspaceStatus
        // When no --backend-name is specified, it should be null (auto-select)
        assertEquals(null, statusCommand.options.backendName)
    }

    @Test
    fun `runtimeOptions rejects invalid backend name`() {
        val failure = assertThrows<CliFailure> {
            parser.parse(
                arrayOf(
                    "workspace",
                    "status",
                    "--workspace-root=$tempDir",
                    "--backend-name=foo",
                ),
            )
        }

        assertEquals("CLI_USAGE", failure.code)
        assertTrue(failure.message.contains("Unsupported --backend-name=foo"))
    }

    @Test
    fun `workspace stop parses from workspace root`() {
        val command = parser.parse(
            arrayOf(
                "workspace",
                "stop",
                "--workspace-root=$tempDir",
            ),
        )

        assertTrue(command is CliCommand.WorkspaceStop)
        val stopCommand = command as CliCommand.WorkspaceStop
        assertEquals(tempDir, stopCommand.options.workspaceRoot.toJavaPath())
    }

    @Test
    fun `daemon start parses workspace root`() {
        val command = parser.parse(
            arrayOf(
                "daemon",
                "start",
                "--workspace-root=$tempDir",
            ),
        ) as CliCommand.DaemonStart

        assertEquals(tempDir, command.options.workspaceRoot)
        assertTrue(command.options.standaloneArgs.any { it.contains("workspace-root") })
        assertNull(command.options.runtimeLibsDir)
    }

    @Test
    fun `daemon start passes runtime-libs-dir when provided`() {
        val runtimeLibsDir = tempDir.resolve("runtime-libs")
        val command = parser.parse(
            arrayOf(
                "daemon",
                "start",
                "--workspace-root=$tempDir",
                "--runtime-libs-dir=$runtimeLibsDir",
            ),
        ) as CliCommand.DaemonStart

        assertEquals(runtimeLibsDir, command.options.runtimeLibsDir)
        assertTrue(command.options.standaloneArgs.none { it.contains("runtime-libs-dir") })
    }

    @Test
    fun `daemon start forwards profile flags to backend`() {
        val command = parser.parse(
            arrayOf(
                "daemon",
                "start",
                "--workspace-root=$tempDir",
                "--profile",
                "--profile-modes=cpu,alloc",
                "--profile-duration=45",
                "--profile-otlp-endpoint=http://localhost:4317",
            ),
        ) as CliCommand.DaemonStart

        assertTrue(command.options.standaloneArgs.contains("--profile"))
        assertTrue(command.options.standaloneArgs.contains("--profile-modes=cpu,alloc"))
        assertTrue(command.options.standaloneArgs.contains("--profile-duration=45"))
        assertTrue(command.options.standaloneArgs.contains("--profile-otlp-endpoint=http://localhost:4317"))
    }

    @Test
    fun `config init parses`() {
        val command = parser.parse(arrayOf("config", "init"))

        assertEquals(CliCommand.ConfigInit, command)
    }

    @Test
    fun `daemon stop is unknown`() {
        val failure = assertThrows<CliFailure> {
            parser.parse(
                arrayOf(
                    "daemon",
                    "stop",
                    "--workspace-root=$tempDir",
                ),
            )
        }

        assertEquals("CLI_USAGE", failure.code)
    }













    @Test
    fun `metrics fan-in parses with defaults`() {
        val command = parser.parse(
            arrayOf("metrics", "fan-in", "--workspace-root=$tempDir"),
        )

        assertTrue(command is CliCommand.Metrics)
        val metrics = command as CliCommand.Metrics
        assertEquals(MetricsSubcommand.FAN_IN, metrics.subcommand)
        assertEquals(tempDir.toAbsolutePath().normalize(), metrics.workspaceRoot)
        assertEquals(50, metrics.limit)
    }

    @Test
    fun `metrics fan-out parses with custom limit`() {
        val command = parser.parse(
            arrayOf("metrics", "fan-out", "--workspace-root=$tempDir", "--limit=20"),
        )

        assertTrue(command is CliCommand.Metrics)
        val metrics = command as CliCommand.Metrics
        assertEquals(MetricsSubcommand.FAN_OUT, metrics.subcommand)
        assertEquals(20, metrics.limit)
    }

    @Test
    fun `metrics coupling parses`() {
        val command = parser.parse(
            arrayOf("metrics", "coupling", "--workspace-root=$tempDir"),
        )

        assertTrue(command is CliCommand.Metrics)
        assertEquals(MetricsSubcommand.COUPLING, (command as CliCommand.Metrics).subcommand)
    }

    @Test
    fun `metrics dead-code parses`() {
        val command = parser.parse(
            arrayOf("metrics", "dead-code", "--workspace-root=$tempDir"),
        )

        assertTrue(command is CliCommand.Metrics)
        assertEquals(MetricsSubcommand.DEAD_CODE, (command as CliCommand.Metrics).subcommand)
    }

    @Test
    fun `metrics low-usage parses`() {
        val command = parser.parse(
            arrayOf("metrics", "low-usage", "--workspace-root=$tempDir", "--limit=10"),
        )

        assertTrue(command is CliCommand.Metrics)
        val metrics = command as CliCommand.Metrics
        assertEquals(MetricsSubcommand.LOW_USAGE, metrics.subcommand)
        assertEquals(10, metrics.limit)
    }

    @Test
    fun `metrics cycles parses`() {
        val command = parser.parse(
            arrayOf("metrics", "cycles", "--workspace-root=$tempDir"),
        )

        assertTrue(command is CliCommand.Metrics)
        assertEquals(MetricsSubcommand.CYCLES, (command as CliCommand.Metrics).subcommand)
    }

    @Test
    fun `metrics module-depth parses`() {
        val command = parser.parse(
            arrayOf("metrics", "module-depth", "--workspace-root=$tempDir"),
        )

        assertTrue(command is CliCommand.Metrics)
        assertEquals(MetricsSubcommand.MODULE_DEPTH, (command as CliCommand.Metrics).subcommand)
    }

    @Test
    fun `metrics impact parses with required symbol`() {
        val command = parser.parse(
            arrayOf("metrics", "impact", "--workspace-root=$tempDir", "--symbol=com.example.Foo"),
        )

        assertTrue(command is CliCommand.Metrics)
        val metrics = command as CliCommand.Metrics
        assertEquals(MetricsSubcommand.IMPACT, metrics.subcommand)
        assertEquals("com.example.Foo", metrics.symbol)
        assertEquals(3, metrics.depth)
    }

    @Test
    fun `metrics impact with custom depth`() {
        val command = parser.parse(
            arrayOf("metrics", "impact", "--workspace-root=$tempDir", "--symbol=com.example.Foo", "--depth=5"),
        )

        val metrics = command as CliCommand.Metrics
        assertEquals(5, metrics.depth)
    }

    @Test
    fun `metrics graph parses interactive option`() {
        val command = parser.parse(
            arrayOf("metrics", "graph", "--workspace-root=$tempDir", "--symbol=com.example.Foo", "--depth=2", "--interactive=true"),
        )

        assertTrue(command is CliCommand.Metrics)
        val metrics = command as CliCommand.Metrics
        assertEquals(MetricsSubcommand.GRAPH, metrics.subcommand)
        assertEquals("com.example.Foo", metrics.symbol)
        assertEquals(2, metrics.depth)
        assertTrue(metrics.interactive)
    }

    @Test
    fun `metrics impact fails without symbol`() {
        assertThrows<CliFailure> {
            parser.parse(
                arrayOf("metrics", "impact", "--workspace-root=$tempDir"),
            )
        }
    }

    @Test
    fun `metrics fan-in defaults workspace root to current working directory when not provided`() {
        val command = parser.parse(arrayOf("metrics", "fan-in"))
        assertTrue(command is CliCommand.Metrics)
        val metrics = command as CliCommand.Metrics
        assertEquals(MetricsSubcommand.FAN_IN, metrics.subcommand)
        assertEquals(Path.of(System.getProperty("user.dir", ".")).toAbsolutePath().normalize(), metrics.workspaceRoot)
    }
}
