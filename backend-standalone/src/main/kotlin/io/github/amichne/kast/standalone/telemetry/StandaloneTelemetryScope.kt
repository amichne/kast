package io.github.amichne.kast.standalone.telemetry

internal enum class StandaloneTelemetryScope {
    RENAME,
    CALL_HIERARCHY,
    REFERENCES,
    SYMBOL_RESOLVE,
    WORKSPACE_DISCOVERY,
    ;

    companion object {
        fun parse(rawValue: String): StandaloneTelemetryScope? = when (rawValue.trim().lowercase()) {
            "rename" -> RENAME
            "call-hierarchy", "call_hierarchy", "callhierarchy" -> CALL_HIERARCHY
            "references", "find-references", "find_references" -> REFERENCES
            "symbol-resolve", "symbol_resolve", "symbolresolve", "resolve" -> SYMBOL_RESOLVE
            "workspace-discovery", "workspace_discovery", "workspacediscovery", "discovery" -> WORKSPACE_DISCOVERY
            else -> null
        }
    }
}
