package io.github.amichne.kast.appserver.runtime

/** Protocol method sets that govern controller routing and read-only access. */
internal object BrokerSessionMethodPolicy {
    val approvalMethods =
        setOf(
            "item/commandExecution/requestApproval",
            "item/fileChange/requestApproval",
            "item/permissions/requestApproval",
            "item/tool/requestUserInput",
            "mcpServer/elicitation/request",
            "execCommandApproval",
            "applyPatchApproval",
        )
    val readMethods =
        setOf(
            "thread/read",
            "thread/resume",
            "thread/fork",
            "thread/unsubscribe",
            "thread/items/list",
            "thread/turns/list",
            "thread/goal/get",
        )
}
