package io.github.amichne.kast.protocol.contract

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ImpactReviewedFindingTest {
    @Test
    fun `compact finding emits reviewed relations independently of a representation model`() {
        val values = ImpactFindingFixture()
        val reviewed = ImpactReviewedValueFixture()
        val plain = values.finding().copy(representation = ImpactFindingRepresentationDocument.NotModeled)
        val empty = Json.parseToJsonElement(Json.encodeToString(plain)).jsonObject
        assertEquals(emptyList<Nothing>(), empty.getValue("reviewedRelations").jsonArray.toList())
        val finding = plain.copy(reviewedRelations = reviewed.bounded(listOf(reviewed.transfer.binding)))
        val encoded = Json.parseToJsonElement(Json.encodeToString(finding)).jsonObject
        assertEquals(
            setOf(
                "path",
                "producer",
                "destination",
                "representation",
                "terminal",
                "boundaryObligations",
                "classification",
                "reviewedRelations",
            ),
            encoded.keys,
        )
        assertEquals(
            "NOT_MODELED",
            encoded.getValue("representation").jsonObject.getValue("type").jsonPrimitive.content,
        )
        val relation = encoded.getValue("reviewedRelations").jsonArray.single().jsonObject
        assertEquals("KOTLIN_2_4_21_ITERABLE_MAP_V1", relation.getValue("rule").jsonPrimitive.content)
        assertEquals("NOT_ESTABLISHED", relation.getValue("actualExecution").jsonPrimitive.content)
        assertEquals("NOT_ESTABLISHED", relation.getValue("invocationCount").jsonPrimitive.content)
        assertTrue(ImpactWitnessDocument.Finding(finding).hasSourceBasis(reviewed.binding.basis))
        val foreign =
            reviewed.transfer.binding.copy(
                basis =
                    (reviewed.binding.basis as ImpactSemanticBasisDocument.Published).copy(
                        root = reviewed.text("/other")
                    )
            )
        assertFalse(
            ImpactWitnessDocument.Finding(finding.copy(reviewedRelations = reviewed.bounded(listOf(foreign))))
                .hasSourceBasis(reviewed.binding.basis)
        )
    }

    @Test
    fun `reviewed witnesses and representation histories expose separate provenance tags`() {
        val values = ImpactFindingFixture()
        val fixture = ImpactReviewedValueFixture()
        val witness: ImpactWitnessDocument =
            ImpactWitnessDocument.ReviewedTransfer(values.count(0), values.count(0), fixture.transfer)
        val history: ImpactRepresentationHistoryDocument =
            ImpactRepresentationHistoryDocument.ReviewedTransfer(fixture.transfer)
        val finding: ImpactFindingProvenanceDocument =
            ImpactFindingProvenanceDocument.ReviewedLibrary(fixture.transfer.binding)
        val encodedWitness = Json.parseToJsonElement(Json.encodeToString(witness)).jsonObject
        val encodedHistory = Json.parseToJsonElement(Json.encodeToString(history)).jsonObject
        val encodedFinding = Json.parseToJsonElement(Json.encodeToString(finding)).jsonObject
        assertEquals("REVIEWED_TRANSFER", encodedWitness.getValue("type").jsonPrimitive.content)
        assertEquals(setOf("type", "observationOrdinal", "transferOrdinal", "transfer"), encodedWitness.keys)
        assertEquals("REVIEWED_TRANSFER", encodedHistory.getValue("type").jsonPrimitive.content)
        assertEquals(setOf("type", "transfer"), encodedHistory.keys)
        assertEquals("REVIEWED_LIBRARY", encodedFinding.getValue("type").jsonPrimitive.content)
        assertEquals(setOf("type", "binding"), encodedFinding.keys)
    }

    @Test
    fun `reviewed source evidence encodes exact artifact spans and rejects changed hashes`() {
        val fixture = ImpactReviewedValueFixture()
        val artifacts =
            org.junit.jupiter.api.Assertions.assertInstanceOf(
                ImpactReviewedMapArtifactsDocument::class.java,
                fixture.binding.artifacts,
            )
        val encoded =
            Json.parseToJsonElement(Json.encodeToString<ImpactReviewedArtifactsDocument>(artifacts)).jsonObject
        assertEquals(
            setOf(
                "binaryArtifactSha256",
                "sourceArtifactSha256",
                "sourceEntry",
                "sourceEntrySha256",
                "facadeSha256",
                "implementationSha256",
                "mapLines",
                "mapToLines",
            ),
            encoded.keys,
        )
        assertEquals("commonMain/generated/_Collections.kt", encoded.getValue("sourceEntry").jsonPrimitive.content)
        assertEquals(
            "bd8250210584cb659847dce9cda660f8e9906e5def7fbebcea93dc6dcb6a88a3",
            encoded.getValue("binaryArtifactSha256").jsonPrimitive.content,
        )
        assertEquals(
            "1e29c0668b0aa555e7b154e5da2e8094e1b755cfee25737839195bbbdad8a4a2",
            encoded.getValue("sourceArtifactSha256").jsonPrimitive.content,
        )
        assertEquals("1744", encoded.getValue("mapLines").jsonObject.getValue("first").jsonPrimitive.content)
        assertEquals("1746", encoded.getValue("mapLines").jsonObject.getValue("last").jsonPrimitive.content)
        assertEquals("1819", encoded.getValue("mapToLines").jsonObject.getValue("first").jsonPrimitive.content)
        assertEquals("1823", encoded.getValue("mapToLines").jsonObject.getValue("last").jsonPrimitive.content)
        val changed =
            listOf(
                artifacts.copy(sourceEntrySha256 = fixture.text("f".repeat(64))),
                artifacts.copy(facadeSha256 = fixture.text("f".repeat(64))),
                artifacts.copy(implementationSha256 = fixture.text("f".repeat(64))),
                artifacts.copy(mapLines = artifacts.mapToLines),
            )
        for (artifact in changed) assertFalse(
            fixture.binding.copy(artifacts = artifact).admits(fixture.binding.enclosing)
        )
    }
}
