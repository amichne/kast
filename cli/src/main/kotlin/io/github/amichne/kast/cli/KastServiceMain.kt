package io.github.amichne.kast.cli

import io.github.amichne.kast.appserver.AppServerAction
import io.github.amichne.kast.appserver.AppServerManagementFailure
import io.github.amichne.kast.appserver.AppServerManagementResult
import io.github.amichne.kast.appserver.AppServerManager
import io.github.amichne.kast.appserver.DaemonManagementRejection
import io.github.amichne.kast.appserver.InstalledAppServerManager
import io.github.amichne.kast.appserver.PersistentBrokerServiceFailure
import io.github.amichne.kast.appserver.SemanticCoordinatorRetirement
import io.github.amichne.kast.appserver.retireFencedSemanticCoordinator
import io.github.amichne.kast.distribution.contract.configuration.ResolvedKastConfiguration
import io.github.amichne.kast.distribution.managed.InstalledSavedConfigurationIngress
import io.github.amichne.kast.distribution.managed.SavedConfigurationIngress
import io.github.amichne.kast.kernel.Refinement
import java.nio.file.Path
import kotlin.system.exitProcess
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/** Private installation control entry point, independent of the public command graph. */
object KastServiceMain {
    @JvmStatic
    fun main(arguments: Array<String>) {
        if (arguments.toList() == listOf("host-admission")) {
            when (val observed = observeConfiguredHosts(Path.of(System.getProperty("user.home")), System.getenv())) {
                is io.github.amichne.kast.appserver.ide.HostedServicesObservation.Admitted -> exitProcess(0)
                is io.github.amichne.kast.appserver.ide.HostedServicesObservation.Rejected -> {
                    System.err.println(
                        serviceControlJson.encodeToString<ServiceControlFailureDocument>(
                            ServiceControlFailureDocument.Host(observed.failure)
                        )
                    )
                    exitProcess(SERVICE_CONTROL_REJECTED_EXIT_CODE)
                }
            }
        }
        if (tryManagedHeadlessShutdown(arguments.toList())) return
        if (arguments.toList() == listOf("retire-semantic")) {
            retireSemanticOwner(requireFence = true)
            return
        }
        if (arguments.toList() == listOf("disable")) retireSemanticOwner(requireFence = false)
        val outcome = runServiceControl(selectServiceControl(arguments.toList()))
        when (outcome) {
            ServiceControlOutcome.Completed -> Unit
            is ServiceControlOutcome.Registered -> println(outcome.document)
            is ServiceControlOutcome.Inspected -> println(outcome.document)
            is ServiceControlOutcome.Rejected -> {
                System.err.println(serviceControlJson.encodeToString<ServiceControlFailureDocument>(outcome.failure))
                exitProcess(SERVICE_CONTROL_REJECTED_EXIT_CODE)
            }
        }
    }
}

internal fun observeConfiguredHosts(
    home: Path,
    environment: Map<String, String>,
): io.github.amichne.kast.appserver.ide.HostedServicesObservation {
    val rejected =
        io.github.amichne.kast.appserver.ide.HostedServicesObservation.Rejected(
            io.github.amichne.kast.appserver.ide.ExistingIdeFailure.CONFIGURATION_REJECTED
        )
    return when (val saved = InstalledSavedConfigurationIngress.load(environment)) {
        is SavedConfigurationIngress.Rejected -> rejected
        is SavedConfigurationIngress.Loaded ->
            when (val configuration = ResolvedKastConfiguration.resolve(saved.sources)) {
                is Refinement.Rejected -> rejected
                is Refinement.Refined ->
                    io.github.amichne.kast.appserver.ide.observeRunningHostedServices(home, configuration.value)
            }
    }
}

private fun runServiceControl(selection: ServiceControlSelection): ServiceControlOutcome =
    when (selection) {
        ServiceControlSelection.Rejected -> ServiceControlOutcome.Rejected(ServiceControlFailureDocument.Arguments)
        is ServiceControlSelection.Register ->
            withInstalledManager { executeWorkspaceRegistration(it, selection.workspace) }
        is ServiceControlSelection.Selected ->
            withInstalledManager {
                executeServiceControl(selection.action, it, Path.of("").toAbsolutePath())
            }
    }

private fun withInstalledManager(action: (AppServerManager) -> ServiceControlOutcome): ServiceControlOutcome =
    when (val installed = installedKastExecutable()) {
        is Refinement.Rejected ->
            ServiceControlOutcome.Rejected(ServiceControlFailureDocument.Product(installed.failure))
        is Refinement.Refined ->
            action(
                InstalledAppServerManager(
                    installed.value,
                    Path.of(System.getProperty("user.home")),
                    serviceControlEnvironment(installed.value, System.getenv()),
                )
            )
    }

internal sealed interface ServiceControlSelection {
    data class Selected(val action: ServiceControlAction) : ServiceControlSelection

    data class Register(val workspace: Path) : ServiceControlSelection

    data object Rejected : ServiceControlSelection
}

internal enum class ServiceControlAction(val managerAction: AppServerAction) {
    STATUS(AppServerAction.Status),
    BOOTSTRAP(AppServerAction.Bootstrap),
    ENABLE(AppServerAction.Enable),
    DISABLE(AppServerAction.Disable),
    REPAIR(AppServerAction.Repair),
    STOP(AppServerAction.Stop),
}

internal fun selectServiceControl(arguments: List<String>): ServiceControlSelection {
    if (arguments.size == 2 && arguments.first() == "register") {
        val workspace =
            try {
                Path.of(arguments.last())
            } catch (_: Exception) {
                return ServiceControlSelection.Rejected
            }
        return if (workspace.isAbsolute) ServiceControlSelection.Register(workspace)
        else ServiceControlSelection.Rejected
    }
    return when (arguments) {
        listOf("bootstrap") -> ServiceControlSelection.Selected(ServiceControlAction.BOOTSTRAP)
        listOf("status") -> ServiceControlSelection.Selected(ServiceControlAction.STATUS)
        listOf("enable") -> ServiceControlSelection.Selected(ServiceControlAction.ENABLE)
        listOf("disable") -> ServiceControlSelection.Selected(ServiceControlAction.DISABLE)
        listOf("repair", "--destructive") -> ServiceControlSelection.Selected(ServiceControlAction.REPAIR)
        listOf("stop") -> ServiceControlSelection.Selected(ServiceControlAction.STOP)
        else -> ServiceControlSelection.Rejected
    }
}

/** Match the installed wrapper's default while preserving an explicitly selected configuration. */
internal fun serviceControlEnvironment(kast: Path, environment: Map<String, String>): Map<String, String> =
    if ("KAST_CONFIGURATION_FILE" in environment) environment
    else environment + ("KAST_CONFIGURATION_FILE" to kast.parent.parent.resolve("config/environment").toString())

internal sealed interface ServiceControlOutcome {
    data object Completed : ServiceControlOutcome

    data class Registered(val document: JsonObject) : ServiceControlOutcome

    data class Inspected(val document: JsonObject) : ServiceControlOutcome

    data class Rejected(val failure: ServiceControlFailureDocument) : ServiceControlOutcome
}

internal fun executeServiceControl(
    action: ServiceControlAction,
    manager: AppServerManager,
    workspace: Path,
): ServiceControlOutcome =
    when (val result = manager.execute(action.managerAction, workspace)) {
        is AppServerManagementResult.Completed ->
            if (action == ServiceControlAction.STATUS) ServiceControlOutcome.Inspected(result.document)
            else ServiceControlOutcome.Completed
        is AppServerManagementResult.Rejected ->
            ServiceControlOutcome.Rejected(
                ServiceControlFailureDocument.Management(result.failure, result.serviceFailure)
            )
        is AppServerManagementResult.DaemonRejected ->
            ServiceControlOutcome.Rejected(ServiceControlFailureDocument.Daemon(result.reason))
    }

internal fun executeWorkspaceRegistration(manager: AppServerManager, workspace: Path): ServiceControlOutcome =
    when (val result = manager.execute(AppServerAction.Register, workspace)) {
        is AppServerManagementResult.Completed -> ServiceControlOutcome.Registered(result.document)
        is AppServerManagementResult.Rejected ->
            ServiceControlOutcome.Rejected(
                ServiceControlFailureDocument.Management(result.failure, result.serviceFailure)
            )
        is AppServerManagementResult.DaemonRejected ->
            ServiceControlOutcome.Rejected(ServiceControlFailureDocument.Daemon(result.reason))
    }

@Serializable
internal sealed interface ServiceControlFailureDocument {
    @Serializable
    @SerialName("HOST_ADMISSION_REJECTED")
    data class Host(val failure: io.github.amichne.kast.appserver.ide.ExistingIdeFailure) :
        ServiceControlFailureDocument

    @Serializable
    @SerialName("MANAGED_HEADLESS_SHUTDOWN_REJECTED")
    data class Headless(val failure: io.github.amichne.kast.cli.installation.ManagedHeadlessShutdownFailure) :
        ServiceControlFailureDocument

    @Serializable
    @SerialName("semantic_coordinator")
    data class SemanticCoordinator(val failure: SemanticCoordinatorRetirement) : ServiceControlFailureDocument

    @Serializable @SerialName("arguments") data object Arguments : ServiceControlFailureDocument

    @Serializable
    @SerialName("product")
    data class Product(val failure: InstalledKastControlProductFailure) : ServiceControlFailureDocument

    @Serializable
    @SerialName("management")
    data class Management(
        val failure: AppServerManagementFailure,
        val serviceFailure: PersistentBrokerServiceFailure?,
    ) : ServiceControlFailureDocument

    @Serializable
    @SerialName("daemon")
    data class Daemon(val reason: DaemonManagementRejection) : ServiceControlFailureDocument
}

internal val serviceControlJson = Json { encodeDefaults = true }

private const val SERVICE_CONTROL_REJECTED_EXIT_CODE = 64

private fun retireSemanticOwner(requireFence: Boolean) {
    val retired =
        when (val installed = installedKastExecutable()) {
            is Refinement.Rejected -> SemanticCoordinatorRetirement.OWNERSHIP_UNPROVEN
            is Refinement.Refined ->
                runBlocking {
                    retireFencedSemanticCoordinator(
                        installed.value.parent.parent,
                        Path.of(System.getProperty("user.home")),
                    )
                }
        }
    when (retired) {
        SemanticCoordinatorRetirement.ABSENT,
        SemanticCoordinatorRetirement.EXITED -> Unit
        SemanticCoordinatorRetirement.UNFENCED -> if (requireFence) semanticRetirementRejected(retired)
        SemanticCoordinatorRetirement.OWNERSHIP_UNPROVEN,
        SemanticCoordinatorRetirement.SHUTDOWN_UNOBSERVED,
        SemanticCoordinatorRetirement.EXIT_UNOBSERVED -> semanticRetirementRejected(retired)
    }
}

private fun semanticRetirementRejected(retired: SemanticCoordinatorRetirement): Nothing {
    System.err.println(
        serviceControlJson.encodeToString<ServiceControlFailureDocument>(
            ServiceControlFailureDocument.SemanticCoordinator(retired)
        )
    )
    exitProcess(SERVICE_CONTROL_REJECTED_EXIT_CODE)
}

private fun tryManagedHeadlessShutdown(arguments: List<String>): Boolean {
    if (arguments != listOf("disable") || System.getenv("KAST_MANAGED_HEADLESS_STATE") == null) return false
    val installed = installedKastExecutable()
    val outcome =
        if (installed is Refinement.Refined)
            io.github.amichne.kast.cli.installation.shutdownManagedHeadless(
                System.getenv("KAST_CONFIGURATION_FILE")?.let { Path.of(it).parent.parent }
                    ?: installed.value.parent.parent,
                Path.of(System.getProperty("user.home")),
                Path.of(System.getenv("KAST_MANAGED_HEADLESS_STATE")),
            )
        else
            io.github.amichne.kast.cli.installation.ManagedHeadlessShutdownOutcome.Rejected(
                io.github.amichne.kast.cli.installation.ManagedHeadlessShutdownFailure.ADMISSION_REJECTED
            )
    if (outcome is io.github.amichne.kast.cli.installation.ManagedHeadlessShutdownOutcome.Rejected) {
        System.err.println(
            serviceControlJson.encodeToString<ServiceControlFailureDocument>(
                ServiceControlFailureDocument.Headless(outcome.failure)
            )
        )
        exitProcess(SERVICE_CONTROL_REJECTED_EXIT_CODE)
    }
    return true
}
