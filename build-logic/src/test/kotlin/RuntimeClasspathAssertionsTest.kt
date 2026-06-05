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
            "io/github/amichne/kast/api/client/ServerLaunchOptions.class",
        )

        val missing = RuntimeClasspathAssertions.missingRequiredClassEntries(
            runtimeLibsDirectory = runtimeLibs,
            classpathEntries = listOf("analysis-api.jar"),
            requiredClassEntries = listOf("io/github/amichne/kast/api/client/ServerLaunchOptions.class"),
        )

        assertEquals(emptyList<String>(), missing)
    }

    @Test
    fun `required class entry is reported when no runtime jar contains it`(@TempDir runtimeLibs: Path) {
        writeJar(
            runtimeLibs.resolve("backend-headless.jar"),
            "io/github/amichne/kast/headless/HeadlessMainKt.class",
        )

        val missing = RuntimeClasspathAssertions.missingRequiredClassEntries(
            runtimeLibsDirectory = runtimeLibs,
            classpathEntries = listOf("backend-headless.jar"),
            requiredClassEntries = listOf("io/github/amichne/kast/api/client/ServerLaunchOptions.class"),
        )

        assertEquals(listOf("io/github/amichne/kast/api/client/ServerLaunchOptions.class"), missing)
    }

    @Test
    fun `runtime entries with forbidden prefixes are reported`() {
        val forbiddenEntries = RuntimeClasspathAssertions.entriesMatchingAnyPrefix(
            classpathEntries = listOf(
                "backend-headless-1.0-launcher.jar",
                "backend-intellij-1.0-base.jar",
                "analysis-server-1.0.jar",
                "platform-loader.jar",
            ),
            jarPrefixes = listOf("analysis-server-", "backend-intellij-"),
        )

        assertEquals(listOf("backend-intellij-1.0-base.jar", "analysis-server-1.0.jar"), forbiddenEntries)
    }

    @Test
    fun `missing jar prefixes are reported from plugin lib entries`() {
        val missingPrefixes = RuntimeClasspathAssertions.missingJarPrefixes(
            classpathEntries = listOf(
                "analysis-api-1.0.jar",
                "backend-intellij-1.0-base.jar",
                "kotlinx-coroutines-core-jvm-1.10.2.jar",
            ),
            requiredJarPrefixes = listOf(
                "analysis-api-",
                "analysis-server-",
                "backend-intellij-",
                "kotlinx-coroutines-core",
            ),
        )

        assertEquals(listOf("analysis-server-"), missingPrefixes)
    }

    @Test
    fun `jar entries containing nested plugin descriptors are reported`(@TempDir runtimeLibs: Path) {
        writeJar(
            runtimeLibs.resolve("backend-headless-1.0-plugin-descriptor.jar"),
            "META-INF/plugin.xml",
        )
        writeJar(
            runtimeLibs.resolve("backend-intellij-1.0-base.jar"),
            "META-INF/plugin.xml",
        )
        writeJar(
            runtimeLibs.resolve("analysis-api-1.0.jar"),
            "io/github/amichne/kast/api/client/ServerLaunchOptions.class",
        )

        val entries = RuntimeClasspathAssertions.entriesContainingJarEntry(
            runtimeLibsDirectory = runtimeLibs,
            classpathEntries = listOf(
                "backend-headless-1.0-plugin-descriptor.jar",
                "backend-intellij-1.0-base.jar",
                "analysis-api-1.0.jar",
            ),
            jarEntry = "META-INF/plugin.xml",
        )

        assertEquals(
            listOf("backend-headless-1.0-plugin-descriptor.jar", "backend-intellij-1.0-base.jar"),
            entries,
        )
    }

    @Test
    fun `portable distribution jars with forbidden suffixes are reported`(@TempDir portableDist: Path) {
        Files.createDirectories(portableDist.resolve("libs"))
        Files.createDirectories(portableDist.resolve("runtime-libs"))
        Files.writeString(portableDist.resolve("libs/backend-headless-1.0-all.jar"), "fat jar")
        Files.writeString(portableDist.resolve("runtime-libs/backend-headless-1.0-launcher.jar"), "launcher")

        val entries = RuntimeClasspathAssertions.filesWithAnySuffix(
            directory = portableDist,
            suffixes = listOf("-all.jar"),
        )

        assertEquals(listOf("libs/backend-headless-1.0-all.jar"), entries)
    }

    private fun writeJar(path: Path, vararg entryNames: String) {
        Files.createDirectories(path.parent)
        ZipOutputStream(Files.newOutputStream(path)).use { output ->
            entryNames.forEach { entryName ->
                output.putNextEntry(ZipEntry(entryName))
                output.write(byteArrayOf(0))
                output.closeEntry()
            }
        }
    }
}
