package support.tasks

import java.io.ByteArrayOutputStream
import javax.inject.Inject
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import org.gradle.work.DisableCachingByDefault

/** Keeps local task history for a declared command and its real diagnostic report. */
@DisableCachingByDefault(because = "Host tools and private fixture paths do not establish portable output identity.")
abstract class VerificationReportTask @Inject constructor(private val execution: ExecOperations) : DefaultTask() {
    @get:Internal abstract val repositoryDirectory: DirectoryProperty
    @get:InputFile @get:PathSensitive(PathSensitivity.ABSOLUTE) abstract val executableFile: RegularFileProperty
    @get:Input abstract val arguments: ListProperty<String>
    @get:Input abstract val environmentVariables: MapProperty<String, String>
    @get:Input abstract val hostIdentity: MapProperty<String, String>
    @get:InputFiles @get:PathSensitive(PathSensitivity.RELATIVE) abstract val sourceFiles: ConfigurableFileCollection
    @get:InputFiles @get:PathSensitive(PathSensitivity.ABSOLUTE) abstract val runtimeFiles: ConfigurableFileCollection
    @get:OutputFile abstract val reportFile: RegularFileProperty

    init {
        arguments.convention(emptyList())
        environmentVariables.convention(mapOf("LC_ALL" to "C"))
        hostIdentity.convention(listOf("os.name", "os.arch", "os.version").associateWith(System::getProperty))
    }

    @TaskAction
    fun examine() {
        val report = reportFile.get().asFile
        report.parentFile.mkdirs()
        // A start failure must not leave a previous successful report at this path.
        report.delete()
        val output = ByteArrayOutputStream()
        val result = execution.exec {
            executable(executableFile.get().asFile)
            args(arguments.get())
            workingDir(repositoryDirectory.get().asFile)
            setEnvironment(environmentVariables.get())
            standardOutput = output
            errorOutput = output
            isIgnoreExitValue = true
        }
        report.writeBytes(output.toByteArray())
        if (result.exitValue != 0) {
            logger.error(output.toString(Charsets.UTF_8))
            throw GradleException("$path failed with exit code ${result.exitValue}. Report: $report")
        }
    }
}
