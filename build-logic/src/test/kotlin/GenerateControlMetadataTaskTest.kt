import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import support.tasks.GenerateControlMetadataTask
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
            "idea-home/plugins/kast-indexer/lib/indexer-fixture-plugin.jar",
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
            productVersion.set("1.0.0")
            ideaBuild.set("262")
            kotlinPluginBuild.set("2.3.10")
            runtimeBaseUrl.set("https://example.test/runtime")
            outputDirectory.set(output.toFile())
        }

        task.generate()

        assertArrayEquals(
            Files.readAllBytes(registry),
            Files.readAllBytes(output.resolve("operation-registry.json")),
        )
    }

    private fun write(relative: String, content: String): Path =
        temporaryDirectory.resolve(relative).also { path ->
            Files.createDirectories(path.parent)
            Files.writeString(path, content)
        }
}
