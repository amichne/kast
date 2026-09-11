package conventions

import conventions.jsoncontracts.JsonContractScanRequest
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
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations

/** Only DTO encoding and process launch occur in Gradle's classloader; compiler PSI stays in the child JVM. */
@CacheableTask
abstract class VerifyJsonContractsTask @Inject constructor(private val execution: ExecOperations) : DefaultTask() {
    @get:Internal abstract val repositoryDirectory: DirectoryProperty
    @get:InputFiles @get:PathSensitive(PathSensitivity.RELATIVE) abstract val sourceFiles: ConfigurableFileCollection
    @get:InputFile @get:PathSensitive(PathSensitivity.RELATIVE) abstract val baselineFile: RegularFileProperty
    @get:Classpath abstract val parserClasspath: ConfigurableFileCollection
    @get:OutputFile abstract val reportFile: RegularFileProperty

    @TaskAction
    fun verify() {
        val root = repositoryDirectory.get().asFile
        val request =
            JsonContractScanRequest(
                root = root.absolutePath,
                baseline = baselineFile.get().asFile.absolutePath,
                sources = sourceFiles.files.sortedBy { it.path }.map { it.relativeTo(root).invariantSeparatorsPath },
                report = reportFile.get().asFile.absolutePath,
            )
        val requestFile = temporaryDir.resolve("request.json")
        requestFile.writeText(Json.encodeToString(request))
        val result = execution.javaexec {
            classpath(parserClasspath)
            mainClass.set("conventions.jsoncontracts.JsonContractsMain")
            args(requestFile.absolutePath)
            maxHeapSize = "1g"
            isIgnoreExitValue = true
        }
        if (result.exitValue != 0) {
            throw GradleException(
                "JSON contract syntax verification failed. Review ${reportFile.get().asFile} and migrate the reported expressions to DTOs; do not expand allowances."
            )
        }
    }
}
