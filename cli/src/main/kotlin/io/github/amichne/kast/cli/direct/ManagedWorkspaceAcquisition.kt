package io.github.amichne.kast.cli.direct

import io.github.amichne.kast.appserver.DaemonOperationCall
import io.github.amichne.kast.appserver.DaemonOperationClient
import io.github.amichne.kast.appserver.DaemonOperationClientRejection
import io.github.amichne.kast.appserver.DaemonOperationFailure
import io.github.amichne.kast.appserver.DaemonOperationProtocolFailure
import io.github.amichne.kast.appserver.DaemonOperationResult
import io.github.amichne.kast.appserver.PreparedMcpWorkspace
import io.github.amichne.kast.appserver.WorkspaceOperationAdmission
import io.github.amichne.kast.distribution.cli.ManagedWorkspaceQuery
import io.github.amichne.kast.distribution.contract.configuration.ConfigurationDisclosure
import io.github.amichne.kast.distribution.contract.configuration.ConfigurationParameter
import io.github.amichne.kast.distribution.managed.CanonicalRoot
import io.github.amichne.kast.kernel.Refinement
import java.nio.file.Path

/**
 * Explicit caller configuration. [host] selects the retained Host and must agree with a registered workspace; [local]
 * selects output and waiting for one request and never enters a retained binding.
 */
internal class WorkspaceSettings private constructor(val host: Map<String, String>, val local: Map<String, String>) {
    val preparationWait: String?
        get() = local[ConfigurationParameter.WORKSPACE_PREPARATION_WAIT.key]

    /** Replaces the service's caller configuration with this overlay; [local] selects the request-local part. */
    fun applyTo(service: Map<String, String>, local: Map<String, String> = this.local): Map<String, String> =
        service.filterKeys { it !in CALLER_CONFIGURATION_KEYS } + host + local

    companion object {
        /** Selects the non-secret configuration overlay from one caller's environment. */
        fun explicit(environment: Map<String, String>): WorkspaceSettings {
            val explicit = environment.filterKeys { it in CALLER_CONFIGURATION_KEYS }
            return WorkspaceSettings(
                explicit.filterKeys { it !in REQUEST_LOCAL_KEYS },
                explicit.filterKeys { it in REQUEST_LOCAL_KEYS },
            )
        }
    }
}

/** The Host selection that one admitted lease resolves to. Request-local output and wait settings are excluded. */
internal data class WorkspaceHostIdentity(val home: Path, val environment: Map<String, String>)

/** One prepared Host binding. It retains Host authority and owns no workspace lease. */
internal class PreparedWorkspaceBinding(
    val identity: WorkspaceHostIdentity,
    private val client: DaemonOperationClient,
    val authority: PreparedMcpWorkspace? = null,
    private val owner: AutoCloseable,
) : AutoCloseable {
    fun read(root: CanonicalRoot, operation: DaemonOperationCall): DaemonOperationResult = client.read(root, operation)

    override fun close() = owner.close()
}

/** Installed workspace effects behind the coordinator registry. Every lease belongs to one preparation or request. */
internal interface ManagedWorkspaceAcquisition {
    /** Opens a preparation-scoped lease, prepares one Host binding, and closes that lease before returning. */
    fun prepare(
        root: CanonicalRoot,
        environment: Map<String, String>,
    ): Refinement<PreparedWorkspaceBinding, DaemonOperationFailure>

    /** Runs [action] under one request-scoped lease of an existing workspace generation. */
    fun <T> withRequestLease(
        root: CanonicalRoot,
        environment: Map<String, String>,
        action: (WorkspaceHostIdentity) -> T,
    ): Refinement<T, DaemonOperationFailure>

    /** Executes one existing-authority operation with its own lease and client. It never prepares a Host. */
    fun existing(
        root: CanonicalRoot,
        environment: Map<String, String>,
        operation: DaemonOperationCall,
    ): DaemonOperationResult
}

internal class InstalledWorkspaceAcquisition(private val context: InstalledClientContext) :
    ManagedWorkspaceAcquisition {
    override fun prepare(
        root: CanonicalRoot,
        environment: Map<String, String>,
    ): Refinement<PreparedWorkspaceBinding, DaemonOperationFailure> {
        val workspace =
            when (
                val opened =
                    ManagedWorkspaceQuery.open(context.installation, root, environment, ::inspectWorkspaceSeedHost)
            ) {
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
                is Refinement.Refined ->
                    Refinement.Refined(
                        PreparedWorkspaceBinding(
                            workspace.hostIdentity(),
                            selected.value.capabilities.read,
                            checkNotNull(selected.value.prepared),
                            selected.value,
                        )
                    )
            }
        }
    }

    override fun <T> withRequestLease(
        root: CanonicalRoot,
        environment: Map<String, String>,
        action: (WorkspaceHostIdentity) -> T,
    ): Refinement<T, DaemonOperationFailure> {
        val workspace =
            when (val opened = ManagedWorkspaceQuery.openExisting(context.installation, root, environment)) {
                is Refinement.Rejected ->
                    return Refinement.Rejected(DaemonOperationFailure.ManagedWorkspace(opened.failure))
                is Refinement.Refined -> opened.value
            }
        return workspace.use {
            admitInstallation()?.let {
                return@use Refinement.Rejected(it)
            }
            Refinement.Refined(action(workspace.hostIdentity()))
        }
    }

    override fun existing(
        root: CanonicalRoot,
        environment: Map<String, String>,
        operation: DaemonOperationCall,
    ): DaemonOperationResult {
        val workspace =
            when (val opened = ManagedWorkspaceQuery.openExisting(context.installation, root, environment)) {
                is Refinement.Rejected ->
                    return rejectedOperation(DaemonOperationFailure.ManagedWorkspace(opened.failure))
                is Refinement.Refined -> opened.value
            }
        return workspace.use {
            admitInstallation()?.let {
                return@use rejectedOperation(it)
            }
            when (
                val selected = ManagedToolClient.admit(root, workspace, WorkspaceOperationAdmission.EXISTING_AUTHORITY)
            ) {
                is Refinement.Rejected -> rejectedOperation(selected.failure.daemonFailure())
                is Refinement.Refined -> selected.value.use { it.capabilities.read.read(root, operation) }
            }
        }
    }

    /** The workspace lease excludes retirement; the Control is rechecked after acquiring it. */
    private fun admitInstallation(): DaemonOperationFailure? =
        if (context.observe() == InstalledToolAdmission.AVAILABLE) null
        else DaemonOperationFailure.Protocol(DaemonOperationProtocolFailure.LIFECYCLE_TRANSITION)
}

private fun ManagedWorkspaceQuery.hostIdentity() =
    WorkspaceHostIdentity(home, environment.filterKeys { it !in REQUEST_LOCAL_KEYS })

internal fun ManagedToolFailure.daemonFailure(): DaemonOperationFailure =
    when (this) {
        is ManagedToolFailure.Workspace -> DaemonOperationFailure.ManagedWorkspace(reason)
        is ManagedToolFailure.Preparation -> reason
        ManagedToolFailure.Stopped,
        ManagedToolFailure.Unproven ->
            DaemonOperationFailure.Protocol(DaemonOperationProtocolFailure.LIFECYCLE_TRANSITION)
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
