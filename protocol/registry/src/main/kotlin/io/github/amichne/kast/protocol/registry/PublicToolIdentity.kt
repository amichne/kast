// Generated from tools.schema.json by packaging/generate-public-query.py. Do not edit.
package io.github.amichne.kast.protocol.registry

import io.github.amichne.kast.protocol.contract.CanonicalOperation

const val PUBLIC_TOOL_CONTRACT_VERSION = 5
const val PUBLIC_TOOL_NAMESPACE_DESCRIPTION = "Compiler-grounded Kotlin source intelligence from Kast."

/** Closed presentation identities; canonical operations retain effect and budget ownership. */
enum class PublicToolIdentity(
    val toolName: String,
    val operation: CanonicalOperation,
    val description: String,
    val loading: HostedToolLoading,
) {
    QUERY_SYMBOLS("query_symbols", CanonicalOperation.QUERY_RUN,
        "Run, resume, and read one compositional compiler-grounded Kotlin symbol query. For exact name sear" +
            "ch, use {\"request\":{\"type\":\"RUN\",\"source\":{\"type\":\"SEARCH_DECLARATIONS\",\"declarationN" +
            "ame\":\"Order\"}}}; source is directly inside request. For remembered words, use source {\"type\":" +
            "\"SEARCH_TEXT\",\"word\":\"launchd\",\"scope\":{\"type\":\"DIRECTORY\",\"relativeDirectoryPath\":" +
            "\"app-server\"}} to receive exact containing-declaration refs and bounded lexical matches. This wo" +
            "rd mode is case-sensitive and excludes regex and qualified literals. For one-hop references, add " +
            "\"steps\":[{\"type\":\"WALK\",\"relation\":\"REFERENCES\",\"maximumDepth\":1}] inside RUN. To keep" +
            " the result, set request.retention to RETAIN; then page its returned reference with request.type R" +
            "EAD_RESULT and request.result. Resume execution with request.type RESUME and its exact continuatio" +
            "n. Apply structured predicates, relation expansion, bounded walk, set composition, and retained bi" +
            "nding projection and joins. Join preserves both named output cells and occurrence evidence; anti-j" +
            "oin requires complete right coverage. Choose symbol, occurrence, traversal record, or binding row " +
            "output. Retained results preserve qualification and omissions; execution continuation and result p" +
            "resentation cursor remain distinct. Discovery observations preserve the declared universe, phase c" +
            "ounts, coverage and unfinished input. Reference occurrence rows retain compiler target identity an" +
            "d explicit declaration-owned, file-scoped or unavailable ownership. A published execution page rep" +
            "lays idempotently; concurrent use of one checkpoint is rejected with continuation-in-use until its" +
            " owner publishes or drains. Set steps[].expansionScope to WORKSPACE, RETAINED_SEED, or SOURCE_DOMA" +
            "IN to control native relation destinations separately from source discovery and output predicates." +
            " Inspect a callback occurrence, anonymous callable body, or proof declaration with request {\"type" +
            "\":\"READ_SOURCE\",\"candidateRef\":\"<exact issued candidate reference>\"}; this returns the exis" +
            "ting source.read document for that exact range, preserving freshness and authority failures.",
        HostedToolLoading.EAGER,
    ),
    CHECK_DIAGNOSTICS("check_diagnostics", CanonicalOperation.DIAGNOSTIC_CHECK,
        "Check compiler diagnostics for a file or recursively beneath a directory in the session workspace." +
            " Use '.' explicitly for the workspace root. This is not symbol search and does not build the proje" +
            "ct. Results retain incomplete-coverage evidence; an incomplete empty result does not establish tha" +
            "t the scope is clean. Requires the saved, indexed IntelliJ state.",
        HostedToolLoading.EAGER,
    ),
    ADD_DECLARATION("add_declaration", CanonicalOperation.CHANGE,
        "Add one declaration to an existing Kotlin source file selected by an exact Kast symbol reference. " +
            "Kast plans, applies, verifies, and reports a verified receipt or finite failure with recovery evid" +
            "ence. Preserve the returned result; a cancelled or missing response does not prove no write occurr" +
            "ed.",
        HostedToolLoading.DEFERRED,
    ),
    REPLACE_BODY("replace_body", CanonicalOperation.CHANGE,
        "Replace only the block body of one existing, non-inline named Kotlin function selected by an exact" +
            " Kast symbol reference. Kast plans, applies, verifies, and reports a verified receipt or finite fa" +
            "ilure with recovery evidence. The signature and surrounding source are preserved.",
        HostedToolLoading.DEFERRED,
    ),
}
