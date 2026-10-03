package io.github.amichne.kast.cli

import com.networknt.schema.InputFormat
import com.networknt.schema.SchemaRegistry
import com.networknt.schema.SpecificationVersion
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.BoundedProtocolList
import io.github.amichne.kast.protocol.contract.ImpactBoundaryCompatibilityDocument
import io.github.amichne.kast.protocol.contract.ImpactBoundaryContractDocument
import io.github.amichne.kast.protocol.contract.ImpactBoundaryKindDocument
import io.github.amichne.kast.protocol.contract.ImpactBoundaryPositionDocument
import io.github.amichne.kast.protocol.contract.ImpactBoundaryRuleDocument
import io.github.amichne.kast.protocol.contract.ImpactBoundaryTerminalDocument
import io.github.amichne.kast.protocol.contract.ImpactCallablePositionDocument
import io.github.amichne.kast.protocol.contract.ImpactDeclarationReferenceDocument
import io.github.amichne.kast.protocol.contract.ImpactEvidenceRevisionDocument
import io.github.amichne.kast.protocol.contract.ImpactModelDocument
import io.github.amichne.kast.protocol.contract.ImpactModelFormatDocument
import io.github.amichne.kast.protocol.contract.ImpactModelIdentifierDocument
import io.github.amichne.kast.protocol.contract.ImpactModelIdentityDocument
import io.github.amichne.kast.protocol.contract.ImpactModelValuePositionDocument
import io.github.amichne.kast.protocol.contract.ImpactModelVersionDocument
import io.github.amichne.kast.protocol.contract.ImpactRepresentationRuleDocument
import io.github.amichne.kast.protocol.contract.ImpactSemanticBasisDocument
import io.github.amichne.kast.protocol.contract.ImpactSourceRangeDocument
import io.github.amichne.kast.protocol.contract.ImpactValueRoleDocument
import io.github.amichne.kast.protocol.contract.ImpactValueSiteReferenceDocument
import io.github.amichne.kast.protocol.contract.ProtocolOffset
import io.github.amichne.kast.protocol.contract.ProtocolText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ImpactModelSchemaTest {
    @Test
    fun `canonical model schema closes all variants and requires type enum discriminators`() {
        val schemaDocument = generatedRequestSchema(ImpactModelDocument.serializer())
        assertEquals(
            "type",
            schemaDocument.getValue("discriminator").jsonObject.getValue("propertyName").jsonPrimitive.content,
        )
        val variants = schemaDocument.getValue("anyOf").jsonArray
        assertEquals(
            setOf("REPRESENTATION", "BOUNDARY"),
            variants
                .map {
                    it.jsonObject
                        .getValue("properties")
                        .jsonObject
                        .getValue("type")
                        .jsonObject
                        .getValue("enum")
                        .jsonArray
                        .single()
                        .jsonPrimitive
                        .content
                }
                .toSet(),
        )
        for (variant in variants) {
            assertEquals("false", variant.jsonObject.getValue("additionalProperties").jsonPrimitive.content)
            assertTrue(variant.jsonObject.getValue("required").jsonArray.any { it.jsonPrimitive.content == "type" })
        }
        val schema =
            SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12).getSchema(schemaDocument.toString())
        for (document in examples()) {
            val encoded = Json.encodeToString(ImpactModelDocument.serializer(), document)
            assertTrue(schema.validate(encoded, InputFormat.JSON).isEmpty(), encoded)
            listOf(
                    encoded.dropLast(1) + ",\"extra\":true}",
                    encoded.replaceFirst("\"schemaVersion\":1", "\"schemaVersion\":0"),
                    encoded.replaceFirst("\"version\":1", "\"version\":0"),
                    encoded.replaceFirst("\"generation\":7", "\"generation\":\"7\""),
                    encoded.replaceFirst(
                        "\"compilerIdentity\":\"canonical-signature",
                        "\"compilerIdentity\":\"bad-signature",
                    ),
                )
                .forEach { raw -> assertTrue(schema.validate(raw, InputFormat.JSON).isNotEmpty(), raw) }
        }
    }

    private fun examples(): List<ImpactModelDocument> = with(Fixture()) { listOf(representation, boundary) }

    private inner class Fixture {

        val identity =
            ImpactModelIdentityDocument(
                id("encryption"),
                ImpactModelVersionDocument.parse(1).refined(),
                id("review:913"),
            )
        val declaration =
            ImpactDeclarationReferenceDocument(
                ImpactSemanticBasisDocument.Published(
                    text("/workspace"),
                    ImpactEvidenceRevisionDocument.parse(7).refined(),
                ),
                text("/workspace/File.kt"),
                ImpactSourceRangeDocument(offset(0), offset(100)),
                text("canonical-signature-sha256-v1|" + "a".repeat(64)),
            )
        val output = ImpactCallablePositionDocument(declaration, ImpactModelValuePositionDocument.Result)
        val input = ImpactCallablePositionDocument(declaration, ImpactModelValuePositionDocument.Argument(offset(0)))
        val representation =
            ImpactModelDocument.Representation(
                ImpactModelFormatDocument.Current,
                identity,
                bounded(listOf(id("HIPED"), id("PLAINTEXT"), id("VOLTAGE"))),
                bounded(
                    listOf(
                        ImpactRepresentationRuleDocument.Origin(id("origin"), output, id("HIPED")),
                        ImpactRepresentationRuleDocument.Transfer(id("transfer"), input, output),
                        ImpactRepresentationRuleDocument.Transformation(
                            id("decrypt"),
                            input,
                            output,
                            id("HIPED"),
                            id("PLAINTEXT"),
                        ),
                        ImpactRepresentationRuleDocument.ConsumerExpectation(id("consumer"), input, id("VOLTAGE")),
                    )
                ),
            )
        val site =
            ImpactValueSiteReferenceDocument(
                declaration,
                ImpactSourceRangeDocument(offset(10), offset(11)),
                ImpactValueRoleDocument.PropertyAssignment,
            )
        val position =
            ImpactBoundaryPositionDocument(
                site,
                ImpactBoundaryKindDocument.PERSISTENCE,
                ImpactBoundaryContractDocument(id("cache-schema"), ImpactModelVersionDocument.parse(1).refined()),
                id("ciphertext"),
            )
        val boundary =
            ImpactModelDocument.Boundary(
                ImpactModelFormatDocument.Current,
                identity,
                bounded(
                    listOf(
                        ImpactBoundaryRuleDocument.Continuation(
                            id("reader-writer"),
                            position,
                            position.copy(slot = id("reader")),
                            bounded(listOf(ImpactBoundaryCompatibilityDocument.CONTRACT_COMPATIBLE)),
                        ),
                        ImpactBoundaryRuleDocument.Terminal(
                            id("retention"),
                            position,
                            ImpactBoundaryTerminalDocument.REVIEWED_RETENTION,
                        ),
                    )
                ),
            )
    }

    private fun id(raw: String) = ImpactModelIdentifierDocument.parse(raw).refined()

    private fun text(raw: String) = ProtocolText.parse(raw).refined()

    private fun offset(raw: Int) = ProtocolOffset.parse(raw).refined()

    private fun <T> bounded(values: List<T>) = BoundedProtocolList.create(values).refined()

    private fun <S, F> Refinement<S, F>.refined(): S =
        when (this) {
            is Refinement.Refined -> value
            is Refinement.Rejected -> error(failure.toString())
        }
}
