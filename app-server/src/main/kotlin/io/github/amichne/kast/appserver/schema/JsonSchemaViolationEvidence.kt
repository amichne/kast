package io.github.amichne.kast.appserver.schema

import com.networknt.schema.Error
import io.github.amichne.kast.kernel.NonEmptyFailures
import kotlinx.serialization.Serializable

/** Closed syntactic hints; neither validator messages nor instance values cross this boundary. */
@Serializable
internal enum class JsonSchemaViolationKeyword(val wireName: String) {
    TYPE("type"),
    REQUIRED("required"),
    ENUM("enum"),
    CONST("const"),
    PATTERN("pattern"),
    ADDITIONAL_PROPERTIES("additionalProperties"),
    ONE_OF("oneOf"),
    ANY_OF("anyOf"),
    MINIMUM("minimum"),
    MAXIMUM("maximum"),
    MIN_LENGTH("minLength"),
    MAX_LENGTH("maxLength"),
    MIN_ITEMS("minItems"),
    MAX_ITEMS("maxItems"),
    UNKNOWN("");

    companion object {
        fun from(raw: String?): JsonSchemaViolationKeyword = entries.firstOrNull { it.wireName == raw } ?: UNKNOWN
    }
}

@Serializable
internal enum class JsonSchemaViolationField(val wireName: String) {
    DOCUMENT("document"),
    STATUS("status"),
    OPERATION("operation"),
    LIVE("live"),
    ROOT("root"),
    HOST("host"),
    EPOCH("epoch"),
    CONTENT_VIEW("contentView"),
    VERSION("version"),
    GRAPH("graph"),
    SNAPSHOT("snapshot"),
    CANONICAL_ROOT("canonicalRoot"),
    GENERATION("generation"),
    NODES("nodes"),
    EDGES("edges"),
    PROOFS("proofs"),
    ID("id"),
    SELECTOR("selector"),
    KIND("kind"),
    NAME("name"),
    QUALIFIED_IDENTITY("qualifiedIdentity"),
    FILE("file"),
    RANGE("range"),
    START_INCLUSIVE("startInclusive"),
    END_EXCLUSIVE("endExclusive"),
    PROOF("proof"),
    DEPTH("depth"),
    MEANING("meaning"),
    SOURCE("source"),
    TARGET("target"),
    OCCURRENCE("occurrence"),
    CANDIDATE_SELECTOR("candidateSelector"),
    PROVENANCE("provenance"),
    COVERAGE("coverage"),
    IDENTITY("identity"),
    QUALIFICATION("qualification"),
    LIMITATIONS("limitations"),
    RELATION_LIMITATIONS("relationLimitations"),
    CONTINUATION("continuation"),
    REASON("reason"),
    DIAGNOSTIC("diagnostic"),
    UNKNOWN("");

    companion object {
        fun from(raw: String?): JsonSchemaViolationField = entries.firstOrNull { it.wireName == raw } ?: UNKNOWN
    }
}

@Serializable
internal data class JsonSchemaViolationObservation(
    val keyword: JsonSchemaViolationKeyword,
    val field: JsonSchemaViolationField,
) {
    companion object {
        fun from(error: Error): JsonSchemaViolationObservation =
            JsonSchemaViolationObservation(
                JsonSchemaViolationKeyword.from(error.keyword),
                JsonSchemaViolationField.from(error.property ?: error.instanceLocation.getName(-1)),
            )
    }
}

/** Deduplication bounds this evidence by the finite keyword × field product. */
internal class JsonSchemaViolationEvidence
private constructor(private val observations: Set<JsonSchemaViolationObservation>) {
    fun toDocument(): JsonSchemaViolationEvidenceDocument =
        JsonSchemaViolationEvidenceDocument(
            observations.sortedWith(compareBy({ it.keyword.ordinal }, { it.field.ordinal }))
        )

    companion object {
        fun from(failures: NonEmptyFailures<JsonConstraintViolation>): JsonSchemaViolationEvidence =
            JsonSchemaViolationEvidence(failures.map { it.observation }.toSet())
    }
}

@Serializable
internal data class JsonSchemaViolationEvidenceDocument(val observations: List<JsonSchemaViolationObservation>)
