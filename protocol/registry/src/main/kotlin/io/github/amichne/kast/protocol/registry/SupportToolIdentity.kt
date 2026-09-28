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
        "Inspect or explicitly control one selected IDEA workspace. Ordinary semantic calls prepare" +
            " the exact project and wait for native readiness automatically; do not use this tool as a " +
            "routine read preflight. For a requested control operation, open, present, release or close" +
            " an exact project. The host owns document save, VFS refresh and model reload selection. Op" +
            "ening is background best effort. Preserve returned host and project identities and reuse r" +
            "equest IDs only for the same operation. Pending work requires status, not repeated open. T" +
            "rust and failed saves require user resolution. Release never closes a project. Borrowed or" +
            " presented projects are protected from agent cleanup; request_user_close requests exact-ta" +
            "rget controller approval.",
        "WorkspaceLifecycleToolInput",
        setOf(SupportToolHost.APP_SERVER),
    ),
    HEALTH_CHECK(
        "health_check",
        "Observe exact workspace binding and saved/indexed readiness. This passive check does not v" +
            "alidate semantic answers.",
        "McpHealthRequest",
        setOf(SupportToolHost.MCP, SupportToolHost.RPC),
    ),
}
