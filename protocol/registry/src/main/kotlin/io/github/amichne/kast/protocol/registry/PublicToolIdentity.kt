// Generated from tools.schema.json by packaging/generate-public-query.py. Do not edit.
package io.github.amichne.kast.protocol.registry

import io.github.amichne.kast.protocol.contract.CanonicalOperation

const val PUBLIC_TOOL_CONTRACT_VERSION = 17
const val PUBLIC_TOOL_NAMESPACE_DESCRIPTION = "Compiler-grounded Kotlin source intelligence from Kast."

/** Closed presentation identities; canonical operations retain effect and budget ownership. */
enum class PublicToolIdentity(
    val toolName: String,
    val operation: CanonicalOperation,
    val description: String,
    val loading: HostedToolLoading,
) {
    QUERY_SYMBOLS("query_symbols", CanonicalOperation.QUERY_RUN,
        "Run, resume, and read compiler-grounded Kotlin queries in the bound workspace. For RUN, provide a " +
            "source, zero or more nonterminal steps, and exactly one terminalStep object tagged EMIT or TRACE. " +
            "Put output in terminalStep.output. EMIT accepts all output choices. TRACE accepts only symbols and" +
            " owns expansionScope directly. Never put TRACE in steps or output on RUN. Apply known name, kind, " +
            "scope, and source-set constraints before discovery. Start with request {\"type\":\"RUN\",\"source" +
            "\":{\"type\":\"SEARCH_DECLARATIONS\",\"declarationName\":\"Order\",\"declarationKinds\":[\"CLASS\"" +
            "],\"scope\":{\"type\":\"DIRECTORY\",\"relativeDirectoryPath\":\"orders\",\"sourceSetNames\":[\"mai" +
            "n\"]}},\"terminalStep\":{\"type\":\"EMIT\"}}. Select the exact owner, receiver, signature, and loc" +
            "ation, never the first overload. Pass its unchanged issued ref in source {\"type\":\"SYMBOL_REFS\"" +
            ",\"symbolRefs\":[\"<issued ref>\"]}. Use EXPAND_RELATION for direct references or callers. Use TRA" +
            "CE for references, implementations or overrides, direct callers, and one further caller layer. Dis" +
            "covery scope selects seeds. Expansion scope selects destinations. SOURCE_DOMAIN applies source-set" +
            ", production/test, and generated-source constraints before enumeration. If the host supports tool " +
            "composition, chain discovery, exact selection, and expansion inside code. Keep successful intermed" +
            "iate results inside that call. Return task findings with scope, completion, omissions, and recover" +
            "y facts. Do not omit requested matches or hide failures to reduce tokens. Apply path exclusions be" +
            "fore read batches. Remove duplicate paths. Reuse unchanged source reads until a source change or f" +
            "reshness rejection. Inspect unresolved imports, companion qualifiers, and unmatched source sites b" +
            "efore a complete source-use answer. Every RUN uses COMPLETE_ONLY within COMPILER_RESOLVED_STATIC_V" +
            "1. Preserve typed rejection and recovery evidence when completion is unproven. Use RESUME only wit" +
            "h an issued output continuation, READ_RESULT with an issued result reference and cursor, and READ_" +
            "SOURCE with an issued candidateSelector as candidateRef. Keep these capabilities distinct. Set ret" +
            "ention RETAIN for later presentation. Budgets share one admitted grant under host ceilings. The sc" +
            "hema also defines graph walk with WALK, predicates, retained composition, joins, and value IMPACT." +
            " Use known request shapes directly. Read only the needed schema section or named example for unfam" +
            "iliar shapes. Stop when evidence answers the task.",
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
