import conventions.KotlinFileLengthTask
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class KotlinFileLengthBaselineTest {
    @TempDir lateinit var temporaryDirectory: Path

    @Test
    fun `baseline accepts existing debt but rejects growth and new oversized files with cached configuration`() {
        val runner = fixture()
        val existing = temporaryDirectory.resolve("src/main/kotlin/Existing.kt")
        existing.parent.createDirectories()
        existing.writeText("package example\n\nclass Existing\n")
        temporaryDirectory.resolve("baseline.tsv").writeText("src/main/kotlin/Existing.kt\t3\n")

        runner.build()

        existing.writeText("package example\n\nclass Existing\n// growth\n")
        val growth = runner.buildAndFail()
        assertTrue(growth.output.contains("Reusing configuration cache."), growth.output)
        assertTrue(growth.output.contains("Existing.kt: 4 lines (limit 3)"), growth.output)

        existing.writeText("package example\nclass Existing\n")
        runner.build()
        val added = temporaryDirectory.resolve("src/main/kotlin/Added.kt")
        added.writeText("package example\n\nclass Added\n")
        val newFile = runner.buildAndFail()
        assertTrue(newFile.output.contains("Added.kt: 3 lines (limit 2)"), newFile.output)
    }

    @Test
    fun `baseline content changes are validated on a reused configuration cache`() {
        val runner = fixture()
        val source = temporaryDirectory.resolve("src/main/kotlin/Existing.kt")
        source.parent.createDirectories()
        source.writeText("package example\n\nclass Existing\n")
        val baseline = temporaryDirectory.resolve("baseline.tsv")
        baseline.writeText("src/main/kotlin/Existing.kt\t3\n")
        runner.build()

        baseline.writeText("src/main/kotlin/Existing.kt\tnot-a-limit\n")
        val invalid = runner.buildAndFail()
        assertTrue(invalid.output.contains("Reusing configuration cache."), invalid.output)
        assertTrue(
            invalid.output.contains("Invalid Kotlin file-length baseline: INVALID_LIMIT at line 1"),
            invalid.output,
        )

        baseline.writeText("src/main/kotlin/Existing.kt\t3\nsrc/main/kotlin/Existing.kt\t4\n")
        val duplicate = runner.buildAndFail()
        assertTrue(duplicate.output.contains("DUPLICATE_PATH at line 2"), duplicate.output)
    }

    private fun fixture(): GradleRunner {
        val taskClasspath =
            Path.of(KotlinFileLengthTask::class.java.protectionDomain.codeSource.location.toURI())
                .toString()
                .replace("\\", "\\\\")
                .replace("'", "\\'")
        temporaryDirectory.resolve("settings.gradle").writeText("rootProject.name = 'file-length-baseline-fixture'\n")
        temporaryDirectory
            .resolve("build.gradle")
            .writeText(
                """
            buildscript { dependencies { classpath files('$taskClasspath') } }
            tasks.register('verifyKotlinLength', conventions.KotlinFileLengthTask) {
                sourceFiles.from(fileTree('src/main') { include('**/*.kt') })
                maximumLines = 2
                baselineFile.set(layout.projectDirectory.file('baseline.tsv'))
            }
            """
                    .trimIndent()
            )
        return GradleRunner.create()
            .withProjectDir(temporaryDirectory.toFile())
            .withArguments("verifyKotlinLength", "--configuration-cache", "--console=plain")
    }
}
