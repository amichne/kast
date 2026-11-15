import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class IdeaLibraryTransformTest {
    @TempDir lateinit var root: Path

    @Test
    fun `compile selection keeps each family and omits shadowing jars`() {
        val entries = listOf(
            "idea/lib/platform.jar",
            "idea/lib/modules/nested.jar",
            "idea/lib/intellij.libraries.kotlinx.serialization.json.jar",
            "idea/lib/intellij.libraries.ktor.utils.jar",
            "idea/lib/intellij.libraries.ktor.client.jar",
            "idea/plugins/Kotlin/lib/kotlin-plugin.jar",
            "idea/plugins/Kotlin/lib/jps/kotlin-jps.jar",
            "idea/plugins/Kotlin/lib/kotlinc/lib/kotlin-compiler.jar",
            "idea/plugins/Kotlin/lib/kotlinc.kotlin-compiler-common.jar",
            "idea/plugins/java/lib/java-impl.jar",
            "idea/plugins/gradle-java/lib/gradle-java.jar",
            "idea/plugins/python/lib/python.jar",
            "idea/plugins/Kotlin/README.md",
        )
        val selected = IdeaLibraryFamily.entries.associateWith { family -> entries.filter(family::compiles) }

        assertEquals(
            mapOf(
                IdeaLibraryFamily.PLATFORM to listOf(
                    "idea/lib/platform.jar",
                    "idea/lib/modules/nested.jar",
                    "idea/lib/intellij.libraries.ktor.client.jar",
                ),
                IdeaLibraryFamily.KOTLIN to listOf(
                    "idea/plugins/Kotlin/lib/kotlin-plugin.jar",
                    "idea/plugins/Kotlin/lib/kotlinc.kotlin-compiler-common.jar",
                ),
                IdeaLibraryFamily.JAVA to listOf("idea/plugins/java/lib/java-impl.jar"),
                IdeaLibraryFamily.GRADLE to listOf("idea/plugins/gradle-java/lib/gradle-java.jar"),
            ),
            selected,
        )
    }

    @Test
    fun `configuration-time resolution yields the jars without running a task`() {
        val project = fixture(
            mapOf(
                "idea/lib/platform.jar" to "platform bytes",
                "idea/plugins/Kotlin/lib/kotlin-plugin.jar" to "Kotlin bytes",
                "idea/plugins/Kotlin/lib/jps/kotlin-jps.jar" to "JPS bytes",
            ),
        )

        val output = GradleRunner.create()
            .withProjectDir(project.toFile())
            .withArguments("help", "--offline", "--console=plain", "--no-configuration-cache")
            .build().output

        assertTrue(output.contains("resolved: kotlin-plugin.jar=Kotlin bytes"), output)
        assertFalse(output.contains("kotlin-jps.jar"), output)
        assertFalse(output.contains("platform.jar"), output)
    }

    @Test
    fun `an archive without the requested family fails resolution`() {
        val project = fixture(mapOf("idea/lib/platform.jar" to "platform bytes"))

        val output = GradleRunner.create()
            .withProjectDir(project.toFile())
            .withArguments("help", "--offline", "--console=plain", "--no-configuration-cache")
            .buildAndFail().output

        assertTrue(output.contains("has no KOTLIN libraries"), output)
    }

    private fun fixture(entries: Map<String, String>): Path {
        val project = root.resolve("project").createDirectories()
        ZipOutputStream(Files.newOutputStream(project.resolve("idea.zip"))).use { zip ->
            entries.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes.toByteArray())
                zip.closeEntry()
            }
        }
        val classes = Path.of(ExtractIdeaLibrariesTransform::class.java.protectionDomain.codeSource.location.toURI())
        project.resolve("settings.gradle").writeText("rootProject.name = 'idea-library-fixture'")
        project.resolve("build.gradle").writeText(
            """
            buildscript { dependencies { classpath files('${classes.toString().replace("\\", "/")}') } }
            def distribution = configurations.create('distribution') { canBeConsumed = false; canBeResolved = true }
            dependencies.add('distribution', files('idea.zip'))
            IdeaLibraryTransformKt.ideaLibraries(project, distribution, IdeaLibraryFamily.KOTLIN).files.each {
                println("resolved: ${'$'}{it.name}=${'$'}{it.text}")
            }
            """.trimIndent(),
        )
        return project
    }
}
