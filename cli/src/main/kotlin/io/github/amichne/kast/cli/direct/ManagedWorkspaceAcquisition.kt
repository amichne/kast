package io.github.amichne.kast.cli.direct

import io.github.amichne.kast.appserver.DaemonOperationCall
import io.github.amichne.kast.appserver.DaemonOperationClient
import io.github.amichne.kast.appserver.DaemonOperationClientRejection
import io.github.amichne.kast.appserver.DaemonOperationFailure
import io.github.amichne.kast.appserver.DaemonOperationProtocolFailure
import io.github.amichne.kast.appserver.DaemonOperationResult
import io.github.amichne.kast.appserver.PreparedMcpWorkspace
import io.github.amichne.kast.appserver.WorkspaceOperationAdmission
import io.github.amichne.kast.distribution.cli.ContainerFailure
import io.github.amichne.kast.distribution.cli.InstalledProductRuntime
import io.github.amichne.kast.distribution.cli.ManagedWorkspaceQuery
import io.github.amichne.kast.distribution.cli.WorkspaceQueryEnvironment
import io.github.amichne.kast.distribution.contract.configuration.ConfigurationDisclosure
import io.github.amichne.kast.distribution.contract.configuration.ConfigurationParameter
import io.github.amichne.kast.distribution.contract.gradle.GradleImportEnvironment
import io.github.amichne.kast.distribution.contract.gradle.GradleImportEnvironmentIdentity
import io.github.amichne.kast.distribution.managed.CanonicalRoot
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.wire.presentation.CanonicalJsonDocument
import java.nio.file.Path
import kotlinx.serialization.Required
import kotlinx.serialization.Serializable

/**
 * Explicit caller configuration. [host] selects the retained Host and must agree with a registered workspace; [local]
 * selects output and waiting for one request and never enters a retained binding.
 */
internal class WorkspaceSettings
private constructor(
    host: Map<String, String>,
    val local: Map<String, String>,
    private val delegated: GradleImportEnvironment,
) {
    val host = WorkspaceSettingsIdentity(host, delegated.identity)
    val preparationWait: String?
        get() = local[ConfigurationParameter.WORKSPACE_PREPARATION_WAIT.key]

    /** Replaces the service's caller configuration with this overlay; [local] selects the request-local part. */
    fun applyTo(service: Map<String, String>, local: Map<String, String> = this.local): WorkspaceQueryEnvironment =
        WorkspaceQueryEnvironment(
            service.filterKeys { it !in CALLER_CONFIGURATION_KEYS } + host.values + local,
            delegated,
        )

    companion object {
        /** Selects the non-secret configuration overlay from one caller's environment. */
        fun explicit(
            environment: Map<String, String>,
            delegated: GradleImportEnvironment? = null,
        ): WorkspaceSettings {
            val explicit = environment.filterKeys { it in CALLER_CONFIGURATION_KEYS }
            return WorkspaceSettings(
                explicit.filterKeys { it !in REQUEST_LOCAL_KEYS },
                explicit.filterKeys { it in REQUEST_LOCAL_KEYS },
                delegated
                    ?: when (
                        val admitted =
                            GradleImportEnvironment.admit(
                                environment[GradleImportEnvironment.VARIABLES_SETTING].orEmpty(),
                                "",
                                environment,
                            )
                    ) {
                        is Refinement.Rejected -> GradleImportEnvironment.Empty
                        is Refinement.Refined -> admitted.value
                    },
            )
        }
    }
}

internal data class WorkspaceSettingsIdentity(
    val values: Map<String, String>,
    val delegated: GradleImportEnvironmentIdentity,
)

/** The Host selection that one admitted lease resolves to. Request-local output and wait settings are excluded. */
internal data class WorkspaceHostIdentity(
    val home: Path,
    val environment: Map<String, String>,
    val delegated: GradleImportEnvironmentIdentity = GradleImportEnvironment.Empty.identity,
)

/**
 * One prepared Host binding. It retains Host authority and owns no workspace lease. [readiness] inspects the exact
 * retained project target without a semantic call.
 */
internal class PreparedWorkspaceBinding(
    val identity: WorkspaceHostIdentity,
    private val client: DaemonOperationClient,
    private val readiness: () -> Refinement<Unit, DaemonOperationFailure>,
    val authority: PreparedMcpWorkspace? = null,
    private val owner: AutoCloseable,
) : AutoCloseable {
    /** A workspace wait reports success only after the current Host confirms this exact retained project target. */
    fun execute(root: CanonicalRoot, operation: DaemonOperationCall): DaemonOperationResult =
        if (operation != DaemonOperationCall.PrepareWorkspace) client.read(root, operation)
        else
            when (val current = readiness()) {
                is Refinement.Rejected -> rejectedOperation(current.failure)
                is Refinement.Refined -> preparedWorkspace
            }

    override fun close() = owner.close()
}

@Serializable internal data class PreparedWorkspaceDocument(@Required val ready: Boolean = true)

private val preparedWorkspace =
    DaemonOperationResult.Complete(
        CanonicalJsonDocument.generated(PreparedWorkspaceDocument.serializer()).create(PreparedWorkspaceDocument())
    )

/** Installed workspace effects behind the coordinator registry. Every lease belongs to one preparation or request. */
internal interface ManagedWorkspaceAcquisition {
    /** Opens a preparation-scoped lease, prepares one Host binding, and closes that lease before returning. */
    fun prepare(
        root: CanonicalRoot,
        environment: WorkspaceQueryEnvironment,
    ): Refinement<PreparedWorkspaceBinding, DaemonOperationFailure>

    /** Runs [action] under one request-scoped lease of an existing workspace generation. */
    fun <T> withRequestLease(
        root: CanonicalRoot,
        environment: WorkspaceQueryEnvironment,
        expected: WorkspaceHostIdentity,
        action: () -> T,
    ): Refinement<T, DaemonOperationFailure>

    /** Executes one existing-authority operation with its own lease and client. It never prepares a Host. */
    fun existing(
        root: CanonicalRoot,
        environment: WorkspaceQueryEnvironment,
        operation: DaemonOperationCall,
        expected: WorkspaceHostIdentity?,
    ): DaemonOperationResult
}

internal class InstalledWorkspaceAcquisition(private val context: InstalledClientContext) :
    ManagedWorkspaceAcquisition {
    private val product by lazy { InstalledProductRuntime.admit(context.installation) }

    override fun prepare(
        root: CanonicalRoot,
        environment: WorkspaceQueryEnvironment,
    ): Refinement<PreparedWorkspaceBinding, DaemonOperationFailure> {
        val workspace =
            when (val opened = open(root, environment, WorkspaceOperationAdmission.PREPARE)) {
                is Refinement.Rejected ->
                    return Refinement.Rejected(DaemonOperationFailure.ManagedWorkspace(opened.failure))
                is Refinement.Refined -> opened.value
            }
        return workspace.use {
            admitInstallation()?.let {
                return@use Refinement.Rejected(it)
            }
            when (val selected = ManagedToolClient.admit(root, workspace, WorkspaceOperationAdmission.PREPARE)) {
                is Refinement.Rejected -> Refinement.Rejected(selected.failure.daemonFailure())
                is Refinement.Refined -> {
                    val authority = checkNotNull(selected.value.prepared)
                    Refinement.Refined(
                        PreparedWorkspaceBinding(
                            workspace.hostIdentity(),
                            selected.value.capabilities.read,
                            authority::confirmReady,
                            authority,
                            selected.value,
                        )
                    )
                }
            }
        }
    }

    override fun <T> withRequestLease(
        root: CanonicalRoot,
        environment: WorkspaceQueryEnvironment,
        expected: WorkspaceHostIdentity,
        action: () -> T,
    ): Refinement<T, DaemonOperationFailure> {
        val workspace =
            when (val opened = open(root, environment, WorkspaceOperationAdmission.EXISTING_AUTHORITY)) {
                is Refinement.Rejected ->
                    return Refinement.Rejected(DaemonOperationFailure.ManagedWorkspace(opened.failure))
                is Refinement.Refined -> opened.value
            }
        return workspace.use {
            admitInstallation()?.let {
                return@use Refinement.Rejected(it)
            }
            if (workspace.hostIdentity() != expected) return@use Refinement.Rejected(configurationConflict)
            Refinement.Refined(action())
        }
    }

    override fun existing(
        root: CanonicalRoot,
        environment: WorkspaceQueryEnvironment,
        operation: DaemonOperationCall,
        expected: WorkspaceHostIdentity?,
    ): DaemonOperationResult {
        val workspace =
            when (val opened = open(root, environment, WorkspaceOperationAdmission.EXISTING_AUTHORITY)) {
                is Refinement.Rejected ->
                    return rejectedOperation(DaemonOperationFailure.ManagedWorkspace(opened.failure))
                is Refinement.Refined -> opened.value
            }
        return workspace.use {
            admitInstallation()?.let {
                return@use rejectedOperation(it)
            }
            if (expected != null && workspace.hostIdentity() != expected)
                return@use rejectedOperation(configurationConflict)
            when (
                val selected = ManagedToolClient.admit(root, workspace, WorkspaceOperationAdmission.EXISTING_AUTHORITY)
            ) {
                is Refinement.Rejected -> rejectedOperation(selected.failure.daemonFailure())
                is Refinement.Refined -> selected.value.use { it.capabilities.read.read(root, operation) }
            }
        }
    }

    private fun open(
        root: CanonicalRoot,
        environment: WorkspaceQueryEnvironment,
        admission: WorkspaceOperationAdmission,
    ): Refinement<ManagedWorkspaceQuery, ContainerFailure> =
        when (val admitted = product) {
            is Refinement.Rejected -> admitted
            is Refinement.Refined ->
                when (admission) {
                    WorkspaceOperationAdmission.PREPARE ->
                        ManagedWorkspaceQuery.open(admitted.value, root, environment, ::inspectWorkspaceSeedHost)
                    WorkspaceOperationAdmission.EXISTING_AUTHORITY ->
                        ManagedWorkspaceQuery.openExisting(admitted.value, root, environment)
                }
        }

    /** The workspace lease excludes retirement; the Control is rechecked after acquiring it. */
    private fun admitInstallation(): DaemonOperationFailure? =
        if (context.observe() == InstalledToolAdmission.AVAILABLE) null
        else DaemonOperationFailure.Protocol(DaemonOperationProtocolFailure.LIFECYCLE_TRANSITION)
}

private fun ManagedWorkspaceQuery.hostIdentity(): WorkspaceHostIdentity {
    val names = gradleImportEnvironment.evidence.map { it.name.value }.toSet()
    return WorkspaceHostIdentity(
        home,
        environment.filterKeys { it !in REQUEST_LOCAL_KEYS && it !in names },
        gradleImportEnvironment.identity,
    )
}

internal fun ManagedToolFailure.daemonFailure(): DaemonOperationFailure =
    when (this) {
        is ManagedToolFailure.Workspace -> DaemonOperationFailure.ManagedWorkspace(reason)
        is ManagedToolFailure.Preparation -> reason
    }

internal fun rejectedOperation(failure: DaemonOperationFailure): DaemonOperationResult =
    DaemonOperationResult.Rejected(DaemonOperationClientRejection.Server(failure))

private val CALLER_CONFIGURATION_KEYS =
    ConfigurationParameter.entries
        .filter { it.declaration().disclosure != ConfigurationDisclosure.SECRET_PRESENCE }
        .map { it.key }
        .toSet()

private val REQUEST_LOCAL_KEYS =
    setOf(
        ConfigurationParameter.OUTPUT_TOON.key,
        ConfigurationParameter.WORKSPACE_PREPARATION_WAIT.key,
        ConfigurationParameter.DEBUG.key,
    )
