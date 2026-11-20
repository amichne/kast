package io.github.amichne.kast.cli.publicshell

import kotlinx.serialization.Required
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

// These DTOs only encode flag ingress. They establish no semantic validity or authority.
@Serializable internal data class CliEnvelope(val request: CliAction, val verbose: Boolean = false)

@Serializable
internal data class CliDiagnosticInput(
    val relativePath: String,
    val maxDiagnostics: Long?,
    val continuation: String?,
    val executionBudget: CliBudget?,
    val verbose: Boolean,
)

@Serializable
internal sealed interface CliAction {
    @Serializable
    @SerialName("RUN")
    data class Run(
        val source: CliSource,
        val steps: List<CliStep>?,
        val terminalStep: CliTerminal,
        val retention: CliRetention?,
        val executionBudget: CliBudget?,
    ) : CliAction

    @Serializable
    @SerialName("READ_RESULT")
    data class ReadResult(
        val result: String,
        val cursor: Long?,
        @SerialName("evidence_cursor") val evidenceCursor: Long?,
        val output: CliOutput?,
        val executionBudget: CliBudget?,
        /** An issued detail selector, copied unchanged; public tool admission owns its schema and authority. */
        val detail: JsonObject? = null,
    ) : CliAction

    @Serializable
    @SerialName("RESUME")
    data class Resume(val continuation: String, val executionBudget: CliBudget?) : CliAction

    @Serializable
    @SerialName("READ_SOURCE")
    data class ReadSource(
        val candidateRef: String,
        val region: CliRegion?,
        val text: CliText?,
        val executionBudget: CliBudget?,
    ) : CliAction
}

@Serializable
internal sealed interface CliSource {
    @Serializable
    @SerialName("SEARCH_DECLARATIONS")
    data class Search(
        val declarationName: String,
        val nameMatch: CliNameMatch?,
        val declarationKinds: List<CliKind>?,
        val scope: CliDirectory?,
    ) : CliSource

    @Serializable
    @SerialName("IMPACT")
    data class Impact(
        val seeds: List<io.github.amichne.kast.protocol.contract.QueryImpactProducerDocument>,
        @Required val domain: CliWorkspaceScope = CliWorkspaceScope(),
        @Required
        val flow: io.github.amichne.kast.protocol.contract.QueryImpactFlowDocument =
            io.github.amichne.kast.protocol.contract.QueryImpactFlowDocument.KOTLIN_FORWARD_V1,
        @Required
        val declarations: List<io.github.amichne.kast.protocol.contract.QueryImpactDeclarationDocument> = emptyList(),
        @Required val models: List<io.github.amichne.kast.protocol.contract.ImpactRequestedModelDocument> = emptyList(),
    ) : CliSource

    @Serializable @SerialName("SYMBOL_REFS") data class References(val symbolRefs: List<String>) : CliSource

    @Serializable
    @SerialName("ALL_DECLARATIONS")
    data class All(val declarationKinds: List<CliKind>?, val scope: CliDirectory?) : CliSource

    /** An issued retained-result reference, copied unchanged; restoration checks its basis and coverage. */
    @Serializable @SerialName("RESULT") data class Result(val result: String) : CliSource

    @Serializable @SerialName("AT_LOCATION") data class Location(val file: String, val offset: Long) : CliSource
}

@Serializable internal data class CliWorkspaceScope(@Required val type: String = "WORKSPACE")

@Serializable
internal data class CliDirectory(
    val relativeDirectoryPath: String,
    val sourceSetNames: List<String>?,
    @Required val type: String = "DIRECTORY",
)

@Serializable
internal data class CliBudget(
    val maxElapsedMs: Long? = null,
    val maxResults: Long? = null,
    val maxReturnedBytes: Long? = null,
)

/** Ordered pipeline stages. Set and join stages name one retained right operand; admission owns coverage proof. */
@Serializable
internal sealed interface CliStep

@Serializable
@SerialName("EXPAND_RELATION")
internal data class CliExpandRelation(val relation: CliRelation, val expansionScope: CliSourceDomain? = null) :
    CliStep

/** A relation destination domain. Omission keeps WORKSPACE; admission owns path and source-set validity. */
@Serializable
internal data class CliSourceDomain(
    val sourceSets: List<String>,
    val directory: String?,
    val sourcePolicy: CliSourcePolicy,
    val generatedSources: CliGeneratedSources,
    @Required val type: String = "SOURCE_DOMAIN",
)

@Serializable
internal enum class CliSourcePolicy {
    PRODUCTION_ONLY,
    PRODUCTION_AND_TEST,
}

@Serializable
internal enum class CliGeneratedSources {
    INCLUDE,
    EXCLUDE,
}

@Serializable
@SerialName("WHERE")
internal data class CliWhere(val predicate: CliVisibilityPredicate) : CliStep

@Serializable
internal data class CliVisibilityPredicate(
    val values: List<CliVisibility>,
    @Required val type: String = "VISIBILITY",
)

@Serializable
internal data class CliRetainedInput(val result: String, @Required val type: String = "RESULT")

@Serializable @SerialName("INTERSECT") internal data class CliIntersect(val right: CliRetainedInput) : CliStep

@Serializable @SerialName("UNION") internal data class CliUnion(val right: CliRetainedInput) : CliStep

@Serializable @SerialName("DIFFERENCE") internal data class CliDifference(val right: CliRetainedInput) : CliStep

@Serializable
@SerialName("JOIN")
internal data class CliJoin(val mode: CliJoinMode, val right: CliRetainedInput) : CliStep

@Serializable
internal sealed interface CliJoinMode {
    @Serializable @SerialName("SEMI") data object Semi : CliJoinMode

    @Serializable @SerialName("ANTI") data object Anti : CliJoinMode
}

@Serializable
internal enum class CliVisibility(val spelling: String) {
    PUBLIC("public"),
    PROTECTED("protected"),
    INTERNAL("internal"),
    PRIVATE("private"),
    LOCAL("local"),
}

@Serializable
internal sealed interface CliTerminal {
    @Serializable @SerialName("EMIT") data class Emit(val output: CliOutput?) : CliTerminal

    @Serializable
    @SerialName("TRACE")
    data class Trace(val output: CliOutput?, val expansionScope: CliSourceDomain? = null) : CliTerminal
}

@Serializable
internal sealed interface CliOutput {
    @Serializable @SerialName("SYMBOLS") data class Symbols(val fields: List<CliField>) : CliOutput

    @Serializable
    @SerialName("IMPACT_WITNESS")
    data class Witness(val section: io.github.amichne.kast.protocol.contract.ImpactWitnessSectionDocument) : CliOutput

    @Serializable @SerialName("OCCURRENCES") data object Occurrences : CliOutput
}

@Serializable
internal sealed interface CliText {
    @Serializable @SerialName("COMPLETE") data object Complete : CliText

    @Serializable @SerialName("NONE") data object None : CliText
}

@Serializable
internal enum class CliNameMatch {
    EXACT,
    FUZZY,
}

@Serializable
internal enum class CliRetention {
    RETAIN
}

@Serializable
internal enum class CliKind(val spelling: String) {
    CLASS("class"),
    FUNCTION("function"),
    PROPERTY("property"),
    TYPE_ALIAS("type-alias"),
}

@Serializable
internal enum class CliField(val spelling: String) {
    NAME("name"),
    LOCATION("location"),
    SIGNATURE("signature"),
    SOURCE("source"),
}

@Serializable
internal enum class CliRelation(val spelling: String) {
    REFERENCES("references"),
    CALLERS("callers"),
    CALLEES("callees"),
    IMPLEMENTATIONS("implementations"),
    INHERITORS("inheritors"),
    OVERRIDES("overrides"),
    TYPE_USES("type-uses"),
}

@Serializable
internal enum class CliRegion(val spelling: String) {
    ANCHOR("anchor"),
    CALLABLE_BODY("callable-body"),
    CLASS_BODY("class-body"),
    FILE("file"),
    ENCLOSING_DECLARATION("enclosing-declaration"),
    ENCLOSING_CALLABLE_BODY("enclosing-callable-body"),
    ENCLOSING_CLASS_BODY("enclosing-class-body"),
}
