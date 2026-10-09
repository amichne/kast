package io.github.amichne.kast.appserver.query

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.BoundedProtocolList
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
import io.github.amichne.kast.protocol.contract.ImpactWitnessSectionDocument
import io.github.amichne.kast.protocol.contract.ProtocolOffset
import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.protocol.contract.QueryContainmentDocument
import io.github.amichne.kast.protocol.contract.QueryDirectoryScopeDocument
import io.github.amichne.kast.protocol.contract.QueryDiscoveryInclusionPolicyDocument
import io.github.amichne.kast.protocol.contract.QueryDiscoverySourcePolicyDocument
import io.github.amichne.kast.protocol.contract.QueryExpansionScopeDocument
import io.github.amichne.kast.protocol.contract.QueryFromDocument
import io.github.amichne.kast.protocol.contract.QueryImpactDeclarationDocument
import io.github.amichne.kast.protocol.contract.QueryImpactFlowDocument
import io.github.amichne.kast.protocol.contract.QueryImpactProducerDocument
import io.github.amichne.kast.protocol.contract.QueryOutputDocument
import io.github.amichne.kast.protocol.contract.QueryResultReference
import io.github.amichne.kast.protocol.contract.QueryRunRequest
import io.github.amichne.kast.protocol.registry.PublicToolIdentity
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PublicImpactSourceContractTest {
    private val json = Json {
        encodeDefaults = true
        explicitNulls = true
    }

    @Test
    fun `public impact question preserves seed callable domain flow and supplied representation model`() {
        val declaration = declarationReference()
        val model = originModel(declaration)
        val seeds = producerSeeds()
        val declarations =
            bounded(listOf(QueryImpactDeclarationDocument(text("NON_ISSUED_MODEL_TEST_ONLY"), declaration)))
        val source =
            PublicToolImpactSource(
                seeds = seeds,
                declarations = declarations,
                domain = sourceDomain(),
                flow = QueryImpactFlowDocument.KOTLIN_FORWARD_V1,
                models = bounded(listOf(model)),
            )
        val raw =
            json.encodeToJsonElement(
                PublicToolQuerySymbols.serializer(),
                PublicToolQuerySymbols(PublicToolRunAction(source, output = PublicToolValuePathsOutput)),
            )
        val admitted = PublicToolContract.admit(PublicToolIdentity.QUERY_SYMBOLS, raw).refined()
        val run = (admitted.canonical as PublicToolCanonical.Query).request as QueryRunRequest.Run
        val question = (run.from as QueryFromDocument.Impact).investigation
        assertEquals(seeds, question.seeds)
        assertEquals(declarations, question.declarations)
        assertEquals(listOf(model), question.models.values)
        assertEquals(QueryImpactFlowDocument.KOTLIN_FORWARD_V1, question.flow)
        assertEquals(
            QueryExpansionScopeDocument.Sources(
                sourceSets = bounded(listOf(text("main"))),
                directory = QueryDirectoryScopeDocument(text("src/main"), QueryContainmentDocument.DIRECT),
                sourcePolicy = QueryDiscoverySourcePolicyDocument.PRODUCTION_ONLY,
                generatedSources = QueryDiscoveryInclusionPolicyDocument.EXCLUDE,
            ),
            question.domain,
        )
        assertEquals(QueryOutputDocument.ValuePaths, run.output)
        assertEncodedQuestion(run)
    }

    @Test
    fun `impact witness run lowers the original investigation without a completion input`() {
        val source =
            PublicToolImpactSource(
                seeds = producerSeeds(),
                declarations = bounded(emptyList()),
                models = bounded(emptyList()),
                domain = PublicToolImpactRetainedDomain,
                flow = QueryImpactFlowDocument.KOTLIN_FORWARD_V1,
            )
        for (section in ImpactWitnessSectionDocument.entries) {
            val input =
                PublicToolQuerySymbols(
                    PublicToolRunAction(
                        source,
                        output = PublicToolImpactWitnessOutput(section),
                    )
                )
            val encoded = json.encodeToJsonElement(PublicToolQuerySymbols.serializer(), input)
            val admitted = PublicToolContract.admit(PublicToolIdentity.QUERY_SYMBOLS, encoded).refined()
            val run = (admitted.canonical as PublicToolCanonical.Query).request as QueryRunRequest.Run
            assertEquals(QueryOutputDocument.ImpactWitness(section), run.output)
            assertEquals(producerSeeds(), (run.from as QueryFromDocument.Impact).investigation.seeds)
        }
    }

    @Test
    fun `value paths read uses the existing retained result route`() {
        val reference = QueryResultReference.parse("result:v1:00000000-0000-0000-0000-000000000001").refined()
        val raw =
            json.encodeToJsonElement(
                PublicToolQuerySymbols.serializer(),
                PublicToolQuerySymbols(PublicToolReadResultAction(reference, output = PublicToolValuePathsOutput)),
            )
        val admitted = PublicToolContract.admit(PublicToolIdentity.QUERY_SYMBOLS, raw).refined()
        val read = (admitted.canonical as PublicToolCanonical.Query).request as QueryRunRequest.ReadResult
        assertEquals(reference, read.result)
        assertEquals(QueryOutputDocument.ValuePaths, read.output)
    }

    @Test
    fun `each impact witness section lowers only through retained read result`() {
        val reference = QueryResultReference.parse("result:v1:00000000-0000-0000-0000-000000000001").refined()
        for (section in ImpactWitnessSectionDocument.entries) {
            val raw =
                json.encodeToJsonElement(
                    PublicToolQuerySymbols.serializer(),
                    PublicToolQuerySymbols(
                        PublicToolReadResultAction(reference, output = PublicToolImpactWitnessOutput(section))
                    ),
                )
            val admitted = PublicToolContract.admit(PublicToolIdentity.QUERY_SYMBOLS, raw).refined()
            val read = (admitted.canonical as PublicToolCanonical.Query).request as QueryRunRequest.ReadResult
            assertEquals(reference, read.result)
            assertEquals(QueryOutputDocument.ImpactWitness(section), read.output)
        }
        val invalid = javaClass.getResource("/impact-source/witness-on-run.json")!!.readText()
        assertEquals(
            Refinement.Rejected(PublicToolInputFailure.SchemaRejected),
            PublicToolContract.admit(PublicToolIdentity.QUERY_SYMBOLS, Json.parseToJsonElement(invalid)),
        )
    }

    @Test
    fun `impact syntax rejects omitted model vocabulary unknown flow and missing callable identity`() {
        for (name in listOf("missing-models", "unknown-flow", "missing-callable")) {
            val raw = javaClass.getResource("/impact-source/$name.json")!!.readText()
            assertEquals(
                Refinement.Rejected(PublicToolInputFailure.SchemaRejected),
                PublicToolContract.admit(PublicToolIdentity.QUERY_SYMBOLS, Json.parseToJsonElement(raw)),
                name,
            )
        }
    }

    @Test
    fun `optional requested sites preserve exact native role claims and null omission means empty universe`() {
        val target =
            ImpactValueSiteReferenceDocument(declarationReference(), range(10, 20), ImpactValueRoleDocument.LocalRead)
        for (targets in listOf(null, bounded(listOf(target)))) {
            val source =
                PublicToolImpactSource(
                    seeds = producerSeeds(),
                    declarations = bounded(emptyList()),
                    domain = PublicToolImpactWorkspaceDomain,
                    flow = QueryImpactFlowDocument.KOTLIN_FORWARD_V1,
                    models = bounded(emptyList()),
                    requestedSites = targets,
                )
            val encoded =
                json.encodeToJsonElement(
                    PublicToolQuerySymbols.serializer(),
                    PublicToolQuerySymbols(PublicToolRunAction(source, output = PublicToolValuePathsOutput)),
                )
            val admitted = PublicToolContract.admit(PublicToolIdentity.QUERY_SYMBOLS, encoded).refined()
            val run = (admitted.canonical as PublicToolCanonical.Query).request as QueryRunRequest.Run
            assertEquals(
                targets?.values.orEmpty(),
                (run.from as QueryFromDocument.Impact).investigation.requestedSites.values,
            )
        }
    }

    @Test
    fun `omitted requested universe uses empty default and unknown target role fails schema admission`() {
        val target =
            ImpactValueSiteReferenceDocument(declarationReference(), range(10, 20), ImpactValueRoleDocument.LocalRead)
        val source =
            PublicToolImpactSource(
                seeds = producerSeeds(),
                declarations = bounded(emptyList()),
                models = bounded(emptyList()),
                domain = PublicToolImpactWorkspaceDomain,
                flow = QueryImpactFlowDocument.KOTLIN_FORWARD_V1,
            )
        val request = PublicToolQuerySymbols(PublicToolRunAction(source, output = PublicToolValuePathsOutput))
        val omitted = Json {
            encodeDefaults = false
            explicitNulls = false
        }
            .encodeToJsonElement(PublicToolQuerySymbols.serializer(), request)
        val admitted = PublicToolContract.admit(PublicToolIdentity.QUERY_SYMBOLS, omitted).refined()
        assertEquals(
            emptyList<ImpactValueSiteReferenceDocument>(),
            (((admitted.canonical as PublicToolCanonical.Query).request as QueryRunRequest.Run).from
                    as QueryFromDocument.Impact)
                .investigation
                .requestedSites
                .values,
        )
        val selected =
            request.copy(
                request =
                    PublicToolRunAction(
                        source.copy(requestedSites = bounded(listOf(target))),
                        output = PublicToolValuePathsOutput,
                    )
            )
        val malformed =
            json.encodeToString(PublicToolQuerySymbols.serializer(), selected).replace("LOCAL_READ", "ABSENT")
        assertEquals(
            Refinement.Rejected(PublicToolInputFailure.SchemaRejected),
            PublicToolContract.admit(PublicToolIdentity.QUERY_SYMBOLS, Json.parseToJsonElement(malformed)),
        )
    }

    private fun assertEncodedQuestion(run: QueryRunRequest.Run) {
        val encoded = json.encodeToJsonElement(QueryRunRequest.serializer(), run).jsonObject
        val retained = encoded.getValue("from").jsonObject.getValue("investigation").jsonObject
        assertEquals(setOf("seeds", "declarations", "domain", "flow", "models", "requestedSites"), retained.keys)
        assertEquals("KOTLIN_FORWARD_V1", retained.getValue("flow").jsonPrimitive.content)
        assertEquals(
            "custom-encryption",
            retained
                .getValue("models")
                .jsonArray
                .single()
                .jsonObject
                .getValue("model")
                .jsonObject
                .getValue("id")
                .jsonPrimitive
                .content,
        )
    }

    private fun declarationReference() =
        ImpactDeclarationReferenceDocument(
            basis =
                ImpactSemanticBasisDocument.Published(
                    text("/workspace"),
                    ImpactEvidenceRevisionDocument.parse(7).refined(),
                ),
            file = text("/workspace/File.kt"),
            range = range(0, 200),
            compilerIdentity = text("canonical-signature-sha256-v1|" + "a".repeat(64)),
        )

    private fun originModel(declaration: ImpactDeclarationReferenceDocument) =
        ImpactModelDocument.Representation(
            schemaVersion = ImpactModelFormatDocument.Current,
            model =
                ImpactModelIdentityDocument(
                    id("custom-encryption"),
                    ImpactModelVersionDocument.parse(1).refined(),
                    id("review:913"),
                ),
            states = bounded(listOf(id("HIPED"))),
            rules =
                bounded(
                    listOf(
                        ImpactRepresentationRuleDocument.Origin(
                            id("encrypt-result"),
                            ImpactCallablePositionDocument(declaration, ImpactModelValuePositionDocument.Result),
                            id("HIPED"),
                        )
                    )
                ),
        )

    private fun producerSeeds() =
        bounded(
            listOf(
                QueryImpactProducerDocument(
                    text("NON_ISSUED_ENCLOSING_TEST_ONLY"),
                    text("NON_ISSUED_CALLABLE_TEST_ONLY"),
                    range(20, 35),
                )
            )
        )

    private fun sourceDomain() =
        PublicToolImpactSourceDomain(
            sourceSets = bounded(listOf(text("main"))),
            directory = text("src/main"),
            includeSubdirectories = false,
            sourcePolicy = PublicToolSourcePolicy.PRODUCTION_ONLY,
            generatedSources = PublicToolGeneratedSources.EXCLUDE,
        )

    private fun range(start: Int, end: Int) =
        ImpactSourceRangeDocument(ProtocolOffset.parse(start).refined(), ProtocolOffset.parse(end).refined())

    private fun text(value: String) = ProtocolText.parse(value).refined()

    private fun id(value: String) = ImpactModelIdentifierDocument.parse(value).refined()

    private fun <T> bounded(values: List<T>) = BoundedProtocolList.create(values).refined()

    private fun <V, F> Refinement<V, F>.refined(): V =
        when (this) {
            is Refinement.Refined -> value
            is Refinement.Rejected -> error(failure.toString())
        }
}
