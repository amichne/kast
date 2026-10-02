import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import support.tasks.GenerateControlMetadataTask
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*

class GenerateControlMetadataTaskTest {
    @TempDir lateinit var directory: Path

    @Test
    fun `control metadata requires the hosted contract without requiring a host package`() {
        val hostedContract = HostedContractFixture("HOSTED_CONTRACT", "kast.ide-hosted.runtime.v2", "sha256:" + "a".repeat(64), "sha256:" + "b".repeat(64), listOf("query.run"))
        val contract = write("hosted-contract.json", Json.encodeToString(hostedContract))
        val registry = write("registry.json", Json.encodeToString(RegistryFixture(1, listOf("query.run"))))
        val catalogue = write("catalogue.json", Json.encodeToString(CatalogueFixture(emptyList())))
        val output = directory.resolve("generated")
        val project = ProjectBuilder.builder().withProjectDir(directory.toFile()).build()
        val task = project.tasks.register("metadata", GenerateControlMetadataTask::class.java).get().apply {
            hostedContractFile.set(contract.toFile())
            licenseFile.set(write("LICENSE", "license").toFile())
            operationRegistryFile.set(registry.toFile())
            configurationCatalogueFile.set(catalogue.toFile())
            providerCatalogueFile.set(catalogue.toFile())
            productVersion.set("1.0.0")
            ideaBuild.set("262.9437.185")
            kotlinPluginBuild.set("262.9437.185-IJ")
            outputDirectory.set(output.toFile())
        }
        task.generate()
        val manifest = Json.parseToJsonElement(Files.readString(output.resolve("ide-host.json"))).jsonObject
        assertEquals(setOf("schemaVersion", "productVersion", "execution", "ideaBuild", "kotlinPluginBuild", "requiredHostedContract"), manifest.keys)
        assertEquals("existing_ide", manifest.getValue("execution").jsonPrimitive.content)
        assertEquals(2, manifest.getValue("schemaVersion").jsonPrimitive.int)
        assertEquals(Json.parseToJsonElement(Files.readString(contract)), manifest["requiredHostedContract"])
        assertFalse(Files.exists(output.resolve("semantic-runtime.json")))
        assertArrayEquals(Files.readAllBytes(registry), Files.readAllBytes(output.resolve("operation-registry.json")))
        assertArrayEquals(Files.readAllBytes(catalogue), Files.readAllBytes(output.resolve("configuration-schema.json")))
        assertArrayEquals(Files.readAllBytes(catalogue), Files.readAllBytes(output.resolve("provider-catalog.json")))
        task.productVersion.set("2.0.0")
        task.generate()
        val changed = Json.parseToJsonElement(Files.readString(output.resolve("ide-host.json"))).jsonObject
        assertEquals(manifest["requiredHostedContract"], changed["requiredHostedContract"])
        assertEquals("2.0.0", changed.getValue("productVersion").jsonPrimitive.content)
    }
    private fun write(name: String, content: String): Path = directory.resolve(name).also { Files.writeString(it, content) }
    @Serializable private data class HostedContractFixture(val type: String, val runtimeProtocolIdentity: String, val operationRegistryDigest: String, val wireSchemaDigest: String, val capabilities: List<String>)
    @Serializable private data class RegistryFixture(val schemaVersion: Int, val operationIds: List<String>)
    @Serializable private data class CatalogueFixture(val parameters: List<String>)
}
