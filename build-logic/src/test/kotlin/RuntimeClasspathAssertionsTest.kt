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
            runtimeLibs.resolve("indexer.jar"),
            "io/github/amichne/kast/indexer/KastIndexerMainKt.class",
        )

        val missing = RuntimeClasspathAssertions.missingRequiredClassEntries(
            runtimeLibsDirectory = runtimeLibs,
            classpathEntries = listOf("indexer.jar"),
            requiredClassEntries = listOf("io/github/amichne/kast/api/client/ServerLaunchOptions.class"),
        )

        assertEquals(listOf("io/github/amichne/kast/api/client/ServerLaunchOptions.class"), missing)
    }

    @Test
    fun `runtime entries with forbidden prefixes are reported`() {
        val forbiddenEntries = RuntimeClasspathAssertions.entriesMatchingAnyPrefix(
            classpathEntries = listOf(
                "indexer-1.0-launcher.jar",
                "indexer-1.0-plugin.jar",
                "analysis-server-1.0.jar",
                "platform-loader.jar",
            ),
            jarPrefixes = listOf("analysis-server-", "indexer-1.0-plugin"),
        )

        assertEquals(listOf("indexer-1.0-plugin.jar", "analysis-server-1.0.jar"), forbiddenEntries)
    }

    @Test
    fun `missing jar prefixes are reported from plugin lib entries`() {
        val missingPrefixes = RuntimeClasspathAssertions.missingJarPrefixes(
            classpathEntries = listOf(
                "analysis-api-1.0.jar",
                "indexer-1.0-plugin.jar",
                "kotlinx-coroutines-core-jvm-1.10.2.jar",
            ),
            requiredJarPrefixes = listOf(
                "analysis-api-",
                "analysis-server-",
                "indexer-",
                "kotlinx-coroutines-core",
            ),
        )

        assertEquals(listOf("analysis-server-"), missingPrefixes)
    }

    @Test
    fun `jar entries containing nested plugin descriptors are reported`(@TempDir runtimeLibs: Path) {
        writeJar(
            runtimeLibs.resolve("indexer-1.0-plugin.jar"),
            "META-INF/plugin.xml",
        )
        writeJar(
            runtimeLibs.resolve("unexpected-plugin.jar"),
            "META-INF/plugin.xml",
        )
        writeJar(
            runtimeLibs.resolve("analysis-api-1.0.jar"),
            "io/github/amichne/kast/api/client/ServerLaunchOptions.class",
        )

        val entries = RuntimeClasspathAssertions.entriesContainingJarEntry(
            runtimeLibsDirectory = runtimeLibs,
            classpathEntries = listOf(
                "indexer-1.0-plugin.jar",
                "unexpected-plugin.jar",
                "analysis-api-1.0.jar",
            ),
            jarEntry = "META-INF/plugin.xml",
        )

        assertEquals(
            listOf("indexer-1.0-plugin.jar", "unexpected-plugin.jar"),
            entries,
        )
    }

    @Test
    fun `renamed jar containing a platform Kotlin class is reported`(@TempDir pluginLibs: Path) {
        writeJar(
            pluginLibs.resolve("renamed-support.jar"),
            "org/jetbrains/kotlin/cli/common/arguments/Freezable.class",
        )

        val entries = RuntimeClasspathAssertions.entriesContainingAnyJarEntry(
            runtimeLibsDirectory = pluginLibs,
            classpathEntries = listOf("renamed-support.jar"),
            jarEntries = listOf(
                "org/jetbrains/kotlin/cli/common/arguments/Freezable.class",
                "org/jetbrains/kotlin/jps/build/KotlinBuilder.class",
            ),
        )

        assertEquals(listOf("renamed-support.jar"), entries)
    }

    @Test
    fun `missing platform Kotlin class is reported from platform plugin jars`(@TempDir pluginLibs: Path) {
        writeJar(
            pluginLibs.resolve("jps/kotlin-jps-plugin.jar"),
            "org/jetbrains/kotlin/jps/build/KotlinBuilder.class",
        )

        val missing = RuntimeClasspathAssertions.missingRequiredClassEntries(
            runtimeLibsDirectory = pluginLibs,
            classpathEntries = listOf("jps/kotlin-jps-plugin.jar"),
            requiredClassEntries = listOf(
                "org/jetbrains/kotlin/jps/build/KotlinBuilder.class",
                "org/jetbrains/kotlin/cli/common/arguments/Freezable.class",
            ),
        )

        assertEquals(
            listOf("org/jetbrains/kotlin/cli/common/arguments/Freezable.class"),
            missing,
        )
    }

    @Test
    fun `portable distribution jars with forbidden suffixes are reported`(@TempDir portableDist: Path) {
        Files.createDirectories(portableDist.resolve("libs"))
        Files.createDirectories(portableDist.resolve("runtime-libs"))
        Files.writeString(portableDist.resolve("libs/indexer-1.0-all.jar"), "fat jar")
        Files.writeString(portableDist.resolve("runtime-libs/indexer-1.0-launcher.jar"), "launcher")

        val entries = RuntimeClasspathAssertions.filesWithAnySuffix(
            directory = portableDist,
            suffixes = listOf("-all.jar"),
        )

        assertEquals(listOf("libs/indexer-1.0-all.jar"), entries)
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
