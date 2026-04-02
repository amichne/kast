import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlin.io.path.name
import kotlin.io.path.writeText

abstract class SyncRuntimeLibsTask : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val appJar: RegularFileProperty

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val runtimeJars: ConfigurableFileCollection

    @get:Input
    abstract val runtimeJarPathsInOrder: ListProperty<String>

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @get:OutputFile
    abstract val classpathFile: RegularFileProperty

    @TaskAction
    fun sync() {
        val runtimeLibsDirectory = outputDirectory.get().asFile.toPath()
        if (Files.exists(runtimeLibsDirectory)) {
            runtimeLibsDirectory.toFile().deleteRecursively()
        }
        Files.createDirectories(runtimeLibsDirectory)

        val copiedEntries = mutableListOf<String>()

        val appJarPath = appJar.get().asFile.toPath()
        Files.copy(
            appJarPath,
            runtimeLibsDirectory.resolve(appJarPath.name),
            StandardCopyOption.REPLACE_EXISTING,
        )
        copiedEntries += appJarPath.name

        runtimeJarPathsInOrder.get()
            .map(java.nio.file.Path::of)
            .filter(Files::isRegularFile)
            .forEach { sourcePath ->
                Files.copy(
                    sourcePath,
                    runtimeLibsDirectory.resolve(sourcePath.name),
                    StandardCopyOption.REPLACE_EXISTING,
                )
                copiedEntries += sourcePath.name
            }

        classpathFile.get().asFile.toPath().writeText(
            copiedEntries.joinToString(separator = "\n", postfix = "\n"),
        )
    }
}
