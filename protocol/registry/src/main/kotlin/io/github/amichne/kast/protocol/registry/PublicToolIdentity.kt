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
        "Search or enumerate Kotlin declarations by name, kind and scope; start from returned exact" +
            " references; filter visibility, expand relations, or deduplicate in ordered steps. Exact n" +
            "ame matching is default; fuzzy requires explicit opt-in. Use symbol_lookup when candidate " +
            "discovery by file, location, name or source text is required. Source restrictions do not c" +
            "onstrain expansion destinations. Compiler identity, bounded work, and completeness remain " +
            "owned by Kast.",
        HostedToolLoading.EAGER,
    ),
}
