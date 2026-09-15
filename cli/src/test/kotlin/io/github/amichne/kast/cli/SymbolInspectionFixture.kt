package io.github.amichne.kast.cli

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Independent DTOs for expected symbol output, including intentionally contradictory schema inputs. */
internal object SymbolInspectionFixture {
    fun process(kind: String, qualifiedIdentity: String, signature: String): String =
        Json.encodeToString(
            Envelope(
                "completed",
                Document(
                    "symbol.inspect",
                    "complete",
                    Symbol(
                        "exact:v1:3:1",
                        kind,
                        "Controller",
                        Json.decodeFromString<String?>(qualifiedIdentity),
                        "src/Controller.kt",
                        Range(0, 10),
                        Evidence(
                            "canonical-signature-sha256-v1|" + "a".repeat(64),
                            Json.decodeFromString<Signature>(signature),
                        ),
                    ),
                    "strict",
                ),
            )
        )

    fun expectedClass(identity: String): String =
        Json.encodeToString(
            Document(
                "symbol.inspect",
                "complete",
                Symbol(
                    "exact:A",
                    "classlike",
                    "A",
                    "A",
                    "src/A.kt",
                    Range(0, 7),
                    Evidence(identity, Signature("class-like", "A")),
                ),
                "strict",
            )
        )

    @Serializable private data class Envelope(val status: String, val document: Document)

    @Serializable
    private data class Document(val operation: String, val status: String, val symbol: Symbol, val acquisition: String)

    @Serializable
    private data class Symbol(
        val selector: String,
        val kind: String,
        val name: String,
        val qualifiedIdentity: String?,
        val file: String,
        val range: Range,
        val compilerEvidence: Evidence,
    )

    @Serializable private data class Range(val startInclusive: Int, val endExclusive: Int)

    @Serializable private data class Evidence(val identity: String, val signature: Signature)

    @Serializable
    private data class Signature(
        val type: String,
        val qualifiedIdentity: String,
        val receiver: Receiver? = null,
        val contextReceivers: List<String>? = null,
        val returnType: String? = null,
    )

    @Serializable private data class Receiver(val type: String, val compilerType: String)
}
