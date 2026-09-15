package io.github.amichne.kast.cli

import com.networknt.schema.InputFormat
import com.networknt.schema.SchemaRegistry
import com.networknt.schema.SpecificationVersion
import io.github.amichne.kast.cli.projection.CanonicalSymbolCliDocuments
import io.github.amichne.kast.kernel.EvidenceEnvelope
import io.github.amichne.kast.kernel.EvidenceGeneration
import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.CanonicalOperation
import io.github.amichne.kast.protocol.contract.CompilerSignatureDocument
import io.github.amichne.kast.protocol.contract.CompilerSymbolEvidenceDocument
import io.github.amichne.kast.protocol.contract.ProtocolOffset
import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.protocol.contract.SourceRangeDocument
import io.github.amichne.kast.protocol.contract.SymbolDocument
import io.github.amichne.kast.protocol.contract.SymbolInspectAcquisition
import io.github.amichne.kast.protocol.contract.SymbolInspectRejection
import io.github.amichne.kast.protocol.contract.SymbolInspectResult
import io.github.amichne.kast.protocol.contract.SymbolKindDocument
import io.github.amichne.kast.protocol.contract.SymbolQualifiedIdentityDocument
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SymbolRevalidationSchemaTest {
    private val registry = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
    private val schema = registry.getSchema(installedServerOutputSchema(CanonicalOperation.SYMBOL_INSPECT).toString())

    @Test
    fun `both acquisition variants are explicit and unknown or missing acquisition rejects`() {
        val symbol = fixtureSymbol()
        for (acquisition in SymbolInspectAcquisition.entries) {
            val outcome =
                OperationOutcome.Complete(
                    EvidenceEnvelope(
                        CanonicalOperation.SYMBOL_INSPECT.id,
                        EvidenceGeneration.parse(1).value(),
                        SymbolInspectResult(symbol, acquisition),
                    )
                )
            val projected = CanonicalSymbolCliDocuments.projectInspection(outcome) as ProjectedCliOutcome.Complete
            val document = Json.parseToJsonElement(projected.document.value).jsonObject
            assertEquals(
                if (acquisition == SymbolInspectAcquisition.STRICT) "strict" else "reacquired",
                document.getValue("acquisition").jsonPrimitive.content,
            )
            assertTrue(validate(document).isEmpty())
            assertFalse(
                validate(
                        Json.parseToJsonElement(
                                projected.document.value.replace(
                                    ",\"acquisition\":\"${document.getValue("acquisition").jsonPrimitive.content}\"",
                                    "",
                                )
                            )
                            .jsonObject
                    )
                    .isEmpty()
            )
            assertFalse(
                validate(
                        Json.parseToJsonElement(
                                projected.document.value.replace(
                                    "\"${document.getValue("acquisition").jsonPrimitive.content}\"",
                                    "\"unknown\"",
                                )
                            )
                            .jsonObject
                    )
                    .isEmpty()
            )
        }
    }

    @Test
    fun `every finite inspection rejection satisfies provider schema and unknown rejects`() {
        for (reason in SymbolInspectRejection.entries) {
            val result =
                CanonicalSymbolCliDocuments.projectInspection(OperationOutcome.Rejected(reason))
                    as ProjectedCliOutcome.Rejected
            val document = Json.parseToJsonElement(result.document.value).jsonObject
            assertTrue(validate(document).isEmpty(), reason.name)
            assertFalse(
                validate(
                        Json.parseToJsonElement(
                                result.document.value.replace(document.getValue("reason").toString(), "\"unknown\"")
                            )
                            .jsonObject
                    )
                    .isEmpty()
            )
        }
    }

    @Test
    fun `installed inspection refusal uses CLI spelling rather than wire enum spelling`() {
        val result =
            CanonicalSymbolCliDocuments.projectInspection(
                OperationOutcome.Rejected(SymbolInspectRejection.EXACT_SELECTOR_STALE)
            ) as ProjectedCliOutcome.Rejected
        assertEquals(
            "exact-selector-stale",
            Json.parseToJsonElement(result.document.value).jsonObject.getValue("reason").jsonPrimitive.content,
        )
    }

    private fun fixtureSymbol(): SymbolDocument {
        val qualified = text("sample.Alias")
        return SymbolDocument.create(
                text("exact:v5:0123456789abcdef012345"),
                SymbolKindDocument.TYPE_ALIAS,
                text("Alias"),
                SymbolQualifiedIdentityDocument.Available(qualified),
                text("src/Alias.kt"),
                SourceRangeDocument.create(ProtocolOffset.parse(0).value(), ProtocolOffset.parse(8).value()).value(),
                CompilerSymbolEvidenceDocument.fromSignature(CompilerSignatureDocument.TypeAlias(qualified)).value(),
            )
            .value()
    }

    private fun validate(document: JsonObject) =
        schema.validate(Json.encodeToString(Envelope("completed", document)), InputFormat.JSON)

    /** Provider contract-defined dynamic canonical document. */
    @Serializable private data class Envelope(val status: String, val document: JsonObject)

    private fun text(value: String) = ProtocolText.parse(value).value()

    private fun <T> Refinement<T, *>.value(): T =
        when (this) {
            is Refinement.Refined -> value
            is Refinement.Rejected -> error("Fixture $failure")
        }
}
