import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.Serializable
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

@Serializable private data class ExpectedOwner(val name: String)

@Serializable
private data class ExpectedClaudePlugin(
    val name: String,
    val version: String,
    val description: String,
    val author: ExpectedOwner,
    val homepage: String,
    val repository: String,
    val license: String,
)

@Serializable private data class ExpectedClaudeEntry(val name: String, val source: String, val description: String)

@Serializable
private data class ExpectedClaudeMarketplace(
    val name: String,
    val description: String,
    val owner: ExpectedOwner,
    val plugins: List<ExpectedClaudeEntry>,
)

class GenerateAgentToolsTaskTest {
    @TempDir lateinit var directory: Path

    @Test
    fun `default composition installs CLI guidance and native preparation hooks without MCP`() {
        val task = task()
        task.generate()
        val output = task.outputDirectory.get().asFile.toPath()
        val plugin = output.resolve("plugins/kast")
        val manifest = Json.parseToJsonElement(Files.readString(plugin.resolve(".codex-plugin/plugin.json"))).jsonObject
        assertEquals("./hooks/hooks.json", manifest.getValue("hooks").jsonPrimitive.content)
        assertTrue("mcpServers" !in manifest)
        for (path in listOf("hooks/session_start.py", ".mcp.json", "mcp.json")) assertTrue(Files.notExists(plugin.resolve(path)), path)
        assertTrue(Files.isRegularFile(plugin.resolve("hooks/hooks.json")))
        val mcp = Json.parseToJsonElement(Files.readString(output.resolve("compositions/mcp/plugins/kast/.codex-plugin/plugin.json"))).jsonObject
        assertEquals("./.mcp.json", mcp.getValue("mcpServers").jsonPrimitive.content)
        for (path in listOf(".github", "plugins/kast/.github")) assertTrue(Files.notExists(output.resolve(path)), path)
    }

    @Test
    fun `MCP composition binds the native MCP entry and preserves shared context bytes`() {
        val task = task()
        task.generate()
        val output = task.outputDirectory.get().asFile.toPath().resolve("compositions/mcp")
        val plugin = output.resolve("plugins/kast")
        val manifest = Json.parseToJsonElement(Files.readString(plugin.resolve(".codex-plugin/plugin.json"))).jsonObject
        assertEquals("1.2.3", manifest.getValue("version").jsonPrimitive.content)
        assertEquals("./hooks/hooks.json", manifest.getValue("hooks").jsonPrimitive.content)
        assertEquals("./skills/", manifest.getValue("skills").jsonPrimitive.content)
        for (root in listOf(output.parent.parent, output)) {
            val skill = Files.readString(root.resolve("plugins/kast/skills/kast/SKILL.md"))
            assertTrue(skill.substringAfter("---\n").substringBefore("\n---").contains("\nmetadata:\n  version: \"1.2.3\""))
        }
        assertEquals("./.mcp.json", manifest.getValue("mcpServers").jsonPrimitive.content)
        val marketplace = Json.parseToJsonElement(Files.readString(output.resolve(".agents/plugins/marketplace.json"))).jsonObject
        val entry = marketplace.getValue("plugins").jsonArray.single().jsonObject
        assertEquals("kast", marketplace.getValue("name").jsonPrimitive.content)
        assertEquals("./plugins/kast", entry.getValue("source").jsonObject.getValue("path").jsonPrimitive.content)
        assertTrue(Files.notExists(plugin.resolve("mcp.json")))
        for (name in listOf("references/query-examples.json", "references/onboarding.md", "skills/kast/public-cli.md")) {
            assertArrayEquals(Files.readAllBytes(task.sourceDirectory.get().asFile.toPath().resolve(name)),
                Files.readAllBytes(plugin.resolve(name)))
        }
        assertArrayEquals(Files.readAllBytes(task.sourceDirectory.get().asFile.toPath().resolve("mcp.json")),
            Files.readAllBytes(plugin.resolve(".mcp.json")))
        val mcp = Json.parseToJsonElement(Files.readString(plugin.resolve(".mcp.json"))).jsonObject
        val server = mcp.getValue("mcpServers").jsonObject.getValue("kast").jsonObject
        assertEquals("3780", server.getValue("tool_timeout_sec").jsonPrimitive.content)
        assertEquals("3780000", server.getValue("timeout").jsonPrimitive.content)
        assertEquals("/bin/bash", server.getValue("command").jsonPrimitive.content)
        assertEquals(listOf("-c", "PATH=\"\${HOME}/.local/bin:\${PATH}\" exec kast --internal-entrypoint=mcp"),
            mcp.getValue("mcpServers").jsonObject.getValue("kast").jsonObject.getValue("args").jsonArray.map {
                it.jsonPrimitive.content
            })
    }

    @Test
    fun `both compositions present one identity to Claude Code and Codex`() {
        val task = task()
        task.generate()
        val output = task.outputDirectory.get().asFile.toPath()
        val source = Json.parseToJsonElement(
            Files.readString(task.sourceDirectory.get().asFile.toPath().resolve(".codex-plugin/plugin.json")),
        ).jsonObject
        val description = source.getValue("description").jsonPrimitive.content
        for (composition in listOf(output, output.resolve("compositions/mcp"))) {
            val plugin = composition.resolve("plugins/kast")
            // Claude Code discovers the skill, the hook and .mcp.json; a manifest path would replace a default.
            // An unknown key rejects, so these decodes pin the exact shapes.
            assertEquals(
                ExpectedClaudePlugin(
                    "kast", "1.2.3", description, ExpectedOwner("Amichne"), "https://kast.michne.com/",
                    "https://github.com/amichne/kast", "GPL-3.0-only",
                ),
                Json.decodeFromString<ExpectedClaudePlugin>(
                    Files.readString(plugin.resolve(".claude-plugin/plugin.json")),
                ),
            )
            assertEquals(
                ExpectedClaudeMarketplace(
                    "kast", description, ExpectedOwner("Amichne"),
                    listOf(ExpectedClaudeEntry("kast", "./plugins/kast", description)),
                ),
                Json.decodeFromString<ExpectedClaudeMarketplace>(
                    Files.readString(composition.resolve(".claude-plugin/marketplace.json")),
                ),
            )
            val codex = Json.parseToJsonElement(Files.readString(plugin.resolve(".codex-plugin/plugin.json"))).jsonObject
            for (shared in listOf("name", "version", "description", "license")) {
                assertEquals(
                    codex.getValue(shared),
                    Json.parseToJsonElement(Files.readString(plugin.resolve(".claude-plugin/plugin.json")))
                        .jsonObject.getValue(shared),
                )
            }
        }
        // Only the MCP composition carries the server that both agents would start.
        assertTrue(Files.notExists(output.resolve("plugins/kast/.mcp.json")))
        assertTrue(Files.isRegularFile(output.resolve("compositions/mcp/plugins/kast/.mcp.json")))
    }

    @Test
    fun `both compositions carry the Copilot hook and a versioned Pi package`() {
        val task = task()
        task.generate()
        val output = task.outputDirectory.get().asFile.toPath()
        for (composition in listOf(output, output.resolve("compositions/mcp"))) {
            val plugin = composition.resolve("plugins/kast")
            // Copilot reads this file before hooks/hooks.json, so Claude Code and Codex keep their own envelope.
            val copilot = Json.parseToJsonElement(Files.readString(plugin.resolve("hooks.json"))).jsonObject
            assertEquals("1", copilot.getValue("version").jsonPrimitive.content)
            val hook = copilot.getValue("hooks").jsonObject.getValue("sessionStart").jsonArray.single().jsonObject
            assertTrue(hook.getValue("bash").jsonPrimitive.content.endsWith("hook session-start --host plain"))
            assertTrue(Files.readString(plugin.resolve("hooks/hooks.json")).contains("--host agent"))
            val pi = Json.parseToJsonElement(Files.readString(plugin.resolve("package.json"))).jsonObject
            assertEquals("1.2.3", pi.getValue("version").jsonPrimitive.content)
            val resources = pi.getValue("pi").jsonObject
            for (path in listOf("extensions", "skills").flatMap { resources.getValue(it).jsonArray }) {
                assertTrue(Files.exists(plugin.resolve(path.jsonPrimitive.content)), path.jsonPrimitive.content)
            }
        }
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
    fun `missing preparation hook rejects before replacing admitted output`() {
        val task = task()
        task.generate()
        val output = task.outputDirectory.get().asFile.resolve("plugins/kast/hooks/hooks.json")
        val original = output.readBytes()
        task.sourceDirectory.get().asFile.resolve("hooks/hooks.json").delete()
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
    fun `a launcher other than the native MCP entry rejects before replacing admitted output`() {
        val task = task()
        task.generate()
        val source = task.sourceDirectory.get().asFile.resolve("mcp.json")
        val output = task.outputDirectory.get().asFile.resolve("compositions/mcp/plugins/kast/.mcp.json")
        val protected = output.readBytes()
        source.writeText(source.readText().replace(
            "exec kast --internal-entrypoint=mcp",
            "exec python3 server.py",
        ))
        assertThrows(IllegalStateException::class.java) { task.generate() }
        assertArrayEquals(protected, output.readBytes())
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
