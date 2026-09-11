package conventions

import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

abstract class KotlinFileLengthTask : DefaultTask() {
    @get:Internal abstract val repositoryDirectory: DirectoryProperty

    init {
        repositoryDirectory.convention(project.rootProject.layout.projectDirectory)
    }

    @get:InputFiles @get:PathSensitive(PathSensitivity.RELATIVE) abstract val sourceFiles: ConfigurableFileCollection

    @get:Input abstract var maximumLines: Int

    @get:InputFile
    @get:Optional
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val baselineFile: RegularFileProperty

    private data class Observation(val path: String, val lines: Int, val limit: Int)

    @TaskAction
    fun verify() {
        require(maximumLines > 0) { "maximumLines must be positive" }
        val repositoryRoot = repositoryDirectory.get().asFile

        val baseline =
            baselineFile.orNull?.asFile?.let { file ->
                when (val admission = KotlinFileLengthBaseline.parse(file.readLines())) {
                    is KotlinFileLengthBaseline.Admission.Accepted -> admission.baseline
                    is KotlinFileLengthBaseline.Admission.Rejected ->
                        error("Invalid Kotlin file-length baseline: ${admission.reason} at line ${admission.line}")
                }
            } ?: KotlinFileLengthBaseline.EMPTY

        val violations =
            sourceFiles.files
                .asSequence()
                .filter { it.isFile && it.extension == "kt" }
                .map { file ->
                    val path = file.relativeTo(repositoryRoot).invariantSeparatorsPath
                    Observation(path, file.readLines().size, baseline.limitFor(path, maximumLines))
                }
                .filter { observation -> observation.lines > observation.limit }
                .sortedBy { observation -> observation.path }
                .toList()

        check(violations.isEmpty()) {
            buildString {
                appendLine("Kotlin files exceed the $maximumLines-line budget:")
                violations.forEach { observation ->
                    appendLine("  ${observation.path}: ${observation.lines} lines (limit ${observation.limit})")
                }
                append("Split a real semantic unit; do not compress code or weaken cohesion to satisfy this gate.")
            }
        }
    }
}
