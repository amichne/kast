// Generated from tools.schema.json by packaging/generate-public-query.py. Do not edit.
package io.github.amichne.kast.protocol.registry

import io.github.amichne.kast.protocol.contract.CanonicalOperation

const val PUBLIC_TOOL_CONTRACT_VERSION = 2
const val PUBLIC_TOOL_NAMESPACE_DESCRIPTION = "Compiler-grounded Kotlin source intelligence from Kast."

/** Closed presentation identities; canonical operations retain effect and budget ownership. */
enum class PublicToolIdentity(
    val toolName: String,
    val operation: CanonicalOperation,
    val description: String,
    val loading: HostedToolLoading,
) {
    QUERY_SYMBOLS("query_symbols", CanonicalOperation.QUERY_RUN,
        "Run, resume, and read one compositional compiler-grounded Kotlin symbol query. For exact n" +
            "ame search, use {\"request\":{\"type\":\"RUN\",\"source\":{\"type\":\"SEARCH_DECLARATIONS\",\"declarati" +
            "onName\":\"Order\"}}}; source is directly inside request. For one-hop references, add \"steps\"" +
            ":[{\"type\":\"WALK\",\"relation\":\"REFERENCES\",\"maximumDepth\":1}] inside RUN. To keep the result" +
            ", set request.retention to RETAIN; then page its returned reference with request.type READ" +
            "_RESULT and request.result. Resume execution with request.type RESUME and its exact contin" +
            "uation. Apply structured predicates, relation expansion, bounded walk, set composition, an" +
            "d retained binding projection and joins. Join preserves both named output cells and occurr" +
            "ence evidence; anti-join requires complete right coverage. Choose symbol, occurrence, trav" +
            "ersal record, or binding row output. Retained results preserve qualification and omissions" +
            "; execution continuation and result presentation cursor remain distinct.",
        HostedToolLoading.EAGER,
    ),
    READ_SOURCE("read_source", CanonicalOperation.SOURCE_READ,
        "Read bounded Kotlin source and structural context for an exact Kast symbol reference. Sele" +
            "ct a region, text window, structural entities, and continuation without changing source. P" +
            "reserve references and coverage in the result.",
        HostedToolLoading.DEFERRED,
    ),
    CHECK_DIAGNOSTICS("check_diagnostics", CanonicalOperation.DIAGNOSTIC_CHECK,
        "Check compiler diagnostics for a file or recursively beneath a directory in the session wo" +
            "rkspace. Use '.' explicitly for the workspace root. This is not symbol search and does not" +
            " build the project. Results retain incomplete-coverage evidence; an incomplete empty resul" +
            "t does not establish that the scope is clean. Requires the saved, indexed IntelliJ state.",
        HostedToolLoading.EAGER,
    ),
    ADD_DECLARATION("add_declaration", CanonicalOperation.CHANGE,
        "Add one declaration to an existing Kotlin source file selected by an exact Kast symbol ref" +
            "erence. Kast plans, applies, verifies, and reports a verified receipt or finite failure wi" +
            "th recovery evidence. Preserve the returned result; a cancelled or missing response does n" +
            "ot prove no write occurred.",
        HostedToolLoading.DEFERRED,
    ),
}
