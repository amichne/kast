package io.github.amichne.kast.standalone.telemetry

internal enum class StandaloneTelemetryScope {
    RENAME,
    CALL_HIERARCHY,
    REFERENCES,
    SYMBOL_RESOLVE,
    FILE_OUTLINE,
    WORKSPACE_SYMBOL_SEARCH,
    WORKSPACE_FILES,
    WORKSPACE_DISCOVERY,
    SESSION_LOCK,
    SESSION_LIFECYCLE,
    INDEXING,
    ;

    companion object {
        fun parse(rawValue: String): StandaloneTelemetryScope? = when (rawValue.trim().lowercase()) {
            "rename" -> RENAME
            "call-hierarchy", "call_hierarchy", "callhierarchy" -> CALL_HIERARCHY
            "references", "find-references", "find_references" -> REFERENCES
            "symbol-resolve", "symbol_resolve", "symbolresolve", "resolve" -> SYMBOL_RESOLVE
            "file-outline", "file_outline", "fileoutline", "outline" -> FILE_OUTLINE
            "workspace-symbol", "workspace_symbol", "workspacesymbol" -> WORKSPACE_SYMBOL_SEARCH
            "workspace-files", "workspace_files", "workspacefiles" -> WORKSPACE_FILES
            "workspace-discovery", "workspace_discovery", "workspacediscovery", "discovery" -> WORKSPACE_DISCOVERY
            "session-lock", "session_lock", "sessionlock", "lock" -> SESSION_LOCK
            "session-lifecycle", "session_lifecycle", "sessionlifecycle", "lifecycle" -> SESSION_LIFECYCLE
            "indexing", "index" -> INDEXING
            else -> null
        }
    }
}
