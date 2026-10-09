// Generated from tools.schema.json by packaging/generate-public-query.py. Do not edit.
package io.github.amichne.kast.protocol.registry

import io.github.amichne.kast.protocol.contract.CanonicalOperation

const val PUBLIC_TOOL_CONTRACT_VERSION = 14
const val PUBLIC_TOOL_NAMESPACE_DESCRIPTION = "Compiler-grounded Kotlin source intelligence from Kast."

/** Closed presentation identities; canonical operations retain effect and budget ownership. */
enum class PublicToolIdentity(
    val toolName: String,
    val operation: CanonicalOperation,
    val description: String,
    val loading: HostedToolLoading,
) {
    QUERY_SYMBOLS("query_symbols", CanonicalOperation.QUERY_RUN,
        "Run, resume, and read compiler-grounded Kotlin queries in the bound workspace. Start with request " +
            "{\"type\":\"RUN\",\"source\":{\"type\":\"SEARCH_DECLARATIONS\",\"declarationName\":\"Order\",\"dec" +
            "larationKinds\":[\"CLASS\"],\"scope\":{\"type\":\"DIRECTORY\",\"relativeDirectoryPath\":\"orders\"" +
            ",\"sourceSetNames\":[\"main\"]}}}. Select by signature and location, then pass the unchanged issue" +
            "d ref in source {\"type\":\"SYMBOL_REFS\",\"symbolRefs\":[\"<issued ref>\"]}. To trace affected us" +
            "es, add steps [{\"type\":\"TRACE\",\"expansionScope\":{\"type\":\"WORKSPACE\"}}] and SYMBOLS outpu" +
            "t. TRACE covers references, implementations or overrides, direct callers, and one further caller l" +
            "ayer; its topology is fixed. Discovery scope selects the seed; expansionScope selects relation des" +
            "tinations. SOURCE_DOMAIN restricts native destinations before enumeration. Every run requires COMP" +
            "LETE_ONLY within COMPILER_RESOLVED_STATIC_V1. Preserve typed rejection and recovery evidence when " +
            "completion is unproven. Use RESUME only with an issued output continuation, READ_RESULT only with " +
            "a retained result reference and its cursor, and READ_SOURCE only with an issued candidateSelector " +
            "as candidateRef. These capabilities are distinct. Set retention RETAIN for later presentation. Bud" +
            "gets share one admitted grant and remain subject to host ceilings. The schema also defines one-hop" +
            " EXPAND_RELATION, graph walk with WALK, predicates, retained composition, joins, and value IMPACT;" +
            " choose them only for those specific questions.",
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
