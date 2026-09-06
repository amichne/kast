package conventions

import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

abstract class KotlinFileLengthTask : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sourceFiles: ConfigurableFileCollection

    @get:Input
    abstract var maximumLines: Int

    @TaskAction
    fun verify() {
        require(maximumLines > 0) { "maximumLines must be positive" }

        val violations = sourceFiles.files
            .asSequence()
            .filter { it.isFile && it.extension == "kt" }
            .map { file -> file to file.readLines().size }
            .filter { (_, lines) -> lines > maximumLines }
            .sortedBy { (file, _) -> file.relativeTo(project.rootDir).invariantSeparatorsPath }
            .toList()

        check(violations.isEmpty()) {
            buildString {
                appendLine("Kotlin files exceed the $maximumLines-line budget:")
                violations.forEach { (file, lines) ->
                    appendLine("  ${file.relativeTo(project.rootDir).invariantSeparatorsPath}: $lines lines")
                }
                append("Split a real semantic unit; do not compress code or weaken cohesion to satisfy this gate.")
            }
        }
    }
}
