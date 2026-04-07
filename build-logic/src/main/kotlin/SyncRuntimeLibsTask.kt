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
import org.gradle.work.ChangeType
import org.gradle.work.Incremental
import org.gradle.work.InputChanges
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.name
import kotlin.io.path.writeText

abstract class SyncRuntimeLibsTask : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val appJar: RegularFileProperty

    @get:Incremental
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
    fun sync(inputChanges: InputChanges) {
        val runtimeLibsDirectory = outputDirectory.get().asFile.toPath()
        Files.createDirectories(runtimeLibsDirectory)

        val copiedEntries = mutableListOf<String>()

        val appJarPath = appJar.get().asFile.toPath()
        copyIfChanged(appJarPath, runtimeLibsDirectory.resolve(appJarPath.name))
        copiedEntries += appJarPath.name

        val runtimeJarSources = runtimeJarPathsInOrder.get()
            .map(Path::of)
            .filter(Files::isRegularFile)
            .filter { it.name.endsWith(".jar") }

        if (inputChanges.isIncremental) {
            inputChanges.getFileChanges(runtimeJars).forEach { change ->
                if (change.changeType == ChangeType.REMOVED) {
                    Files.deleteIfExists(runtimeLibsDirectory.resolve(change.file.toPath().name))
                }
            }
        }

        runtimeJarSources.forEach { sourcePath ->
            copyIfChanged(sourcePath, runtimeLibsDirectory.resolve(sourcePath.name))
            copiedEntries += sourcePath.name
        }

        pruneUnexpectedEntries(
            runtimeLibsDirectory = runtimeLibsDirectory,
            expectedEntries = buildSet {
                add(appJarPath.name)
                addAll(copiedEntries.drop(1))
            },
            classpathFile = classpathFile.get().asFile.toPath(),
        )

        writeClasspathFile(
            classpathPath = classpathFile.get().asFile.toPath(),
            entries = copiedEntries,
        )
    }
}

private fun copyIfChanged(sourcePath: Path, targetPath: Path) {
    targetPath.parent?.let(Files::createDirectories)
    if (Files.exists(targetPath) && Files.mismatch(sourcePath, targetPath) == -1L) {
        return
    }
    Files.copy(
        sourcePath,
        targetPath,
        StandardCopyOption.REPLACE_EXISTING,
        StandardCopyOption.COPY_ATTRIBUTES,
    )
}

private fun pruneUnexpectedEntries(
    runtimeLibsDirectory: Path,
    expectedEntries: Set<String>,
    classpathFile: Path,
) {
    Files.list(runtimeLibsDirectory).use { entries ->
        entries.forEach { existingPath ->
            val entryName = existingPath.name
            if (existingPath == classpathFile || entryName in expectedEntries) {
                return@forEach
            }
            existingPath.toFile().deleteRecursively()
        }
    }
}

private fun writeClasspathFile(
    classpathPath: Path,
    entries: List<String>,
) {
    val classpathContents = entries.joinToString(separator = "\n", postfix = "\n")
    if (Files.exists(classpathPath) && Files.readString(classpathPath) == classpathContents) {
        return
    }
    classpathPath.parent?.let(Files::createDirectories)
    classpathPath.writeText(classpathContents)
}
