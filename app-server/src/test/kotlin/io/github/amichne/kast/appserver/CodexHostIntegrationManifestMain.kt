package io.github.amichne.kast.appserver

import io.github.amichne.kast.appserver.host.admission.CodexHostMode
import io.github.amichne.kast.kernel.Refinement
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.HexFormat
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*

/** Produces one deterministic receipt for the host projections and their executable proof graph. */
internal object CodexHostIntegrationManifestMain {
    @JvmStatic
    fun main(arguments: Array<String>) {
        require(arguments.size == 2) {
            "Expected output manifest and installed acceptance receipt"
        }
        val repository = Path.of("").toAbsolutePath().normalize()
        val output = Path.of(arguments[0]).toAbsolutePath().normalize()
        val installedReceipt = Path.of(arguments[1]).toAbsolutePath().normalize()
        require(Files.isRegularFile(installedReceipt)) {
            "Installed Codex host acceptance receipt is absent"
        }
        val installed = installedReceipt.readInstalledReceipt()
        val source = GitSourceSnapshot.capture(repository)
        val document =
            CodexHostIntegrationManifest(
                desktopDiscovery = installed.desktopDiscovery,
                desktopStartupArguments = installed.desktopStartupArguments,
                sourceRevision = source.revision,
                sourceTreeSha256 = source.treeDigest,
                catalogProjectionSha256 = installed.catalogProjectionSha256,
                kastContractSha256 = installed.kastContractSha256,
                catalogToolNames = installed.catalogToolNames,
                protocolAuthority =
                    ProtocolAuthorityEvidence(
                        codexVersion = installed.codexVersion,
                        schemaSha256 = installed.codexProtocolSha256,
                    ),
                privateService = installed.privateService,
                installedArtifacts =
                    InstalledArtifactEvidence(
                        kast = installed.kastExecutableSha256,
                        kastCodexFacade = installed.kastFacadeSha256,
                        codex = installed.codexExecutableSha256,
                    ),
                hostModes = CodexHostMode.entries.map(HostModeManifestEntry::from),
                installedCommands =
                    listOf(
                        "kast codex",
                        "kast codex desktop",
                        "kast-codex",
                        "kast-codex app-server",
                    ),
                dependencyProofs =
                    listOf(
                        DependencyProof("HOST-01", "CodexHostInvocationTest"),
                        DependencyProof("HOST-02", "KastCodexMainLifecycleTest"),
                        DependencyProof("HOST-03", "DesktopStdioHostTest"),
                        DependencyProof("HOST-04", "ManagedCodexUpstreamTest"),
                        DependencyProof("HOST-05", "CodexProtocolAdapterTest"),
                        DependencyProof("HOST-06", "CodexObserverReplayTest"),
                        DependencyProof("HOST-07", "InstalledCodexClientLauncherTest"),
                        DependencyProof(
                            "HOST-08",
                            "installedCodexHostTest private coordinator + stdio attachment",
                        ),
                        DependencyProof("HOST-09", "AgentSessionProjectionTest"),
                    ),
                proofCommands =
                    listOf(
                            "./gradlew :app-server:test :cli:test :cli:nativeTest",
                            "./gradlew installedProductTest",
                            "./gradlew installedCodexHostTest",
                            "./gradlew verifyKastArchitecture",
                            "./gradlew :app-server:generateCodexHostIntegrationManifest",
                        )
                        .map(ProofCommand::from),
                installedAcceptanceSha256 = sha256(Files.readAllBytes(installedReceipt)),
            )
        Files.createDirectories(output.parent)
        val temporary = output.resolveSibling("${output.fileName}.tmp")
        Files.writeString(temporary, manifestJson.encodeToString(document) + "\n")
        try {
            Files.move(
                temporary,
                output,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            Files.move(temporary, output, StandardCopyOption.REPLACE_EXISTING)
        }
    }
}

private val manifestJson = Json {
    encodeDefaults = true
    explicitNulls = false
}

private fun Path.readInstalledReceipt(): InstalledCodexHostAcceptanceReceipt =
    try {
        manifestJson.decodeFromString(Files.readString(this))
    } catch (failure: RuntimeException) {
        throw IllegalArgumentException("Installed acceptance receipt is invalid", failure)
    }

private fun sha256(bytes: ByteArray): Sha256Digest =
    Sha256Digest("sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)))

@Serializable
private data class InstalledCodexHostAcceptanceReceipt(
    val schemaVersion: Int,
    val taskId: InstalledAcceptanceTask,
    val outcome: CompletionOutcome,
    val parentClosure: ParentClosure,
    val stdoutProtocol: StdoutProtocol,
    val initialize: ValidationOutcome,
    val threadStart: ValidationOutcome,
    val facadeRole: FacadeRole,
    val codexVersion: String,
    val catalogProjectionSha256: Sha256Digest,
    val codexProtocolSha256: Sha256Digest,
    val kastContractSha256: Sha256Digest,
    val catalogToolNames: List<String>,
    val kastExecutableSha256: Sha256Digest,
    val kastFacadeSha256: Sha256Digest,
    val codexExecutableSha256: Sha256Digest,
    val privateService: InstalledPrivateServiceReceipt,
    val persistentServiceAfterDetach: ValidationOutcome,
    val desktopCompatibility: DesktopQualification,
    val desktopDiscovery: DesktopDiscovery,
    val desktopStartupArguments: ValidationOutcome,
) {
    init {
        require(schemaVersion == 1) { "Installed acceptance schema version is unsupported" }
        require(codexVersion.isNotBlank()) { "Installed Codex version is absent" }
        require(catalogToolNames.isNotEmpty()) { "Installed catalog is empty" }
        require(catalogToolNames.all(String::isNotBlank)) { "Installed catalog contains an empty tool name" }
        require(catalogToolNames.distinct().size == catalogToolNames.size) {
            "Installed catalog contains duplicate tool names"
        }
    }
}

/** A captured private coordinator observation; it does not claim ownership of Codex's ordinary daemon. */
@Serializable
private data class InstalledPrivateServiceReceipt(
    val socketPath: EvidencePath,
    val ordinaryDaemonSocket: OrdinaryDaemonObservation,
    val phase: PrivateServicePhase,
    val statusEvidence: EvidencePath,
    val qualification: PrivateServiceStatus,
) {
    init {
        require(socketPath.value.endsWith("/state/run/c.sock")) { "Private coordinator socket is invalid" }
        require(statusEvidence.value.endsWith("/status-frontend_prepared.json")) {
            "Prepared status evidence is absent"
        }
    }
}

@Serializable
private enum class OrdinaryDaemonObservation {
    ABSENT
}

@Serializable
private enum class PrivateServicePhase {
    FRONTEND_PREPARED
}

@Serializable
private enum class ReadyEvidence {
    @SerialName("ready") READY
}

@Serializable
private enum class UnobservedEvidence {
    @SerialName("unobserved") UNOBSERVED
}

@Serializable
private enum class UnqualifiedEvidence {
    @SerialName("unqualified") UNQUALIFIED
}

@Serializable
private enum class PreparedEvidence {
    @SerialName("prepared") PREPARED
}

@Serializable
private enum class MatchedEvidence {
    @SerialName("matched") MATCHED
}

@Serializable
private enum class RegisteredEvidence {
    @SerialName("registered") REGISTERED
}

@Serializable
private enum class StatusOperation {
    @SerialName("app-server.status") STATUS
}

@Serializable
private data class PrivateServiceStatus(
    val operation: StatusOperation,
    val transport: ReadyEvidence,
    val protocol: UnobservedEvidence,
    val catalog: UnobservedEvidence,
    val semantic: UnobservedEvidence,
    val desktop: UnqualifiedEvidence,
    val coordinator: PrivateCoordinatorStatus,
    val service: PrivateServiceOwnership,
    val host: PrivateHostStatus,
    val registry: PrivateRegistryStatus,
    val enrollment: EvidencePath,
    val session: JsonElement,
) {
    init {
        require(session == JsonNull) { "Passive status must not manufacture frontend session evidence" }
        require(registry.workspaces.any { it.root == enrollment }) { "Enrolled root is not registered" }
    }
}

@Serializable
private data class PrivateCoordinatorStatus(val state: ReadyEvidence, val observation: JsonObject) {
    @Transient
    private val admitted: CoordinatorStatusSnapshot =
        when (val result = CoordinatorStatusSnapshot.admit(observation)) {
            is Refinement.Refined -> result.value
            is Refinement.Rejected -> throw IllegalArgumentException("Coordinator status is invalid")
        }

    init {
        require(admitted.hostAttachment == CoordinatorHostAttachment.PREPARED) {
            "Coordinator did not retain prepared frontend"
        }
    }
}

@Serializable private data class PrivateServiceOwnership(val state: ReadyEvidence, val ownership: MatchedEvidence)

@Serializable private data class PrivateHostStatus(val attachment: PreparedEvidence, val desktop: UnqualifiedEvidence)

@Serializable
private data class PrivateRegisteredWorkspace(val root: EvidencePath, val workspaceId: String) {
    init {
        val expected =
            HexFormat.of()
                .formatHex(MessageDigest.getInstance("SHA-256").digest(root.value.toByteArray(StandardCharsets.UTF_8)))
        require(workspaceId == expected) { "Workspace identity does not match canonical root" }
    }
}

@Serializable
private data class PrivateRegistryStatus(
    val state: RegisteredEvidence,
    val revision: Long,
    val count: Int,
    val workspaces: List<PrivateRegisteredWorkspace>,
) {
    init {
        require(revision > 0 && count in 1..10_000 && count == workspaces.size) {
            "Registry observation is inconsistent"
        }
        require(workspaces.map { it.workspaceId }.distinct().size == count) { "Registry contains duplicate identities" }
    }
}

@JvmInline
@Serializable
private value class EvidencePath(val value: String) {
    init {
        val path = Path.of(value)
        require(path.isAbsolute && path.normalize().toString() == value) {
            "Evidence path is not canonical absolute syntax"
        }
    }
}

@Serializable
private enum class InstalledAcceptanceTask {
    @SerialName("HOST-08") HOST_08
}

@Serializable
private enum class CompletionOutcome {
    COMPLETE
}

@Serializable
private enum class ParentClosure {
    CLEAN
}

@Serializable
private enum class StdoutProtocol {
    JSONL_ONLY
}

@Serializable
private enum class ValidationOutcome {
    VALIDATED
}

@Serializable
private enum class FacadeRole {
    @SerialName("app-server-stdio") APP_SERVER_STDIO
}

@Serializable
private enum class DesktopQualification {
    UNQUALIFIED
}

@Serializable
private enum class DesktopDiscovery {
    NOT_REQUIRED
}

@Serializable
private data class CodexHostIntegrationManifest(
    val desktopDiscovery: DesktopDiscovery,
    val desktopStartupArguments: ValidationOutcome,
    val schemaVersion: Int = 4,
    val desktopCompatibility: DesktopQualification = DesktopQualification.UNQUALIFIED,
    val taskId: IntegrationTask = IntegrationTask.HOST_10,
    val outcome: CompletionOutcome = CompletionOutcome.COMPLETE,
    val invariant: String =
        "One qualified Kast tool catalog and execution path; host projections only determine how Codex reaches it.",
    val sourceRevision: GitRevision,
    val sourceTreeSha256: Sha256Digest,
    val catalogAuthority: CatalogAuthority = CatalogAuthority.AGENT_SESSION_BOOTSTRAP,
    val catalogProjectionSha256: Sha256Digest,
    val kastContractSha256: Sha256Digest,
    val catalogToolNames: List<String>,
    val protocolAuthority: ProtocolAuthorityEvidence,
    val privateService: InstalledPrivateServiceReceipt,
    val installedArtifacts: InstalledArtifactEvidence,
    val hostModes: List<HostModeManifestEntry>,
    val installedCommands: List<String>,
    val dependencyProofs: List<DependencyProof>,
    val proofCommands: List<ProofCommand>,
    val installedAcceptanceSha256: Sha256Digest,
)

@Serializable
private enum class IntegrationTask {
    @SerialName("HOST-10") HOST_10
}

@Serializable
private enum class CatalogAuthority {
    @SerialName("AgentSessionBootstrap") AGENT_SESSION_BOOTSTRAP
}

@Serializable
private data class ProtocolAuthorityEvidence(
    val kind: ProtocolAuthorityKind = ProtocolAuthorityKind.INSTALLED_CODEX_GENERATED_JSON_SCHEMA,
    val codexVersion: String,
    val schemaSha256: Sha256Digest,
)

@Serializable
private enum class ProtocolAuthorityKind {
    @SerialName("installed-codex-generated-json-schema") INSTALLED_CODEX_GENERATED_JSON_SCHEMA
}

@Serializable
private data class InstalledArtifactEvidence(
    val kast: Sha256Digest,
    val kastCodexFacade: Sha256Digest,
    val codex: Sha256Digest,
)

@Serializable
private enum class HostModeEvidence {
    @SerialName("CLI_REMOTE_CLIENT") CLI_REMOTE_CLIENT,
    @SerialName("APP_SERVER_STDIO") APP_SERVER_STDIO,
}

@Serializable
private data class HostModeManifestEntry(
    val mode: HostModeEvidence,
    val transport: HostTransportEvidence,
) {
    companion object {
        fun from(mode: CodexHostMode): HostModeManifestEntry =
            when (mode) {
                CodexHostMode.CLI_REMOTE_CLIENT ->
                    HostModeManifestEntry(
                        HostModeEvidence.CLI_REMOTE_CLIENT,
                        HostTransportEvidence.BROKER_UDS_CODEX_REMOTE,
                    )
                CodexHostMode.APP_SERVER_STDIO ->
                    HostModeManifestEntry(
                        HostModeEvidence.APP_SERVER_STDIO,
                        HostTransportEvidence.JSONL_STDIO_BROKER_UDS,
                    )
            }
    }
}

@Serializable
private enum class HostTransportEvidence {
    @SerialName("broker-uds-codex-remote") BROKER_UDS_CODEX_REMOTE,
    @SerialName("jsonl-stdio-broker-uds") JSONL_STDIO_BROKER_UDS,
}

@Serializable
private data class DependencyProof(
    val task: String,
    val proof: String,
)

@Serializable
private data class ProofCommand(
    val command: String,
    val sha256: Sha256Digest,
) {
    companion object {
        fun from(command: String): ProofCommand =
            ProofCommand(
                command = command,
                sha256 = sha256(command.toByteArray(StandardCharsets.UTF_8)),
            )
    }
}

@JvmInline
@Serializable
private value class Sha256Digest(val value: String) {
    init {
        require(Regex("sha256:[0-9a-f]{64}").matches(value)) { "SHA-256 digest is invalid" }
    }
}

@JvmInline
@Serializable
private value class GitRevision(val value: String) {
    init {
        require(Regex("[0-9a-f]{40}").matches(value)) { "Git revision is invalid" }
    }
}

private data class GitSourceSnapshot(
    val revision: GitRevision,
    val treeDigest: Sha256Digest,
) {
    companion object {
        fun capture(repository: Path): GitSourceSnapshot {
            val revision = executeGit(repository, "rev-parse", "HEAD").toString(StandardCharsets.UTF_8).trim()
            val admittedRevision = GitRevision(revision)
            val paths =
                executeGit(
                        repository,
                        "ls-files",
                        "-co",
                        "--exclude-standard",
                        "-z",
                    )
                    .toString(StandardCharsets.UTF_8)
                    .split('\u0000')
                    .filter(String::isNotEmpty)
                    .sorted()
            val digest = MessageDigest.getInstance("SHA-256")
            paths.forEach { relative ->
                val path = repository.resolve(relative).normalize()
                require(path.startsWith(repository)) { "Git path escaped the repository" }
                digest.update(relative.toByteArray(StandardCharsets.UTF_8))
                digest.update(0.toByte())
                if (Files.isRegularFile(path)) {
                    Files.newInputStream(path).use { input ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            digest.update(buffer, 0, count)
                        }
                    }
                } else {
                    digest.update("MISSING".toByteArray(StandardCharsets.UTF_8))
                }
                digest.update(0.toByte())
            }
            return GitSourceSnapshot(
                admittedRevision,
                Sha256Digest("sha256:${HexFormat.of().formatHex(digest.digest())}"),
            )
        }

        private fun executeGit(repository: Path, vararg arguments: String): ByteArray {
            val process =
                ProcessBuilder(listOf("git") + arguments)
                    .directory(repository.toFile())
                    .redirectError(ProcessBuilder.Redirect.INHERIT)
                    .start()
            val output = process.inputStream.readAllBytes()
            require(process.waitFor() == 0) { "Git source evidence was unavailable" }
            return output
        }
    }
}
