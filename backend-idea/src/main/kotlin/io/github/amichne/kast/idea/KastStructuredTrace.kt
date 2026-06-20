package io.github.amichne.kast.idea

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import io.github.amichne.kast.api.client.WorkspaceIdentity
import java.nio.file.Path
import java.util.UUID

internal data class KastStructuredTraceFields(
    val invocationId: String? = null,
    val parentInvocationId: String? = null,
    val agentRole: String? = null,
    val agentInstanceId: String? = null,
    val reviewInvocationId: String? = null,
    val sdkRegistrationScope: String? = null,
    val targetFilePath: String? = null,
    val moduleName: String? = null,
    val gradleProjectPath: String? = null,
)

internal object KastStructuredTrace {
    private val LOG = Logger.getInstance(KastStructuredTrace::class.java)

    fun newInvocationId(): String = UUID.randomUUID().toString()

    fun isEnabled(): Boolean =
        System.getProperty("kast.idea.trace").isTruthy() || System.getenv("KAST_IDEA_TRACE").isTruthy()

    fun event(
        eventName: String,
        project: Project? = null,
        workspaceRoot: Path? = project?.basePath?.let(Path::of),
        fields: KastStructuredTraceFields = KastStructuredTraceFields(),
        outcome: String? = null,
        detail: Map<String, Any?> = emptyMap(),
    ) {
        if (!isEnabled()) return
        LOG.info(
            traceJson(
                eventName = eventName,
                workspaceRoot = workspaceRoot,
                ideaProjectName = project?.name,
                ideaProjectBasePath = project?.basePath,
                fields = fields,
                outcome = outcome,
                detail = detail,
            ),
        )
    }

    fun traceJson(
        eventName: String,
        workspaceRoot: Path?,
        ideaProjectName: String? = null,
        ideaProjectBasePath: String? = null,
        fields: KastStructuredTraceFields = KastStructuredTraceFields(),
        outcome: String? = null,
        detail: Map<String, Any?> = emptyMap(),
        processId: Long = ProcessHandle.current().pid(),
        threadName: String = Thread.currentThread().name,
    ): String {
        val canonicalWorkspaceRoot = workspaceRoot?.canonicalPathString()
        val workspaceIdentity = workspaceRoot?.let { root ->
            runCatching { WorkspaceIdentity.fromWorkspaceRoot(root) }.getOrNull()
        }
        val canonicalTargetFilePath = fields.targetFilePath?.let(::canonicalPathStringOrFallback)
        val record = linkedMapOf<String, Any?>(
            "type" to "kast.idea.trace",
            "schemaVersion" to 1,
            "eventName" to eventName,
            "invocationId" to fields.invocationId,
            "parentInvocationId" to fields.parentInvocationId,
            "agentRole" to fields.agentRole,
            "agentInstanceId" to fields.agentInstanceId,
            "reviewInvocationId" to fields.reviewInvocationId,
            "workspaceId" to workspaceIdentity?.workspaceId?.value,
            "canonicalWorkspaceId" to workspaceIdentity?.canonicalWorkspaceId?.value,
            "workspaceRoot" to workspaceRoot?.toAbsolutePath()?.normalize()?.toString(),
            "canonicalWorkspaceRoot" to canonicalWorkspaceRoot,
            "workspaceDataDirectory" to workspaceIdentity?.workspaceDataDirectory?.value,
            "workspaceCacheDirectory" to workspaceIdentity?.workspaceCacheDirectory?.value,
            "sourceIndexDatabasePath" to workspaceIdentity?.sourceIndexDatabasePath?.value,
            "defaultSocketPath" to workspaceIdentity?.defaultSocketPath?.value,
            "gradleRoot" to workspaceIdentity?.gradleRoot?.root?.value,
            "gradleSettingsFile" to workspaceIdentity?.gradleRoot?.settingsFile?.value,
            "gradleSettingsFileHash" to workspaceIdentity?.gradleRoot?.settingsFileHash?.value,
            "ideaProjectName" to ideaProjectName,
            "ideaProjectBasePath" to ideaProjectBasePath,
            "processId" to processId,
            "threadName" to threadName,
            "sdkRegistrationScope" to fields.sdkRegistrationScope,
            "targetFilePath" to fields.targetFilePath,
            "canonicalTargetFilePath" to canonicalTargetFilePath,
            "moduleName" to fields.moduleName,
            "gradleProjectPath" to fields.gradleProjectPath,
            "outcome" to outcome,
            "detail" to detail,
        )
        return record.toJsonObject()
    }
}

private fun String?.isTruthy(): Boolean = when (this?.trim()?.lowercase()) {
    "1", "true", "yes", "on" -> true
    else -> false
}

private fun Path.canonicalPathString(): String =
    runCatching { toRealPath().toString() }.getOrElse { toAbsolutePath().normalize().toString() }

private fun canonicalPathStringOrFallback(rawPath: String): String =
    runCatching { Path.of(rawPath).canonicalPathString() }.getOrElse { rawPath }

private fun Map<String, Any?>.toJsonObject(): String =
    entries.joinToString(prefix = "{", postfix = "}") { (key, value) ->
        "${key.jsonString()}:${value.toJsonValue()}"
    }

private fun Iterable<*>.toJsonArray(): String =
    joinToString(prefix = "[", postfix = "]") { value -> value.toJsonValue() }

private fun Any?.toJsonValue(): String = when (this) {
    null -> "null"
    is String -> jsonString()
    is Number, is Boolean -> toString()
    is Map<*, *> -> entries.joinToString(prefix = "{", postfix = "}") { entry ->
        "${entry.key.toString().jsonString()}:${entry.value.toJsonValue()}"
    }
    is Iterable<*> -> toJsonArray()
    is Array<*> -> asIterable().toJsonArray()
    else -> toString().jsonString()
}

private fun String.jsonString(): String = buildString(length + 2) {
    append('"')
    for (char in this@jsonString) {
        when (char) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (char.code < 0x20) {
                append("\\u")
                append(char.code.toString(16).padStart(4, '0'))
            } else {
                append(char)
            }
        }
    }
    append('"')
}
