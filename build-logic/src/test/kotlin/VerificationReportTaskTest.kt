import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.deleteExisting
import kotlin.io.path.readText
import kotlin.io.path.writeText
import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import support.tasks.VerificationReportTask

class VerificationReportTaskTest {
    @TempDir lateinit var root: Path

    @Test
    fun `unchanged inputs reuse the report and source tool and environment changes rerun`() {
        fixture()
        assertEquals(TaskOutcome.SUCCESS, run().task(":examine")?.outcome)
        assertTrue(report().contains("first:original"))
        assertEquals(TaskOutcome.UP_TO_DATE, run().task(":examine")?.outcome)

        root.resolve("input.txt").writeText("second")
        assertEquals(TaskOutcome.SUCCESS, run().task(":examine")?.outcome)
        assertTrue(report().contains("second:original"))

        root.resolve("tool.sh").writeText("printf 'tool:'; cat input.txt; printf ':%s' \"\$TOKEN\"\n")
        assertEquals(TaskOutcome.SUCCESS, run().task(":examine")?.outcome)
        assertTrue(report().contains("tool:second:original"))
        assertEquals(TaskOutcome.SUCCESS, run("-Ptoken=changed").task(":examine")?.outcome)
        assertTrue(report().contains("tool:second:changed"))

        val shell = root.resolve("alternate-shell")
        Files.createSymbolicLink(shell, Path.of("/bin/sh"))
        assertEquals(TaskOutcome.SUCCESS, run("-Ptoken=changed", "-Pshell=$shell").task(":examine")?.outcome)
        assertEquals(TaskOutcome.UP_TO_DATE, run("-Ptoken=changed", "-Pshell=$shell").task(":examine")?.outcome)
        root.resolve("build/report.txt").deleteExisting()
        assertEquals(TaskOutcome.SUCCESS, run("-Ptoken=changed", "-Pshell=$shell").task(":examine")?.outcome)
    }

    @Test
    fun `failed commands keep diagnostics and cannot reuse a previous success`() {
        fixture()
        run()
        root.resolve("input.txt").writeText("reject")
        val failure = runner().buildAndFail()
        assertEquals(TaskOutcome.FAILED, failure.task(":examine")?.outcome)
        assertTrue(failure.output.contains("rejected fixture"), failure.output)
        assertTrue(report().contains("rejected fixture"))
        assertEquals(TaskOutcome.FAILED, runner().buildAndFail().task(":examine")?.outcome)

        root.resolve("input.txt").writeText("first")
        assertEquals(TaskOutcome.SUCCESS, run().task(":examine")?.outcome)
        assertFalse(report().contains("rejected fixture"))
        assertEquals(TaskOutcome.UP_TO_DATE, run().task(":examine")?.outcome)
    }

    private fun fixture() {
        root.resolve("settings.gradle").writeText("rootProject.name = 'verification-report-fixture'\n")
        root.resolve("input.txt").writeText("first")
        root.resolve("tool.sh").writeText(
            "if [ \"\$(cat input.txt)\" = reject ]; then echo 'rejected fixture' >&2; exit 7; fi\n" +
                "cat input.txt; printf ':%s' \"\$TOKEN\"\n",
        )
        val classes = Path.of(VerificationReportTask::class.java.protectionDomain.codeSource.location.toURI())
        root.resolve("build.gradle").writeText(
            """
            buildscript { dependencies { classpath files('${classes.toString().replace("'", "\\'")}') } }
            tasks.register('examine', support.tasks.VerificationReportTask) {
                repositoryDirectory = layout.projectDirectory
                executableFile = file(providers.gradleProperty('shell').getOrElse('/bin/sh'))
                arguments = ['tool.sh']
                environmentVariables.put('TOKEN', providers.gradleProperty('token').orElse('original'))
                sourceFiles.from('input.txt', 'tool.sh')
                reportFile = layout.buildDirectory.file('report.txt')
            }
            """.trimIndent(),
        )
    }

    private fun runner(vararg arguments: String): GradleRunner = GradleRunner.create()
        .withProjectDir(root.toFile())
        .withArguments("examine", "--configuration-cache", "--console=plain", "--max-workers=2", *arguments)

    private fun run(vararg arguments: String): BuildResult = runner(*arguments).build()

    private fun report(): String = root.resolve("build/report.txt").readText()
}
