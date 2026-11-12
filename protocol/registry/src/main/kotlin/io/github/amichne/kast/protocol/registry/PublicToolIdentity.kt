// Generated from tools.schema.json by packaging/generate-public-query.py. Do not edit.
package io.github.amichne.kast.protocol.registry

import io.github.amichne.kast.protocol.contract.CanonicalOperation

const val PUBLIC_TOOL_CONTRACT_VERSION = 19
enum class PublicToolCatalog { COMMON, COMPLETE }

const val PUBLIC_COMMON_QUERY_DESCRIPTION =
    "Choose upfront: declaration selection returns symbols without implicit expansion; EXPAND_RELATION " +
            "follows one selected compiler-confirmed hop; TRACE uses a fixed affected-use recipe (references, i" +
            "mplementations or overrides, direct callers and one further caller layer), returning grouped symbo" +
            "ls. TRACE does not track runtime values. Custom-depth WALK and forward value IMPACT require the CO" +
            "MPLETE catalog; COMMON rejects them without promotion. READ_RESULT and RESUME deliver existing evi" +
            "dence; READ_SOURCE reads issued source selectors. Select exact overloads, copy issued references u" +
            "nchanged, and preserve original authority, freshness, completion and recovery evidence. A presenta" +
            "tion page does not prove semantic completion."

const val PUBLIC_TOOL_NAMESPACE_DESCRIPTION = "Compiler-grounded Kotlin source intelligence from Kast."

/** Closed presentation identities; canonical operations retain effect and budget ownership. */
enum class PublicToolIdentity(
    val toolName: String,
    val operation: CanonicalOperation,
    val description: String,
    val loading: HostedToolLoading,
) {
    QUERY_SYMBOLS("query_symbols", CanonicalOperation.QUERY_RUN,
        "Choose the operation before execution. QUERY is the pipeline envelope, not a separate analysis alg" +
            "orithm.\n\nSELECT: use SEARCH_DECLARATIONS, ALL_DECLARATIONS, AT_LOCATION or issued SYMBOL_REFS to" +
            " return declarations; no relationship expansion is implicit.\n\nRELATION/TRAVERSAL: EXPAND_RELATIO" +
            "N follows one compiler-confirmed relationship hop; WALK repeats one selected relation to an explic" +
            "it maximumDepth and returns depth-bearing TRAVERSAL_RECORDS. CALLERS follows incoming calls; CALLE" +
            "ES follows outgoing calls, including constructors.\n\nTRACE: a fixed affected-use recipe over exac" +
            "t declarations: references, implementations or overrides, direct callers, and one further caller l" +
            "ayer. It returns grouped SYMBOLS with proven connections and occurrences; it has no arbitrary dept" +
            "h control and does not follow runtime values.\n\nIMPACT: a forward Kotlin value investigation from" +
            " an exact producer invocation, requiring enclosing and callable refs plus the whole invocation ran" +
            "ge. It returns VALUE_PATHS or IMPACT_WITNESS; model-assisted transfers and unresolved boundaries k" +
            "eep their qualifications. It is not a general declaration dependency walk.\n\nExpansion scope rest" +
            "ricts destinations independently of discovery scope. None of these proves runtime behavior or unre" +
            "stricted transitive reachability.\n\nREAD_RESULT and RESUME only deliver produced evidence; READ_S" +
            "OURCE reads source under an issued selector. Use simple exact-name flags for discovery; use one JS" +
            "ON RUN request for WALK or IMPACT. Inspect only the needed syntax after choosing the operation.\n" +
            "\nEvery RUN uses COMPLETE_ONLY under COMPILER_RESOLVED_STATIC_V1. Preserve incomplete verdicts and" +
            " recovery evidence. Presentation limits do not reduce the semantic question. Copy issued reference" +
            "s unchanged; inspect overload signatures before exact selection.",
        HostedToolLoading.DEFERRED,
    ),
    CHECK_DIAGNOSTICS("check_diagnostics", CanonicalOperation.DIAGNOSTIC_CHECK,
        "Check compiler diagnostics for a file or recursively beneath a directory in the session workspace." +
            " Use '.' explicitly for the workspace root. This is not symbol search and does not build the proje" +
            "ct. Results retain incomplete-coverage evidence; an incomplete empty result does not establish tha" +
            "t the scope is clean. Requires the saved, indexed IntelliJ state.",
        HostedToolLoading.EAGER,
    ),
    ADD_DECLARATION("add_declaration", CanonicalOperation.CHANGE,
        "Add one declaration at an exact top-level Kotlin target. A class or object target inserts into its" +
            " existing body. A function, property, or type alias target inserts after that declaration. Kast pl" +
            "ans and applies the change, examines the resulting source, and reports a verified receipt or finit" +
            "e failure with recovery evidence. Preserve the returned result. A cancelled or missing response do" +
            "es not prove that no write occurred.",
        HostedToolLoading.DEFERRED,
    ),
    REPLACE_BODY("replace_body", CanonicalOperation.CHANGE,
        "Replace only the block body of one existing, non-inline named Kotlin function selected by an exact" +
            " Kast symbol reference. Kast plans, applies, verifies, and reports a verified receipt or finite fa" +
            "ilure with recovery evidence. The signature and surrounding source are preserved.",
        HostedToolLoading.DEFERRED,
    ),
    ;

    fun descriptionFor(catalog: PublicToolCatalog): String =
        if (this == QUERY_SYMBOLS && catalog == PublicToolCatalog.COMMON) PUBLIC_COMMON_QUERY_DESCRIPTION
        else description
}
