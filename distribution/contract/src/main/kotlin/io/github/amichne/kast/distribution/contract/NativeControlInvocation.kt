package io.github.amichne.kast.distribution.contract

import io.github.amichne.kast.kernel.Refinement

enum class NativeControlEntry(val selector: String) {
    MANAGEMENT("management"),
    QUERY("query"),
    MCP("mcp"),
    RPC("rpc"),
    SERVICE("service"),
    DAEMON("daemon"),
    CODEX("codex"),
    INSTALLER("installer"),
}

data class NativeControlInvocation(val entry: NativeControlEntry, val arguments: List<String>)

enum class NativeControlFailure {
    ENTRYPOINT_REJECTED
}

fun admitNativeControlInvocation(arguments: List<String>): Refinement<NativeControlInvocation, NativeControlFailure> =
    when (val first = arguments.firstOrNull()) {
        null -> Refinement.Refined(NativeControlInvocation(NativeControlEntry.MANAGEMENT, arguments))
        else ->
            if (first.startsWith("--internal-entrypoint=")) {
                val selector = first.removePrefix("--internal-entrypoint=")
                when (val entry = NativeControlEntry.entries.find { it.selector == selector }) {
                    null -> Refinement.Rejected(NativeControlFailure.ENTRYPOINT_REJECTED)
                    else -> Refinement.Refined(NativeControlInvocation(entry, arguments.drop(1)))
                }
            } else Refinement.Refined(NativeControlInvocation(NativeControlEntry.MANAGEMENT, arguments))
    }
