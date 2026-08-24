import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import support.tasks.WriteJavaProcessOutputTask
import java.nio.file.Path
import kotlin.io.path.readText

class WriteJavaProcessOutputTaskTest {
    @TempDir
    lateinit var temporaryDirectory: Path

    @Test
    fun `java projection output is captured byte for byte`() {
        val project = ProjectBuilder.builder().withProjectDir(temporaryDirectory.toFile()).build()
        val target = temporaryDirectory.resolve("generated/registry.json")
        val task = project.tasks.register(
            "writeJavaProcessOutputUnderTest",
            WriteJavaProcessOutputTask::class.java,
        ).get().apply {
            classpath.from(
                WriteJavaProcessOutputFixture::class.java.protectionDomain.codeSource.location,
                Unit::class.java.protectionDomain.codeSource.location,
            )
            mainClass.set(WriteJavaProcessOutputFixture::class.java.name)
            outputFile.set(target.toFile())
        }

        task.write()

        assertEquals("{\"projection\":true}\n", target.readText())
    }
}

object WriteJavaProcessOutputFixture {
    @JvmStatic
    fun main(arguments: Array<String>) {
        check(arguments.isEmpty())
        print("{\"projection\":true}\n")
    }
}
