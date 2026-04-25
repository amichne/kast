import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class RuntimeJarPathOrderingTest {
    @Test
    fun `runtime jar paths include not-yet-created jars`(@TempDir directory: Path) {
        val existingJar = directory.resolve("existing.jar").toFile()
        val absentJar = directory.resolve("analysis-api.jar").toFile()
        existingJar.writeText("not a real jar; only the path matters")

        val paths = RuntimeJarPathOrdering.inOrder(
            files = linkedSetOf(
                existingJar,
                absentJar,
                directory.resolve("classes").toFile(),
            ),
        )

        assertEquals(
            listOf(
                existingJar.absolutePath,
                absentJar.absolutePath,
            ),
            paths,
        )
    }
}
