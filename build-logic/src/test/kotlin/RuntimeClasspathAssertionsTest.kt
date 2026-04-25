import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class RuntimeClasspathAssertionsTest {
    @Test
    fun `required class entry is satisfied by a jar on the runtime classpath`(@TempDir runtimeLibs: Path) {
        writeJar(
            runtimeLibs.resolve("analysis-api.jar"),
            "io/github/amichne/kast/api/client/StandaloneServerOptions.class",
        )

        val missing = RuntimeClasspathAssertions.missingRequiredClassEntries(
            runtimeLibsDirectory = runtimeLibs,
            classpathEntries = listOf("analysis-api.jar"),
            requiredClassEntries = listOf("io/github/amichne/kast/api/client/StandaloneServerOptions.class"),
        )

        assertEquals(emptyList<String>(), missing)
    }

    @Test
    fun `required class entry is reported when no runtime jar contains it`(@TempDir runtimeLibs: Path) {
        writeJar(
            runtimeLibs.resolve("backend-standalone.jar"),
            "io/github/amichne/kast/standalone/StandaloneMainKt.class",
        )

        val missing = RuntimeClasspathAssertions.missingRequiredClassEntries(
            runtimeLibsDirectory = runtimeLibs,
            classpathEntries = listOf("backend-standalone.jar"),
            requiredClassEntries = listOf("io/github/amichne/kast/api/client/StandaloneServerOptions.class"),
        )

        assertEquals(listOf("io/github/amichne/kast/api/client/StandaloneServerOptions.class"), missing)
    }

    private fun writeJar(path: Path, entryName: String) {
        Files.createDirectories(path.parent)
        ZipOutputStream(Files.newOutputStream(path)).use { output ->
            output.putNextEntry(ZipEntry(entryName))
            output.write(byteArrayOf(0))
            output.closeEntry()
        }
    }
}
