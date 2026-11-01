package io.github.amichne.kast.cli.publicshell

import kotlinx.serialization.Required
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// These DTOs only encode flag ingress. They establish no semantic validity or authority.
@Serializable internal data class CliEnvelope(val request: CliAction, val verbose: Boolean = false)

@Serializable
internal sealed interface CliAction {
    @Serializable
    @SerialName("RUN")
    data class Run(
        val source: CliSource,
        val steps: List<CliExpandRelation>?,
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

    @Serializable @SerialName("SYMBOL_REFS") data class References(val symbolRefs: List<String>) : CliSource

    @Serializable @SerialName("AT_LOCATION") data class Location(val file: String, val offset: Long) : CliSource
}

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

@Serializable
internal data class CliExpandRelation(val relation: CliRelation, @Required val type: String = "EXPAND_RELATION")

@Serializable
internal sealed interface CliTerminal {
    @Serializable @SerialName("EMIT") data class Emit(val output: CliOutput?) : CliTerminal

    @Serializable @SerialName("TRACE") data class Trace(val output: CliOutput?) : CliTerminal
}

@Serializable
internal sealed interface CliOutput {
    @Serializable @SerialName("SYMBOLS") data class Symbols(val fields: List<CliField>) : CliOutput

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
