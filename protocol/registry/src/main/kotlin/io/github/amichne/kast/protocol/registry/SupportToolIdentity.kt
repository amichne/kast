// Generated from tools.schema.json by packaging/generate-public-query.py. Do not edit.
package io.github.amichne.kast.protocol.registry

enum class SupportToolHost {
    APP_SERVER,
    MCP,
    RPC,
}

enum class SupportToolIdentity(
    val toolName: String,
    val description: String,
    val binding: String,
    val hosts: Set<SupportToolHost>,
) {
    WORKSPACE_LIFECYCLE(
        "workspace_lifecycle",
        "Inspect or explicitly control one selected IDEA workspace. Ordinary semantic calls prepare the exa" +
            "ct project and wait for native readiness automatically; do not use this tool as a routine read pre" +
            "flight. For a requested control operation, open, present, release or close an exact project. The h" +
            "ost owns document save, VFS refresh and model reload selection. Opening is background best effort." +
            " Preserve returned host and project identities and reuse request IDs only for the same operation. " +
            "Pending work requires status, not repeated open. Trust and failed saves require user resolution. R" +
            "elease never closes a project. Borrowed or presented projects are protected from agent cleanup; re" +
            "quest_user_close requests exact-target controller approval.",
        "WorkspaceLifecycleToolInput",
        setOf(SupportToolHost.APP_SERVER),
    ),
    HEALTH_CHECK(
        "health_check",
        "Observe exact workspace binding and saved/indexed readiness. This passive check does not validate " +
            "semantic answers.",
        "McpHealthRequest",
        setOf(SupportToolHost.MCP, SupportToolHost.RPC),
    ),
}
