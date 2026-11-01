package support.architecture.gradle

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

@Serializable
data class ModuleBuildFacts(
    val projectPath: String,
    val productionDependencies: List<String>,
    val verificationDependencies: List<String>,
    val exportedDependencies: List<String>,
    val roleConventions: List<String>,
    val classDirectoryOwners: List<String>,
    val sourceRoots: List<String>,
) : java.io.Serializable

@CacheableTask
abstract class CollectModuleBuildFacts : DefaultTask() {
    @get:Input abstract val facts: Property<ModuleBuildFacts>
    @get:OutputFile abstract val reportFile: RegularFileProperty

    @TaskAction
    fun collect() {
        val target = reportFile.get().asFile
        target.parentFile.mkdirs()
        target.writeText(Json.encodeToString(ModuleBuildFacts.serializer(), facts.get()) + "\n")
    }
}

abstract class ModuleBuildFactsConsumer : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val moduleReports: ConfigurableFileCollection

    protected fun moduleFacts(): List<ModuleBuildFacts> = moduleReports.files.sortedBy { it.path }.map {
        Json.decodeFromString(ModuleBuildFacts.serializer(), it.readText())
    }
}
