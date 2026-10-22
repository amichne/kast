import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class DetektBaselineTest {
    @TempDir lateinit var temporaryDirectory: Path

    @Test
    fun `native baseline accepts recorded findings and rejects new findings`() {
        temporaryDirectory.resolve("settings.gradle").writeText("rootProject.name = 'detekt-baseline-fixture'\n")
        temporaryDirectory
            .resolve("build.gradle")
            .writeText(
                """
                plugins { id 'dev.detekt' }
                repositories { mavenCentral() }
                detekt {
                    toolVersion = '2.0.0-alpha.6'
                    baseline.set(layout.projectDirectory.file('baseline.xml'))
                }
                """
                    .trimIndent()
            )
        val source = temporaryDirectory.resolve("src/main/kotlin/example/Example.kt")
        source.parent.createDirectories()
        source.writeText("package example\nfun existing(value: Int): Int = value + 42\n")
        val runner = GradleRunner.create().withProjectDir(temporaryDirectory.toFile()).withPluginClasspath()

        val original = runner.withArguments("detekt", "--console=plain", "--no-watch-fs").buildAndFail()
        assertTrue(original.output.contains("MagicNumber"), original.output)
        runner.withArguments("detektBaseline", "--console=plain", "--no-watch-fs").build()
        runner.withArguments("detekt", "--console=plain", "--no-watch-fs").build()

        source.writeText(
            "package example\nfun existing(value: Int): Int = value + 42\nfun added(value: Int): Int = value + 43\n"
        )
        val added = runner.withArguments("detekt", "--console=plain", "--no-watch-fs").buildAndFail()
        assertTrue(added.output.contains("MagicNumber"), added.output)
        assertTrue(added.output.contains("Example.kt:3:"), added.output)
    }

    @Test
    fun `quality check schedules one aggregate detekt analysis`() {
        temporaryDirectory.resolve("settings.gradle").writeText("rootProject.name = 'detekt-check-fixture'\n")
        temporaryDirectory
            .resolve("build.gradle")
            .writeText(
                """
                plugins {
                    id 'org.jetbrains.kotlin.jvm'
                    id 'kast.kotlin-quality'
                }
                repositories { mavenCentral() }
                """
                    .trimIndent()
            )

        val result =
            GradleRunner.create()
                .withProjectDir(temporaryDirectory.toFile())
                .withPluginClasspath()
                .withArguments("check", "--dry-run", "--console=plain")
                .build()
        val detektTasks =
            result.output.lineSequence().filter { it.matches(Regex("^:detekt(?:Main|Test)? SKIPPED$")) }.toList()

        assertEquals(listOf(":detekt SKIPPED"), detektTasks, result.output)
    }

    @Test
    fun `scoped check includes module quality and tests without native assembly`() {
        temporaryDirectory.resolve("settings.gradle").writeText("rootProject.name = 'scoped-check-fixture'\n")
        temporaryDirectory.resolve("build.gradle").writeText(
            """
            plugins {
                id 'org.jetbrains.kotlin.jvm'
                id 'kast.kotlin-quality'
            }
            tasks.register('nativeCompile') { throw new GradleException('native assembly entered the edit loop') }
            """.trimIndent(),
        )
        val result = GradleRunner.create()
            .withProjectDir(temporaryDirectory.toFile())
            .withPluginClasspath()
            .withArguments("scopedCheck", "--dry-run", "--console=plain")
            .build()
        val scheduled = result.output.lineSequence().filter { it.endsWith(" SKIPPED") }.toSet()
        listOf("spotlessCheck", "checkMainKotlinFileLength", "checkTestKotlinFileLength", "detekt", "test")
            .forEach { task -> assertTrue(":$task SKIPPED" in scheduled, result.output) }
        assertTrue(":nativeCompile SKIPPED" !in scheduled, result.output)
    }
}
