import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.SetProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.zip.ZipFile

enum class IdeaLibraryFamily {
    PLATFORM,
    KOTLIN,
    JAVA,
    GRADLE;

    internal fun includes(entryName: String): Boolean {
        if (!entryName.endsWith(".jar")) return false
        val segments = entryName.split('/')
        val pluginIndex = segments.indexOf("plugins")
        val pluginLibrary = pluginIndex >= 0 && pluginIndex + 3 < segments.size && segments[pluginIndex + 2] == "lib"
        return when (this) {
            PLATFORM -> pluginIndex < 0 && "lib" in segments
            KOTLIN -> pluginLibrary && segments[pluginIndex + 1] == "Kotlin"
            JAVA -> pluginLibrary && segments[pluginIndex + 1] == "java"
            GRADLE -> pluginLibrary && segments[pluginIndex + 1].startsWith("gradle")
        }
    }
}

@CacheableTask
abstract class ExtractIdeaDistributionTask : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val archives: ConfigurableFileCollection

    @get:Input
    abstract val ideaVersion: Property<String>

    @get:Input
    abstract val libraryFamilies: SetProperty<IdeaLibraryFamily>

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun extract() {
        val selectedFamilies = libraryFamilies.get()
        require(selectedFamilies.isNotEmpty()) { "IDEA extraction requires at least one library family" }
        val archiveFile = archives.singleFile
        val outputRoot = outputDirectory.get().asFile.toPath()
        val parent = outputRoot.parent
                     ?: throw GradleException("IDEA extraction output must have a parent directory: $outputRoot")
        Files.createDirectories(parent)
        val tempRoot = Files.createTempDirectory(parent, "${outputRoot.fileName}.tmp-")
        try {
            ZipFile(archiveFile).use { archive ->
                val entries = archive.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    val target = tempRoot.resolve(entry.name).normalize()
                    if (!target.startsWith(tempRoot)) {
                        throw GradleException("Zip-slip attempt detected while extracting ${entry.name} from $archiveFile.")
                    }

                    if (entry.isDirectory || selectedFamilies.none { family -> family.includes(entry.name) }) continue

                    target.parent?.let(Files::createDirectories)
                    archive.getInputStream(entry).use { input ->
                        Files.copy(input, target, StandardCopyOption.REPLACE_EXISTING)
                    }
                }
            }
            Files.writeString(tempRoot.resolve(".kast-idea-version"), ideaVersion.get())
            outputRoot.toFile().deleteRecursively()
            try {
                Files.move(tempRoot, outputRoot, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(tempRoot, outputRoot, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            tempRoot.toFile().deleteRecursively()
        }
    }
}
