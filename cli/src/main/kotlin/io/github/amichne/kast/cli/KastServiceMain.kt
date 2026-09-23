package io.github.amichne.kast.cli

import io.github.amichne.kast.appserver.AppServerAction
import io.github.amichne.kast.appserver.AppServerManagementFailure
import io.github.amichne.kast.appserver.AppServerManagementResult
import io.github.amichne.kast.appserver.AppServerManager
import io.github.amichne.kast.appserver.DaemonManagementRejection
import io.github.amichne.kast.appserver.InstalledAppServerManager
import io.github.amichne.kast.appserver.PersistentBrokerServiceFailure
import io.github.amichne.kast.cli.ide.BrokerTrustFailure
import io.github.amichne.kast.cli.ide.BrokerTrustRegistrar
import io.github.amichne.kast.cli.ide.BrokerTrustResult
import io.github.amichne.kast.cli.ide.BrokerTrustStatus
import io.github.amichne.kast.cli.ide.FilesystemBrokerTrustRegistrar
import io.github.amichne.kast.kernel.Refinement
import java.nio.file.Path
import kotlin.system.exitProcess
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Private installation control entry point, independent of the public command graph. */
object KastServiceMain {
    @JvmStatic
    fun main(arguments: Array<String>) {
        val selection = selectServiceControl(arguments.toList())
        val outcome =
            when (selection) {
                ServiceControlSelection.Rejected ->
                    ServiceControlOutcome.Rejected(ServiceControlFailureDocument.Arguments)
                ServiceControlSelection.Trust ->
                    when (val installed = installedKastExecutable()) {
                        is Refinement.Rejected ->
                            ServiceControlOutcome.Rejected(ServiceControlFailureDocument.Product(installed.failure))
                        is Refinement.Refined ->
                            executePrivateTrust(
                                FilesystemBrokerTrustRegistrar(Path.of(System.getProperty("user.home")))
                            )
                    }
                is ServiceControlSelection.Selected ->
                    when (val installed = installedKastExecutable()) {
                        is Refinement.Rejected ->
                            ServiceControlOutcome.Rejected(ServiceControlFailureDocument.Product(installed.failure))
                        is Refinement.Refined ->
                            executeServiceControl(
                                selection.action,
                                InstalledAppServerManager(
                                    installed.value,
                                    Path.of(System.getProperty("user.home")),
                                    serviceControlEnvironment(installed.value, System.getenv()),
                                ),
                                Path.of("").toAbsolutePath(),
                            )
                    }
            }
        when (outcome) {
            ServiceControlOutcome.Completed -> Unit
            is ServiceControlOutcome.TrustCompleted ->
                println(serviceControlJson.encodeToString(ServiceTrustCompletionDocument(outcome.status)))
            is ServiceControlOutcome.Rejected -> {
                System.err.println(serviceControlJson.encodeToString<ServiceControlFailureDocument>(outcome.failure))
                exitProcess(SERVICE_CONTROL_REJECTED_EXIT_CODE)
            }
        }
    }
}

internal sealed interface ServiceControlSelection {
    data class Selected(val action: ServiceControlAction) : ServiceControlSelection

    data object Trust : ServiceControlSelection

    data object Rejected : ServiceControlSelection
}

internal enum class ServiceControlAction(val managerAction: AppServerAction) {
    ENABLE(AppServerAction.Enable),
    DISABLE(AppServerAction.Disable),
    REPAIR(AppServerAction.Repair),
    STOP(AppServerAction.Stop),
}

internal fun selectServiceControl(arguments: List<String>): ServiceControlSelection =
    when (arguments) {
        listOf("enable") -> ServiceControlSelection.Selected(ServiceControlAction.ENABLE)
        listOf("disable") -> ServiceControlSelection.Selected(ServiceControlAction.DISABLE)
        listOf("repair", "--destructive") -> ServiceControlSelection.Selected(ServiceControlAction.REPAIR)
        listOf("stop") -> ServiceControlSelection.Selected(ServiceControlAction.STOP)
        listOf("enroll-trust") -> ServiceControlSelection.Trust
        else -> ServiceControlSelection.Rejected
    }

internal fun executePrivateTrust(registrar: BrokerTrustRegistrar): ServiceControlOutcome =
    when (val enrollment = registrar.enroll()) {
        is BrokerTrustResult.Complete -> ServiceControlOutcome.TrustCompleted(enrollment.status)
        is BrokerTrustResult.Rejected ->
            ServiceControlOutcome.Rejected(ServiceControlFailureDocument.Trust(enrollment.failure))
    }

/** Match the installed wrapper's default while preserving an explicitly selected configuration. */
internal fun serviceControlEnvironment(kast: Path, environment: Map<String, String>): Map<String, String> =
    if ("KAST_CONFIGURATION_FILE" in environment) environment
    else environment + ("KAST_CONFIGURATION_FILE" to kast.parent.parent.resolve("config/environment").toString())

internal sealed interface ServiceControlOutcome {
    data object Completed : ServiceControlOutcome

    data class TrustCompleted(val status: BrokerTrustStatus) : ServiceControlOutcome

    data class Rejected(val failure: ServiceControlFailureDocument) : ServiceControlOutcome
}

@Serializable
internal data class ServiceTrustCompletionDocument(val status: BrokerTrustStatus) {
    val outcome: String = "complete"
    val operation: String = "private-trust-enrollment"
}

internal fun executeServiceControl(
    action: ServiceControlAction,
    manager: AppServerManager,
    workspace: Path,
): ServiceControlOutcome =
    when (val result = manager.execute(action.managerAction, workspace)) {
        is AppServerManagementResult.Completed -> ServiceControlOutcome.Completed
        is AppServerManagementResult.Rejected ->
            ServiceControlOutcome.Rejected(
                ServiceControlFailureDocument.Management(result.failure, result.serviceFailure)
            )
        is AppServerManagementResult.DaemonRejected ->
            ServiceControlOutcome.Rejected(ServiceControlFailureDocument.Daemon(result.reason))
    }

@Serializable
internal sealed interface ServiceControlFailureDocument {
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

    @Serializable @SerialName("trust") data class Trust(val failure: BrokerTrustFailure) : ServiceControlFailureDocument
}

internal val serviceControlJson = Json { encodeDefaults = true }

private const val SERVICE_CONTROL_REJECTED_EXIT_CODE = 64
