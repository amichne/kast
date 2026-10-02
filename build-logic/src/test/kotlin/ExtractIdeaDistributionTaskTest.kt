import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.readText
import org.gradle.api.GradleException
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class ExtractIdeaDistributionTaskTest {
    @TempDir lateinit var directory: Path

    @Test
    fun `extraction preserves required libraries and omits unrelated plugins and assets`() {
        val libraries = mapOf(
            "lib/platform.jar" to "platform bytes",
            "plugins/Kotlin/lib/kotlin.jar" to "Kotlin bytes",
            "plugins/Kotlin/lib/jps/build.jar" to "consumer excluded family bytes",
            "plugins/Kotlin/lib/kotlinc/lib/kotlin-compiler.jar" to "consumer excluded compiler bytes",
            "plugins/java/lib/java.jar" to "Java bytes",
        )
        val task = extraction(
            libraries + mapOf(
                "bin/idea.sh" to "unused executable",
                "lib/native/libjnidispatch.dylib" to "unused native library",
                "plugins/python/lib/python.jar" to "unused plugin",
                "plugins/Kotlin/README.md" to "unused asset",
                "plugins/gradle-plugin/lib/gradle.jar" to "unused Gradle bytes",
            ),
            setOf(IdeaLibraryFamily.PLATFORM, IdeaLibraryFamily.KOTLIN, IdeaLibraryFamily.JAVA),
        )

        task.extract()

        assertEquals(libraries + (".kast-idea-version" to "262.1"), outputFiles())
    }

    @Test
    fun `workspace libraries include every Gradle sibling plugin and preserve nested paths`() {
        val libraries = mapOf(
            "lib/platform.jar" to "platform bytes",
            "plugins/gradle-plugin/lib/gradle.jar" to "Gradle bytes",
            "plugins/gradle-java-plugin/lib/nested/tools.jar" to "Gradle Java bytes",
            "idea/plugins/java/lib/wrapped.jar" to "wrapped Java bytes",
        )
        val task = extraction(
            libraries + ("plugins/other/lib/other.jar" to "unused plugin"),
            IdeaLibraryFamily.entries.toSet(),
        )

        task.extract()

        assertEquals(libraries + (".kast-idea-version" to "262.1"), outputFiles())
    }

    @Test
    fun `replacement removes stale files while retaining the new library bytes`() {
        Files.createDirectories(directory.resolve("extracted/plugins/old/lib"))
        Files.writeString(directory.resolve("extracted/plugins/old/lib/stale.jar"), "stale bytes")
        val task = extraction(mapOf("lib/platform.jar" to "current bytes"))

        task.extract()

        assertEquals(mapOf("lib/platform.jar" to "current bytes", ".kast-idea-version" to "262.1"), outputFiles())
    }

    @Test
    fun `unsafe excluded entry rejects extraction and preserves the previous output`() {
        Files.createDirectories(directory.resolve("extracted"))
        Files.writeString(directory.resolve("extracted/previous.jar"), "previous bytes")
        val task = extraction(mapOf(
            "lib/platform.jar" to "new bytes",
            "../escape.txt" to "excluded unsafe asset",
        ))

        assertThrows(GradleException::class.java) { task.extract() }

        assertEquals(mapOf("previous.jar" to "previous bytes"), outputFiles())
        assertFalse(Files.exists(directory.resolve("escape.txt")))
        Files.list(directory).use { paths ->
            assertFalse(paths.anyMatch { it.fileName.toString().startsWith("extracted.tmp-") })
        }
    }

    @Test
    fun `symbol library selection omits Gradle plugins`() {
        val task = extraction(mapOf(
            "lib/platform.jar" to "platform bytes",
            "plugins/gradle-plugin/lib/gradle.jar" to "unused Gradle bytes",
            "plugins/gradle-java-plugin/lib/gradle-java.jar" to "unused Gradle Java bytes",
        ))

        task.extract()

        assertEquals(mapOf("lib/platform.jar" to "platform bytes", ".kast-idea-version" to "262.1"), outputFiles())
    }

    @Test
    fun `empty library selection rejects before replacing the previous output`() {
        Files.createDirectories(directory.resolve("extracted"))
        Files.writeString(directory.resolve("extracted/previous.jar"), "previous bytes")
        val task = extraction(mapOf("lib/platform.jar" to "new bytes"), emptySet())

        assertThrows(IllegalArgumentException::class.java) { task.extract() }

        assertEquals(mapOf("previous.jar" to "previous bytes"), outputFiles())
    }

    @Test
    fun `unspecified library selection cannot extract the whole distribution`() {
        val task = extraction(mapOf("lib/platform.jar" to "new bytes"))
        task.libraryFamilies.unset()

        assertThrows(IllegalArgumentException::class.java) { task.extract() }

        assertFalse(Files.exists(directory.resolve("extracted")))
    }

    private fun extraction(
        entries: Map<String, String>,
        families: Set<IdeaLibraryFamily> = setOf(IdeaLibraryFamily.PLATFORM, IdeaLibraryFamily.KOTLIN, IdeaLibraryFamily.JAVA),
    ): ExtractIdeaDistributionTask {
        val archive = directory.resolve("idea.zip")
        ZipOutputStream(Files.newOutputStream(archive)).use { zip ->
            entries.forEach { (path, bytes) ->
                zip.putNextEntry(ZipEntry(path))
                zip.write(bytes.toByteArray())
                zip.closeEntry()
            }
        }
        val project = ProjectBuilder.builder().withProjectDir(directory.toFile()).build()
        return project.tasks.register("extractUnderTest", ExtractIdeaDistributionTask::class.java).get().apply {
            archives.from(archive.toFile())
            ideaVersion.set("262.1")
            libraryFamilies.set(families)
            outputDirectory.set(directory.resolve("extracted").toFile())
        }
    }

    private fun outputFiles(): Map<String, String> {
        val output = directory.resolve("extracted")
        return Files.walk(output).use { paths ->
            paths.filter(Files::isRegularFile).toList().associate { path ->
                output.relativize(path).toString().replace('\\', '/') to path.readText()
            }
        }
    }
}
