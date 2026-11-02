package io.github.amichne.kast.distribution.cli

import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test

class NativeControlResetSelectionTest {
    private val root = Path.of("/fixture/kast")
    private val image = root.resolve("installation/share/kast/libexec/kast-management").toString()

    @Test
    fun `force reset selects each native worker mode with its exact process identity`() {
        for (mode in listOf("query", "mcp", "rpc", "service", "daemon", "codex")) {
            val snapshot =
                snapshot(
                    listOf("-Duser.home=/fixture/home", "-XX:ActiveProcessorCount=2", "--internal-entrypoint=$mode")
                )
            val selected =
                assertInstanceOf(ResetProcessSelection.Selected::class.java, ResetScopedProcess.select(snapshot, root))
            assertEquals(snapshot, selected.process.snapshot)
            assertEquals(root, selected.process.root)
            assertEquals(
                ResetProcessSelection.Rejected,
                ResetScopedProcess.select(snapshot.copy(startEpochMillis = 0), root),
            )
        }
    }

    @Test
    fun `native memory options preserve force worker selection`() {
        for (option in listOf("-Xmx256m", "-Xms16m", "-Xmn16m", "-Xss1m")) {
            assertInstanceOf(
                ResetProcessSelection.Selected::class.java,
                ResetScopedProcess.select(snapshot(listOf(option, "--internal-entrypoint=mcp")), root),
            )
        }
    }

    @Test
    fun `force reset excludes native management installer and foreign images`() {
        val excluded =
            listOf(
                snapshot(emptyList()),
                snapshot(listOf("uninstall", "--force")),
                snapshot(listOf("--internal-entrypoint=management", "reinstall", "--force")),
                snapshot(listOf("--internal-entrypoint=installer", "--version")),
                snapshot(listOf("uninstall", "--force", "--internal-entrypoint=mcp")),
                snapshot(listOf("-Xnot-a-runtime-option", "--internal-entrypoint=mcp")),
                snapshot(listOf("-DnotAProperty", "--internal-entrypoint=mcp")),
                snapshot(listOf("-XXpremain:Example", "--internal-entrypoint=mcp")),
                snapshot(listOf("--internal-entrypoint=mcp"))
                    .copy(command = "/foreign/share/kast/libexec/kast-management"),
                snapshot(listOf("--internal-entrypoint=mcp"))
                    .copy(command = "$root/../foreign/share/kast/libexec/kast-management"),
                snapshot(listOf("--internal-entrypoint=mcp")).copy(command = "$root/installation/bin/kast-management"),
            )
        for (snapshot in excluded) assertEquals(
            ResetProcessSelection.Unrelated,
            ResetScopedProcess.select(snapshot, root),
        )
    }

    @Test
    fun `an unknown native mode blocks force reset`() {
        assertEquals(
            ResetProcessSelection.Rejected,
            ResetScopedProcess.select(snapshot(listOf("--internal-entrypoint=unknown")), root),
        )
    }

    private fun snapshot(arguments: List<String>) = LifecycleProcessSnapshot(71, 101, image, arguments)
}
