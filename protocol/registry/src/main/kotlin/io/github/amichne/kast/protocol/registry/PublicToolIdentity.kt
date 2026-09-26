// Generated from tools.schema.json by packaging/generate-public-query.py. Do not edit.
package io.github.amichne.kast.protocol.registry

import io.github.amichne.kast.protocol.contract.CanonicalOperation

/** Closed presentation identities; canonical operations retain effect and budget ownership. */
enum class PublicToolIdentity(
    val toolName: String,
    val operation: CanonicalOperation,
    val description: String,
    val loading: HostedToolLoading,
) {
    CHECK_DIAGNOSTICS("check_diagnostics", CanonicalOperation.DIAGNOSTIC_CHECK,
        "Check compiler diagnostics for a file or recursively beneath a directory in the session wo" +
            "rkspace. Use '.' explicitly for the workspace root. This is not symbol search and does not" +
            " build the project. Results retain incomplete-coverage evidence; an incomplete empty resul" +
            "t does not establish that the scope is clean. Requires the saved, indexed IntelliJ state.",
        HostedToolLoading.EAGER,
    ),
    QUERY_SYMBOLS("query_symbols", CanonicalOperation.QUERY_RUN,
        "Run, resume, and read a compositional compiler-grounded Kotlin symbol query. Start from na" +
            "me/scope discovery, exact-symbol references, or selected rows of an immutable retained res" +
            "ult. Apply structured predicates, relation expansion, bounded multi-hop walk, concatenatio" +
            "n, set operations, and explicit deduplication. Choose symbol fields, individual relation o" +
            "ccurrences, or depth-bearing traversal records as output; structured omissions and walk ob" +
            "servations retain incomplete coverage. Retain a completed result when needed for later com" +
            "position; resume uses only the opaque execution continuation, and read_result uses a separ" +
            "ate presentation cursor. Incomplete coverage and bounded work remain explicit.",
        HostedToolLoading.EAGER,
    ),
}
