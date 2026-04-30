import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import proguard.Configuration
import proguard.ConfigurationParser
import proguard.ProGuard
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlin.io.path.writeText

/**
 * Shrinks the assembled runtime-libs directory using ProGuard.
 *
 * Program JARs (everything not in [libraryJars]) are merged and shrunk into a single
 * [SHRUNK_JAR_NAME]. Library JARs (IntelliJ platform JARs and other pass-throughs) are
 * copied unchanged. A new classpath.txt is written with [SHRUNK_JAR_NAME] first, followed
 * by library JARs in their original classpath order.
 *
 * Enabled via the `kast.shrinkRuntime=true` Gradle property. Normal builds skip this task.
 */
@CacheableTask
abstract class ShrinkRuntimeLibsTask : DefaultTask() {

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val inputDirectory: DirectoryProperty

    /** Files whose filenames should be treated as library JARs (passed to ProGuard unchanged). */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val libraryJars: ConfigurableFileCollection

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val proguardRules: RegularFileProperty

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @get:Internal
    abstract val outputClasspathFile: RegularFileProperty

    @TaskAction
    fun shrink() {
        val inputDir = inputDirectory.get().asFile.toPath()
        val outputDir = outputDirectory.get().asFile.toPath()
        Files.createDirectories(outputDir)

        // Clean stale output
        Files.list(outputDir).use { it.toList() }.forEach { it.toFile().deleteRecursively() }

        val orderedJarNames = inputDir.resolve("classpath.txt")
            .toFile()
            .readLines()
            .filter(String::isNotBlank)

        val libraryJarNames = libraryJars.files.map(File::getName).toHashSet()

        val programJarPaths = orderedJarNames
            .filterNot { it in libraryJarNames }
            .map { inputDir.resolve(it).toFile() }
            .filter(File::exists)

        val libraryJarPathsInOrder = orderedJarNames
            .filter { it in libraryJarNames }
            .map { inputDir.resolve(it).toFile() }
            .filter(File::exists)

        val shrunkJarFile = outputDir.resolve(SHRUNK_JAR_NAME).toFile()

        runProGuard(
            programJars = programJarPaths,
            libraryJars = libraryJarPathsInOrder,
            outputJar = shrunkJarFile,
            rulesFile = proguardRules.get().asFile,
        )

        libraryJarPathsInOrder.forEach { libJar ->
            Files.copy(
                libJar.toPath(),
                outputDir.resolve(libJar.name),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.COPY_ATTRIBUTES,
            )
        }

        // Shrunk JAR first so it wins the classpath race before IntelliJ platform JARs.
        val newEntries = listOf(SHRUNK_JAR_NAME) + libraryJarPathsInOrder.map(File::getName)
        outputClasspathFile.get().asFile.toPath()
            .writeText(newEntries.joinToString(separator = "\n", postfix = "\n"))
    }

    private fun runProGuard(
        programJars: List<File>,
        libraryJars: List<File>,
        outputJar: File,
        rulesFile: File,
    ) {
        val args = mutableListOf<String>()

        programJars.forEach { jar ->
            args += "-injars"
            args += jar.absolutePath
        }
        args += "-outjars"
        args += outputJar.absolutePath

        libraryJars.forEach { jar ->
            args += "-libraryjars"
            args += jar.absolutePath
        }

        val jmodsDir = File(System.getProperty("java.home")).resolve("jmods")
        if (jmodsDir.exists()) {
            args += "-libraryjars"
            args += jmodsDir.absolutePath
        }

        args += "-include"
        args += rulesFile.absolutePath

        val config = Configuration()
        ConfigurationParser(args.toTypedArray(), null).parse(config)
        ProGuard(config).execute()
    }

    companion object {
        const val SHRUNK_JAR_NAME = "kast-shrunk.jar"
    }
}
