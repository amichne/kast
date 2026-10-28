package support.architecture.gradle

import java.nio.file.Files
import java.security.MessageDigest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault
import support.architecture.ArchitectureObservationParser
import support.architecture.ArchitectureObservationValidation
import support.architecture.ArchitecturePolicyValidation
import support.architecture.ModuleImpact
import support.architecture.ModuleImpactAdmission

@Serializable
internal enum class VerificationGraphEvidence { DECLARED_PRODUCTION_TEST_FIXTURE_CONSUMERS }

@Serializable
internal data class VerificationInput(val path: String, val sha256: String)

@Serializable
internal data class VerificationConsumers(val owner: String, val affected: List<String>, val sourceRoots: List<String>)

@Serializable
internal data class VerificationGraphDocument(
    val inputs: List<VerificationInput>,
    val consumers: List<VerificationConsumers>,
    val schemaVersion: Int = 1,
    val evidence: VerificationGraphEvidence = VerificationGraphEvidence.DECLARED_PRODUCTION_TEST_FIXTURE_CONSUMERS,
)

/** Serializes the existing module-closure proof with every declared configuration, including test fixtures. */
@DisableCachingByDefault(because = "The small report binds current authored build inputs to collected consumers.")
abstract class GenerateVerificationGraphTask : ModuleBuildFactsConsumer() {
    @get:InputFiles @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val graphInputs: ConfigurableFileCollection
    @get:Internal abstract val rootDirectory: DirectoryProperty
    @get:OutputFile abstract val reportFile: RegularFileProperty

    @TaskAction
    fun generate() {
        val facts = moduleFacts()
        val destination = reportFile.get().asFile.toPath()
        Files.deleteIfExists(destination)
        val policy = when (val result = canonicalArchitecturePolicy()) {
            is ArchitecturePolicyValidation.Valid -> result.architecture
            is ArchitecturePolicyValidation.Invalid -> throw GradleException("Verification graph policy: ${result.failures}")
        }
        val graph = when (val result = ArchitectureObservationParser.parse(
            policy, facts.map { it.projectPath }, facts.flatMap { it.verificationDependencies },
        )) {
            is ArchitectureObservationValidation.Valid -> result.graph
            is ArchitectureObservationValidation.Invalid -> throw GradleException("Verification graph input: ${result.failures}")
        }
        val consumers = graph.modules.sortedBy { it.projectPath }.map { owner ->
            when (val admitted = ModuleImpact.admit(graph, listOf(owner.projectPath))) {
                is ModuleImpactAdmission.Accepted -> VerificationConsumers(
                    owner.projectPath,
                    (listOf(owner.projectPath) + admitted.impact.dependencyPaths().map { it.first().projectPath }).sorted(),
                    facts.flatMap { it.sourceRoots }.filter { it.startsWith(owner.projectPath + "|") }
                        .map { it.substringAfter('|') }.distinct().sorted(),
                )
                is ModuleImpactAdmission.Rejected -> throw GradleException("Verification graph closure: ${admitted.failure}")
            }
        }
        if (consumers.isEmpty()) throw GradleException("Verification graph has no modules")
        val root = rootDirectory.get().asFile.toPath()
        val inputs = graphInputs.files.filter { it.isFile }.map { file ->
            VerificationInput(
                root.relativize(file.toPath()).joinToString("/"),
                MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) },
            )
        }.sortedBy { it.path }
        if (inputs.isEmpty()) throw GradleException("Verification graph has no authored inputs")
        val json = Json { encodeDefaults = true; prettyPrint = true }
        Files.createDirectories(destination.parent)
        Files.writeString(destination, json.encodeToString(VerificationGraphDocument.serializer(), VerificationGraphDocument(inputs, consumers)) + "\n")
    }
}
