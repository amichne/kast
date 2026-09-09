package io.github.amichne.kast.appserver

import io.github.amichne.kast.distribution.contract.configuration.ConfigurationOperationalLimit
import io.github.amichne.kast.distribution.contract.configuration.ConfigurationScope
import io.github.amichne.kast.distribution.contract.configuration.ConfigurationUnit
import io.github.amichne.kast.kernel.ElapsedTimeLimitMillis
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.registry.OperationExecutionBudget

/** Fixed production limits and policy defaults; both runtime admission and inspection use these owners.
 * Defaults remain injectable at their existing typed policy boundaries. Per-phase deadlines are not aggregate bounds.
 */
object BrokerOperationalLimits {
    const val maximumMessageBytes: Int = 64 * 1_024 * 1_024
    const val maximumConnections: Int = 8
    const val inFlightCallsPerConnection: Int = 8
    const val inFlightCallsPerProvider: Int = 4
    const val maximumDescriptorCount: Int = 64
    const val maximumCatalogBytes: Int = 1_024 * 1_024
    const val maximumToolArgumentBytes: Int = 64 * 1_024
    const val maximumToolResultBytes: Int = 1_024 * 1_024
    const val defaultWorkspaceQueued: Int = 32
    const val maximumWorkspaceQueued: Int = 4_096
    const val maximumWorkspaceEvents: Int = 128
    const val maximumSessionEvents: Int = 128
    const val maximumThreadStoreBytes: Int = 4 * 1_024 * 1_024
    const val maximumTaskSessions: Int = 4_096
    const val maximumRuntimeConnections: Int = 32
    const val defaultCodexSchemaBytes: Int = 16 * 1_024 * 1_024
    const val installedCodexSchemaBytes: Int = 32 * 1_024 * 1_024
    const val maximumCodexSchemaFiles: Int = 2_048
    const val maximumCodexCommandOutputBytes: Int = 1_024 * 1_024
    const val maximumProcessInputBytes: Int = 4 * 1_024 * 1_024
    const val maximumProcessOutputBytes: Int = 64 * 1_024 * 1_024
    const val maximumGradleOutputBytes: Int = 512 * 1_024
    const val maximumKastVersionBytes: Int = 4 * 1_024
    const val maximumKastSchemaBytes: Int = 512 * 1_024
    const val maximumKastOutputBytes: Int = 512 * 1_024
    const val maximumWorkspaces: Int = 256
    const val maximumRegistryBytes: Int = 1_048_576
    const val maximumInvocationJournalBytes: Int = 2_097_152
    const val maximumInvocations: Int = 4_096
    const val maximumServerRequests: Int = 4_096
    const val sessionChannelCapacity: Int = 256
    const val maximumSeedConsentBytes: Int = 4_096
    const val maximumControlStateBytes: Int = 16_384
    const val maximumDesktopInspectionBytes: Int = 1_024
    const val maximumClientMessageBytes: Int = 4 * 1_024 * 1_024
    const val maximumInventoryEntries: Int = 4_096
    const val maximumInventoryBytes: Int = 1_024 * 1_024 * 1_024
    const val maximumEpochBytes: Int = 1_024
    const val maximumObserverDiffBytes: Int = 512 * 1_024
    const val maximumObserverChangeFiles: Int = 64
    const val maximumHostArgumentCount: Int = 256
    const val maximumHostArgumentBytes: Int = 65_536
    val upstreamStartup: ElapsedTimeLimitMillis get() = OperationExecutionBudget.LOCAL_QUALIFICATION
    val providerStartup: ElapsedTimeLimitMillis get() = OperationExecutionBudget.PROVIDER_QUALIFICATION
    val workspaceQueueWait: ElapsedTimeLimitMillis get() = OperationExecutionBudget.LOCAL_QUALIFICATION
    val workspaceInteraction: ElapsedTimeLimitMillis get() = OperationExecutionBudget.GRAPH_BUILD.invocation
    val maximumProcessTimeout: ElapsedTimeLimitMillis get() = OperationExecutionBudget.GRAPH_BUILD.invocation
    val gradleInvocation: ElapsedTimeLimitMillis get() = limit(30_000)
    val codexQualification: ElapsedTimeLimitMillis get() = limit(30_000)
    val maximumKastQualification: ElapsedTimeLimitMillis get() = limit(300_000)
    val readinessExchange: ElapsedTimeLimitMillis get() = limit(3_000)
    val serviceChildPhases: ElapsedTimeLimitMillis get() = limit(90_000)
    val serviceStartup: ElapsedTimeLimitMillis get() = limit(120_000)
    val serviceRetirement: ElapsedTimeLimitMillis get() = limit(10_000)
    val serviceStartLock: ElapsedTimeLimitMillis get() = limit(180_000)
    val serviceLockPoll: ElapsedTimeLimitMillis get() = limit(25)
    val servicePoll: ElapsedTimeLimitMillis get() = limit(50)
    val launchctlInvocation: ElapsedTimeLimitMillis get() = limit(5_000)
    val clientConnect: ElapsedTimeLimitMillis get() = limit(10_000)
    val clientProcessRetirementWait: ElapsedTimeLimitMillis get() = limit(2_000)
    val upstreamProcessRetirementWait: ElapsedTimeLimitMillis get() = limit(2_000)
    val clientShutdown: ElapsedTimeLimitMillis get() = limit(10_000)
    val desktopShutdown: ElapsedTimeLimitMillis get() = limit(10_000)
    val desktopInspection: ElapsedTimeLimitMillis get() = limit(5_000)
    val managementExchange: ElapsedTimeLimitMillis get() = limit(5_000)
    val managementConnect: ElapsedTimeLimitMillis get() = limit(2_000)
    val processRetirementWait: ElapsedTimeLimitMillis get() = limit(250)
    val seedConsent: ElapsedTimeLimitMillis get() = limit(60_000)
    val workerStartupJoin: ElapsedTimeLimitMillis get() = limit(10_000)
    val workerControlHandshake: ElapsedTimeLimitMillis get() = limit(10_000)
    val workerShutdownJoin: ElapsedTimeLimitMillis get() = limit(10_000)
    val connectionInitialization: ElapsedTimeLimitMillis get() = limit(10_000)
    val serverShutdownGrace: ElapsedTimeLimitMillis get() = limit(500)
    val serverShutdown: ElapsedTimeLimitMillis get() = limit(2_000)
    val serverFailedStartupShutdown: ElapsedTimeLimitMillis get() = limit(1_000)
    val upstreamHealthPoll: ElapsedTimeLimitMillis get() = limit(25)
    val sessionSend: ElapsedTimeLimitMillis get() = limit(5_000)
    val sessionClose: ElapsedTimeLimitMillis get() = limit(2_000)

    val declarations: List<ConfigurationOperationalLimit> get() = listOf(
        declaration("broker.host.argument.maximum_bytes", maximumHostArgumentBytes.toLong(), ConfigurationUnit.BYTES, ConfigurationScope.HOST_PROFILE, "maximumHostArgumentBytes"),
        declaration("broker.host.argument.maximum_count", maximumHostArgumentCount.toLong(), ConfigurationUnit.COUNT, ConfigurationScope.HOST_PROFILE, "maximumHostArgumentCount"),
        declaration("broker.observer.change.maximum_files", maximumObserverChangeFiles.toLong(), ConfigurationUnit.COUNT, ConfigurationScope.REQUEST, "maximumObserverChangeFiles"),
        declaration("broker.observer.diff.maximum_bytes", maximumObserverDiffBytes.toLong(), ConfigurationUnit.BYTES, ConfigurationScope.REQUEST, "maximumObserverDiffBytes"),
        declaration("broker.message.maximum_bytes", maximumMessageBytes.toLong(), ConfigurationUnit.BYTES, ConfigurationScope.REQUEST, "maximumMessageBytes"),
        declaration("broker.connections.maximum", maximumConnections.toLong(), ConfigurationUnit.COUNT, ConfigurationScope.INSTALLATION, "maximumConnections"),
        declaration("broker.calls.per_connection", inFlightCallsPerConnection.toLong(), ConfigurationUnit.COUNT, ConfigurationScope.HOST_PROFILE, "inFlightCallsPerConnection"),
        declaration("broker.calls.per_provider", inFlightCallsPerProvider.toLong(), ConfigurationUnit.COUNT, ConfigurationScope.HOST_PROFILE, "inFlightCallsPerProvider"),
        declaration("broker.catalog.maximum_descriptors", maximumDescriptorCount.toLong(), ConfigurationUnit.COUNT, ConfigurationScope.HOST_PROFILE, "maximumDescriptorCount"),
        declaration("broker.catalog.maximum_bytes", maximumCatalogBytes.toLong(), ConfigurationUnit.BYTES, ConfigurationScope.HOST_PROFILE, "maximumCatalogBytes"),
        declaration("broker.tool.argument.maximum_bytes", maximumToolArgumentBytes.toLong(), ConfigurationUnit.BYTES, ConfigurationScope.REQUEST, "maximumToolArgumentBytes"),
        declaration("broker.tool.result.maximum_bytes", maximumToolResultBytes.toLong(), ConfigurationUnit.BYTES, ConfigurationScope.REQUEST, "maximumToolResultBytes"),
        declaration("broker.workspace.queue.default", defaultWorkspaceQueued.toLong(), ConfigurationUnit.COUNT, ConfigurationScope.WORKSPACE, "defaultWorkspaceQueued"),
        declaration("broker.workspace.queue.maximum", maximumWorkspaceQueued.toLong(), ConfigurationUnit.COUNT, ConfigurationScope.WORKSPACE, "maximumWorkspaceQueued"),
        declaration("broker.workspace.events.maximum", maximumWorkspaceEvents.toLong(), ConfigurationUnit.COUNT, ConfigurationScope.INSTALLATION, "maximumWorkspaceEvents"),
        declaration("broker.session.events.maximum", maximumSessionEvents.toLong(), ConfigurationUnit.COUNT, ConfigurationScope.INSTALLATION, "maximumSessionEvents"),
        declaration("broker.thread_catalog.maximum_bytes", maximumThreadStoreBytes.toLong(), ConfigurationUnit.BYTES, ConfigurationScope.INSTALLATION, "maximumThreadStoreBytes"),
        declaration("broker.tasks.maximum", maximumTaskSessions.toLong(), ConfigurationUnit.COUNT, ConfigurationScope.INSTALLATION, "maximumTaskSessions"),
        declaration("broker.runtime.connections.maximum", maximumRuntimeConnections.toLong(), ConfigurationUnit.COUNT, ConfigurationScope.INSTALLATION, "maximumRuntimeConnections"),
        declaration("broker.codex.schema.default_bytes", defaultCodexSchemaBytes.toLong(), ConfigurationUnit.BYTES, ConfigurationScope.HOST_PROFILE, "defaultCodexSchemaBytes"),
        declaration("broker.codex.schema.installed_bytes", installedCodexSchemaBytes.toLong(), ConfigurationUnit.BYTES, ConfigurationScope.HOST_PROFILE, "installedCodexSchemaBytes"),
        declaration("broker.codex.schema.maximum_files", maximumCodexSchemaFiles.toLong(), ConfigurationUnit.COUNT, ConfigurationScope.HOST_PROFILE, "maximumCodexSchemaFiles"),
        declaration("broker.codex.command.maximum_output_bytes", maximumCodexCommandOutputBytes.toLong(), ConfigurationUnit.BYTES, ConfigurationScope.REQUEST, "maximumCodexCommandOutputBytes"),
        declaration("broker.process.maximum_input_bytes", maximumProcessInputBytes.toLong(), ConfigurationUnit.BYTES, ConfigurationScope.REQUEST, "maximumProcessInputBytes"),
        declaration("broker.process.maximum_output_bytes", maximumProcessOutputBytes.toLong(), ConfigurationUnit.BYTES, ConfigurationScope.REQUEST, "maximumProcessOutputBytes"),
        declaration("broker.gradle.maximum_output_bytes", maximumGradleOutputBytes.toLong(), ConfigurationUnit.BYTES, ConfigurationScope.REQUEST, "maximumGradleOutputBytes"),
        declaration("broker.kast.version.maximum_bytes", maximumKastVersionBytes.toLong(), ConfigurationUnit.BYTES, ConfigurationScope.REQUEST, "maximumKastVersionBytes"),
        declaration("broker.kast.schema.maximum_bytes", maximumKastSchemaBytes.toLong(), ConfigurationUnit.BYTES, ConfigurationScope.REQUEST, "maximumKastSchemaBytes"),
        declaration("broker.kast.maximum_output_bytes", maximumKastOutputBytes.toLong(), ConfigurationUnit.BYTES, ConfigurationScope.REQUEST, "maximumKastOutputBytes"),
        declaration("broker.registry.maximum_workspaces", maximumWorkspaces.toLong(), ConfigurationUnit.COUNT, ConfigurationScope.INSTALLATION, "maximumWorkspaces"),
        declaration("broker.registry.maximum_bytes", maximumRegistryBytes.toLong(), ConfigurationUnit.BYTES, ConfigurationScope.INSTALLATION, "maximumRegistryBytes"),
        declaration("broker.invocation_journal.maximum_bytes", maximumInvocationJournalBytes.toLong(), ConfigurationUnit.BYTES, ConfigurationScope.INSTALLATION, "maximumInvocationJournalBytes"),
        declaration("broker.invocations.maximum", maximumInvocations.toLong(), ConfigurationUnit.COUNT, ConfigurationScope.INSTALLATION, "maximumInvocations"),
        declaration("broker.server_requests.maximum", maximumServerRequests.toLong(), ConfigurationUnit.COUNT, ConfigurationScope.INSTALLATION, "maximumServerRequests"),
        declaration("broker.session.channel_capacity", sessionChannelCapacity.toLong(), ConfigurationUnit.COUNT, ConfigurationScope.HOST_PROFILE, "sessionChannelCapacity"),
        declaration("broker.seed.consent.maximum_bytes", maximumSeedConsentBytes.toLong(), ConfigurationUnit.BYTES, ConfigurationScope.REQUEST, "maximumSeedConsentBytes"),
        declaration("broker.control.state.maximum_bytes", maximumControlStateBytes.toLong(), ConfigurationUnit.BYTES, ConfigurationScope.INSTALLATION, "maximumControlStateBytes"),
        declaration("broker.desktop.inspection.maximum_bytes", maximumDesktopInspectionBytes.toLong(), ConfigurationUnit.BYTES, ConfigurationScope.REQUEST, "maximumDesktopInspectionBytes"),
        declaration("broker.client.maximum_message_bytes", maximumClientMessageBytes.toLong(), ConfigurationUnit.BYTES, ConfigurationScope.REQUEST, "maximumClientMessageBytes"),
        declaration("broker.inventory.maximum_entries", maximumInventoryEntries.toLong(), ConfigurationUnit.COUNT, ConfigurationScope.INSTALLATION, "maximumInventoryEntries"),
        declaration("broker.inventory.maximum_bytes", maximumInventoryBytes.toLong(), ConfigurationUnit.BYTES, ConfigurationScope.INSTALLATION, "maximumInventoryBytes"),
        declaration("broker.epoch.maximum_bytes", maximumEpochBytes.toLong(), ConfigurationUnit.BYTES, ConfigurationScope.INSTALLATION, "maximumEpochBytes"),
        declaration("broker.upstream_startup", upstreamStartup.value, ConfigurationUnit.MILLISECONDS, ConfigurationScope.HOST_PROFILE, "upstreamStartup"),
        declaration("broker.provider.startup", providerStartup.value, ConfigurationUnit.MILLISECONDS, ConfigurationScope.HOST_PROFILE, "providerStartup"),
        declaration("broker.workspace.queue.wait", workspaceQueueWait.value, ConfigurationUnit.MILLISECONDS, ConfigurationScope.WORKSPACE, "workspaceQueueWait"),
        declaration("broker.workspace.interaction", workspaceInteraction.value, ConfigurationUnit.MILLISECONDS, ConfigurationScope.WORKSPACE, "workspaceInteraction"),
        declaration("broker.process.maximum_timeout", maximumProcessTimeout.value, ConfigurationUnit.MILLISECONDS, ConfigurationScope.REQUEST, "maximumProcessTimeout"),
        declaration("broker.gradle.invocation", gradleInvocation.value, ConfigurationUnit.MILLISECONDS, ConfigurationScope.REQUEST, "gradleInvocation"),
        declaration("broker.codex.qualification", codexQualification.value, ConfigurationUnit.MILLISECONDS, ConfigurationScope.HOST_PROFILE, "codexQualification"),
        declaration("broker.kast.qualification.maximum", maximumKastQualification.value, ConfigurationUnit.MILLISECONDS, ConfigurationScope.HOST_PROFILE, "maximumKastQualification"),
        declaration("broker.readiness.exchange", readinessExchange.value, ConfigurationUnit.MILLISECONDS, ConfigurationScope.REQUEST, "readinessExchange"),
        declaration("broker.service.child_phases", serviceChildPhases.value, ConfigurationUnit.MILLISECONDS, ConfigurationScope.HOST_PROFILE, "serviceChildPhases"),
        declaration("broker.service.startup", serviceStartup.value, ConfigurationUnit.MILLISECONDS, ConfigurationScope.INSTALLATION, "serviceStartup"),
        declaration("broker.service.retirement", serviceRetirement.value, ConfigurationUnit.MILLISECONDS, ConfigurationScope.INSTALLATION, "serviceRetirement"),
        declaration("broker.service.start_lock", serviceStartLock.value, ConfigurationUnit.MILLISECONDS, ConfigurationScope.INSTALLATION, "serviceStartLock"),
        declaration("broker.service.lock_poll", serviceLockPoll.value, ConfigurationUnit.MILLISECONDS, ConfigurationScope.INSTALLATION, "serviceLockPoll"),
        declaration("broker.service.poll", servicePoll.value, ConfigurationUnit.MILLISECONDS, ConfigurationScope.INSTALLATION, "servicePoll"),
        declaration("broker.launchctl.invocation", launchctlInvocation.value, ConfigurationUnit.MILLISECONDS, ConfigurationScope.INSTALLATION, "launchctlInvocation"),
        declaration("broker.client.connect", clientConnect.value, ConfigurationUnit.MILLISECONDS, ConfigurationScope.REQUEST, "clientConnect"),
        declaration("broker.client.process_retirement_wait", clientProcessRetirementWait.value, ConfigurationUnit.MILLISECONDS, ConfigurationScope.HOST_PROFILE, "clientProcessRetirementWait"),
        declaration("broker.upstream.process_retirement_wait", upstreamProcessRetirementWait.value, ConfigurationUnit.MILLISECONDS, ConfigurationScope.HOST_PROFILE, "upstreamProcessRetirementWait"),
        declaration("broker.client.shutdown", clientShutdown.value, ConfigurationUnit.MILLISECONDS, ConfigurationScope.HOST_PROFILE, "clientShutdown"),
        declaration("broker.desktop.shutdown_phase", desktopShutdown.value, ConfigurationUnit.MILLISECONDS, ConfigurationScope.HOST_PROFILE, "desktopShutdown"),
        declaration("broker.desktop.inspection", desktopInspection.value, ConfigurationUnit.MILLISECONDS, ConfigurationScope.REQUEST, "desktopInspection"),
        declaration("broker.management.exchange", managementExchange.value, ConfigurationUnit.MILLISECONDS, ConfigurationScope.REQUEST, "managementExchange"),
        declaration("broker.management.connect", managementConnect.value, ConfigurationUnit.MILLISECONDS, ConfigurationScope.REQUEST, "managementConnect"),
        declaration("broker.process.retirement_wait", processRetirementWait.value, ConfigurationUnit.MILLISECONDS, ConfigurationScope.REQUEST, "processRetirementWait"),
        declaration("broker.seed.consent", seedConsent.value, ConfigurationUnit.MILLISECONDS, ConfigurationScope.REQUEST, "seedConsent"),
        declaration("broker.worker.startup_join", workerStartupJoin.value, ConfigurationUnit.MILLISECONDS, ConfigurationScope.WORKSPACE, "workerStartupJoin"),
        declaration("broker.worker.control_handshake", workerControlHandshake.value, ConfigurationUnit.MILLISECONDS, ConfigurationScope.REQUEST, "workerControlHandshake"),
        declaration("broker.worker.shutdown_join", workerShutdownJoin.value, ConfigurationUnit.MILLISECONDS, ConfigurationScope.INSTALLATION, "workerShutdownJoin"),
        declaration("broker.connection.initialization", connectionInitialization.value, ConfigurationUnit.MILLISECONDS, ConfigurationScope.HOST_PROFILE, "connectionInitialization"),
        declaration("broker.server.shutdown_grace", serverShutdownGrace.value, ConfigurationUnit.MILLISECONDS, ConfigurationScope.INSTALLATION, "serverShutdownGrace"),
        declaration("broker.server.shutdown", serverShutdown.value, ConfigurationUnit.MILLISECONDS, ConfigurationScope.INSTALLATION, "serverShutdown"),
        declaration("broker.server.failed_startup_shutdown", serverFailedStartupShutdown.value, ConfigurationUnit.MILLISECONDS, ConfigurationScope.INSTALLATION, "serverFailedStartupShutdown"),
        declaration("broker.upstream.health_poll", upstreamHealthPoll.value, ConfigurationUnit.MILLISECONDS, ConfigurationScope.HOST_PROFILE, "upstreamHealthPoll"),
        declaration("broker.session.send", sessionSend.value, ConfigurationUnit.MILLISECONDS, ConfigurationScope.REQUEST, "sessionSend"),
        declaration("broker.session.close", sessionClose.value, ConfigurationUnit.MILLISECONDS, ConfigurationScope.HOST_PROFILE, "sessionClose"),
    )

    private fun declaration(key: String, value: Long, unit: ConfigurationUnit, scope: ConfigurationScope, property: String) =
        ConfigurationOperationalLimit(key, ":app-server", value, unit, scope, "BrokerOperationalLimits.$property")

    private fun limit(value: Long): ElapsedTimeLimitMillis = when (val admitted = ElapsedTimeLimitMillis.parse(value)) {
        is Refinement.Refined -> admitted.value
        is Refinement.Rejected -> error("Invalid fixed broker deadline")
    }
}
