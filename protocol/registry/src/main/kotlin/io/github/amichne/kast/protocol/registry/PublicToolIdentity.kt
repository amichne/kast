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
        "Run, resume, and read one compositional compiler-grounded Kotlin symbol query. Start from " +
            "declaration discovery, a containing declaration at file offset, exact references, or immut" +
            "able retained rows. Apply structured predicates, relation expansion, bounded walk, set com" +
            "position, and retained binding projection and joins. Join matches canonical symbol identit" +
            "y and preserves both named output cells, multiplicity, and occurrence evidence; anti-join " +
            "requires complete right coverage. Choose symbol, occurrence, traversal record, or binding " +
            "row output. Retained results preserve qualification and omissions; execution continuation " +
            "and result presentation cursor remain distinct.",
        HostedToolLoading.EAGER,
    ),
}
