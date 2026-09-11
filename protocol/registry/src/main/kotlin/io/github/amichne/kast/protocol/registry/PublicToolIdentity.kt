// Generated from tools.schema.json by packaging/generate-public-query.py. Do not edit.
package io.github.amichne.kast.protocol.registry

import io.github.amichne.kast.protocol.contract.CanonicalOperation

/** Closed presentation identities; canonical operations retain effect and budget ownership. */
enum class PublicToolIdentity(val toolName: String, val operation: CanonicalOperation, val description: String, val loading: HostedToolLoading) {
    SEARCH_CLASSES("search_classes", CanonicalOperation.QUERY_RUN,
        "Find named class-like declarations supported by Kast. This searches declaration names, not use sites or source text. Constructors are not included. Returns names, locations, signatures, opaque symbol references, and completeness evidence. Uses the session workspace's saved, indexed IntelliJ state; unavailable or unready hosts reject the read. No hidden fuzzy retry or scope widening.", HostedToolLoading.EAGER),
    SEARCH_FUNCTIONS("search_functions", CanonicalOperation.QUERY_RUN,
        "Find named functions and methods. This searches declaration names, not call sites or source text. Returns every matching overload separately. Returns names, locations, signatures, opaque symbol references, and completeness evidence. Uses the session workspace's saved, indexed IntelliJ state; unavailable or unready hosts reject the read. No hidden fuzzy retry or scope widening.", HostedToolLoading.EAGER),
    SEARCH_DECLARATIONS("search_declarations", CanonicalOperation.QUERY_RUN,
        "Find named declarations when the kind is unknown or when searching properties or type aliases. Prefer search_classes or search_functions when that kind is known. Not a source-text or usage search. Returns names, locations, signatures, opaque symbol references, and completeness evidence. Uses the session workspace's saved, indexed IntelliJ state; unavailable or unready hosts reject the read. No hidden fuzzy retry or scope widening.", HostedToolLoading.EAGER),
    CHECK_DIAGNOSTICS("check_diagnostics", CanonicalOperation.DIAGNOSTIC_CHECK,
        "Check compiler diagnostics for a file or recursively beneath a directory in the session workspace. Use '.' explicitly for the workspace root. This is not symbol search and does not build the project. Results retain incomplete-coverage evidence; an incomplete empty result does not establish that the scope is clean. Requires the saved, indexed IntelliJ state.", HostedToolLoading.EAGER),
    QUERY_SYMBOLS("query_symbols", CanonicalOperation.QUERY_RUN,
        "Advanced ordered symbol pipelines: search or enumerate declarations, start from returned references, filter visibility, expand semantic relations, and explicitly deduplicate. Prefer the dedicated search tools for ordinary name searches. Source restrictions do not constrain expansion destinations. Compiler identity, bounded work, and completeness remain owned by Kast. Does not accept diagnostic steps.", HostedToolLoading.DEFERRED),
}
