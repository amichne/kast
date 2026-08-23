package support.tasks

import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import javax.inject.Inject
import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations

/** Runs one declared executable and atomically preserves its standard output as a build resource. */
@CacheableTask
abstract class WriteProcessOutputTask @Inject constructor(
    private val execOperations: ExecOperations,
) : DefaultTask() {
    init {
        arguments.convention(emptyList())
        environmentVariables.convention(emptyMap())
    }

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val executableFile: RegularFileProperty

    @get:Input
    abstract val arguments: ListProperty<String>

    @get:Input
    abstract val environmentVariables: MapProperty<String, String>

    @get:OutputFile
    abstract val outputFile: RegularFileProperty

    @TaskAction
    fun write() {
        val output = ByteArrayOutputStream()
        execOperations.exec {
            executable(executableFile.get().asFile)
            args(arguments.get())
            environment(environmentVariables.get())
            standardOutput = output
        }.assertNormalExitValue()

        val target = outputFile.get().asFile.toPath()
        Files.createDirectories(target.parent)
        val temporary = Files.createTempFile(target.parent, target.fileName.toString(), ".tmp")
        try {
            Files.write(temporary, output.toByteArray())
            try {
                Files.move(
                    temporary,
                    target,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temporary)
        }
    }
}
