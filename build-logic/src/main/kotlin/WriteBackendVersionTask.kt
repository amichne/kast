import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction

abstract class WriteBackendVersionTask : DefaultTask() {
    @get:Input
    abstract val backendVersion: Property<String>

    @get:OutputFile
    abstract val versionFile: RegularFileProperty

    @TaskAction
    fun write() {
        versionFile.get().asFile.apply {
            parentFile.mkdirs()
            writeText(backendVersion.get())
        }
    }
}
