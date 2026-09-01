import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import support.tasks.GenerateControlMetadataTask
import support.tasks.SemanticRuntimeDocument
import support.tasks.controlMetadataJson
import java.nio.file.Files
import java.nio.file.Path

class GenerateControlMetadataTaskTest {
    @TempDir
    lateinit var temporaryDirectory: Path

    @Test
    fun `operation registry input is copied byte for byte`() {
        val runtimeArchive = write("runtime.zip", "runtime")
        val runtimeDirectory = temporaryDirectory.resolve("runtime")
        val pluginJar = runtimeDirectory.resolve(
            "private-plugins/kast-indexer/lib/indexer-fixture-plugin.jar",
        )
        Files.createDirectories(pluginJar.parent)
        Files.writeString(pluginJar, "plugin")
        val license = write("LICENSE", "license\n")
        val registry = write(
            "operation-registry.json",
            "{\"schemaVersion\":1,\"operationIds\":[\"workspace.inspect\"]}\n",
        )
        val output = temporaryDirectory.resolve("generated")
        val project = ProjectBuilder.builder().withProjectDir(temporaryDirectory.toFile()).build()
        val task = project.tasks.register(
            "generateControlMetadataUnderTest",
            GenerateControlMetadataTask::class.java,
        ).get().apply {
            this.runtimeArchive.set(runtimeArchive.toFile())
            this.runtimeDirectory.set(runtimeDirectory.toFile())
            this.licenseFile.set(license.toFile())
            this.operationRegistryFile.set(registry.toFile())
            productVersion.set("1.0.\"quoted\\build")
            ideaBuild.set("262")
            kotlinPluginBuild.set("262.9437.185-IJ")
            runtimeBaseUrl.set("https://example.test/runtime")
            outputDirectory.set(output.toFile())
        }

        task.generate()

        assertArrayEquals(
            Files.readAllBytes(registry),
            Files.readAllBytes(output.resolve("operation-registry.json")),
        )
        val runtime = controlMetadataJson.decodeFromString(
            SemanticRuntimeDocument.serializer(),
            Files.readString(output.resolve("semantic-runtime.json")),
        )
        assertEquals("1.0.\"quoted\\build", runtime.productVersion)
        assertEquals("262", runtime.ideaBuild)
        assertEquals("262.9437.185-IJ", runtime.kotlinPluginBuild)
        assertEquals(
            listOf(
                "kast-indexer",
                "runtime-libs/",
                "private-plugins/kast-indexer/",
            ),
            runtime.layout.requiredEntries,
        )
    }

    @Test
    fun `Kast payload digest covers every private extension file`() {
        val runtimeArchive = write("runtime.zip", "runtime")
        val runtimeDirectory = temporaryDirectory.resolve("runtime")
        writeRuntimeFile(
            runtimeDirectory,
            "private-plugins/kast-indexer/lib/indexer-fixture-plugin.jar",
            "indexer",
        )
        val changeAdapter = writeRuntimeFile(
            runtimeDirectory,
            "private-plugins/kast-indexer/lib/change-intellij.jar",
            "change-v1",
        )
        val output = temporaryDirectory.resolve("generated")
        val project = ProjectBuilder.builder().withProjectDir(temporaryDirectory.toFile()).build()
        val task = project.tasks.register(
            "generatePayloadIdentityUnderTest",
            GenerateControlMetadataTask::class.java,
        ).get().apply {
            this.runtimeArchive.set(runtimeArchive.toFile())
            this.runtimeDirectory.set(runtimeDirectory.toFile())
            this.licenseFile.set(write("LICENSE", "license\n").toFile())
            this.operationRegistryFile.set(
                write("operation-registry.json", "{\"schemaVersion\":1}").toFile(),
            )
            productVersion.set("1.0.0")
            ideaBuild.set("262.9437.185")
            kotlinPluginBuild.set("262.9437.185-IJ")
            runtimeBaseUrl.set("https://example.test/runtime")
            outputDirectory.set(output.toFile())
        }

        task.generate()
        val firstDigest = generatedRuntime(output).kastPluginSha256

        Files.writeString(changeAdapter, "change-v2")
        task.generate()

        assertNotEquals(firstDigest, generatedRuntime(output).kastPluginSha256)
    }

    private fun generatedRuntime(output: Path): SemanticRuntimeDocument =
        controlMetadataJson.decodeFromString(
            SemanticRuntimeDocument.serializer(),
            Files.readString(output.resolve("semantic-runtime.json")),
        )

    private fun writeRuntimeFile(
        runtimeDirectory: Path,
        relative: String,
        content: String,
    ): Path = runtimeDirectory.resolve(relative).also { path ->
        Files.createDirectories(path.parent)
        Files.writeString(path, content)
    }

    private fun write(relative: String, content: String): Path =
        temporaryDirectory.resolve(relative).also { path ->
            Files.createDirectories(path.parent)
            Files.writeString(path, content)
        }
}
