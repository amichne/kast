package io.github.amichne.kast.appserver

import io.github.amichne.kast.appserver.runtime.*
import io.github.amichne.kast.kernel.Refinement
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.TimeUnit

sealed interface AppServerAction {
    data object Register : AppServerAction
    data object Enable : AppServerAction
    data object Bootstrap : AppServerAction
    data object Status : AppServerAction
    data object Stop : AppServerAction
    data object Disable : AppServerAction
    class Control private constructor(val operation: ControlOperation, val target: AppServerControlTarget) : AppServerAction {
        companion object {
            fun admit(operation: ControlOperation, threadId: String, connectionId: String): AppServerControlAdmission {
                val thread = io.github.amichne.kast.appserver.core.BrokerThreadId.admit(threadId)
                    ?: return AppServerControlAdmission.Rejected(AppServerControlFailure.INVALID_THREAD)
                val connection = ClientConnectionId.admit(connectionId)
                    ?: return AppServerControlAdmission.Rejected(AppServerControlFailure.INVALID_CONNECTION)
                return AppServerControlAdmission.Admitted(Control(operation,AppServerControlTarget(thread,connection)))
            }
        }
    }
}
class AppServerControlTarget internal constructor(
    internal val thread: io.github.amichne.kast.appserver.core.BrokerThreadId,
    internal val connection: ClientConnectionId,
) {
    val threadId: String get() = thread.value
    val connectionId: String get() = connection.value
}
enum class AppServerControlFailure { INVALID_THREAD, INVALID_CONNECTION }
sealed interface AppServerControlAdmission {
    data class Admitted(val action: AppServerAction.Control) : AppServerControlAdmission
    data class Rejected(val failure: AppServerControlFailure) : AppServerControlAdmission
}
enum class ControlOperation { CLAIM, RELEASE }
enum class AppServerManagementFailure { CONFIGURATION_REJECTED, DESKTOP_OVERRIDE_CONFLICT, DESKTOP_VERSION_UNSUPPORTED, DESKTOP_UNAVAILABLE, DESKTOP_INSPECTION_REJECTED, ENROLLMENT_REJECTED, SERVICE_UNAVAILABLE, SERVICE_OWNERSHIP_UNPROVEN, FILESYSTEM_REJECTED, LAUNCHCTL_REJECTED, CONTROL_REJECTED, STOPPED_FOR_LOGIN, INTERRUPTED }
sealed interface AppServerManagementResult {
    data class Completed(val document: JsonObject) : AppServerManagementResult
    data class Rejected(val failure: AppServerManagementFailure, val serviceFailure: PersistentBrokerServiceFailure? = null) : AppServerManagementResult
}
fun interface AppServerManager { fun execute(action: AppServerAction, workspace: Path): AppServerManagementResult }
object UnavailableAppServerManager : AppServerManager {
    override fun execute(action: AppServerAction, workspace: Path) = AppServerManagementResult.Rejected(AppServerManagementFailure.SERVICE_UNAVAILABLE)
}

/** Installation effects are explicit and remain separate from semantic runtime demand. */
class InstalledAppServerManager(
    private val kast: Path,
    private val userHome: Path,
    private val environment: Map<String,String> = System.getenv(),
) : AppServerManager {
    override fun execute(action: AppServerAction, workspace: Path): AppServerManagementResult {
        val installationRoot = try { kast.toRealPath().parent.parent } catch (_: Exception) {
            return reject(AppServerManagementFailure.CONFIGURATION_REJECTED)
        }
        if ((action == AppServerAction.Enable || action == AppServerAction.Bootstrap) &&
            InstallationLifecycleFence.observe(installationRoot) != InstallationLifecycleStartAdmission.AVAILABLE) {
            return reject(AppServerManagementFailure.SERVICE_OWNERSHIP_UNPROVEN)
        }
        val enrollment = WorkspaceEnrollmentStore(installationRoot.resolve("config/workspaces.json"))
        if (action == AppServerAction.Register) return when (val registered = enrollment.enroll(workspace)) {
            is Refinement.Rejected -> reject(AppServerManagementFailure.ENROLLMENT_REJECTED)
            is Refinement.Refined -> AppServerManagementResult.Completed(buildJsonObject {
                put("operation", "app-server.register")
                put("workspaceId", registered.value.workspace.id.value)
                put("root", registered.value.workspace.root.path.toString())
                put("revision", registered.value.revision.value)
            })
        }
        val selectedEnvironment = if (action == AppServerAction.Enable) environment + (APP_SERVER_ENABLE_ENVIRONMENT to "1") else environment
        val command = when (val resolved = BrokerServiceLaunchCommand.resolveCoordinator(kast,userHome,selectedEnvironment)) {
            is BrokerServiceLaunchCommandResolution.Resolved -> resolved.command
            is BrokerServiceLaunchCommandResolution.Rejected -> return reject(AppServerManagementFailure.CONFIGURATION_REJECTED)
        }
        val agent = userHome.resolve("Library/LaunchAgents/${command.serviceLabel.value}.login.plist")
        return try {
            when (action) {
                AppServerAction.Register -> error("registration is handled before service admission")
                AppServerAction.Enable -> {
                    if (enrollment.enroll(workspace) is Refinement.Rejected) return reject(AppServerManagementFailure.ENROLLMENT_REJECTED)
                    if (Files.exists(agent) && (Files.isSymbolicLink(agent) || !Files.readString(agent).contains("<!-- Kast App Server login bootstrap v1 -->"))) return reject(AppServerManagementFailure.SERVICE_OWNERSHIP_UNPROVEN)
                    Files.createDirectories(agent.parent)
                    val xml = loginAgent(command)
                    val temporary = Files.createTempFile(agent.parent,".kast-login-",".plist")
                    try {
                        Files.writeString(temporary,xml)
                        Files.setPosixFilePermissions(temporary,PosixFilePermissions.fromString("rw-------"))
                        Files.move(temporary,agent,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING)
                    } finally { Files.deleteIfExists(temporary) }
                    Files.deleteIfExists(command.stateDirectory.resolve("stopped"))
                    bootstrap(command)
                }
                AppServerAction.Bootstrap -> {
                    if (enrollment.read() is EnrollmentRead.Rejected) return reject(AppServerManagementFailure.ENROLLMENT_REJECTED)
                    // A new login runs this once. The broker is subsequently restarted by launchd.
                    Files.deleteIfExists(command.stateDirectory.resolve("stopped"))
                    bootstrap(command)
                }
                AppServerAction.Status -> passiveStatus(command, enrollment)
                AppServerAction.Stop, AppServerAction.Disable -> {
                    val host = MacOsPersistentBrokerServiceHost()
                    val first = host.stop(command)
                    val stopped = if (first is PersistentBrokerServiceAdmission.Rejected) {
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
                            if (Files.isSymbolicLink(agent) || !Files.readString(agent).contains("<!-- Kast App Server login bootstrap v1 -->")) return reject(AppServerManagementFailure.SERVICE_OWNERSHIP_UNPROVEN)
                            Files.delete(agent)
                        }

                    }
                    complete(if (action == AppServerAction.Stop) "stopped" else "disabled")
                }
                is AppServerAction.Control -> {
                    val result = rpc(command,"kast/appServer/control/${action.operation.name.lowercase()}",buildJsonObject { put("threadId",action.target.threadId);put("connectionId",action.target.connectionId) })
                        ?: return reject(AppServerManagementFailure.SERVICE_UNAVAILABLE)
                    if (result.containsKey("error")) reject(AppServerManagementFailure.CONTROL_REJECTED)
                    else AppServerManagementResult.Completed(result)
                }
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt(); reject(AppServerManagementFailure.INTERRUPTED)
        } catch (_: Exception) { reject(AppServerManagementFailure.FILESYSTEM_REJECTED) }
    }

    private fun passiveStatus(command: BrokerServiceLaunchCommand, registry: WorkspaceEnrollmentStore): AppServerManagementResult {
        val observed = runBlocking { InstalledWorkerClient(kast, userHome, environment).status(command) }
        val service = when (observed) {
            is CoordinatorStatusRead.Observed -> PassiveServiceState.READY
            is CoordinatorStatusRead.Rejected -> when (observed.failure) {
                WorkerControlFailure.UNAVAILABLE, WorkerControlFailure.DEADLINE_EXCEEDED -> PassiveServiceState.UNAVAILABLE
                WorkerControlFailure.INVALID_REQUEST, WorkerControlFailure.IDENTITY_REJECTED,
                WorkerControlFailure.SERVICE_IDENTITY_REJECTED, WorkerControlFailure.WORKSPACE_CONFIGURATION_REJECTED,
                WorkerControlFailure.COORDINATOR_IDENTITY_REJECTED, WorkerControlFailure.WORKER_BINDING_IDENTITY_REJECTED,
                WorkerControlFailure.REGISTRATION_REJECTED, WorkerControlFailure.RECEIPT_REJECTED,
                WorkerControlFailure.LIFECYCLE_TRANSITION, WorkerControlFailure.RECOVERY_REQUIRED,
                WorkerControlFailure.CAPACITY_REJECTED, WorkerControlFailure.STARTUP_REJECTED,
                WorkerControlFailure.RETIREMENT_UNPROVEN -> PassiveServiceState.REJECTED
            }
        }
        val registered = registry.snapshot()
        return AppServerManagementResult.Completed(buildJsonObject {
            put("operation", "app-server.status")
            put("transport", if (service == PassiveServiceState.READY) "ready" else "unavailable")
            put("protocol", "unobserved")
            put("catalog", "unobserved")
            put("semantic", "unobserved")
            put("desktop", "unqualified")
            putJsonObject("coordinator") {
                put("state", service.name.lowercase())
                when (observed) {
                    is CoordinatorStatusRead.Observed -> put("observation", observed.snapshot.document())
                    is CoordinatorStatusRead.Rejected -> put("reason", observed.failure.name)
                }
            }
            putJsonObject("service") {
                put("state", service.name.lowercase())
                put("ownership", if (observed is CoordinatorStatusRead.Observed) "matched" else "unobserved")
            }
            putJsonObject("host") {
                put("attachment", when (observed) {
                    is CoordinatorStatusRead.Observed -> observed.snapshot.hostAttachment.name.lowercase()
                    is CoordinatorStatusRead.Rejected -> CoordinatorHostAttachment.UNOBSERVED.name.lowercase()
                })
                put("desktop", "unqualified")
            }
            putJsonObject("registry") {
                when (registered) {
                    is WorkspaceRegistryRead.Read -> {
                        put("state", if (registered.snapshot.workspaces.isEmpty()) "empty" else "registered")
                        put("revision", registered.snapshot.revision.value)
                        put("count", registered.snapshot.workspaces.size)
                        putJsonArray("workspaces") {
                            registered.snapshot.workspaces.forEach { workspace -> add(buildJsonObject {
                                put("workspaceId", workspace.id.value)
                                put("root", workspace.root.path.toString())
                            }) }
                        }
                    }
                    is WorkspaceRegistryRead.Rejected -> { put("state", "rejected"); put("reason", registered.failure.name) }
                }
            }
            put("enrollment", when (registered) {
                is WorkspaceRegistryRead.Read -> registered.snapshot.workspaces.singleOrNull()?.let { JsonPrimitive(it.root.path.toString()) } ?: JsonNull
                is WorkspaceRegistryRead.Rejected -> JsonPrimitive("rejected")
            })
            put("session", JsonNull)
        })
    }

    private fun bootstrap(command: BrokerServiceLaunchCommand): AppServerManagementResult {
        when (val ensured = MacOsPersistentBrokerServiceHost().ensure(command)) {
            PersistentBrokerServiceAdmission.Ready -> Unit
            is PersistentBrokerServiceAdmission.Rejected -> return AppServerManagementResult.Rejected(AppServerManagementFailure.SERVICE_UNAVAILABLE,ensured.failure)
        }
        return complete("ready")
    }
    private fun loginAgent(command: BrokerServiceLaunchCommand): String {
        fun escape(raw: String) = raw.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;")
        val arguments = listOf(command.kast.toString(),"app-server","bootstrap").joinToString("") { "<string>${escape(it)}</string>" }
        val env = mapOf("PATH" to command.executableSearchPath.value,"CODEX_HOME" to command.codexHome.toString(),APP_SERVER_ENABLE_ENVIRONMENT to "1") + command.host.environment()
        return """<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<!-- Kast App Server login bootstrap v1 -->
<plist version="1.0"><dict><key>Label</key><string>${escape(command.serviceLabel.value)}.login</string><key>ProgramArguments</key><array>$arguments</array><key>RunAtLoad</key><true/><key>EnvironmentVariables</key><dict>${env.entries.joinToString("") { "<key>${escape(it.key)}</key><string>${escape(it.value)}</string>" }}</dict></dict></plist>
"""
    }
    private fun rpc(command: BrokerServiceLaunchCommand, method: String, params: JsonObject): JsonObject? = runBlocking {
        kotlinx.coroutines.withTimeoutOrNull(BrokerOperationalLimits.managementExchange.value) {
            val connection = (connectCodexUnixWebSocket(command.publicSocket,BrokerOperationalLimits.maximumClientMessageBytes,BrokerOperationalLimits.managementConnect.value) as? BrokerUpstreamConnectionAdmission.Connected)?.connection ?: return@withTimeoutOrNull null
            try {
                connection.send("""{"id":1,"method":"initialize","params":{"clientInfo":{"name":"kast-control","version":"1"}}}""")
                val initialize = (connection.receive() as? BrokerUpstreamFrame.Text)?.message?.let { Json.parseToJsonElement(it).jsonObject } ?: return@withTimeoutOrNull null
                if (initialize.containsKey("error") || initialize["id"] != JsonPrimitive(1) || initialize["result"] !is JsonObject) return@withTimeoutOrNull null
                connection.send("""{"method":"initialized"}""")
                connection.send(buildJsonObject { put("id",2);put("method",method);put("params",params) }.toString())
                while (true) {
                    val response = (connection.receive() as? BrokerUpstreamFrame.Text)?.message?.let { Json.parseToJsonElement(it).jsonObject } ?: return@withTimeoutOrNull null
                    if (response["id"] == JsonPrimitive(2)) return@withTimeoutOrNull response
                }
                @Suppress("UNREACHABLE_CODE") null
            } finally { connection.close() }
        }
    }
    private fun complete(status: String) = AppServerManagementResult.Completed(buildJsonObject { put("operation","app-server");put("status",status);put("desktop","unqualified") })
    private fun reject(failure: AppServerManagementFailure) = AppServerManagementResult.Rejected(failure)
}

/** Passive observation does not confer authorization to start or attach a host. */
private enum class PassiveServiceState { READY, UNAVAILABLE, REJECTED }
