package io.github.amichne.kast.appserver

import io.github.amichne.kast.appserver.runtime.*
import io.github.amichne.kast.kernel.Refinement
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*

sealed interface AppServerAction {
    data object Register : AppServerAction

    data object Enable : AppServerAction

    data object Repair : AppServerAction

    data object Bootstrap : AppServerAction

    data object Status : AppServerAction

    data object Stop : AppServerAction

    data object Disable : AppServerAction

    class Control private constructor(val operation: ControlOperation, val target: AppServerControlTarget) :
        AppServerAction {
        companion object {
            fun admit(operation: ControlOperation, threadId: String, connectionId: String): AppServerControlAdmission {
                val thread =
                    io.github.amichne.kast.appserver.core.BrokerThreadId.admit(threadId)
                        ?: return AppServerControlAdmission.Rejected(AppServerControlFailure.INVALID_THREAD)
                val connection =
                    ClientConnectionId.admit(connectionId)
                        ?: return AppServerControlAdmission.Rejected(AppServerControlFailure.INVALID_CONNECTION)
                return AppServerControlAdmission.Admitted(
                    Control(operation, AppServerControlTarget(thread, connection))
                )
            }
        }
    }
}

class AppServerControlTarget
internal constructor(
    internal val thread: io.github.amichne.kast.appserver.core.BrokerThreadId,
    internal val connection: ClientConnectionId,
) {
    val threadId: String
        get() = thread.value

    val connectionId: String
        get() = connection.value
}

enum class AppServerControlFailure {
    INVALID_THREAD,
    INVALID_CONNECTION,
}

sealed interface AppServerControlAdmission {
    data class Admitted(val action: AppServerAction.Control) : AppServerControlAdmission

    data class Rejected(val failure: AppServerControlFailure) : AppServerControlAdmission
}

@kotlinx.serialization.Serializable
enum class ControlOperation {
    CLAIM,
    RELEASE,
}

@kotlinx.serialization.Serializable
enum class AppServerManagementFailure {
    PAYLOAD_LIMIT_EXCEEDED,
    CONFIGURATION_REJECTED,
    DESKTOP_OVERRIDE_CONFLICT,
    DESKTOP_VERSION_UNSUPPORTED,
    DESKTOP_UNAVAILABLE,
    DESKTOP_INSPECTION_REJECTED,
    ENROLLMENT_REJECTED,
    SERVICE_UNAVAILABLE,
    SERVICE_OWNERSHIP_UNPROVEN,
    FILESYSTEM_REJECTED,
    LAUNCHCTL_REJECTED,
    CONTROL_REJECTED,
    STOPPED_FOR_LOGIN,
    INTERRUPTED,
}

sealed interface AppServerManagementResult {
    data class Completed(val document: JsonObject) : AppServerManagementResult

    data class DaemonRejected(val reason: DaemonManagementRejection) : AppServerManagementRejection

    data class Rejected(
        val failure: AppServerManagementFailure,
        val serviceFailure: PersistentBrokerServiceFailure? = null,
    ) : AppServerManagementRejection
}

sealed interface AppServerManagementRejection : AppServerManagementResult

fun interface AppServerManager {
    fun execute(action: AppServerAction, workspace: Path): AppServerManagementResult
}

object UnavailableAppServerManager : AppServerManager {
    override fun execute(action: AppServerAction, workspace: Path) =
        AppServerManagementResult.Rejected(AppServerManagementFailure.SERVICE_UNAVAILABLE)
}

/** Installation effects are explicit and remain separate from semantic runtime demand. */
class InstalledAppServerManager(
    private val kast: Path,
    private val userHome: Path,
    private val environment: Map<String, String> = System.getenv(),
) : AppServerManager {
    override fun execute(action: AppServerAction, workspace: Path): AppServerManagementResult {
        val installationRoot =
            try {
                kast.toRealPath().parent.parent
            } catch (_: Exception) {
                return reject(AppServerManagementFailure.CONFIGURATION_REJECTED)
            }
        if (
            (action == AppServerAction.Enable ||
                action == AppServerAction.Repair ||
                action == AppServerAction.Bootstrap) &&
                InstallationLifecycleFence.observe(installationRoot) != InstallationLifecycleStartAdmission.AVAILABLE
        ) {
            return reject(AppServerManagementFailure.SERVICE_OWNERSHIP_UNPROVEN)
        }
        val enrollment = WorkspaceEnrollmentStore(installationRoot.resolve("config/workspaces.json"))
        val command =
            when (val resolved = BrokerServiceLaunchCommand.resolveCoordinator(kast, userHome, environment)) {
                is BrokerServiceLaunchCommandResolution.Resolved -> resolved.command
                is BrokerServiceLaunchCommandResolution.Rejected ->
                    return reject(AppServerManagementFailure.CONFIGURATION_REJECTED)
            }
        val agent = userHome.resolve("Library/LaunchAgents/${command.serviceLabel.value}.login.plist")
        return try {
            if (action == AppServerAction.Register)
                return runBlocking {
                    when (val registered = InstalledDaemonManagementClient(kast).register(command, workspace)) {
                        is Refinement.Rejected -> AppServerManagementResult.DaemonRejected(registered.failure)
                        is Refinement.Refined ->
                            AppServerManagementResult.Completed(
                                DaemonManagementProtocol.json
                                    .encodeToJsonElement(
                                        WorkspaceRegistrationDocument.serializer(),
                                        WorkspaceRegistrationDocument(
                                            registered.value.workspace.id.value,
                                            registered.value.workspace.root.path.toString(),
                                            registered.value.revision.value,
                                        ),
                                    )
                                    .jsonObject
                            )
                    }
                }
            when (action) {
                AppServerAction.Register -> error("registration is handled before service admission")
                AppServerAction.Enable -> {
                    if (enrollment.enroll(workspace) is Refinement.Rejected)
                        return reject(AppServerManagementFailure.ENROLLMENT_REJECTED)
                    if (
                        Files.exists(agent) &&
                            (Files.isSymbolicLink(agent) ||
                                !Files.readString(agent).contains("<!-- Kast App Server login bootstrap v1 -->"))
                    )
                        return reject(AppServerManagementFailure.SERVICE_OWNERSHIP_UNPROVEN)
                    Files.createDirectories(agent.parent)
                    writeLoginAgent(agent, command)
                    Files.deleteIfExists(command.stateDirectory.resolve("stopped"))
                    bootstrap(command)
                }
                AppServerAction.Repair -> {
                    val host = MacOsPersistentBrokerServiceHost()
                    when (val reset = host.destructiveReset(command)) {
                        PersistentBrokerServiceAdmission.Ready -> Unit
                        is PersistentBrokerServiceAdmission.Rejected ->
                            return AppServerManagementResult.Rejected(
                                AppServerManagementFailure.SERVICE_OWNERSHIP_UNPROVEN,
                                reset.failure,
                            )
                    }
                    val registry = installationRoot.resolve("config/workspaces.json")
                    Files.deleteIfExists(registry)
                    if (enrollment.enroll(workspace) is Refinement.Rejected)
                        return reject(AppServerManagementFailure.ENROLLMENT_REJECTED)
                    Files.createDirectories(agent.parent)
                    Files.deleteIfExists(agent)
                    writeLoginAgent(agent, command)
                    Files.deleteIfExists(command.stateDirectory.resolve("stopped"))
                    bootstrap(command)
                }
                AppServerAction.Bootstrap -> {
                    if (enrollment.read() is EnrollmentRead.Rejected)
                        return reject(AppServerManagementFailure.ENROLLMENT_REJECTED)
                    // A new login runs this once. The broker is subsequently restarted by launchd.
                    Files.deleteIfExists(command.stateDirectory.resolve("stopped"))
                    bootstrap(command)
                }
                AppServerAction.Status -> readAppServerStatus(kast, command, enrollment)
                AppServerAction.Stop,
                AppServerAction.Disable -> {
                    val host = MacOsPersistentBrokerServiceHost()
                    val first = host.stop(command)
                    val stopped =
                        if (first is PersistentBrokerServiceAdmission.Rejected) {
                            PublishedBrokerServiceCommand.recover(command)?.let(host::stop) ?: first
                        } else first
                    if (stopped is PersistentBrokerServiceAdmission.Rejected) {
                        return AppServerManagementResult.Rejected(
                            AppServerManagementFailure.SERVICE_OWNERSHIP_UNPROVEN,
                            stopped.failure,
                        )
                    }
                    if (action == AppServerAction.Disable) {
                        if (Files.exists(agent)) {
                            if (
                                Files.isSymbolicLink(agent) ||
                                    !Files.readString(agent).contains("<!-- Kast App Server login bootstrap v1 -->")
                            )
                                return reject(AppServerManagementFailure.SERVICE_OWNERSHIP_UNPROVEN)
                            Files.delete(agent)
                        }
                    }
                    complete(if (action == AppServerAction.Stop) "stopped" else "disabled")
                }
                is AppServerAction.Control ->
                    runBlocking {
                        when (val result = InstalledDaemonManagementClient(kast).control(command, action)) {
                            is Refinement.Rejected -> AppServerManagementResult.DaemonRejected(result.failure)
                            is Refinement.Refined ->
                                AppServerManagementResult.Completed(
                                    DaemonManagementProtocol.json
                                        .encodeToJsonElement(
                                            ControlCompletionDocument.serializer(),
                                            ControlCompletionDocument(),
                                        )
                                        .jsonObject
                                )
                        }
                    }
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            reject(AppServerManagementFailure.INTERRUPTED)
        } catch (_: Exception) {
            reject(AppServerManagementFailure.FILESYSTEM_REJECTED)
        }
    }

    private fun bootstrap(command: BrokerServiceLaunchCommand): AppServerManagementResult {
        when (val ensured = MacOsPersistentBrokerServiceHost().ensure(command)) {
            PersistentBrokerServiceAdmission.Ready -> Unit
            is PersistentBrokerServiceAdmission.Rejected ->
                return AppServerManagementResult.Rejected(
                    AppServerManagementFailure.SERVICE_UNAVAILABLE,
                    ensured.failure,
                )
        }
        return complete("ready")
    }

    private fun loginAgent(command: BrokerServiceLaunchCommand): String {
        fun escape(raw: String) =
            raw.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
        val arguments =
            listOf(command.kast.toString(), "app-server", "bootstrap").joinToString("") {
                "<string>${escape(it)}</string>"
            }
        val env =
            mapOf(
                "PATH" to command.executableSearchPath.value,
                "CODEX_HOME" to command.codexHome.toString(),
            ) + command.host.environment()
        return """<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<!-- Kast App Server login bootstrap v1 -->
<plist version="1.0"><dict><key>Label</key><string>${escape(command.serviceLabel.value)}.login</string><key>ProgramArguments</key><array>$arguments</array><key>RunAtLoad</key><true/><key>EnvironmentVariables</key><dict>${env.entries.joinToString("") { "<key>${escape(it.key)}</key><string>${escape(it.value)}</string>" }}</dict></dict></plist>
"""
    }

    private fun writeLoginAgent(agent: Path, command: BrokerServiceLaunchCommand) {
        val xml = loginAgent(command)
        val temporary = Files.createTempFile(agent.parent, ".kast-login-", ".plist")
        try {
            Files.writeString(temporary, xml)
            Files.setPosixFilePermissions(temporary, PosixFilePermissions.fromString("rw-------"))
            Files.move(temporary, agent, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private fun complete(status: String) =
        AppServerManagementResult.Completed(
            buildJsonObject {
                put("operation", "app-server")
                put("status", status)
                put("desktop", "unqualified")
            }
        )

    private fun reject(failure: AppServerManagementFailure) = AppServerManagementResult.Rejected(failure)
}

@kotlinx.serialization.Serializable
private data class ControlCompletionDocument(
    val id: Int = 2,
    val result: ControlCompletionResult = ControlCompletionResult(),
)

@kotlinx.serialization.Serializable private data class ControlCompletionResult(val status: String = "complete")
