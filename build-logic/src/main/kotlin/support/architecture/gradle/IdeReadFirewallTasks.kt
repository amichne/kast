package support.architecture.gradle

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.UntrackedTask
import support.architecture.ArchitecturePolicyValidation
import support.architecture.IdeReadFirewall
import support.architecture.IdeReadFirewallProof
import support.architecture.IdeReadFirewallResult
import support.architecture.KastArchitecturePolicy
import support.architecture.ValidatedArchitecturePolicy
import java.nio.file.Files

@UntrackedTask(because = "Re-derives every fixed forbidden-authority rejection")
abstract class VerifyKastVfsPassiveFirewallNegativeTask : DefaultTask() {
    @TaskAction
    fun verify() {
        val proof = deriveFirewallProof()
        val observed = proof.forbiddenAuthorities.keys
        val expected = support.architecture.IdeReadForbiddenAuthority.entries.toSet()
        if (observed != expected) {
            throw GradleException("KVP-009 negative firewall cases differ: $observed")
        }
        logger.lifecycle("KVP-009 rejected all {} forbidden authority cases", observed.size)
    }
}

@CacheableTask
abstract class VerifyKastVfsPassiveFirewallTask : DefaultTask() {
    @get:OutputFile
    abstract val reportFile: RegularFileProperty

    @TaskAction
    fun verify() {
        val proof = deriveFirewallProof()
        val document = IdeReadFirewallReportDocument(
            schemaVersion = 1,
            taskId = "KVP-009",
            role = "IDE_READ_ONLY",
            modules = proof.modules.map { it.id.projectPath }.sorted(),
            allowedDependencies = proof.modules.associate { module ->
                module.id.projectPath to module.allowedProjectDependencies
                    .map { it.projectPath }
                    .sorted()
            }.toSortedMap(),
            allowedEffects = proof.modules.associate { module ->
                module.id.projectPath to module.allowedEffects.map(Enum<*>::name).sorted()
            }.toSortedMap(),
            forbiddenAuthorities = proof.forbiddenAuthorities.entries.associate { (authority, effects) ->
                authority.name to effects.map(Enum<*>::name).sorted()
            }.toSortedMap(),
        )
        val target = reportFile.get().asFile.toPath()
        Files.createDirectories(target.parent)
        Files.writeString(
            target,
            ideReadFirewallJson.encodeToString(IdeReadFirewallReportDocument.serializer(), document) + "\n",
        )
    }
}

/**
 * Proof transition: canonical architecture definition -> `IdeReadFirewallProof`.
 * Establishes a validated architecture and complete IDE-read firewall. Expected policy or firewall
 * failures remain closed until rendered as a Gradle failure at this outer build-policy boundary.
 */
private fun deriveFirewallProof(): IdeReadFirewallProof {
    val policy = when (val result = KastArchitecturePolicy.validate()) {
        is ArchitecturePolicyValidation.Valid -> result.architecture
        is ArchitecturePolicyValidation.Invalid -> throw GradleException(
            "Canonical architecture policy is invalid: ${result.failures}",
        )
    }
    return policy.firewallProof()
}

private fun ValidatedArchitecturePolicy.firewallProof(): IdeReadFirewallProof = when (
    val result = IdeReadFirewall.derive(this)
) {
    is IdeReadFirewallResult.Complete -> result.proof
    is IdeReadFirewallResult.Rejected -> throw GradleException(
        "KVP-009 IDE-read firewall rejected: ${result.failures}",
    )
}

@Serializable
private data class IdeReadFirewallReportDocument(
    val schemaVersion: Int,
    val taskId: String,
    val role: String,
    val modules: List<String>,
    val allowedDependencies: Map<String, List<String>>,
    val allowedEffects: Map<String, List<String>>,
    val forbiddenAuthorities: Map<String, List<String>>,
)

private val ideReadFirewallJson = Json {
    encodeDefaults = true
    explicitNulls = false
    prettyPrint = true
}
