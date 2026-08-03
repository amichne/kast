package io.github.amichne.kast.idea.diagnostics

import com.intellij.notification.NotificationType
import io.github.amichne.kast.api.contract.AnalysisTransport

internal enum class KastIndexState {
    IDLE,
    WAITING_FOR_IDE,
    HYDRATING,
    INDEXING,
    READY,
    DEGRADED,
    FAILED,
    CANCELLED,
}

internal enum class KastActivitySeverity {
    INFO,
    WARNING,
    ERROR,
}

internal enum class KastActivityKind(val displayName: String) {
    BACKEND("Backend"),
    CONFIG("Config"),
    INDEX("Index"),
    OPERATION("Operation"),
}

internal enum class KastBackendOperation(val displayName: String) {
    CAPABILITIES("Capabilities"),
    RUNTIME_STATUS("Runtime status"),
    HEALTH("Health"),
    RESOLVE_SYMBOL("Resolve symbol"),
    FIND_REFERENCES("Find references"),
    CALL_HIERARCHY("Call hierarchy"),
    TYPE_HIERARCHY("Type hierarchy"),
    SEMANTIC_INSERTION_POINT("Semantic insertion"),
    DIAGNOSTICS("Diagnostics"),
    RENAME("Rename"),
    PLAN_REPLACEMENT("Plan replacement"),
    PLAN_ADD_FILE("Plan add file"),
    PLAN_ADD_DECLARATION("Plan add declaration"),
    VERIFY_MUTATION_POSTCONDITION("Verify mutation postcondition"),
    EXACT_FILE_OBSERVATION("Exact file observation"),
    EXACT_FILE_IMAGE_CAS("Exact file-image CAS"),
    MUTATION_SCRATCH_INSPECT("Inspect mutation scratch"),
    MUTATION_SCRATCH_RECOVER("Recover mutation scratch"),
    APPLY_EDITS("Apply edits"),
    OPTIMIZE_IMPORTS("Optimize imports"),
    REFRESH("Refresh"),
    FILE_OUTLINE("File outline"),
    WORKSPACE_SYMBOL_SEARCH("Workspace symbols"),
    WORKSPACE_SEARCH("Workspace search"),
    WORKSPACE_FILES("Workspace files"),
    SEMANTIC_GRAPH("Semantic graph"),
    IMPLEMENTATIONS("Implementations"),
    CODE_ACTIONS("Code actions"),
    COMPLETIONS("Completions"),
}

internal fun AnalysisTransport.displayName(): String = when (this) {
    is AnalysisTransport.UnixDomainSocket -> "uds:${socketPath.fileName}"
    AnalysisTransport.Stdio -> "stdio"
    is AnalysisTransport.Tcp -> "tcp:$host:$port"
}

internal fun KastActivitySeverity.toNotificationType(): NotificationType = when (this) {
    KastActivitySeverity.INFO -> NotificationType.INFORMATION
    KastActivitySeverity.WARNING -> NotificationType.WARNING
    KastActivitySeverity.ERROR -> NotificationType.ERROR
}

internal fun Throwable.compactMessage(): String = message?.takeIf(String::isNotBlank) ?: javaClass.simpleName
