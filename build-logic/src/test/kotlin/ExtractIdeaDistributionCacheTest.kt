import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class ExtractIdeaDistributionCacheTest {
    @TempDir lateinit var root: Path

    @Test
    fun `fresh workspace restores exact libraries from task cache and changed inputs miss`() {
        val libraries = mapOf(
            "lib/platform.jar" to "platform bytes",
            "plugins/Kotlin/lib/kotlin.jar" to "Kotlin bytes",
            "plugins/java/lib/java.jar" to "Java bytes",
            "plugins/gradle-plugin/lib/gradle.jar" to "Gradle bytes",
        )
        val seed = fixture("seed")
        archive(seed, libraries)
        assertEquals(TaskOutcome.SUCCESS, run(seed))
        assertEquals(libraries + (".kast-idea-version" to "262.1"), output(seed))

        val restored = fixture("restored")
        Files.copy(seed.resolve("idea.zip"), restored.resolve("idea.zip"))
        assertEquals(TaskOutcome.FROM_CACHE, run(restored))
        assertEquals(libraries + (".kast-idea-version" to "262.1"), output(restored))

        val changed = libraries + ("lib/platform.jar" to "changed platform bytes")
        archive(restored, changed)
        assertEquals(TaskOutcome.SUCCESS, run(restored))
        assertEquals(changed + (".kast-idea-version" to "262.1"), output(restored))
    }

    private fun fixture(name: String): Path {
        val project = root.resolve(name).createDirectories()
        val classes = Path.of(ExtractIdeaDistributionTask::class.java.protectionDomain.codeSource.location.toURI())
        project.resolve("settings.gradle").writeText(
            """
            rootProject.name = 'extraction-cache-fixture'
            buildCache { local { directory = file('../task-cache') } }
            """.trimIndent(),
        )
        project.resolve("build.gradle").writeText(
            """
            buildscript { dependencies { classpath files(${groovyPath(classes)}) } }
            tasks.register('extract', ExtractIdeaDistributionTask) {
                archives.from(file('idea.zip'))
                ideaVersion = '262.1'
                libraryFamilies = IdeaLibraryFamily.values().toList()
                outputDirectory = layout.buildDirectory.dir('extracted')
            }
            """.trimIndent(),
        )
        return project
    }

    private fun run(project: Path): TaskOutcome? = GradleRunner.create()
        .withProjectDir(project.toFile())
        .withTestKitDir(root.resolve("test-kit").toFile())
        .withArguments("extract", "--build-cache", "--configuration-cache", "--console=plain", "--max-workers=2")
        .build().task(":extract")?.outcome

    private fun archive(project: Path, libraries: Map<String, String>) {
        ZipOutputStream(Files.newOutputStream(project.resolve("idea.zip"))).use { zip ->
            (libraries + ("plugins/unused/lib/unused.jar" to "excluded bytes")).forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes.toByteArray())
                zip.closeEntry()
            }
        }
    }

    private fun output(project: Path): Map<String, String> {
        val extracted = project.resolve("build/extracted")
        return Files.walk(extracted).use { paths ->
            paths.filter(Files::isRegularFile).toList().associate { path ->
                extracted.relativize(path).toString().replace('\\', '/') to path.readText()
            }
        }
    }

    private fun groovyPath(path: Path): String = "'" + path.toString().replace("\\", "\\\\").replace("'", "\\'") + "'"
}
