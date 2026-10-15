import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import support.tasks.GenerateAgentToolsTask

class GenerateAgentToolsTaskTest {
    @TempDir lateinit var directory: Path

    @Test
    fun `marketplace binds versioned hooks and preserves shared context bytes`() {
        val task = task()
        task.generate()
        val output = task.outputDirectory.get().asFile.toPath()
        val plugin = output.resolve("plugins/kast")
        val manifest = Json.parseToJsonElement(Files.readString(plugin.resolve(".codex-plugin/plugin.json"))).jsonObject
        assertEquals("1.2.3", manifest.getValue("version").jsonPrimitive.content)
        assertEquals("./hooks/hooks.json", manifest.getValue("hooks").jsonPrimitive.content)
        assertTrue(!manifest.containsKey("skills"))
        assertTrue(Files.notExists(plugin.resolve("skills")))
        assertEquals("./.mcp.json", manifest.getValue("mcpServers").jsonPrimitive.content)
        val marketplace = Json.parseToJsonElement(Files.readString(output.resolve(".agents/plugins/marketplace.json"))).jsonObject
        val entry = marketplace.getValue("plugins").jsonArray.single().jsonObject
        assertEquals("kast", marketplace.getValue("name").jsonPrimitive.content)
        assertEquals("./plugins/kast", entry.getValue("source").jsonObject.getValue("path").jsonPrimitive.content)
        for (name in listOf("hooks/hooks.json", "hooks/copilot.json", "hooks/session_start.py", "references/query-examples.json", "references/onboarding.md")) {
            assertArrayEquals(Files.readAllBytes(task.sourceDirectory.get().asFile.toPath().resolve(name)),
                Files.readAllBytes(plugin.resolve(name)))
        }
        assertArrayEquals(Files.readAllBytes(task.sourceDirectory.get().asFile.toPath().resolve(".mcp.json")),
            Files.readAllBytes(plugin.resolve(".mcp.json")))
        for (name in listOf("hooks.json", "session_start.py", "session_start.py.lock", "prepare_workspace.py", "prepare_workspace.py.lock")) {
            assertArrayEquals(Files.readAllBytes(task.sourceDirectory.get().asFile.toPath().resolve("hooks/$name")),
                Files.readAllBytes(plugin.resolve("hooks/$name")))
        }
        val mcp = Json.parseToJsonElement(Files.readString(plugin.resolve(".mcp.json"))).jsonObject
        assertEquals(listOf("-c", "exec \"\${HOME:?HOME is required}/.local/share/kast/installation/bin/kast-mcp-complete\""),
            mcp.getValue("mcpServers").jsonObject.getValue("kast").jsonObject.getValue("args").jsonArray.map {
                it.jsonPrimitive.content
            })
    }

    @Test
    fun `an escaping hook source rejects before replacing existing output`() {
        val task = task()
        val manifest = task.sourceDirectory.get().asFile.resolve(".codex-plugin/plugin.json")
        manifest.writeText(manifest.readText().replace("./hooks/hooks.json", "../hooks/hooks.json"))
        val protected = task.outputDirectory.get().asFile.resolve("protected")
        protected.parentFile.mkdirs()
        protected.writeText("existing-output")
        assertThrows(IllegalStateException::class.java) { task.generate() }
        assertEquals("existing-output", protected.readText())
    }

    @Test
    fun `missing linked guidance rejects before replacing admitted output`() {
        val task = task()
        task.generate()
        val output = task.outputDirectory.get().asFile.resolve("plugins/kast/hooks/session_start.py")
        val original = output.readBytes()
        task.sourceDirectory.get().asFile.resolve("hooks/session_start.py").delete()
        assertThrows(IllegalStateException::class.java) { task.generate() }
        assertArrayEquals(original, output.readBytes())
    }

    @Test
    fun `stale examples reject before replacing admitted output`() {
        val task = task()
        task.generate()
        val output = task.outputDirectory.get().asFile.resolve("plugins/kast/references/query-examples.json")
        val original = output.readBytes()
        task.sourceDirectory.get().asFile.resolve("references/query-examples.json").writeText("not-json")
        assertThrows(IllegalStateException::class.java) { task.generate() }
        assertArrayEquals(original, output.readBytes())
    }

    @Test
    fun `missing setup script rejects before replacing admitted output`() {
        val task = task()
        task.generate()
        val output = task.outputDirectory.get().asFile.resolve("plugins/kast/hooks/prepare_workspace.py")
        val original = output.readBytes()
        task.sourceDirectory.get().asFile.resolve("hooks/prepare_workspace.py").delete()
        assertThrows(IllegalStateException::class.java) { task.generate() }
        assertArrayEquals(original, output.readBytes())
    }

    @Test
    fun `unknown MCP configuration rejects rather than widening the launcher contract`() {
        val task = task()
        val mcp = task.sourceDirectory.get().asFile.resolve(".mcp.json")
        mcp.writeText(mcp.readText().replace("\"command\"", "\"unrecognized\":true,\"command\""))
        assertThrows(kotlinx.serialization.SerializationException::class.java) { task.generate() }
        assertTrue(Files.notExists(task.outputDirectory.get().asFile.toPath()))
    }

    private fun task(): GenerateAgentToolsTask {
        val repository = Path.of("..").toAbsolutePath().normalize()
        val source = directory.resolve("source").toFile()
        repository.resolve("agent-tools").toFile().copyRecursively(source)
        val marketplace = directory.resolve("marketplace.json")
        Files.copy(repository.resolve(".agents/plugins/marketplace.json"), marketplace)
        val project = ProjectBuilder.builder().withProjectDir(directory.toFile()).build()
        return project.tasks.register("agentTools", GenerateAgentToolsTask::class.java).get().apply {
            sourceDirectory.set(source)
            marketplaceFile.set(marketplace.toFile())
            canonicalQueryExamplesFile.set(repository.resolve("agent-tools/references/query-examples.json").toFile())
            productVersion.set("1.2.3")
            outputDirectory.set(directory.resolve("output").toFile())
        }
    }
}
