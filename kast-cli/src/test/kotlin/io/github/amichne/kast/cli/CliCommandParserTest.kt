package io.github.amichne.kast.cli

import io.github.amichne.kast.api.RefreshQuery
import io.github.amichne.kast.api.SemanticInsertionTarget
import io.github.amichne.kast.api.TypeHierarchyDirection
import org.junit.jupiter.api.Assertions.assertEquals
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
                "call",
                "hierarchy",
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
        assertEquals(io.github.amichne.kast.api.CallDirection.INCOMING, hierarchyCommand.query.direction)
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
                "symbol",
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
                "type",
                "hierarchy",
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
                "semantic",
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
                "imports",
                "optimize",
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
    fun `usage errors include command specific help`() {
        val failure = assertThrows<CliFailure> {
            parser.parse(
                arrayOf(
                    "edits",
                    "apply",
                    "--workspace-root=$tempDir",
                ),
            )
        }

        assertEquals("CLI_USAGE", failure.code)
        assertTrue(checkNotNull(failure.details["usage"]).contains("edits apply"))
        assertTrue(checkNotNull(failure.details["help"]).contains("help edits apply"))
    }
}
