import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction

abstract class WriteBackendVersionTask : DefaultTask() {
    @get:Input
    abstract val backendVersion: Property<String>

    @get:Input
    abstract val backendRevision: Property<String>

    @get:OutputFile
    abstract val versionFile: RegularFileProperty

    @get:OutputFile
    abstract val revisionFile: RegularFileProperty

    @TaskAction
    fun write() {
        val version = backendVersion.get()
        val revision = backendRevision.get()
        require(revision.matches(Regex("[0-9a-f]{40}"))) {
            "Backend revision must be a full 40-character lowercase hexadecimal Git revision"
        }
        val versionOutput = versionFile.get().asFile
        val revisionOutput = revisionFile.get().asFile
        versionOutput.apply {
            parentFile.mkdirs()
            writeText(version)
        }
        revisionOutput.apply {
            parentFile.mkdirs()
            writeText(revision)
        }
    }
}
