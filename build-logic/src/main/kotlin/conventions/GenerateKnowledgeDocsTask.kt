package conventions

import conventions.jsoncontracts.KnowledgeDocsRequest
import javax.inject.Inject
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations

/** Launches the isolated Kotlin PSI parser and emits one detached declaration-document inventory. */
@CacheableTask
abstract class GenerateKnowledgeDocsTask @Inject constructor(private val execution: ExecOperations) : DefaultTask() {
    @get:Internal abstract val repositoryDirectory: DirectoryProperty
    @get:InputFiles @get:PathSensitive(PathSensitivity.RELATIVE) abstract val sourceFiles: ConfigurableFileCollection
    @get:Classpath abstract val parserClasspath: ConfigurableFileCollection
    @get:OutputFile abstract val outputFile: RegularFileProperty

    @TaskAction
    fun generate() {
        if (parserClasspath.isEmpty) {
            throw GradleException(
                "Kast API documentation extraction requires the isolated Kotlin parser classpath; " +
                    "the owning root build did not provide it."
            )
        }
        val root = repositoryDirectory.get().asFile
        val sources =
            sourceFiles.files
                .sortedBy { it.path }
                .map { it.relativeTo(root).invariantSeparatorsPath }
        val request =
            KnowledgeDocsRequest(
                repositoryRoot = root.absolutePath,
                sources = sources,
                output = outputFile.get().asFile.absolutePath,
            )
        val requestFile = temporaryDir.resolve("request.json")
        requestFile.writeText(Json.encodeToString(request))
        val result = execution.javaexec {
            classpath(parserClasspath)
            mainClass.set("conventions.jsoncontracts.KnowledgeDocsMain")
            args(requestFile.absolutePath)
            maxHeapSize = "1g"
            isIgnoreExitValue = true
        }
        if (result.exitValue != 0) {
            throw GradleException(
                "Kast API documentation extraction failed. Review ${outputFile.get().asFile}; " +
                    "unsupported or unreadable production source is not silently omitted."
            )
        }
    }
}
