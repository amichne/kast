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
        val command = when (val resolved = BrokerServiceLaunchCommand.resolve(kast,userHome,environment + (APP_SERVER_ENABLE_ENVIRONMENT to "1"))) {
            is BrokerServiceLaunchCommandResolution.Resolved -> resolved.command
            is BrokerServiceLaunchCommandResolution.Rejected -> return reject(AppServerManagementFailure.CONFIGURATION_REJECTED)
        }
        val enrollment = WorkspaceEnrollmentStore(command.stateDirectory.resolve("workspace.json"))
        val agent = userHome.resolve("Library/LaunchAgents/${command.serviceLabel.value}.login.plist")
        return try {
            when (action) {
                AppServerAction.Enable -> {
                    when (val desktop = InstalledDesktopAttachmentProbe.inspect(environment,userHome)) {
                        DesktopAttachmentAdmission.Eligible -> Unit
                        is DesktopAttachmentAdmission.Rejected -> return reject(when (desktop.failure) {
                            DesktopAttachmentFailure.OVERRIDE_CONFLICT -> AppServerManagementFailure.DESKTOP_OVERRIDE_CONFLICT
                            DesktopAttachmentFailure.VERSION_UNSUPPORTED -> AppServerManagementFailure.DESKTOP_VERSION_UNSUPPORTED
                            DesktopAttachmentFailure.DESKTOP_UNAVAILABLE -> AppServerManagementFailure.DESKTOP_UNAVAILABLE
                            DesktopAttachmentFailure.INSPECTION_REJECTED -> AppServerManagementFailure.DESKTOP_INSPECTION_REJECTED
                        })
                    }
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
                    if ((enrollment.read() as? EnrollmentRead.Read)?.enrollment !is WorkspaceEnrollment.Enrolled) return reject(AppServerManagementFailure.ENROLLMENT_REJECTED)
                    // A new login runs this once. The broker is subsequently restarted by launchd.
                    Files.deleteIfExists(command.stateDirectory.resolve("stopped"))
                    bootstrap(command)
                }
                AppServerAction.Status -> {
                    val response = rpc(command,"kast/appServer/status",JsonObject(emptyMap()))
                    val proof = (response?.get("result") as? JsonObject)?.get("qualification") as? JsonObject
                    val qualified = response?.containsKey("error") == false && proof?.get("serviceIdentity") == JsonPrimitive(command.identity.value) &&
                        listOf("schemaDigest","catalogDigest").all { (proof[it] as? JsonPrimitive)?.contentOrNull?.matches(Regex("sha256:[a-f0-9]{64}")) == true } &&
                        (proof["codexVersion"] as? JsonPrimitive)?.contentOrNull?.isNotBlank() == true

                    val status = buildJsonObject {
                        put("operation","app-server.status")
                        put("transport",if (response == null) "unavailable" else "ready")
                        put("protocol",if (qualified) "qualified" else "unobserved")
                        put("catalog",if (qualified) "qualified" else "unobserved")
                        put("semantic","unobserved")
                        put("desktop","unqualified")
                        put("enrollment",when (val read = enrollment.read()) {
                            is EnrollmentRead.Read -> when (val value = read.enrollment) {
                                is WorkspaceEnrollment.Enrolled -> JsonPrimitive(value.root.path.toString())
                                else -> JsonNull
                            }
                            is EnrollmentRead.Rejected -> JsonPrimitive("rejected")
                        })
                        put("session",response ?: JsonNull)
                    }
                    AppServerManagementResult.Completed(status)
                }
                AppServerAction.Stop, AppServerAction.Disable -> {
                    val stopped = MacOsPersistentBrokerServiceHost().stop(command)
                    if (stopped != PersistentBrokerServiceAdmission.Ready) return reject(AppServerManagementFailure.SERVICE_OWNERSHIP_UNPROVEN)
                    if (action == AppServerAction.Disable) {
                        if (Files.exists(agent)) {
                            if (Files.isSymbolicLink(agent) || !Files.readString(agent).contains("<!-- Kast App Server login bootstrap v1 -->")) return reject(AppServerManagementFailure.SERVICE_OWNERSHIP_UNPROVEN)
                            Files.delete(agent)
                        }
                        val owned = command.stateDirectory.resolve("desktop-opt-in-owned")
                        if (Files.exists(owned, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                            if (Files.isSymbolicLink(owned) || Files.size(owned) > 1_024) return reject(AppServerManagementFailure.SERVICE_OWNERSHIP_UNPROVEN)
                            val previous = Json.parseToJsonElement(Files.readString(owned)).jsonObject.getValue("previous")
                            val current = readLaunchEnvironment("CODEX_APP_SERVER_USE_LOCAL_DAEMON") ?: return reject(AppServerManagementFailure.LAUNCHCTL_REJECTED)
                            if (current == "1") {
                                val restored = if (previous == JsonNull) launchctl("unsetenv","CODEX_APP_SERVER_USE_LOCAL_DAEMON")
                                    else launchctl("setenv","CODEX_APP_SERVER_USE_LOCAL_DAEMON",previous.jsonPrimitive.content)
                                if (!restored) return reject(AppServerManagementFailure.LAUNCHCTL_REJECTED)
                            }
                            Files.delete(owned)
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

    private fun bootstrap(command: BrokerServiceLaunchCommand): AppServerManagementResult {
        when (val ensured = MacOsPersistentBrokerServiceHost().ensure(command)) {
            PersistentBrokerServiceAdmission.Ready -> Unit
            is PersistentBrokerServiceAdmission.Rejected -> return AppServerManagementResult.Rejected(AppServerManagementFailure.SERVICE_UNAVAILABLE,ensured.failure)
        }
        val previous = readLaunchEnvironment("CODEX_APP_SERVER_USE_LOCAL_DAEMON") ?: return reject(AppServerManagementFailure.LAUNCHCTL_REJECTED)
        val owned = command.stateDirectory.resolve("desktop-opt-in-owned")
        if (Files.isSymbolicLink(owned)) return reject(AppServerManagementFailure.SERVICE_OWNERSHIP_UNPROVEN)
        if (previous != "1") {
            if (!Files.exists(owned)) Files.writeString(owned,buildJsonObject { put("previous",previous.takeIf(String::isNotEmpty)?.let(::JsonPrimitive) ?: JsonNull) }.toString(),java.nio.file.StandardOpenOption.CREATE_NEW)
            if (!launchctl("setenv","CODEX_APP_SERVER_USE_LOCAL_DAEMON","1")) return reject(AppServerManagementFailure.LAUNCHCTL_REJECTED)
        }
        return complete("ready")
    }
    private fun loginAgent(command: BrokerServiceLaunchCommand): String {
        fun escape(raw: String) = raw.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;")
        val arguments = listOf(command.kast.toString(),"app-server","bootstrap").joinToString("") { "<string>${escape(it)}</string>" }
        val env = mapOf("PATH" to command.executableSearchPath.value,"CODEX_EXECUTABLE" to command.codex.path.toString(),"CODEX_HOME" to command.codexHome.toString(),APP_SERVER_ENABLE_ENVIRONMENT to "1")
        return """<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<!-- Kast App Server login bootstrap v1 -->
<plist version="1.0"><dict><key>Label</key><string>${escape(command.serviceLabel.value)}.login</string><key>ProgramArguments</key><array>$arguments</array><key>RunAtLoad</key><true/><key>EnvironmentVariables</key><dict>${env.entries.joinToString("") { "<key>${escape(it.key)}</key><string>${escape(it.value)}</string>" }}</dict></dict></plist>
"""
    }
    private fun rpc(command: BrokerServiceLaunchCommand, method: String, params: JsonObject): JsonObject? = runBlocking {
        kotlinx.coroutines.withTimeoutOrNull(5_000) {
            val connection = (connectCodexUnixWebSocket(command.publicSocket,4 * 1_024 * 1_024,2_000) as? BrokerUpstreamConnectionAdmission.Connected)?.connection ?: return@withTimeoutOrNull null
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
    private fun readLaunchEnvironment(name: String): String? {
        val process = ProcessBuilder("/bin/launchctl","getenv",name).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        if (!process.waitFor(5,TimeUnit.SECONDS)) { process.destroyForcibly(); return null }
        val bytes = process.inputStream.use { it.readNBytes(1_025) }
        if (bytes.size > 1_024 || process.exitValue() !in setOf(0,1)) return null
        return bytes.toString(Charsets.UTF_8).trimEnd('\n','\r')
    }
    private fun launchctl(vararg arguments: String): Boolean {
        val process = ProcessBuilder(listOf("/bin/launchctl") + arguments).redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        if (!process.waitFor(5,TimeUnit.SECONDS)) { process.destroyForcibly(); process.waitFor(2,TimeUnit.SECONDS); return false }
        return process.exitValue() == 0
    }
    private fun complete(status: String) = AppServerManagementResult.Completed(buildJsonObject { put("operation","app-server");put("status",status);put("desktop","unqualified") })
    private fun reject(failure: AppServerManagementFailure) = AppServerManagementResult.Rejected(failure)
}
