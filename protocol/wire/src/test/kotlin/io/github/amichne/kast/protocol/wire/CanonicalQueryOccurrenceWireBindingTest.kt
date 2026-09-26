package io.github.amichne.kast.protocol.wire

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.BoundedProtocolList
import io.github.amichne.kast.protocol.contract.CompilerSignatureDocument
import io.github.amichne.kast.protocol.contract.CompilerSymbolEvidenceDocument
import io.github.amichne.kast.protocol.contract.ProtocolOffset
import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.protocol.contract.QueryReferenceDocument
import io.github.amichne.kast.protocol.contract.QueryRelationOmissionDocument
import io.github.amichne.kast.protocol.contract.QueryResultItemDocument
import io.github.amichne.kast.protocol.contract.QueryResultRowReference
import io.github.amichne.kast.protocol.contract.QueryRunResult
import io.github.amichne.kast.protocol.contract.RelationFactCoverageDocument
import io.github.amichne.kast.protocol.contract.RelationFactDocument
import io.github.amichne.kast.protocol.contract.RelationKindDocument
import io.github.amichne.kast.protocol.contract.RelationLimitationDocument
import io.github.amichne.kast.protocol.contract.RelationObservedItemsDocument
import io.github.amichne.kast.protocol.contract.RelationOccurrenceDocument
import io.github.amichne.kast.protocol.contract.RelationOmissionDocument
import io.github.amichne.kast.protocol.contract.RelationOmissionLocationDocument
import io.github.amichne.kast.protocol.contract.RelationOmissionMeasurementDocument
import io.github.amichne.kast.protocol.contract.RelationProvenanceDocument
import io.github.amichne.kast.protocol.contract.RelationProviderDocument
import io.github.amichne.kast.protocol.contract.SourceRangeDocument
import io.github.amichne.kast.protocol.contract.SymbolDocument
import io.github.amichne.kast.protocol.contract.SymbolKindDocument
import io.github.amichne.kast.protocol.contract.SymbolQualifiedIdentityDocument
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CanonicalQueryOccurrenceWireBindingTest {
    @Test
    fun `occurrence rows and attributed omissions retain independent wire evidence`() {
        val result = occurrenceResult()
        val encoded = CanonicalQuerySerializers.result.encode(result, WireValueRole.RESULT) as WireValueEncoding.Encoded
        val body = encoded.value.jsonObject
        assertEquals(
            "occurrence",
            body.getValue("items").jsonArray.single().jsonObject.getValue("type").jsonPrimitive.content,
        )
        assertEquals(
            "exact:v2:caller",
            body
                .getValue("omissions")
                .jsonArray
                .single()
                .jsonObject
                .getValue("subject")
                .jsonObject
                .getValue("token")
                .jsonPrimitive
                .content,
        )
        assertEquals(
            result,
            (CanonicalQuerySerializers.result.decode(encoded.value, WireValueRole.RESULT) as WireDecoding.Decoded)
                .value,
        )
        val missingSelector = encoded.value.toString().replaceFirst("\"candidateSelector\":\"candidate:call\",", "")
        assertTrue(missingSelector != encoded.value.toString())
        assertTrue(decode(missingSelector) is WireDecoding.Rejected)
        val retired = encoded.value.toString().replaceFirst("\"type\":\"occurrence\"", "\"type\":\"relation-read\"")
        assertTrue(decode(retired) is WireDecoding.Rejected)
    }

    private fun decode(raw: String): WireDecoding<QueryRunResult> =
        CanonicalQuerySerializers.result.decode(Json.parseToJsonElement(raw), WireValueRole.RESULT)

    private fun occurrenceResult(): QueryRunResult {
        val caller = relationSymbol("Caller")
        val callee = relationSymbol("Callee")
        val fact =
            RelationFactDocument(
                RelationKindDocument.CALLEES,
                caller,
                callee,
                RelationOccurrenceDocument(text("candidate:call"), caller.file, caller.range),
                RelationProvenanceDocument.K2_AUTHORED_SOURCE,
                RelationFactCoverageDocument.EXACT_COMPILER_CONFIRMED,
            )
        val omission =
            RelationOmissionDocument.create(
                    RelationProviderDocument.INTELLIJ_CALLEES_V2,
                    RelationLimitationDocument.UNRESOLVED_TARGET,
                    RelationOmissionMeasurementDocument.ObservedOnPage(
                        RelationObservedItemsDocument.parse(2).refined()
                    ),
                    bounded(listOf(RelationOmissionLocationDocument(caller.file, caller.range))),
                )
                .refined()
        return QueryRunResult(
            bounded(
                listOf(
                    QueryResultItemDocument.Occurrence(
                        QueryReferenceDocument.ExactSymbol(text("exact:v2:callee")),
                        fact,
                        QueryResultRowReference.parse("result-row:v1:00000000-0000-0000-0000-000000000003").refined(),
                    )
                )
            ),
            bounded(emptyList()),
            bounded(
                listOf(
                    QueryRelationOmissionDocument(
                        QueryReferenceDocument.ExactSymbol(text("exact:v2:caller")),
                        RelationKindDocument.CALLEES,
                        omission,
                    )
                )
            ),
        )
    }

    private fun relationSymbol(name: String): SymbolDocument {
        val identity = text("sample.$name")
        val signature = CompilerSignatureDocument.ClassLike(identity)
        return SymbolDocument.create(
                selector = text("exact:v2:$name"),
                kind = SymbolKindDocument.CLASSLIKE,
                name = text(name),
                qualifiedIdentity = SymbolQualifiedIdentityDocument.Available(identity),
                file = text("src/$name.kt"),
                range =
                    SourceRangeDocument.create(ProtocolOffset.parse(0).refined(), ProtocolOffset.parse(6).refined())
                        .refined(),
                compilerEvidence = CompilerSymbolEvidenceDocument.fromSignature(signature).refined(),
            )
            .refined()
    }

    private fun text(raw: String): ProtocolText = ProtocolText.parse(raw).refined()

    private fun <Value> bounded(values: List<Value>): BoundedProtocolList<Value> =
        BoundedProtocolList.create(values).refined()

    private fun <Value, Failure> Refinement<Value, Failure>.refined(): Value =
        when (this) {
            is Refinement.Refined -> value
            is Refinement.Rejected -> error(failure.toString())
        }
}
