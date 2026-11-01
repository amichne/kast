package io.github.amichne.kast.cli

import com.networknt.schema.InputFormat
import com.networknt.schema.SchemaRegistry
import com.networknt.schema.SpecificationVersion
import io.github.amichne.kast.kernel.ElapsedTimeLimitMillis
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.ResultLimit
import io.github.amichne.kast.kernel.ReturnedByteLimit
import io.github.amichne.kast.kernel.WorkUnitLimit
import io.github.amichne.kast.protocol.contract.BoundedProtocolList
import io.github.amichne.kast.protocol.contract.ImpactCallFrameDocument
import io.github.amichne.kast.protocol.contract.ImpactCallTargetDocument
import io.github.amichne.kast.protocol.contract.ImpactCallbackInputFailureDocument
import io.github.amichne.kast.protocol.contract.ImpactCaptureInvocationDocument
import io.github.amichne.kast.protocol.contract.ImpactCapturedBodyDocument
import io.github.amichne.kast.protocol.contract.ImpactCompilerTransferDocument
import io.github.amichne.kast.protocol.contract.ImpactDeclarationReferenceDocument
import io.github.amichne.kast.protocol.contract.ImpactDeclaredCallbackValueBindingDocument
import io.github.amichne.kast.protocol.contract.ImpactEvidenceRevisionDocument
import io.github.amichne.kast.protocol.contract.ImpactExecutionOwnerDocument
import io.github.amichne.kast.protocol.contract.ImpactFlowBudgetDocument
import io.github.amichne.kast.protocol.contract.ImpactFlowDomainDocument
import io.github.amichne.kast.protocol.contract.ImpactFlowReadPositionDocument
import io.github.amichne.kast.protocol.contract.ImpactImplementationCoverageDocument
import io.github.amichne.kast.protocol.contract.ImpactImplementationDispatchDocument
import io.github.amichne.kast.protocol.contract.ImpactImplementationEndpointDocument
import io.github.amichne.kast.protocol.contract.ImpactImplementationFactDocument
import io.github.amichne.kast.protocol.contract.ImpactImplementationOccurrenceDocument
import io.github.amichne.kast.protocol.contract.ImpactInvocationReferenceDocument
import io.github.amichne.kast.protocol.contract.ImpactSemanticBasisDocument
import io.github.amichne.kast.protocol.contract.ImpactSourceRangeDocument
import io.github.amichne.kast.protocol.contract.ImpactValueRoleDocument
import io.github.amichne.kast.protocol.contract.ImpactValueSiteReferenceDocument
import io.github.amichne.kast.protocol.contract.ProtocolOffset
import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.protocol.contract.QueryDiscoveryCountDocument
import io.github.amichne.kast.protocol.contract.QueryDiscoveryInclusionPolicyDocument
import io.github.amichne.kast.protocol.contract.QueryDiscoverySourcePolicyDocument
import io.github.amichne.kast.protocol.contract.QueryDiscoverySourceSetsDocument
import io.github.amichne.kast.protocol.contract.QueryRelationDomainDocument
import io.github.amichne.kast.protocol.contract.QueryRelationDomainFingerprint
import io.github.amichne.kast.protocol.contract.QueryRelationRequestedDomainDocument
import io.github.amichne.kast.protocol.contract.QuerySemanticScopeDocument
import io.github.amichne.kast.protocol.contract.RelationFactCoverageDocument
import io.github.amichne.kast.protocol.contract.RelationKindDocument
import io.github.amichne.kast.protocol.contract.RelationProvenanceDocument
import io.github.amichne.kast.protocol.contract.RelationProviderDocument
import io.github.amichne.kast.protocol.registry.PublicToolIdentity
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ImplementationDispatchSchemaReuseTest {
    private val json = Json { encodeDefaults = true }
    private val schema by lazy { installedPublicToolOutputSchema(PublicToolIdentity.QUERY_SYMBOLS) }
    private val independent by lazy {
        independentFactoredImpactSchema(ImpactImplementationDispatchDocument.serializer())
    }

    @Test
    fun `shared impact definitions retain their independent complete serializer shapes`() {
        val assertions = LocalSchemaAssertions(schema)
        assertions.assertLocalReferences()
        val independentSchemas = IndependentImpactSchemas()
        val serializers =
            mapOf(
                "impactSemanticBasis" to ImpactSemanticBasisDocument.serializer(),
                "impactDeclarationReference" to ImpactDeclarationReferenceDocument.serializer(),
                "impactInvocationReference" to ImpactInvocationReferenceDocument.serializer(),
                "impactExecutionOwner" to ImpactExecutionOwnerDocument.serializer(),
                "impactCapturedBody" to ImpactCapturedBodyDocument.serializer(),
                "impactCaptureInvocation" to ImpactCaptureInvocationDocument.serializer(),
                "impactCallFrame" to ImpactCallFrameDocument.serializer(),
                "impactCallTarget" to ImpactCallTargetDocument.serializer(),
                "impactValueRole" to ImpactValueRoleDocument.serializer(),
                "impactValueSiteReference" to ImpactValueSiteReferenceDocument.serializer(),
                "impactCompilerTransfer" to ImpactCompilerTransferDocument.serializer(),
                "impactImplementationDispatch" to ImpactImplementationDispatchDocument.serializer(),
                "impactCallbackInputFailure" to ImpactCallbackInputFailureDocument.serializer(),
                "impactFlowDomain" to ImpactFlowDomainDocument.serializer(),
                "impactDeclaredCallbackValueBinding" to ImpactDeclaredCallbackValueBindingDocument.serializer(),
            )
        for ((name, serializer) in serializers) {
            assertions.assertDefinitionEquivalent(
                name,
                independentSchemas.graph(serializer),
            )
        }
    }

    @Test
    fun `factored V1 dispatch positions reuse the exact independent serializer shape with local references`() {
        val assertions = LocalSchemaAssertions(schema)
        assertions.assertLocalReferences()
        assertions.assertDefinitionEquivalent("impactImplementationDispatch", independent)
        assertEquals(5, referenceCount(schema, "impactImplementationDispatch"))
    }

    @Test
    fun `resolved dispatch definition admits canonical proof shape and rejects malformed nested evidence`() {
        val registry = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
        val validators =
            listOf(
                CallbackSchemaFixture(schema).validator("impactImplementationDispatch"),
                registry.getSchema(
                    independentImpactSchemaWithDefinitions(ImpactImplementationDispatchDocument.serializer()).toString()
                ),
            )
        val admitted = json.encodeToString(ImpactImplementationDispatchDocument.serializer(), dispatch())
        validators.forEach { assertTrue(it.validate(admitted, InputFormat.JSON).isEmpty(), admitted) }
        for ((before, after) in
            listOf(
                "\"subjectFingerprint\":\"${"b".repeat(64)}\"" to "\"subjectFingerprint\":\"bad\"",
                "\"INTELLIJ_DEFINITIONS_V2\"" to "\"UNPROVEN_PROVIDER\"",
                "\"authority\":" to "\"unprovenAuthority\":",
                "\"K2_AUTHORED_SOURCE\"" to "\"INFERRED_SOURCE\"",
                "\"EXACT_COMPILER_CONFIRMED\"" to "\"INFERRED_MATCH\"",
                "\"exactCount\":1" to "\"exactCount\":-1",
                "\"facts\":" to "\"unprovenFacts\":",
            )) {
            val rejected = admitted.replace(before, after)
            assertNotEquals(admitted, rejected, "Negative control must change the admitted proof")
            validators.forEach { assertFalse(it.validate(rejected, InputFormat.JSON).isEmpty(), rejected) }
        }
    }

    private fun dispatch(): ImpactImplementationDispatchDocument {
        val basis =
            ImpactSemanticBasisDocument.Published(text("/workspace"), ImpactEvidenceRevisionDocument.parse(7).value())
        val subject =
            ImpactDeclarationReferenceDocument(
                basis,
                text("/workspace/Api.kt"),
                ImpactSourceRangeDocument(ProtocolOffset.parse(0).value(), ProtocolOffset.parse(100).value()),
                text("canonical-signature-sha256-v1|" + "b".repeat(64)),
            )
        val implementation =
            subject.copy(
                file = text("/workspace/Impl.kt"),
                compilerIdentity = text("canonical-signature-sha256-v1|" + "c".repeat(64)),
            )
        val endpoint = ImpactImplementationEndpointDocument(subject, text("b".repeat(64)))
        val fact =
            ImpactImplementationFactDocument(
                endpoint,
                ImpactImplementationEndpointDocument(implementation, text("c".repeat(64))),
                endpoint,
                ImpactImplementationOccurrenceDocument(implementation.file, implementation.range),
                basis,
                RelationProvenanceDocument.K2_AUTHORED_SOURCE,
                RelationFactCoverageDocument.EXACT_COMPILER_CONFIRMED,
            )
        return ImpactImplementationDispatchDocument(
            question = question(subject),
            subjectFingerprint = text("b".repeat(64)),
            provider = RelationProviderDocument.INTELLIJ_DEFINITIONS_V2,
            facts = bounded(listOf(fact)),
            encodedBytes = count(300),
            examinedWorkUnits = count(1),
            resultCount = count(1),
            omissions = bounded(emptyList()),
            scopeExclusions = bounded(emptyList()),
            coverage = ImpactImplementationCoverageDocument.Exhausted(count(1)),
        )
    }

    private fun question(subject: ImpactDeclarationReferenceDocument): ImpactFlowDomainDocument =
        ImpactFlowDomainDocument(
            subject,
            RelationKindDocument.IMPLEMENTATIONS,
            QueryRelationRequestedDomainDocument.WORKSPACE,
            QueryRelationDomainDocument(
                QuerySemanticScopeDocument.Workspace,
                QueryDiscoverySourcePolicyDocument.PRODUCTION_ONLY,
                QueryDiscoveryInclusionPolicyDocument.EXCLUDE,
                QueryDiscoveryInclusionPolicyDocument.EXCLUDE,
                QueryDiscoverySourceSetsDocument.All,
                null,
                null,
                bounded(emptyList()),
            ),
            QueryRelationDomainFingerprint.parse("b".repeat(64)).value(),
            ImpactFlowBudgetDocument(
                ElapsedTimeLimitMillis.parse(100).value(),
                WorkUnitLimit.parse(10).value(),
                ResultLimit.parse(2).value(),
                ReturnedByteLimit.parse(10000).value(),
            ),
            ImpactFlowReadPositionDocument.Start,
        )

    private fun text(value: String) = ProtocolText.parse(value).value()

    private fun count(value: Long) = QueryDiscoveryCountDocument.parse(value).value()

    private fun <T> bounded(values: List<T>) = BoundedProtocolList.create(values).value()

    private fun <T> Refinement<T, *>.value(): T = (this as Refinement.Refined).value
}
