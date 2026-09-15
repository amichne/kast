package io.github.amichne.kast.appserver

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject

@Serializable
internal enum class AppServerEvidence {
    @SerialName("ready") READY,
    @SerialName("unobserved") UNOBSERVED,
    @SerialName("unavailable") UNAVAILABLE,
    @SerialName("unqualified") UNQUALIFIED,
}

@Serializable
internal enum class PublicEndpointKind {
    @SerialName("private") PRIVATE,
    @SerialName("codex-control") CODEX_CONTROL,
}

@Serializable
internal enum class PublicEndpointOwnership {
    @SerialName("kast") KAST,
    @SerialName("unobserved") UNOBSERVED,
}

@Serializable
internal data class PublicEndpointStatus(
    val kind: PublicEndpointKind,
    val path: String,
    val ownership: PublicEndpointOwnership,
)

@Serializable
internal data class UpstreamStatus(
    val state: AppServerEvidence,
    val kind: String = "managed-codex",
    val endpoint: String = "private",
)

/** Boundary DTOs preserve the existing status fields; they grant no admission capabilities. */
@Serializable
internal data class AppServerStatusDocument(
    val transport: AppServerEvidence,
    val publicEndpoint: PublicEndpointStatus,
    val upstream: UpstreamStatus,
    val coordinator: CoordinatorStatusPresentation,
    val service: ServiceStatusPresentation,
    val host: HostStatusPresentation,
    val paths: StatusPaths,
    val registry: RegistryStatusPresentation,
    val enrollment: String?,
    val operation: String = "app-server.status",
    val lifecycle: AppServerEvidence = AppServerEvidence.UNOBSERVED,
    val protocol: AppServerEvidence = AppServerEvidence.UNOBSERVED,
    val catalog: AppServerEvidence = AppServerEvidence.UNOBSERVED,
    val semantic: AppServerEvidence = AppServerEvidence.UNOBSERVED,
    val desktop: AppServerEvidence = AppServerEvidence.UNQUALIFIED,
    val session: String? = null,
) {
    fun document(): JsonObject = Json { encodeDefaults = true }.encodeToJsonElement(this).jsonObject
}

@Serializable
internal data class CoordinatorStatusPresentation(
    val state: String,
    val observation: CoordinatorStatusDocument? = null,
    val reason: WorkerControlFailure? = null,
)

@Serializable
internal data class ServiceStatusPresentation(val state: String, val ownership: String)

@Serializable
internal data class HostStatusPresentation(
    val attachment: String,
    val desktop: AppServerEvidence = AppServerEvidence.UNQUALIFIED,
)

@Serializable
internal data class StatusPaths(
    val serviceLog: String,
    val launchEnvironment: String,
    val savedConfiguration: String,
    val workspaceRegistry: String,
)

@Serializable
internal data class RegistryStatusPresentation(
    val state: String,
    val revision: Long? = null,
    val count: Int? = null,
    val workspaces: List<RegisteredWorkspaceStatus>? = null,
    val reason: EnrollmentFailure? = null,
)

@Serializable
internal data class RegisteredWorkspaceStatus(val workspaceId: String, val root: String)
