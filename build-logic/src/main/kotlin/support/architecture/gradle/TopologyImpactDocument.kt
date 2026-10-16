package support.architecture.gradle

import java.security.MessageDigest
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import support.architecture.ModuleId
import support.architecture.ModuleImpact
import support.architecture.ModuleImpactFailure

internal val topologyImpactJson = Json {
    classDiscriminator = "status"
    encodeDefaults = true
}

@Serializable
internal sealed interface TopologyImpactDocument {
    @Serializable
    @SerialName("COMPUTED")
    data class Computed(
        val graphIdentity: String,
        val graph: DeclaredProjectGraphDocument,
        val selectedModules: List<String>,
        val affectedModules: List<ModuleImpactPathDocument>,
        val schemaVersion: Int = 1,
        val evidence: TopologyImpactEvidence = TopologyImpactEvidence.DECLARED_PRODUCTION_PROJECT_DEPENDENCIES,
    ) : TopologyImpactDocument

    @Serializable
    @SerialName("REJECTED")
    data class Rejected(
        val code: TopologyImpactRejection,
        val details: List<String>,
        val schemaVersion: Int = 1,
    ) : TopologyImpactDocument

    @Serializable
    @SerialName("DISABLED")
    data class Disabled(val schemaVersion: Int = 1) : TopologyImpactDocument
}

@Serializable
internal enum class TopologyImpactEvidence { DECLARED_PRODUCTION_PROJECT_DEPENDENCIES }

@Serializable
internal enum class TopologyImpactRejection {
    INVALID_SWITCH,
    INVALID_POLICY,
    INVALID_OBSERVATION,
    EMPTY_GRAPH,
    MISSING_ENDPOINT,
    EMPTY_SELECTION,
    UNKNOWN_MODULE,
    DUPLICATE_SELECTION,
}

@Serializable
internal data class DeclaredProjectGraphDocument(
    val modules: List<String>,
    val dependencies: List<DeclaredProjectDependencyDocument>,
)

@Serializable
internal data class DeclaredProjectDependencyDocument(val consumer: String, val dependency: String)

@Serializable
internal data class ModuleImpactPathDocument(val module: String, val dependencyPath: List<String>)

internal fun impactDocument(impact: ModuleImpact): TopologyImpactDocument.Computed {
    val graph = DeclaredProjectGraphDocument(
        impact.modules.map(ModuleId::projectPath),
        impact.dependencies.map {
            DeclaredProjectDependencyDocument(it.consumer.projectPath, it.dependency.projectPath)
        },
    )
    val encodedGraph = topologyImpactJson.encodeToString(DeclaredProjectGraphDocument.serializer(), graph)
    val digest = MessageDigest.getInstance("SHA-256").digest(encodedGraph.encodeToByteArray())
        .joinToString("") { "%02x".format(it) }
    return TopologyImpactDocument.Computed(
        graphIdentity = "sha256:$digest",
        graph = graph,
        selectedModules = impact.selected.map(ModuleId::projectPath),
        affectedModules = impact.dependencyPaths().map { path ->
            ModuleImpactPathDocument(path.first().projectPath, path.map(ModuleId::projectPath))
        },
    )
}

internal fun impactRejection(failure: ModuleImpactFailure): TopologyImpactDocument.Rejected = when (failure) {
    ModuleImpactFailure.EmptyGraph -> TopologyImpactDocument.Rejected(TopologyImpactRejection.EMPTY_GRAPH, emptyList())
    is ModuleImpactFailure.MissingEndpoint -> TopologyImpactDocument.Rejected(
        TopologyImpactRejection.MISSING_ENDPOINT,
        listOf(failure.edge.consumer.projectPath, failure.edge.dependency.projectPath),
    )
    ModuleImpactFailure.EmptySelection -> TopologyImpactDocument.Rejected(
        TopologyImpactRejection.EMPTY_SELECTION, emptyList(),
    )
    is ModuleImpactFailure.UnknownModule -> TopologyImpactDocument.Rejected(
        TopologyImpactRejection.UNKNOWN_MODULE, listOf(failure.path),
    )
    is ModuleImpactFailure.DuplicateSelection -> TopologyImpactDocument.Rejected(
        TopologyImpactRejection.DUPLICATE_SELECTION,
        listOf(failure.module.projectPath),
    )
}
