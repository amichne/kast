import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import support.tasks.GenerateControlMetadataTask
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*

class GenerateControlMetadataTaskTest {
    @TempDir lateinit var directory: Path

    @Test
    fun `control metadata binds the actual hosted plugin and copies authoritative schemas`() {
        val plugin = write("kast-ide-hosted.zip", "complete-plugin-archive")
        val registry = write("registry.json", Json.encodeToString(RegistryFixture(1, listOf("query.run"))))
        val catalogue = write("catalogue.json", Json.encodeToString(CatalogueFixture(emptyList())))
        val output = directory.resolve("generated")
        val project = ProjectBuilder.builder().withProjectDir(directory.toFile()).build()
        val task = project.tasks.register("metadata", GenerateControlMetadataTask::class.java).get().apply {
            pluginArchive.set(plugin.toFile())
            licenseFile.set(write("LICENSE", "license").toFile())
            operationRegistryFile.set(registry.toFile())
            configurationCatalogueFile.set(catalogue.toFile())
            productVersion.set("1.0.0")
            ideaBuild.set("262.9437.185")
            kotlinPluginBuild.set("262.9437.185-IJ")
            outputDirectory.set(output.toFile())
        }
        task.generate()
        val manifest = Json.parseToJsonElement(Files.readString(output.resolve("ide-host.json"))).jsonObject
        assertEquals(setOf("schemaVersion", "productVersion", "execution", "ideaBuild", "kotlinPluginBuild", "fileName", "sha256", "bytes"), manifest.keys)
        assertEquals(JsonPrimitive("existing_ide"), manifest["execution"])
        assertEquals(JsonPrimitive("kast-ide-hosted.zip"), manifest["fileName"])
        val expectedDigest = "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(plugin)))
        assertEquals(JsonPrimitive(expectedDigest), manifest["sha256"])
        assertEquals(JsonPrimitive(Files.size(plugin)), manifest["bytes"])
        assertFalse(Files.exists(output.resolve("semantic-runtime.json")))
        assertArrayEquals(Files.readAllBytes(registry), Files.readAllBytes(output.resolve("operation-registry.json")))
        assertArrayEquals(Files.readAllBytes(catalogue), Files.readAllBytes(output.resolve("configuration-schema.json")))
        Files.writeString(plugin, "changed-plugin-archive")
        task.generate()
        val changed = Json.parseToJsonElement(Files.readString(output.resolve("ide-host.json"))).jsonObject
        assertNotEquals(manifest["sha256"], changed["sha256"])
    }
    private fun write(name: String, content: String): Path = directory.resolve(name).also { Files.writeString(it, content) }
    @Serializable private data class RegistryFixture(val schemaVersion: Int, val operationIds: List<String>)
    @Serializable private data class CatalogueFixture(val parameters: List<String>)
}
