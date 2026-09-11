package conventions.jsoncontracts

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Serialized boundary records contain syntax evidence, never source payloads or semantic claims. */
@Serializable
enum class JsonContractEvidence {
    KOTLIN_PSI_SYNTAX
}

@Serializable
enum class JsonContractExpressionKind {
    JSON_BUILDER,
    JSON_OBJECT,
    JSON_ARRAY,
    JSON_LITERAL,
}

@Serializable
data class JsonContractFingerprint(
    val path: String,
    val scope: String,
    val kind: JsonContractExpressionKind,
    val sha256: String,
)

@Serializable
data class JsonContractFinding(val fingerprint: JsonContractFingerprint, val count: Int, val lines: List<Int>)

@Serializable
data class JsonContractAllowance(val fingerprint: JsonContractFingerprint, val count: Int, val justification: String)

@Serializable data class JsonContractBaselineDocument(val version: Int, val allowances: List<JsonContractAllowance>)

@Serializable
enum class JsonContractBaselineFailure {
    INVALID_DOCUMENT,
    UNREADABLE_DOCUMENT,
    UNSUPPORTED_VERSION,
    INVALID_PATH,
    INVALID_SCOPE,
    INVALID_HASH,
    INVALID_COUNT,
    INVALID_JUSTIFICATION,
    DUPLICATE_FINGERPRINT,
}

@Serializable
enum class JsonContractSourceFailure {
    INVALID_PATH,
    INVALID_KOTLIN,
    UNREADABLE_SOURCE,
}

@Serializable
sealed interface JsonContractViolation {
    @Serializable
    @SerialName("unapproved")
    data class Unapproved(val finding: JsonContractFinding) : JsonContractViolation

    @Serializable
    @SerialName("countExceeded")
    data class CountExceeded(val finding: JsonContractFinding, val allowed: Int) : JsonContractViolation

    @Serializable
    @SerialName("staleAllowance")
    data class StaleAllowance(val allowance: JsonContractAllowance, val observedCount: Int) : JsonContractViolation

    @Serializable
    @SerialName("invalidBaseline")
    data class InvalidBaseline(val reason: JsonContractBaselineFailure) : JsonContractViolation

    @Serializable
    @SerialName("invalidSource")
    data class InvalidSource(val path: String, val line: Int, val reason: JsonContractSourceFailure) :
        JsonContractViolation
}

@Serializable
data class JsonContractReport(
    val evidence: JsonContractEvidence,
    val findings: List<JsonContractFinding>,
    val violations: List<JsonContractViolation>,
)

data class JsonContractSource(val path: String, val content: String)
