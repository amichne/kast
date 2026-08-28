import java.nio.file.Path
import kotlin.io.path.readText
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import support.tasks.WriteProcessOutputTask

class WriteProcessOutputTaskTest {
    @TempDir
    lateinit var temporaryDirectory: Path

    @Test
    fun `declared process output is captured byte for byte`() {
        val project = ProjectBuilder.builder().withProjectDir(temporaryDirectory.toFile()).build()
        val target = temporaryDirectory.resolve("generated/schema.json")
        val task = project.tasks.register(
            "writeProcessOutputUnderTest",
            WriteProcessOutputTask::class.java,
        ).get().apply {
            executableFile.set(Path.of("/bin/sh").toFile())
            arguments.set(listOf("-c", "printf '%s\\n' \"${'$'}TASK_OUTPUT\""))
            environmentVariables.set(mapOf("TASK_OUTPUT" to "{\"projection\":true}"))
            outputFile.set(target.toFile())
        }

        task.write()

        assertEquals("{\"projection\":true}\n", target.readText())
    }
}
