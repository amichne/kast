package io.github.amichne.kast.protocol.contract

import io.github.amichne.kast.kernel.Refinement
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test

class ImpactModelIngressTest {
    @Test
    fun `all four representation rules encode with required CAPS_CASE discriminator and exact identity`() {
        val fixture = fixture()
        val encoded = Json.encodeToJsonElement(ImpactModelDocument.serializer(), fixture)
        assertInstanceOf(Refinement.Refined::class.java, ImpactModelIngress.decode(encoded))
        val origin =
            Json.encodeToJsonElement(ImpactRepresentationRuleDocument.serializer(), fixture.rules.values.first())
        val expected = Json.parseToJsonElement(javaClass.getResource("/impact-model-origin.expected.json")!!.readText())
        assertEquals(expected, origin)
    }

    @Test
    fun `unknown fields discriminators and quoted numeric identities reject at raw ingress`() {
        val encoded = Json.encodeToString(ImpactModelDocument.serializer(), fixture())
        val cases =
            listOf(
                encoded.replaceFirst("\"type\":\"REPRESENTATION\"", "\"type\":\"UNKNOWN\""),
                encoded.dropLast(1) + ",\"extra\":true}",
                encoded.replaceFirst("\"schemaVersion\":1", "\"schemaVersion\":2"),
                encoded.replaceFirst("\"generation\":7", "\"generation\":\"7\""),
            )
        for (raw in cases) assertEquals(
            Refinement.Rejected(ImpactModelSyntaxFailure.MALFORMED_DOCUMENT),
            ImpactModelIngress.decode(Json.parseToJsonElement(raw)),
        )
    }

    @Test
    fun `duplicate rule IDs undeclared representation and result input remain finite syntax failures`() {
        val fixture = fixture()
        val first = fixture.rules.values.first() as ImpactRepresentationRuleDocument.Origin
        assertEquals(
            Refinement.Rejected(ImpactModelSyntaxFailure.DUPLICATE_RULE_ID),
            AdmittedImpactModelSyntax.admit(
                fixture.copy(rules = bounded(listOf(first, first.copy(state = id("PLAINTEXT")))))
            ),
        )
        assertEquals(
            Refinement.Rejected(ImpactModelSyntaxFailure.UNDECLARED_STATE),
            AdmittedImpactModelSyntax.admit(fixture.copy(rules = bounded(listOf(first.copy(state = id("String")))))),
        )
        assertEquals(
            Refinement.Rejected(ImpactModelSyntaxFailure.INVALID_POSITION),
            AdmittedImpactModelSyntax.admit(
                fixture.copy(
                    rules =
                        bounded(
                            listOf(
                                first.copy(
                                    output =
                                        first.output.copy(
                                            position = ImpactModelValuePositionDocument.Argument(offset(0))
                                        )
                                )
                            )
                        )
                )
            ),
        )
    }

    @Test
    fun `boundary continuation retains independent published bases and explicit assumptions`() {
        val source = boundary("/server", 7, ImpactBoundaryKindDocument.SERIALIZATION)
        val target = boundary("/client", 19, ImpactBoundaryKindDocument.SERIALIZATION)
        val rule =
            ImpactBoundaryRuleDocument.Continuation(
                id("wire"),
                source,
                target,
                bounded(listOf(ImpactBoundaryCompatibilityDocument.REPRESENTATION_PRESERVED)),
            )
        val model = ImpactModelDocument.Boundary(ImpactModelFormatDocument.Current, model(), bounded(listOf(rule)))
        assertInstanceOf(
            Refinement.Refined::class.java,
            ImpactModelIngress.decode(Json.encodeToJsonElement(ImpactModelDocument.serializer(), model)),
        )
        assertEquals(source.site.enclosing.basis, rule.source.site.enclosing.basis)
        assertEquals(target.site.enclosing.basis, rule.target.site.enclosing.basis)
    }

    @Test
    fun `persisted retention terminal cannot be attached to a serialization boundary`() {
        val source = boundary("/server", 7, ImpactBoundaryKindDocument.SERIALIZATION)
        val rule =
            ImpactBoundaryRuleDocument.Terminal(
                id("terminal"),
                source,
                ImpactBoundaryTerminalDocument.REVIEWED_RETENTION,
            )
        assertEquals(
            Refinement.Rejected(ImpactModelSyntaxFailure.INVALID_TERMINAL),
            AdmittedImpactModelSyntax.admit(
                ImpactModelDocument.Boundary(ImpactModelFormatDocument.Current, model(), bounded(listOf(rule)))
            ),
        )
    }

    private fun fixture(): ImpactModelDocument.Representation {
        val declaration = declaration("/workspace", 7)
        val output = ImpactCallablePositionDocument(declaration, ImpactModelValuePositionDocument.Result)
        val input = ImpactCallablePositionDocument(declaration, ImpactModelValuePositionDocument.Argument(offset(0)))
        return ImpactModelDocument.Representation(
            ImpactModelFormatDocument.Current,
            model(),
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
    }

    private fun model() =
        ImpactModelIdentityDocument(id("encryption"), ImpactModelVersionDocument.parse(1).refined(), id("review:913"))

    private fun declaration(root: String, generation: Long) =
        ImpactDeclarationReferenceDocument(
            ImpactSemanticBasisDocument.Published(
                text(root),
                ImpactEvidenceRevisionDocument.parse(generation).refined(),
            ),
            text("$root/File.kt"),
            ImpactSourceRangeDocument(offset(0), offset(100)),
            text("canonical-signature-sha256-v1|" + "a".repeat(64)),
        )

    private fun boundary(root: String, generation: Long, kind: ImpactBoundaryKindDocument) =
        ImpactBoundaryPositionDocument(
            ImpactValueSiteReferenceDocument(
                declaration(root, generation),
                ImpactSourceRangeDocument(offset(10), offset(11)),
                ImpactValueRoleDocument.PropertyAssignment,
            ),
            kind,
            ImpactBoundaryContractDocument(id("payload-schema"), ImpactModelVersionDocument.parse(1).refined()),
            id("ciphertext"),
        )

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
