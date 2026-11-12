package io.github.amichne.kast.cli.publicshell

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** Actual native process and JSON framing; no compiler or Host qualification is claimed here. */
@Tag("component-native")
class NativeComponentExecutableTest {
    @Test
    fun `native passive commands emit one JSON document without component admission`(@TempDir temporary: Path) {
        val root = temporary.toRealPath()
        val commands =
            listOf(
                listOf("describe"),
                listOf("query", "plan", "--name", "OrderService", "--json"),
                listOf("query", "plan", "--name", "OrderService", "--json", "--jq", "."),
            )
        for ((index, command) in commands.withIndex()) {
            val stdout = root.resolve("$index.stdout")
            val stderr = root.resolve("$index.stderr")
            // The jq child needs an existing working directory, not an admitted workspace.
            val workspace = if ("--jq" in command) root else root.resolve("workspace")
            val child =
                ProcessBuilder(prefix(root, workspace) + listOf("exec", "--") + command)
                    .directory(root.toFile())
                    .redirectOutput(stdout.toFile())
                    .redirectError(stderr.toFile())
                    .start()
            try {
                assertTrue(child.waitFor(30, TimeUnit.SECONDS), "native passive command did not exit")
                assertEquals(0, child.exitValue(), Files.readString(stderr))
                val document = Json.parseToJsonElement(Files.readString(stdout)).jsonObject
                if (index == 0) assertEquals("COMPLETE", document.getValue("catalog").jsonPrimitive.content)
                else assertEquals("planned", document.getValue("type").jsonPrimitive.content)
            } finally {
                if (child.isAlive) child.destroyForcibly().waitFor()
            }
        }
        assertFalse(Files.exists(root.resolve("store")))
        assertFalse(Files.exists(root.resolve("workspace")))
    }

    private fun prefix(root: Path, workspace: Path): List<String> =
        listOf(
            requireNotNull(System.getProperty("kast.componentQualification")),
            "--store",
            root.resolve("store").toString(),
            "--workspace",
            workspace.toString(),
            "--runtime-archive",
            root.resolve("runtime.zip").toString(),
            "--runtime-sha256",
            "a".repeat(64),
            "--control",
            root.resolve("control").toString(),
            "--control-sha256",
            "b".repeat(64),
            "--source-revision",
            "c".repeat(40),
            "--version",
            "1.2.3",
        )
}
