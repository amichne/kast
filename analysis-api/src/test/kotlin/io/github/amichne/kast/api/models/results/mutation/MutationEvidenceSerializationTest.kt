package io.github.amichne.kast.api

import io.github.amichne.kast.api.contract.Location
import io.github.amichne.kast.api.contract.NonNegativeInt
import io.github.amichne.kast.api.contract.NormalizedPath
import io.github.amichne.kast.api.contract.SymbolIdentity
import io.github.amichne.kast.api.contract.SymbolKind
import io.github.amichne.kast.api.contract.result.MutationPostconditionEvidence
import io.github.amichne.kast.api.contract.result.RelationshipResultEvidence
import io.github.amichne.kast.api.contract.result.RelationshipSearchCoverage
import io.github.amichne.kast.api.contract.result.RelationshipSearchLimitation
import io.github.amichne.kast.api.contract.result.ReplacementFunctionSignature
import io.github.amichne.kast.api.contract.result.ReplacementModality
import io.github.amichne.kast.api.contract.result.ReplacementOutboundEvidence
import io.github.amichne.kast.api.contract.result.ReplacementProofDimension
import io.github.amichne.kast.api.contract.result.ReplacementVisibility
import io.github.amichne.kast.api.contract.result.ResultCardinality
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class MutationEvidenceSerializationTest {
    private val json = Json { explicitNulls = false }

    @Test
    fun `rename postcondition evidence round trips through complete sealed roots`() {
        val target = target()
        val evidence = MutationPostconditionEvidence.Rename(
            resultingTarget = target,
            evidence = RelationshipResultEvidence.Complete(
                cardinality = ResultCardinality.Exact(0),
                coverage = RelationshipSearchCoverage.complete(),
            ),
            occurrences = emptyList(),
        )

        assertRoundTrip(evidence, nestedName = "evidence", evidenceType = "COMPLETE")
    }

    @Test
    fun `replacement postcondition evidence round trips through complete sealed roots`() {
        val evidence = MutationPostconditionEvidence.Replacement(
            resultingTarget = target(),
            sourceRange = Location(
                filePath = SOURCE_FILE,
                startOffset = 0,
                endOffset = 13,
                startLine = 1,
                startColumn = 1,
                preview = "fun greet()",
            ),
            signature = replacementSignature(),
            outboundEvidence = ReplacementOutboundEvidence.Complete.of(0),
            outboundReferences = emptyList(),
        )

        assertRoundTrip(evidence, nestedName = "outboundEvidence", evidenceType = "complete")
    }

    @Test
    fun `complete rename boundary rejects limited relationship evidence`() {
        val limited = RelationshipResultEvidence.Limited(
            cardinality = ResultCardinality.KnownMinimum(0),
            coverage = RelationshipSearchCoverage.limited(RelationshipSearchLimitation.BACKEND_INCOMPLETE),
        )
        val encoded = json.encodeToString(RelationshipResultEvidence.serializer(), limited)

        assertThrows(SerializationException::class.java) {
            json.decodeFromString(RelationshipResultEvidence.CompleteSerializer, encoded)
        }
    }

    @Test
    fun `complete replacement boundary rejects limited replacement evidence`() {
        val limited = ReplacementOutboundEvidence.Limited.of(
            knownMinimumCount = 0,
            dimensions = listOf(ReplacementProofDimension.SEMANTIC_GENERATION_UNCHANGED),
        )
        val encoded = json.encodeToString(ReplacementOutboundEvidence.serializer(), limited)

        assertThrows(SerializationException::class.java) {
            json.decodeFromString(ReplacementOutboundEvidence.CompleteSerializer, encoded)
        }
    }

    @Test
    fun `complete replacement boundary rejects known minimum cardinality`() {
        val encoded = json.encodeToJsonElement(
            ReplacementOutboundEvidence.serializer(),
            ReplacementOutboundEvidence.Complete.of(0),
        ).jsonObject
        val knownMinimum = json.encodeToJsonElement(
            ResultCardinality.serializer(),
            ResultCardinality.KnownMinimum(0),
        )
        val malformed = JsonObject(encoded + ("cardinality" to knownMinimum))

        assertThrows(SerializationException::class.java) {
            json.decodeFromJsonElement(ReplacementOutboundEvidence.CompleteSerializer, malformed)
        }
    }

    private fun assertRoundTrip(
        evidence: MutationPostconditionEvidence,
        nestedName: String,
        evidenceType: String,
    ) {
        val encoded = json.encodeToJsonElement(MutationPostconditionEvidence.serializer(), evidence).jsonObject
        val nestedEvidence = encoded.getValue(nestedName).jsonObject

        assertEquals(evidenceType, nestedEvidence.getValue("type").jsonPrimitive.content)
        assertEquals(
            "EXACT",
            nestedEvidence.getValue("cardinality").jsonObject.getValue("type").jsonPrimitive.content,
        )
        assertEquals(
            evidence,
            json.decodeFromJsonElement(MutationPostconditionEvidence.serializer(), encoded),
        )
    }

    private fun target(): SymbolIdentity = SymbolIdentity(
        fqName = "sample.greet",
        kind = SymbolKind.FUNCTION,
        declarationFile = NormalizedPath.parse(SOURCE_FILE),
        declarationStartOffset = NonNegativeInt(4),
    )

    private fun replacementSignature(): ReplacementFunctionSignature = ReplacementFunctionSignature.of(
        name = "greet",
        receiverType = null,
        contextReceiverTypes = emptyList(),
        typeParameters = emptyList(),
        valueParameters = emptyList(),
        returnType = "kotlin.Unit",
        visibility = ReplacementVisibility.PUBLIC,
        modality = ReplacementModality.FINAL,
        hasStableParameterNames = true,
        suspend = false,
        operator = false,
        inline = false,
        override = false,
        infix = false,
        static = false,
        tailrec = false,
        external = false,
        expect = false,
        actual = false,
    )

    private companion object {
        const val SOURCE_FILE = "/workspace/src/Sample.kt"
    }
}
