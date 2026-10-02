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
    fun `marketplace binds the versioned plugin and preserves the independent skill bytes`() {
        val task = task()
        task.generate()
        val output = task.outputDirectory.get().asFile.toPath()
        val plugin = output.resolve("plugins/kast")
        val manifest = Json.parseToJsonElement(Files.readString(plugin.resolve(".codex-plugin/plugin.json"))).jsonObject
        assertEquals("1.2.3", manifest.getValue("version").jsonPrimitive.content)
        assertEquals("./skills/", manifest.getValue("skills").jsonPrimitive.content)
        assertEquals("./.mcp.json", manifest.getValue("mcpServers").jsonPrimitive.content)
        val marketplace = Json.parseToJsonElement(Files.readString(output.resolve(".agents/plugins/marketplace.json"))).jsonObject
        val entry = marketplace.getValue("plugins").jsonArray.single().jsonObject
        assertEquals("kast", marketplace.getValue("name").jsonPrimitive.content)
        assertEquals("./plugins/kast", entry.getValue("source").jsonObject.getValue("path").jsonPrimitive.content)
        for (name in listOf("SKILL.md", "references/query-examples.json", "references/query-patterns.md")) {
            assertArrayEquals(Files.readAllBytes(task.sourceDirectory.get().asFile.toPath().resolve("skills/kast/$name")),
                Files.readAllBytes(plugin.resolve("skills/kast/$name")))
        }
        assertArrayEquals(Files.readAllBytes(task.sourceDirectory.get().asFile.toPath().resolve(".mcp.json")),
            Files.readAllBytes(plugin.resolve(".mcp.json")))
        val mcp = Json.parseToJsonElement(Files.readString(plugin.resolve(".mcp.json"))).jsonObject
        assertEquals(listOf("-c", "exec \"\${HOME:?HOME is required}/.local/share/kast/installation/bin/kast-mcp-complete\""),
            mcp.getValue("mcpServers").jsonObject.getValue("kast").jsonObject.getValue("args").jsonArray.map {
                it.jsonPrimitive.content
            })
    }

    @Test
    fun `an escaping skill source rejects before replacing existing output`() {
        val task = task()
        val manifest = task.sourceDirectory.get().asFile.resolve(".codex-plugin/plugin.json")
        manifest.writeText(manifest.readText().replace("./skills/", "../skills/"))
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
        val output = task.outputDirectory.get().asFile.resolve("plugins/kast/skills/kast/references/connection.md")
        val original = output.readBytes()
        task.sourceDirectory.get().asFile.resolve("skills/kast/references/connection.md").delete()
        assertThrows(IllegalStateException::class.java) { task.generate() }
        assertArrayEquals(original, output.readBytes())
    }

    @Test
    fun `stale skill examples reject before replacing admitted output`() {
        val task = task()
        task.generate()
        val output = task.outputDirectory.get().asFile.resolve("plugins/kast/skills/kast/references/query-examples.json")
        val original = output.readBytes()
        task.sourceDirectory.get().asFile.resolve("skills/kast/references/query-examples.json").writeText("not-json")
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
            canonicalQueryExamplesFile.set(repository.resolve(
                "app-server/src/main/resources/io/github/amichne/kast/appserver/query/query_symbols.examples.json",
            ).toFile())
            productVersion.set("1.2.3")
            outputDirectory.set(directory.resolve("output").toFile())
        }
    }
}
