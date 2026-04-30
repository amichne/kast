package io.github.amichne.kast.cli

import io.github.amichne.kast.api.contract.Location
import io.github.amichne.kast.api.contract.result.ImportOptimizeResult
import io.github.amichne.kast.api.contract.Symbol
import io.github.amichne.kast.api.contract.SymbolKind
import io.github.amichne.kast.api.contract.SemanticInsertionResult
import io.github.amichne.kast.api.contract.result.TypeHierarchyNode
import io.github.amichne.kast.api.contract.result.TypeHierarchyResult
import io.github.amichne.kast.api.contract.result.TypeHierarchyStats
import io.github.amichne.kast.api.contract.TextEdit
import io.github.amichne.kast.cli.results.InstallResult
import io.github.amichne.kast.cli.skill.InstallSkillResult
import io.github.amichne.kast.cli.tty.defaultCliJson
import io.github.amichne.kast.cli.tty.writeCliJson
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class CliJsonTest {
    @Test
    fun `writeCliJson serializes install results`() {
        val output = StringBuilder()

        writeCliJson(
            output = output,
            value = InstallResult(
                instanceName = "my-dev",
                instanceRoot = "/tmp/instances/my-dev",
                launcherPath = "/tmp/bin/kast-my-dev",
            ),
            json = defaultCliJson(),
        )

        val result = defaultCliJson().decodeFromString<InstallResult>(output.toString())

        assertEquals("my-dev", result.instanceName)
        assertEquals("/tmp/instances/my-dev", result.instanceRoot)
        assertEquals("/tmp/bin/kast-my-dev", result.launcherPath)
    }

    @Test
    fun `writeCliJson serializes install skill results`() {
        val output = StringBuilder()

        writeCliJson(
            output = output,
            value = InstallSkillResult(
                installedAt = "/tmp/workspace/.agents/skills/kast",
                version = "0.1.1-SNAPSHOT",
                skipped = false,
            ),
            json = defaultCliJson(),
        )

        val result = defaultCliJson().decodeFromString<InstallSkillResult>(output.toString())

        assertEquals("/tmp/workspace/.agents/skills/kast", result.installedAt)
        assertEquals("0.1.1-SNAPSHOT", result.version)
        assertEquals(false, result.skipped)
    }

    @Test
    fun `writeCliJson serializes type hierarchy results`() {
        val output = StringBuilder()

        writeCliJson(
            output = output,
            value = TypeHierarchyResult(
                root = TypeHierarchyNode(
                    symbol = Symbol(
                        fqName = "sample.FriendlyGreeter",
                        kind = SymbolKind.CLASS,
                        location = Location(
                            filePath = "/tmp/workspace/src/Types.kt",
                            startOffset = 10,
                            endOffset = 26,
                            startLine = 4,
                            startColumn = 12,
                            preview = "open class FriendlyGreeter : Greeter",
                        ),
                        supertypes = listOf("sample.Greeter"),
                    ),
                    children = emptyList(),
                ),
                stats = TypeHierarchyStats(
                    totalNodes = 1,
                    maxDepthReached = 0,
                    truncated = false,
                ),
            ),
            json = defaultCliJson(),
        )

        val result = defaultCliJson().decodeFromString<TypeHierarchyResult>(output.toString())

        assertEquals("sample.FriendlyGreeter", result.root.symbol.fqName)
        assertEquals(listOf("sample.Greeter"), result.root.symbol.supertypes)
        assertEquals(false, result.stats.truncated)
    }

    @Test
    fun `writeCliJson serializes semantic insertion results`() {
        val output = StringBuilder()

        writeCliJson(
            output = output,
            value = SemanticInsertionResult(
                insertionOffset = 42,
                filePath = "/tmp/workspace/src/Types.kt",
            ),
            json = defaultCliJson(),
        )

        val result = defaultCliJson().decodeFromString<SemanticInsertionResult>(output.toString())

        assertEquals(42, result.insertionOffset)
        assertEquals("/tmp/workspace/src/Types.kt", result.filePath)
    }

    @Test
    fun `writeCliJson serializes import optimize results`() {
        val output = StringBuilder()

        writeCliJson(
            output = output,
            value = ImportOptimizeResult(
                edits = listOf(
                    TextEdit(
                        filePath = "/tmp/workspace/src/Sample.kt",
                        startOffset = 10,
                        endOffset = 28,
                        newText = "",
                    ),
                ),
                fileHashes = emptyList(),
                affectedFiles = listOf("/tmp/workspace/src/Sample.kt"),
            ),
            json = defaultCliJson(),
        )

        val result = defaultCliJson().decodeFromString<ImportOptimizeResult>(output.toString())

        assertEquals(1, result.edits.size)
        assertEquals(listOf("/tmp/workspace/src/Sample.kt"), result.affectedFiles)
    }
}
