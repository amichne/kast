package io.github.amichne.kast.cli

import io.github.amichne.kast.api.contract.query.RefreshQuery
import io.github.amichne.kast.api.contract.SemanticInsertionTarget
import io.github.amichne.kast.api.contract.SymbolKind
import io.github.amichne.kast.api.contract.TypeHierarchyDirection
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
    fun `call hierarchy parses from inline options`() {
        val command = parser.parse(
            arrayOf(
                "call-hierarchy",
                "--workspace-root=$tempDir",
                "--file-path=$tempDir/Sample.kt",
                "--offset=12",
                "--direction=incoming",
                "--depth=0",
                "--max-total-calls=32",
                "--max-children-per-node=8",
                "--timeout-millis=4000",
            ),
        )

        assertTrue(command is CliCommand.CallHierarchy)
        val hierarchyCommand = command as CliCommand.CallHierarchy
        assertEquals(tempDir, hierarchyCommand.options.workspaceRoot)
        assertEquals(io.github.amichne.kast.api.contract.CallDirection.INCOMING, hierarchyCommand.query.direction)
        assertEquals(0, hierarchyCommand.query.depth)
        assertEquals(32, hierarchyCommand.query.maxTotalCalls)
        assertEquals(8, hierarchyCommand.query.maxChildrenPerNode)
        assertEquals(4000L, hierarchyCommand.query.timeoutMillis)
    }

    @Test
    fun `workspace refresh parses from inline options`() {
        val command = parser.parse(
            arrayOf(
                "workspace",
                "refresh",
                "--workspace-root=$tempDir",
                "--file-paths=$tempDir/A.kt,$tempDir/B.kt",
            ),
        )

        assertTrue(command is CliCommand.WorkspaceRefresh)
        val refreshCommand = command as CliCommand.WorkspaceRefresh
        assertEquals(tempDir, refreshCommand.options.workspaceRoot)
        assertEquals(
            RefreshQuery(
                filePaths = listOf(
                    tempDir.resolve("A.kt").toString(),
                    tempDir.resolve("B.kt").toString(),
                ),
            ),
            refreshCommand.query,
        )
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
    fun `symbol resolve parses no auto start`() {
        val command = parser.parse(
            arrayOf(
                "resolve",
                "--workspace-root=$tempDir",
                "--file-path=$tempDir/Sample.kt",
                "--offset=12",
                "--no-auto-start=true",
            ),
        )

        assertTrue(command is CliCommand.ResolveSymbol)
        val resolveCommand = command as CliCommand.ResolveSymbol
        assertTrue(resolveCommand.options.noAutoStart)
    }

    @Test
    fun `type hierarchy parses from inline options`() {
        val command = parser.parse(
            arrayOf(
                "type-hierarchy",
                "--workspace-root=$tempDir",
                "--file-path=$tempDir/Types.kt",
                "--offset=18",
                "--direction=both",
                "--depth=2",
                "--max-results=24",
            ),
        )

        assertTrue(command is CliCommand.TypeHierarchy)
        val hierarchyCommand = command as CliCommand.TypeHierarchy
        assertEquals(tempDir, hierarchyCommand.options.workspaceRoot)
        assertEquals(TypeHierarchyDirection.BOTH, hierarchyCommand.query.direction)
        assertEquals(2, hierarchyCommand.query.depth)
        assertEquals(24, hierarchyCommand.query.maxResults)
    }

    @Test
    fun `semantic insertion point parses from inline options`() {
        val command = parser.parse(
            arrayOf(
                "insertion-point",
                "--workspace-root=$tempDir",
                "--file-path=$tempDir/Types.kt",
                "--offset=18",
                "--target=after-imports",
            ),
        )

        assertTrue(command is CliCommand.SemanticInsertionPoint)
        val insertionCommand = command as CliCommand.SemanticInsertionPoint
        assertEquals(tempDir, insertionCommand.options.workspaceRoot)
        assertEquals(SemanticInsertionTarget.AFTER_IMPORTS, insertionCommand.query.target)
    }

    @Test
    fun `imports optimize parses from inline options`() {
        val command = parser.parse(
            arrayOf(
                "optimize-imports",
                "--workspace-root=$tempDir",
                "--file-paths=$tempDir/A.kt,$tempDir/B.kt",
            ),
        )

        assertTrue(command is CliCommand.ImportOptimize)
        val optimizeCommand = command as CliCommand.ImportOptimize
        assertEquals(tempDir, optimizeCommand.options.workspaceRoot)
        assertEquals(
            listOf(
                tempDir.resolve("A.kt").toString(),
                tempDir.resolve("B.kt").toString(),
            ),
            optimizeCommand.query.filePaths,
        )
    }

    @Test
    fun `version flag returns version command`() {
        val command = parser.parse(arrayOf("--version"))

        assertSame(CliCommand.Version, command)
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
        assertEquals("intellij", statusCommand.options.backendName)
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
        assertEquals(tempDir, stopCommand.options.workspaceRoot)
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
    fun `usage errors include command specific help`() {
        val failure = assertThrows<CliFailure> {
            parser.parse(
                arrayOf(
                    "apply-edits",
                    "--workspace-root=$tempDir",
                ),
            )
        }

        assertEquals("CLI_USAGE", failure.code)
        assertTrue(checkNotNull(failure.details["usage"]).contains("apply-edits"))
        assertTrue(checkNotNull(failure.details["help"]).contains("help apply-edits"))
    }

    @Test
    fun `file outline parses from inline options`() {
        val command = parser.parse(
            arrayOf(
                "outline",
                "--workspace-root=$tempDir",
                "--file-path=$tempDir/Sample.kt",
            ),
        )

        assertTrue(command is CliCommand.FileOutline)
        val outlineCommand = command as CliCommand.FileOutline
        assertEquals(tempDir, outlineCommand.options.workspaceRoot)
        assertEquals(tempDir.resolve("Sample.kt").toString(), outlineCommand.query.filePath)
    }

    @Test
    fun `workspace symbol parses from inline options`() {
        val command = parser.parse(
            arrayOf(
                "workspace-symbol",
                "--workspace-root=$tempDir",
                "--pattern=MyClass",
            ),
        )

        assertTrue(command is CliCommand.WorkspaceSymbol)
        val symbolCommand = command as CliCommand.WorkspaceSymbol
        assertEquals(tempDir, symbolCommand.options.workspaceRoot)
        assertEquals("MyClass", symbolCommand.query.pattern)
        assertEquals(false, symbolCommand.query.regex)
        assertEquals(100, symbolCommand.query.maxResults)
    }

    @Test
    fun `workspace symbol parses regex and kind options`() {
        val command = parser.parse(
            arrayOf(
                "workspace-symbol",
                "--workspace-root=$tempDir",
                "--pattern=.*Service",
                "--regex=true",
                "--kind=CLASS",
                "--max-results=50",
            ),
        )

        assertTrue(command is CliCommand.WorkspaceSymbol)
        val symbolCommand = command as CliCommand.WorkspaceSymbol
        assertEquals(".*Service", symbolCommand.query.pattern)
        assertEquals(true, symbolCommand.query.regex)
        assertEquals(SymbolKind.CLASS, symbolCommand.query.kind)
        assertEquals(50, symbolCommand.query.maxResults)
    }

    @Test
    fun `resolve parses include-body option`() {
        val command = parser.parse(
            arrayOf(
                "resolve",
                "--workspace-root=$tempDir",
                "--file-path=$tempDir/Sample.kt",
                "--offset=12",
                "--include-body=true",
            ),
        )

        assertTrue(command is CliCommand.ResolveSymbol)
        val resolveCommand = command as CliCommand.ResolveSymbol
        assertEquals(true, resolveCommand.query.includeDeclarationScope)
    }

    @Test
    fun `resolve defaults include-body to false`() {
        val command = parser.parse(
            arrayOf(
                "resolve",
                "--workspace-root=$tempDir",
                "--file-path=$tempDir/Sample.kt",
                "--offset=12",
            ),
        )

        assertTrue(command is CliCommand.ResolveSymbol)
        val resolveCommand = command as CliCommand.ResolveSymbol
        assertEquals(false, resolveCommand.query.includeDeclarationScope)
    }

    @Test
    fun `resolve parses include-documentation option`() {
        val command = parser.parse(
            arrayOf(
                "resolve",
                "--workspace-root=$tempDir",
                "--file-path=$tempDir/Sample.kt",
                "--offset=12",
                "--include-documentation=true",
            ),
        )

        assertTrue(command is CliCommand.ResolveSymbol)
        val resolveCommand = command as CliCommand.ResolveSymbol
        assertEquals(true, resolveCommand.query.includeDocumentation)
    }

    @Test
    fun `workspace symbol parses include-body option`() {
        val command = parser.parse(
            arrayOf(
                "workspace-symbol",
                "--workspace-root=$tempDir",
                "--pattern=MyClass",
                "--include-body=true",
            ),
        )

        assertTrue(command is CliCommand.WorkspaceSymbol)
        val symbolCommand = command as CliCommand.WorkspaceSymbol
        assertEquals(true, symbolCommand.query.includeDeclarationScope)
    }

    @Test
    fun `implementations parses from inline options`() {
        val command = parser.parse(
            arrayOf(
                "implementations",
                "--workspace-root=$tempDir",
                "--file-path=$tempDir/Types.kt",
                "--offset=10",
                "--max-results=5",
            ),
        )

        assertTrue(command is CliCommand.Implementations)
        val implementationsCommand = command as CliCommand.Implementations
        assertEquals(5, implementationsCommand.query.maxResults)
    }

    @Test
    fun `code actions parses inline options`() {
        val command = parser.parse(
            arrayOf(
                "code-actions",
                "--workspace-root=$tempDir",
                "--file-path=$tempDir/Types.kt",
                "--offset=10",
                "--diagnostic-code=UNRESOLVED_REFERENCE",
            ),
        )

        assertTrue(command is CliCommand.CodeActions)
        val codeActionsCommand = command as CliCommand.CodeActions
        assertEquals("UNRESOLVED_REFERENCE", codeActionsCommand.query.diagnosticCode)
    }

    @Test
    fun `completions parses inline options`() {
        val command = parser.parse(
            arrayOf(
                "completions",
                "--workspace-root=$tempDir",
                "--file-path=$tempDir/Types.kt",
                "--offset=10",
                "--max-results=7",
                "--kind-filter=FUNCTION,CLASS",
            ),
        )

        assertTrue(command is CliCommand.Completions)
        val completionsCommand = command as CliCommand.Completions
        assertEquals(7, completionsCommand.query.maxResults)
        assertEquals(setOf(SymbolKind.FUNCTION, SymbolKind.CLASS), completionsCommand.query.kindFilter)
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
    fun `metrics impact fails without symbol`() {
        assertThrows<CliFailure> {
            parser.parse(
                arrayOf("metrics", "impact", "--workspace-root=$tempDir"),
            )
        }
    }

    @Test
    fun `metrics fails without workspace-root`() {
        assertThrows<CliFailure> {
            parser.parse(
                arrayOf("metrics", "fan-in"),
            )
        }
    }
}
