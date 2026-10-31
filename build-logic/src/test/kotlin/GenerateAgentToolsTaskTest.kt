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
    fun `default composition installs CLI guidance without MCP or Python hooks`() {
        val task = task()
        task.generate()
        val output = task.outputDirectory.get().asFile.toPath()
        val plugin = output.resolve("plugins/kast")
        for (manifestPath in listOf(".codex-plugin/plugin.json", ".github/plugin/plugin.json")) {
            val manifest = Json.parseToJsonElement(Files.readString(plugin.resolve(manifestPath))).jsonObject
            assertTrue("hooks" !in manifest)
            assertTrue("mcpServers" !in manifest)
        }
        for (path in listOf("hooks", ".mcp.json", "skills/kast/scripts")) assertTrue(Files.notExists(plugin.resolve(path)), path)
        val mcp = Json.parseToJsonElement(Files.readString(output.resolve("compositions/mcp/plugins/kast/.codex-plugin/plugin.json"))).jsonObject
        assertEquals("./.mcp.json", mcp.getValue("mcpServers").jsonPrimitive.content)
        assertTrue(Files.exists(output.resolve(".github/plugin/marketplace.json")))
    }

    @Test
    fun `MCP composition binds versioned transport and preserves shared context bytes`() {
        val task = task()
        task.generate()
        val output = task.outputDirectory.get().asFile.toPath().resolve("compositions/mcp")
        val plugin = output.resolve("plugins/kast")
        val manifest = Json.parseToJsonElement(Files.readString(plugin.resolve(".codex-plugin/plugin.json"))).jsonObject
        assertEquals("1.2.3", manifest.getValue("version").jsonPrimitive.content)
        assertTrue("hooks" !in manifest)
        assertEquals("./skills/", manifest.getValue("skills").jsonPrimitive.content)
        assertTrue(Files.exists(plugin.resolve("skills/kast/SKILL.md")))
        assertEquals("./.mcp.json", manifest.getValue("mcpServers").jsonPrimitive.content)
        val marketplace = Json.parseToJsonElement(Files.readString(output.resolve(".agents/plugins/marketplace.json"))).jsonObject
        val entry = marketplace.getValue("plugins").jsonArray.single().jsonObject
        assertEquals("kast", marketplace.getValue("name").jsonPrimitive.content)
        assertEquals("./plugins/kast", entry.getValue("source").jsonObject.getValue("path").jsonPrimitive.content)
        for (name in listOf("references/query-examples.json", "references/onboarding.md", "skills/kast/SKILL.md", "skills/kast/scripts/server.py", "skills/kast/scripts/server.py.lock")) {
            assertArrayEquals(Files.readAllBytes(task.sourceDirectory.get().asFile.toPath().resolve(name)),
                Files.readAllBytes(plugin.resolve(name)))
        }
        assertArrayEquals(Files.readAllBytes(task.sourceDirectory.get().asFile.toPath().resolve("mcp.json")),
            Files.readAllBytes(plugin.resolve(".mcp.json")))
        val mcp = Json.parseToJsonElement(Files.readString(plugin.resolve(".mcp.json"))).jsonObject
        val server = mcp.getValue("mcpServers").jsonObject.getValue("kast").jsonObject
        assertEquals("3780", server.getValue("tool_timeout_sec").jsonPrimitive.content)
        assertEquals("3780000", server.getValue("timeout").jsonPrimitive.content)
        assertEquals(listOf("-c", "PATH=\"\${HOME}/.local/bin:\${PATH}\" exec uv run --locked --script \"\${PLUGIN_ROOT:-\${COPILOT_PLUGIN_ROOT:-\${HOME}/.local/share/kast/installation/share/kast/agent-tools/compositions/mcp/plugins/kast}}/skills/kast/scripts/server.py\""),
            mcp.getValue("mcpServers").jsonObject.getValue("kast").jsonObject.getValue("args").jsonArray.map {
                it.jsonPrimitive.content
            })
    }

    @Test
    fun `short host deadlines reject before replacing admitted output`() {
        val task = task()
        task.generate()
        val source = task.sourceDirectory.get().asFile.resolve("mcp.json")
        val original = source.readText()
        val output = task.outputDirectory.get().asFile.resolve("compositions/mcp/plugins/kast/.mcp.json")
        val protected = output.readBytes()
        for (shortened in listOf(original.replace("3780000", "180000"), original.replace("3780,", "180,"))) {
            source.writeText(shortened)
            assertThrows(IllegalStateException::class.java) { task.generate() }
            assertArrayEquals(protected, output.readBytes())
        }
    }

    @Test
    fun `an escaping hook source rejects before replacing existing output`() {
        val task = task()
        val manifest = task.sourceDirectory.get().asFile.resolve(".codex-plugin/plugin.json")
        manifest.writeText(manifest.readText().replace("\"skills\": \"./skills/\"", "\"skills\": \"../skills/\""))
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
        val output = task.outputDirectory.get().asFile.resolve("plugins/kast/skills/kast/public-cli.md")
        val original = output.readBytes()
        task.sourceDirectory.get().asFile.resolve("skills/kast/public-cli.md").delete()
        assertThrows(IllegalStateException::class.java) { task.generate() }
        assertArrayEquals(original, output.readBytes())
    }

    @Test
    fun `private environment rejects before replacing admitted compositions`() {
        val task = task()
        task.generate()
        val output = task.outputDirectory.get().asFile.resolve("plugins/kast/skills/kast/SKILL.md")
        val original = output.readBytes()
        val environment = task.sourceDirectory.get().asFile.resolve(".venv")
        environment.mkdirs()
        environment.resolve("pyvenv.cfg").writeText("private fixture runtime")
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
    fun `marketplace contains no dependency environment or duplicate preparation owner`() {
        val task = task()
        task.generate()
        val plugin = task.outputDirectory.get().asFile.resolve("plugins/kast")
        for (path in listOf("hooks/prepare_workspace.py", "hooks/prepare_workspace.py.lock", "pyproject.toml", "uv.lock")) {
            assertTrue(!plugin.resolve(path).exists(), path)
        }
    }

    @Test
    fun `unknown MCP configuration rejects rather than widening the launcher contract`() {
        val task = task()
        val mcp = task.sourceDirectory.get().asFile.resolve("mcp.json")
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
