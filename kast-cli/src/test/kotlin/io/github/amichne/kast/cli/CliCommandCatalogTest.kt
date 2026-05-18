package io.github.amichne.kast.cli

import io.github.amichne.kast.cli.tty.CliCommandCatalog
import io.github.amichne.kast.cli.tty.CliCommandMetadata
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CliCommandCatalogTest {
    @Test
    fun `catalog excludes removed workspace skill metrics and direct command paths`() {
        val disallowedPaths = setOf(
            listOf("workspace", "status"),
            listOf("workspace", "ensure"),
            listOf("workspace", "refresh"),
            listOf("workspace", "stop"),
            listOf("workspace", "files"),
            listOf("resolve"),
            listOf("references"),
            listOf("call-hierarchy"),
            listOf("type-hierarchy"),
            listOf("insertion-point"),
            listOf("diagnostics"),
            listOf("outline"),
            listOf("workspace-symbol"),
            listOf("workspace-search"),
            listOf("implementations"),
            listOf("code-actions"),
            listOf("completions"),
            listOf("rename"),
            listOf("optimize-imports"),
            listOf("apply-edits"),
            listOf("skill", "resolve"),
            listOf("skill", "references"),
            listOf("skill", "callers"),
            listOf("skill", "diagnostics"),
            listOf("skill", "rename"),
            listOf("skill", "scaffold"),
            listOf("skill", "write-and-validate"),
            listOf("skill", "workspace-files"),
            listOf("skill", "workspace-search"),
            listOf("skill", "file-outline"),
            listOf("skill", "workspace-symbol"),
            listOf("skill", "metrics"),
            listOf("metrics", "fan-in"),
            listOf("metrics", "fan-out"),
            listOf("metrics", "coupling"),
            listOf("metrics", "low-usage"),
            listOf("metrics", "cycles"),
            listOf("metrics", "module-depth"),
            listOf("metrics", "dead-code"),
            listOf("metrics", "impact"),
            listOf("metrics", "graph"),
        )

        val allPaths = allCommandMetadata().map(CliCommandMetadata::path).toSet()
        assertTrue(
            disallowedPaths.intersect(allPaths).isEmpty(),
            "Catalog still contains removed paths: ${disallowedPaths.intersect(allPaths).sortedBy { it.joinToString(" ") }}",
        )
        assertTrue(
            CliCommandCatalog.visibleCommands().none { it.path.firstOrNull() == "workspace" },
            "Visible help should not contain workspace namespace commands",
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun allCommandMetadata(): List<CliCommandMetadata> {
        val field = CliCommandCatalog::class.java.getDeclaredField("commands")
        field.isAccessible = true
        return field.get(CliCommandCatalog) as List<CliCommandMetadata>
    }
}
