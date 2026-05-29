import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.name

abstract class VerifyClasspathLayoutTask : DefaultTask() {
    init {
        forbiddenRuntimeJarPrefixes.convention(emptyList())
        requiredRuntimeClassEntries.convention(emptyList())
        requiredPluginJarPrefixes.convention(emptyList())
        requiredPluginClassEntries.convention(emptyList())
    }

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val runtimeLibsDirectory: DirectoryProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val runtimeClasspathFile: RegularFileProperty

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val pluginLibsDirectory: DirectoryProperty

    @get:Input
    abstract val forbiddenRuntimeJarPrefixes: ListProperty<String>

    @get:Input
    abstract val requiredRuntimeClassEntries: ListProperty<String>

    @get:Input
    abstract val requiredPluginJarPrefixes: ListProperty<String>

    @get:Input
    abstract val requiredPluginClassEntries: ListProperty<String>

    @TaskAction
    fun verify() {
        val runtimeLibsPath = runtimeLibsDirectory.get().asFile.toPath()
        val runtimeClasspathPath = runtimeClasspathFile.get().asFile.toPath()
        val runtimeClasspathEntries = classpathEntries(runtimeClasspathPath)

        val forbiddenRuntimeEntries = RuntimeClasspathAssertions.entriesMatchingAnyPrefix(
            classpathEntries = runtimeClasspathEntries,
            jarPrefixes = forbiddenRuntimeJarPrefixes.get(),
        )
        if (forbiddenRuntimeEntries.isNotEmpty()) {
            throw GradleException(
                "Headless runtime-libs classpath must not include plugin runtime jars: " +
                    forbiddenRuntimeEntries.joinToString(),
            )
        }

        val missingRuntimeClasses = RuntimeClasspathAssertions.missingRequiredClassEntries(
            runtimeLibsDirectory = runtimeLibsPath,
            classpathEntries = runtimeClasspathEntries,
            requiredClassEntries = requiredRuntimeClassEntries.get(),
        )
        if (missingRuntimeClasses.isNotEmpty()) {
            throw GradleException(
                "Headless runtime-libs classpath is missing launcher or platform classes: " +
                    missingRuntimeClasses.joinToString(),
            )
        }

        val pluginLibsPath = pluginLibsDirectory.get().asFile.toPath()
        val pluginClasspathEntries = jarEntries(pluginLibsPath)

        val missingPluginJarPrefixes = RuntimeClasspathAssertions.missingJarPrefixes(
            classpathEntries = pluginClasspathEntries,
            requiredJarPrefixes = requiredPluginJarPrefixes.get(),
        )
        if (missingPluginJarPrefixes.isNotEmpty()) {
            throw GradleException(
                "Headless plugin lib directory is missing plugin runtime jars with prefixes: " +
                    missingPluginJarPrefixes.joinToString(),
            )
        }

        val missingPluginClasses = RuntimeClasspathAssertions.missingRequiredClassEntries(
            runtimeLibsDirectory = pluginLibsPath,
            classpathEntries = pluginClasspathEntries,
            requiredClassEntries = requiredPluginClassEntries.get(),
        )
        if (missingPluginClasses.isNotEmpty()) {
            throw GradleException(
                "Headless plugin lib directory is missing required plugin classes: " +
                    missingPluginClasses.joinToString(),
            )
        }
    }

    private fun classpathEntries(classpathPath: Path): List<String> {
        if (!Files.isRegularFile(classpathPath)) {
            throw GradleException("Headless runtime classpath file is missing: $classpathPath")
        }
        return Files.readAllLines(classpathPath)
            .map(String::trim)
            .filter(String::isNotEmpty)
    }

    private fun jarEntries(directory: Path): List<String> {
        if (!Files.isDirectory(directory)) {
            throw GradleException("Headless plugin lib directory is missing: $directory")
        }
        return Files.list(directory).use { paths ->
            paths
                .filter(Files::isRegularFile)
                .map { it.name }
                .filter { it.endsWith(".jar") }
                .sorted()
                .toList()
        }
    }
}
