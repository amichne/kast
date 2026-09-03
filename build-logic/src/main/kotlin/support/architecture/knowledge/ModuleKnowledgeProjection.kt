package support.architecture.knowledge

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import support.architecture.ValidatedArchitecturePolicy
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
    val architectureVerificationReport: ByteArray,
    val observedProjectDependencies: List<String>,
    val observedExportedProjectDependencies: List<String>,
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

    data object MalformedArchitectureVerificationReport : ModuleKnowledgeFailure

    data class UnsupportedArchitectureVerificationSchema(val observed: Int) :
        ModuleKnowledgeFailure

    data class ArchitectureNotAccepted(
        val status: String,
        val findingCount: Int,
    ) : ModuleKnowledgeFailure

    data class InvalidAgentGuidePath(val observed: String) : ModuleKnowledgeFailure

    data class DuplicateAgentGuidePath(val path: String) : ModuleKnowledgeFailure

    data object MissingRootAgentGuide : ModuleKnowledgeFailure

    data class MissingGoverningAgentGuide(val projectPath: String) : ModuleKnowledgeFailure

    data class MalformedObservedProjectDependency(val notation: String) : ModuleKnowledgeFailure

    data class UnknownObservedProjectDependencyEndpoint(val projectPath: String) :
        ModuleKnowledgeFailure

    data class UnapprovedObservedProjectDependency(
        val consumerProjectPath: String,
        val dependencyProjectPath: String,
    ) : ModuleKnowledgeFailure

    data class ExportedProjectDependencyNotObserved(
        val consumerProjectPath: String,
        val dependencyProjectPath: String,
    ) : ModuleKnowledgeFailure
}

internal object ModuleKnowledgeProjection {
    /**
     * Proof transition: `(ValidatedArchitecturePolicy, RawModuleKnowledgeInput) ->
     * ModuleKnowledgeProjectionResult`.
     *
     * Establishes a semantic release identity, an accepted architecture verification report,
     * canonical content-addressed guide paths, and complete governing guide coverage for every
     * validated module. [ModuleKnowledgeProjectionResult.Rejected] is the closed expected failure.
     * Raw strings are extracted only by the release JSON writer boundary.
     */
    fun render(
        architecture: ValidatedArchitecturePolicy,
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
        val verification = when (
            val parsed = AcceptedArchitectureVerification.parse(input.architectureVerificationReport)
        ) {
            is Refinement.Accepted -> parsed.value
            is Refinement.Rejected -> return ModuleKnowledgeProjectionResult.Rejected(parsed.failures)
        }
        val guides = when (val parsed = AgentGuideSet.parse(input.agentGuides)) {
            is Refinement.Accepted -> parsed.value
            is Refinement.Rejected -> return ModuleKnowledgeProjectionResult.Rejected(parsed.failures)
        }
        val policy = ArchitectureProjection.document(architecture)
        val observedGraph = when (
            val parsed = ObservedProjectGraph.parse(
                input.observedProjectDependencies,
                input.observedExportedProjectDependencies,
                policy,
            )
        ) {
            is Refinement.Accepted -> parsed.value
            is Refinement.Rejected -> return ModuleKnowledgeProjectionResult.Rejected(parsed.failures)
        }
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
            architectureVerification = verification.document,
            architecturePolicy = policy,
            observedProjectDependencies = observedGraph.dependencies,
            observedExportedProjectDependencies = observedGraph.exportedDependencies,
            agentGuides = guides.values.sortedBy { it.path.value }.map(AgentGuide::toDocument),
            moduleGuideBindings = bindings,
        )
        return ModuleKnowledgeProjectionResult.Complete(
            moduleKnowledgeJson.encodeToString(ModuleKnowledgeDocument.serializer(), document) + "\n",
        )
    }
}

private data class ObservedProjectGraph(
    val dependencies: List<ObservedProjectDependencyDocument>,
    val exportedDependencies: List<ObservedProjectDependencyDocument>,
) {
    companion object {
        fun parse(
            rawDependencies: List<String>,
            rawExportedDependencies: List<String>,
            policy: ArchitectureProjectionDocument,
        ): Refinement<ObservedProjectGraph> {
            val failures = mutableListOf<ModuleKnowledgeFailure>()
            val allowed = policy.modules.associate { module ->
                module.projectPath to module.allowedProjectDependencies.toSet()
            }
            fun parseEdge(raw: String): ObservedProjectDependencyDocument? {
                val endpoints = raw.split(PROJECT_DEPENDENCY_SEPARATOR)
                if (endpoints.size != 2 || endpoints.any(String::isBlank)) {
                    failures += ModuleKnowledgeFailure.MalformedObservedProjectDependency(raw)
                    return null
                }
                val consumer = endpoints[0]
                val dependency = endpoints[1]
                if (consumer !in allowed) {
                    failures += ModuleKnowledgeFailure.UnknownObservedProjectDependencyEndpoint(
                        consumer,
                    )
                }
                if (dependency !in allowed) {
                    failures += ModuleKnowledgeFailure.UnknownObservedProjectDependencyEndpoint(
                        dependency,
                    )
                }
                if (consumer !in allowed || dependency !in allowed) return null
                if (dependency !in allowed.getValue(consumer)) {
                    failures += ModuleKnowledgeFailure.UnapprovedObservedProjectDependency(
                        consumer,
                        dependency,
                    )
                    return null
                }
                return ObservedProjectDependencyDocument(consumer, dependency)
            }

            val dependencies = rawDependencies.mapNotNull(::parseEdge).distinct().sorted()
            val exported = rawExportedDependencies.mapNotNull(::parseEdge).distinct().sorted()
            exported.filterNot(dependencies::contains).forEach { dependency ->
                failures += ModuleKnowledgeFailure.ExportedProjectDependencyNotObserved(
                    dependency.consumerProjectPath,
                    dependency.dependencyProjectPath,
                )
            }
            return if (failures.isEmpty()) {
                Refinement.Accepted(ObservedProjectGraph(dependencies, exported))
            } else {
                Refinement.Rejected(failures.distinct())
            }
        }

        private const val PROJECT_DEPENDENCY_SEPARATOR = " -> "

        private fun List<ObservedProjectDependencyDocument>.sorted() = sortedWith(
            compareBy(
                ObservedProjectDependencyDocument::consumerProjectPath,
                ObservedProjectDependencyDocument::dependencyProjectPath,
            ),
        )
    }
}

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

private data class AcceptedArchitectureVerification(
    val document: ArchitectureVerificationDocument,
) {
    companion object {
        fun parse(raw: ByteArray): Refinement<AcceptedArchitectureVerification> {
            val report = try {
                architectureVerificationJson.decodeFromString(
                    ArchitectureVerificationReport.serializer(),
                    raw.decodeToString(throwOnInvalidSequence = true),
                )
            } catch (_: SerializationException) {
                return Refinement.Rejected(
                    listOf(ModuleKnowledgeFailure.MalformedArchitectureVerificationReport),
                )
            } catch (_: IllegalArgumentException) {
                return Refinement.Rejected(
                    listOf(ModuleKnowledgeFailure.MalformedArchitectureVerificationReport),
                )
            }
            if (report.schemaVersion != ARCHITECTURE_REPORT_SCHEMA_VERSION) {
                return Refinement.Rejected(
                    listOf(
                        ModuleKnowledgeFailure.UnsupportedArchitectureVerificationSchema(
                            report.schemaVersion,
                        ),
                    ),
                )
            }
            if (report.status != ACCEPTED || report.findings.isNotEmpty()) {
                return Refinement.Rejected(
                    listOf(
                        ModuleKnowledgeFailure.ArchitectureNotAccepted(
                            report.status,
                            report.findings.size,
                        ),
                    ),
                )
            }
            return Refinement.Accepted(
                AcceptedArchitectureVerification(
                    ArchitectureVerificationDocument(
                        schemaVersion = report.schemaVersion,
                        taskPath = ":verifyKastArchitecture",
                        status = report.status,
                        findings = report.findings,
                        reportSha256 = sha256(raw),
                    ),
                ),
            )
        }

        private const val ARCHITECTURE_REPORT_SCHEMA_VERSION = 1
        private const val ACCEPTED = "ACCEPTED"
    }
}

@Serializable
private data class ArchitectureVerificationReport(
    val schemaVersion: Int,
    val status: String,
    val findings: List<ArchitectureVerificationFindingDocument>,
)

private val architectureVerificationJson = Json {
    ignoreUnknownKeys = false
    isLenient = false
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
