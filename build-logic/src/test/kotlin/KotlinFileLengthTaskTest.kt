import conventions.KotlinFileLengthTask
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

class KotlinFileLengthTaskTest {
    @TempDir
    lateinit var temporaryDirectory: Path

    @Test
    fun `cached file length gate reports oversized files and accepts the limit`() {
        val taskClasspath = Path.of(KotlinFileLengthTask::class.java.protectionDomain.codeSource.location.toURI())
            .toString().replace("\\", "\\\\").replace("'", "\\'")
        temporaryDirectory.resolve("settings.gradle").writeText("rootProject.name = 'file-length-fixture'\n")
        temporaryDirectory.resolve("build.gradle").writeText(
            """
            buildscript {
                dependencies { classpath files('$taskClasspath') }
            }
            tasks.register('verifyKotlinLength', conventions.KotlinFileLengthTask) {
                sourceFiles.from(file('src/main/kotlin/Example.kt'))
                maximumLines = 2
            }
            """.trimIndent(),
        )
        val source = temporaryDirectory.resolve("src/main/kotlin/Example.kt")
        source.parent.createDirectories()
        source.writeText("package example\nclass Example\n")
        val runner = GradleRunner.create()
            .withProjectDir(temporaryDirectory.toFile())
            .withArguments("verifyKotlinLength", "--configuration-cache", "--console=plain")

        val initial = runner.build()
        assertEquals(TaskOutcome.SUCCESS, initial.task(":verifyKotlinLength")?.outcome)
        assertTrue(initial.output.contains("Configuration cache entry stored."), initial.output)

        source.writeText("package example\n\nclass Example\n")
        val oversized = runner.buildAndFail()
        assertEquals(TaskOutcome.FAILED, oversized.task(":verifyKotlinLength")?.outcome)
        assertTrue(oversized.output.contains("Reusing configuration cache."), oversized.output)
        assertTrue(oversized.output.contains("src/main/kotlin/Example.kt: 3 lines"), oversized.output)
        assertFalse(oversized.output.contains("Task.project"), oversized.output)

        source.writeText("package example\nclass Example\n")
        val restored = runner.build()
        assertEquals(TaskOutcome.SUCCESS, restored.task(":verifyKotlinLength")?.outcome)
        assertTrue(restored.output.contains("Reusing configuration cache."), restored.output)
    }
}
