package io.github.amichne.kast.appserver.provider

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Fixed valid provider fixture encoded from a DTO; no manual JSON shape. */
internal enum class InspectionFixtureCoverage {
    COMPLETE,
    QUALIFIED,
}

internal fun symbolInspectionObserverFixture(
    coverage: InspectionFixtureCoverage = InspectionFixtureCoverage.COMPLETE
): String =
    Json.encodeToString(
        InspectionEnvelope(
            "completed",
            InspectionDocument(
                "symbol.inspect",
                when (coverage) {
                    InspectionFixtureCoverage.COMPLETE -> "complete"
                    InspectionFixtureCoverage.QUALIFIED -> "qualified"
                },
                "strict",
                InspectionSymbol(
                    "exact:v2:opaque",
                    "classlike",
                    "EventConsumer",
                    "com.aexp.mobile.one.streaming.events.core.EventConsumer",
                    "events/core/src/main/kotlin/sample/EventConsumer.kt",
                    InspectionRange(17, 140),
                    InspectionCompilerEvidence(
                        "canonical-signature-sha256-v1|" + "a".repeat(64),
                        InspectionSignature("class-like", "com.aexp.mobile.one.streaming.events.core.EventConsumer"),
                    ),
                ),
                when (coverage) {
                    InspectionFixtureCoverage.COMPLETE -> null
                    InspectionFixtureCoverage.QUALIFIED -> "compiler-evidence-incomplete"
                },
            ),
        )
    )

@Serializable private data class InspectionEnvelope(val status: String, val document: InspectionDocument)

@Serializable
private data class InspectionDocument(
    val operation: String,
    val status: String,
    val acquisition: String,
    val symbol: InspectionSymbol,
    val qualification: String? = null,
)

@Serializable
private data class InspectionSymbol(
    val selector: String,
    val kind: String,
    val name: String,
    val qualifiedIdentity: String,
    val file: String,
    val range: InspectionRange,
    val compilerEvidence: InspectionCompilerEvidence,
)

@Serializable private data class InspectionRange(val startInclusive: Int, val endExclusive: Int)

@Serializable private data class InspectionCompilerEvidence(val identity: String, val signature: InspectionSignature)

@Serializable private data class InspectionSignature(val type: String, val qualifiedIdentity: String)
