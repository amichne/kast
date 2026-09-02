package support.architecture.knowledge

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import support.architecture.AcceptedArchitectureVerification
import support.architecture.ProjectDependencyObservation
import support.architecture.projection.ArchitectureProjection
import support.architecture.projection.ArchitectureProjectionDocument
import java.security.MessageDigest

internal val moduleKnowledgeJson = Json {
    classDiscriminator = "kind"
    encodeDefaults = true
    explicitNulls = true
    ignoreUnknownKeys = false
    isLenient = false
}

@Serializable
internal data class ModuleKnowledgeDocument(
    val schemaVersion: Int,
    val productVersion: String,
    val sourceRevision: String,
    val architectureVerification: ArchitectureVerificationDocument,
    val architecturePolicy: ArchitectureProjectionDocument,
    val observedProjectDependencies: List<ObservedProjectDependencyDocument>,
    val observedExportedProjectDependencies: List<ObservedProjectDependencyDocument>,
    val agentGuides: List<AgentGuideDocument>,
    val moduleGuideBindings: List<ModuleGuideBindingDocument>,
)

@Serializable
internal data class ArchitectureVerificationDocument(
    val schemaVersion: Int,
    val taskPath: String,
    val status: String,
    val findings: List<ArchitectureVerificationFindingDocument>,
    val reportSha256: String,
)

@Serializable
internal data class ArchitectureVerificationFindingDocument(
    val code: String,
    val message: String,
    val attributes: Map<String, String>,
)

@Serializable
internal data class AgentGuideDocument(
    val path: String,
    val scopeDirectory: String,
    val sha256: String,
    val content: String,
)

@Serializable
internal data class ModuleGuideBindingDocument(
    val projectPath: String,
    val moduleDirectory: String,
    val governingAgentGuidePaths: List<String>,
    val descendantAgentGuidePaths: List<String>,
)

@Serializable
internal data class ObservedProjectDependencyDocument(
    val consumerProjectPath: String,
    val dependencyProjectPath: String,
)

internal data class RawModuleKnowledgeInput(
    val productVersion: String,
    val sourceRevision: String,
    val architectureVerification: AcceptedArchitectureVerification,
    val agentGuides: List<RawAgentGuide>,
)

internal data class RawAgentGuide(
    val relativePath: String,
    val content: String,
)

internal sealed interface ModuleKnowledgeProjectionResult {
    data class Complete(val encoded: String) : ModuleKnowledgeProjectionResult

    data class Rejected(val failures: List<ModuleKnowledgeFailure>) :
        ModuleKnowledgeProjectionResult
}

internal sealed interface ModuleKnowledgeFailure {
    data class InvalidProductVersion(val observed: String) : ModuleKnowledgeFailure

    data class InvalidSourceRevision(val observed: String) : ModuleKnowledgeFailure

    data class InvalidAgentGuidePath(val observed: String) : ModuleKnowledgeFailure

    data class DuplicateAgentGuidePath(val path: String) : ModuleKnowledgeFailure

    data object MissingRootAgentGuide : ModuleKnowledgeFailure

    data class MissingGoverningAgentGuide(val projectPath: String) : ModuleKnowledgeFailure

}

internal object ModuleKnowledgeProjection {
    /**
     * Proof transition: `RawModuleKnowledgeInput -> ModuleKnowledgeProjectionResult`.
     *
     * Establishes a semantic release identity, an accepted architecture verification report,
     * canonical content-addressed guide paths, and complete governing guide coverage for every
     * validated module. Architecture and dependency facts arrive only through
     * [AcceptedArchitectureVerification], so raw Gradle assertions cannot reach serialization.
     * [ModuleKnowledgeProjectionResult.Rejected] is the closed expected failure.
     */
    fun render(
        input: RawModuleKnowledgeInput,
    ): ModuleKnowledgeProjectionResult {
        val productVersion = when (val parsed = ProductVersion.parse(input.productVersion)) {
            is Refinement.Accepted -> parsed.value
            is Refinement.Rejected -> return ModuleKnowledgeProjectionResult.Rejected(parsed.failures)
        }
        val sourceRevision = when (val parsed = SourceRevision.parse(input.sourceRevision)) {
            is Refinement.Accepted -> parsed.value
            is Refinement.Rejected -> return ModuleKnowledgeProjectionResult.Rejected(parsed.failures)
        }
        val verification = input.architectureVerification
        val architecture = verification.architecture
        val guides = when (val parsed = AgentGuideSet.parse(input.agentGuides)) {
            is Refinement.Accepted -> parsed.value
            is Refinement.Rejected -> return ModuleKnowledgeProjectionResult.Rejected(parsed.failures)
        }
        val policy = ArchitectureProjection.document(architecture)
        val bindings = policy.modules.map { module ->
            val moduleDirectory = ModuleDirectory.fromProjectPath(module.projectPath)
            val governing = guides.values.filter { guide -> guide.path.governs(moduleDirectory) }
            if (governing.isEmpty()) {
                return ModuleKnowledgeProjectionResult.Rejected(
                    listOf(ModuleKnowledgeFailure.MissingGoverningAgentGuide(module.projectPath)),
                )
            }
            val descendants = guides.values.filter { guide -> guide.path.descendsFrom(moduleDirectory) }
            ModuleGuideBindingDocument(
                projectPath = module.projectPath,
                moduleDirectory = moduleDirectory.value,
                governingAgentGuidePaths = governing
                    .sortedWith(compareBy({ it.path.scopeDepth }, { it.path.value }))
                    .map { it.path.value },
                descendantAgentGuidePaths = descendants.sortedBy { it.path.value }.map { it.path.value },
            )
        }
        val document = ModuleKnowledgeDocument(
            schemaVersion = 1,
            productVersion = productVersion.value,
            sourceRevision = sourceRevision.value,
            architectureVerification = ArchitectureVerificationDocument(
                schemaVersion = 1,
                taskPath = ":verifyKastArchitecture",
                status = "ACCEPTED",
                findings = emptyList(),
                reportSha256 = sha256(verification.reportBytes()),
            ),
            architecturePolicy = policy,
            observedProjectDependencies = verification.projectDependencies().toDocuments(),
            observedExportedProjectDependencies =
                verification.exportedProjectDependencies().toDocuments(),
            agentGuides = guides.values.sortedBy { it.path.value }.map(AgentGuide::toDocument),
            moduleGuideBindings = bindings,
        )
        return ModuleKnowledgeProjectionResult.Complete(
            moduleKnowledgeJson.encodeToString(ModuleKnowledgeDocument.serializer(), document) + "\n",
        )
    }
}

private fun Set<ProjectDependencyObservation>.toDocuments():
    List<ObservedProjectDependencyDocument> = map { dependency ->
        ObservedProjectDependencyDocument(
            dependency.consumer.projectPath,
            dependency.dependency.projectPath,
        )
    }.sortedWith(
        compareBy(
            ObservedProjectDependencyDocument::consumerProjectPath,
            ObservedProjectDependencyDocument::dependencyProjectPath,
        ),
    )

private sealed interface Refinement<out T> {
    data class Accepted<T>(val value: T) : Refinement<T>

    data class Rejected(val failures: List<ModuleKnowledgeFailure>) : Refinement<Nothing>
}

@JvmInline
private value class ProductVersion private constructor(val value: String) {
    companion object {
        fun parse(raw: String): Refinement<ProductVersion> =
            if (SEMANTIC_VERSION.matches(raw)) {
                Refinement.Accepted(ProductVersion(raw))
            } else {
                Refinement.Rejected(listOf(ModuleKnowledgeFailure.InvalidProductVersion(raw)))
            }

        private val SEMANTIC_VERSION = Regex("[0-9]+\\.[0-9]+\\.[0-9]+")
    }
}

@JvmInline
private value class SourceRevision private constructor(val value: String) {
    companion object {
        fun parse(raw: String): Refinement<SourceRevision> =
            if (GIT_REVISION.matches(raw)) {
                Refinement.Accepted(SourceRevision(raw))
            } else {
                Refinement.Rejected(listOf(ModuleKnowledgeFailure.InvalidSourceRevision(raw)))
            }

        private val GIT_REVISION = Regex("[0-9a-f]{40}")
    }
}

private data class AgentGuideSet(val values: List<AgentGuide>) {
    companion object {
        fun parse(rawGuides: List<RawAgentGuide>): Refinement<AgentGuideSet> {
            val failures = mutableListOf<ModuleKnowledgeFailure>()
            val guides = rawGuides.mapNotNull { raw ->
                when (val path = AgentGuidePath.parse(raw.relativePath)) {
                    is Refinement.Accepted -> AgentGuide(path.value, raw.content)
                    is Refinement.Rejected -> {
                        failures += path.failures
                        null
                    }
                }
            }
            guides.groupingBy { it.path }.eachCount().filterValues { it > 1 }.keys.forEach { path ->
                failures += ModuleKnowledgeFailure.DuplicateAgentGuidePath(path.value)
            }
            if (guides.none { it.path.value == ROOT_AGENT_GUIDE }) {
                failures += ModuleKnowledgeFailure.MissingRootAgentGuide
            }
            return if (failures.isEmpty()) {
                Refinement.Accepted(AgentGuideSet(guides))
            } else {
                Refinement.Rejected(failures.distinct())
            }
        }

        private const val ROOT_AGENT_GUIDE = "AGENTS.md"
    }
}

private data class AgentGuide(
    val path: AgentGuidePath,
    val content: String,
) {
    fun toDocument(): AgentGuideDocument = AgentGuideDocument(
        path = path.value,
        scopeDirectory = path.scopeDirectory,
        sha256 = sha256(content.encodeToByteArray()),
        content = content,
    )
}

@JvmInline
private value class AgentGuidePath private constructor(val value: String) {
    private val scopeSegments: List<String> get() = value.split('/').dropLast(1)
    val scopeDepth: Int get() = scopeSegments.size
    val scopeDirectory: String get() = scopeSegments.joinToString("/").ifEmpty { "." }

    fun governs(module: ModuleDirectory): Boolean = module.segments.startsWith(scopeSegments)

    fun descendsFrom(module: ModuleDirectory): Boolean =
        scopeSegments.size > module.segments.size && scopeSegments.startsWith(module.segments)

    companion object {
        fun parse(raw: String): Refinement<AgentGuidePath> {
            val segments = raw.split('/')
            val valid = raw.isNotBlank() &&
                !raw.startsWith('/') &&
                '\\' !in raw &&
                segments.none { it.isBlank() || it == "." || it == ".." } &&
                segments.last() == "AGENTS.md"
            return if (valid) {
                Refinement.Accepted(AgentGuidePath(raw))
            } else {
                Refinement.Rejected(listOf(ModuleKnowledgeFailure.InvalidAgentGuidePath(raw)))
            }
        }
    }
}

@JvmInline
private value class ModuleDirectory private constructor(val value: String) {
    val segments: List<String> get() = value.split('/')

    companion object {
        fun fromProjectPath(projectPath: String): ModuleDirectory {
            val segments = projectPath.removePrefix(":").split(':')
            return ModuleDirectory(segments.joinToString("/"))
        }
    }
}

private fun <T> List<T>.startsWith(prefix: List<T>): Boolean =
    size >= prefix.size && subList(0, prefix.size) == prefix

private fun sha256(bytes: ByteArray): String =
    "sha256:" + MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { byte -> "%02x".format(byte) }
