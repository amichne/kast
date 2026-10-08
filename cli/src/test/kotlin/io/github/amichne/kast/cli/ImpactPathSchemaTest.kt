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
import io.github.amichne.kast.protocol.contract.ImpactBoundaryContractDocument
import io.github.amichne.kast.protocol.contract.ImpactBoundaryKindDocument
import io.github.amichne.kast.protocol.contract.ImpactBoundaryObligationDocument
import io.github.amichne.kast.protocol.contract.ImpactBoundaryPositionDocument
import io.github.amichne.kast.protocol.contract.ImpactBoundaryRequiredDocument
import io.github.amichne.kast.protocol.contract.ImpactBoundaryRuleDocument
import io.github.amichne.kast.protocol.contract.ImpactBoundaryTerminalDocument
import io.github.amichne.kast.protocol.contract.ImpactBoundaryUnresolvedDocument
import io.github.amichne.kast.protocol.contract.ImpactBranchCompletionDocument
import io.github.amichne.kast.protocol.contract.ImpactCallablePositionDocument
import io.github.amichne.kast.protocol.contract.ImpactCompilerTransferDocument
import io.github.amichne.kast.protocol.contract.ImpactConsumerOutcomeDocument
import io.github.amichne.kast.protocol.contract.ImpactDeclarationReferenceDocument
import io.github.amichne.kast.protocol.contract.ImpactEvidenceRevisionDocument
import io.github.amichne.kast.protocol.contract.ImpactExecutionStopDocument
import io.github.amichne.kast.protocol.contract.ImpactFlowBudgetDocument
import io.github.amichne.kast.protocol.contract.ImpactFlowDomainDocument
import io.github.amichne.kast.protocol.contract.ImpactFlowEndObservationDocument
import io.github.amichne.kast.protocol.contract.ImpactFlowReadPositionDocument
import io.github.amichne.kast.protocol.contract.ImpactFlowTerminalDocument
import io.github.amichne.kast.protocol.contract.ImpactFlowUnsupportedDocument
import io.github.amichne.kast.protocol.contract.ImpactModelIdentifierDocument
import io.github.amichne.kast.protocol.contract.ImpactModelIdentityDocument
import io.github.amichne.kast.protocol.contract.ImpactModelValuePositionDocument
import io.github.amichne.kast.protocol.contract.ImpactModelVersionDocument
import io.github.amichne.kast.protocol.contract.ImpactNativeReadRejectionDocument
import io.github.amichne.kast.protocol.contract.ImpactPathDocument
import io.github.amichne.kast.protocol.contract.ImpactPathTerminalDocument
import io.github.amichne.kast.protocol.contract.ImpactPeerAcquisitionReceiptDocument
import io.github.amichne.kast.protocol.contract.ImpactPeerContinuationReasonDocument
import io.github.amichne.kast.protocol.contract.ImpactPeerSiteAdmissionDocument
import io.github.amichne.kast.protocol.contract.ImpactReadContractRejectionDocument
import io.github.amichne.kast.protocol.contract.ImpactReadRejectionDocument
import io.github.amichne.kast.protocol.contract.ImpactRepresentationEvidenceDocument
import io.github.amichne.kast.protocol.contract.ImpactRepresentationRuleDocument
import io.github.amichne.kast.protocol.contract.ImpactRequestedBoundaryDocument
import io.github.amichne.kast.protocol.contract.ImpactRuleReferenceDocument
import io.github.amichne.kast.protocol.contract.ImpactScopeExclusionDocument
import io.github.amichne.kast.protocol.contract.ImpactSemanticBasisDocument
import io.github.amichne.kast.protocol.contract.ImpactSiteAdmissionDocument
import io.github.amichne.kast.protocol.contract.ImpactSourceRangeDocument
import io.github.amichne.kast.protocol.contract.ImpactTransferEvidenceDocument
import io.github.amichne.kast.protocol.contract.ImpactTransferKindDocument
import io.github.amichne.kast.protocol.contract.ImpactTryBranchAlternativeDocument
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
import io.github.amichne.kast.protocol.contract.RelationKindDocument
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ImpactPathSchemaTest {
    @Test
    fun `transfer schema requires closed normal completion evidence and preserves exact branch anchors`() {
        val document = generatedRequestSchema(ImpactCompilerTransferDocument.serializer())
        assertEquals(setOf("source", "target", "kind", "evidence"), requiredFields(document))
        val schema =
            SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12).getSchema(document.toString())
        val f = Fixture()
        val target =
            ImpactValueSiteReferenceDocument(
                f.declaration,
                ImpactSourceRangeDocument(offset(5), offset(90)),
                ImpactValueRoleDocument.ExpressionResult,
            )
        val branchRange = ImpactSourceRangeDocument(offset(8), offset(15))
        for (alternative in
            listOf(ImpactTryBranchAlternativeDocument.TryBody, ImpactTryBranchAlternativeDocument.CatchBody(2))) {
            val transfer =
                ImpactCompilerTransferDocument(
                    f.site,
                    target,
                    ImpactTransferKindDocument.BRANCH_ALTERNATIVE,
                    ImpactTransferEvidenceDocument.NormalBranchResult(
                        target.range,
                        branchRange,
                        alternative,
                        ImpactBranchCompletionDocument.NORMAL_COMPLETION,
                    ),
                )
            assertTrue(transfer.admitsEvidence())
            val raw = Json.encodeToString(ImpactCompilerTransferDocument.serializer(), transfer)
            val proof = Json.parseToJsonElement(raw).jsonObject.getValue("evidence").jsonObject
            assertEquals("NORMAL_BRANCH_RESULT", proof.getValue("type").jsonPrimitive.content)
            assertEquals("NORMAL_COMPLETION", proof.getValue("condition").jsonPrimitive.content)
            assertEquals("8", proof.getValue("branch_range").jsonObject.getValue("start").jsonPrimitive.content)
            assertTrue(schema.validate(raw, InputFormat.JSON).isEmpty(), raw)
            for (malformed in
                listOf(
                    raw.replace("\"condition\":\"NORMAL_COMPLETION\"", "\"condition\":\"EXCEPTION_ENTRY\""),
                    raw.replace("\"condition\":\"NORMAL_COMPLETION\"", "\"unknown\":true"),
                    raw.replace("\"type\":\"NORMAL_BRANCH_RESULT\"", "\"type\":\"ASSUMED\""),
                    raw.replace("\"type\":\"NORMAL_BRANCH_RESULT\",", ""),
                )) assertTrue(schema.validate(malformed, InputFormat.JSON).isNotEmpty(), malformed)
        }
    }

    @Test
    fun `branch transfer admission rejects a source outside its originating branch`() {
        val f = Fixture()
        val target =
            ImpactValueSiteReferenceDocument(
                f.declaration,
                ImpactSourceRangeDocument(offset(5), offset(90)),
                ImpactValueRoleDocument.ExpressionResult,
            )
        for (alternative in
            listOf(ImpactTryBranchAlternativeDocument.TryBody, ImpactTryBranchAlternativeDocument.CatchBody(2))) {
            val otherBranch =
                ImpactTransferEvidenceDocument.NormalBranchResult(
                    target.range,
                    ImpactSourceRangeDocument(offset(30), offset(40)),
                    alternative,
                    ImpactBranchCompletionDocument.NORMAL_COMPLETION,
                )
            assertTrue(
                !ImpactCompilerTransferDocument(
                        f.site,
                        target,
                        ImpactTransferKindDocument.BRANCH_ALTERNATIVE,
                        otherBranch,
                    )
                    .admitsEvidence()
            )
        }
    }

    @Test
    fun `direct transfer schema requires explicit evidence discriminator`() {
        val schema =
            SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
                .getSchema(generatedRequestSchema(ImpactCompilerTransferDocument.serializer()).toString())
        val f = Fixture()
        val target =
            ImpactValueSiteReferenceDocument(
                f.declaration,
                ImpactSourceRangeDocument(offset(5), offset(90)),
                ImpactValueRoleDocument.ExpressionResult,
            )
        val direct =
            Json.encodeToString(
                ImpactCompilerTransferDocument.serializer(),
                ImpactCompilerTransferDocument(f.site, target, ImpactTransferKindDocument.BRANCH_ALTERNATIVE),
            )
        assertEquals(
            "DIRECT",
            Json.parseToJsonElement(direct)
                .jsonObject
                .getValue("evidence")
                .jsonObject
                .getValue("type")
                .jsonPrimitive
                .content,
        )
        assertTrue(
            schema.validate(direct.replace(",\"evidence\":{\"type\":\"DIRECT\"}", ""), InputFormat.JSON).isNotEmpty()
        )
    }

    @Test
    fun `path schema closes terminal evidence variants and preserves all required discriminator shapes`() {
        val document = generatedRequestSchema(ImpactPathDocument.serializer())
        val terminal = document.getValue("properties").jsonObject.getValue("terminal").jsonObject
        assertEquals(
            "type",
            terminal.getValue("discriminator").jsonObject.getValue("propertyName").jsonPrimitive.content,
        )
        val variants = terminal.getValue("anyOf").jsonArray
        assertEquals(
            setOf(
                "CONSUMER",
                "MODELED_TERMINAL",
                "UNRESOLVED_FLOW",
                "UNRESOLVED_BOUNDARY",
                "UNRESOLVED_READ",
                "UNRESOLVED_PEER_CONTINUATION",
                "EXECUTION_STOP",
                "SUPPORTED_DOMAIN_END",
                "EXPLICIT_SCOPE_EXCLUSION",
            ),
            discriminatorValues(variants),
        )
        for (variant in variants) {
            assertEquals("false", variant.jsonObject.getValue("additionalProperties").jsonPrimitive.content)
            assertTrue(variant.jsonObject.getValue("required").jsonArray.any { it.jsonPrimitive.content == "type" })
        }
        val schema =
            SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12).getSchema(document.toString())
        for (example in examples()) {
            val raw = Json.encodeToString(ImpactPathDocument.serializer(), example)
            assertTrue(schema.validate(raw, InputFormat.JSON).isEmpty(), raw)
            for (malformed in
                listOf(
                    raw.dropLast(1) + ",\"complete\":true}",
                    raw.replaceFirst("\"generation\":7", "\"generation\":\"7\""),
                    raw.replaceFirst("\"type\":\"NOT_MODELED\"", "\"type\":\"ASSUMED\""),
                )) assertTrue(schema.validate(malformed, InputFormat.JSON).isNotEmpty(), malformed)
        }
    }

    @Test
    fun `peer path terminal requires exact target completed receipt and unresolved reason`() {
        val document = generatedRequestSchema(ImpactPathTerminalDocument.serializer())
        val peer =
            document
                .getValue("anyOf")
                .jsonArray
                .single {
                    it.jsonObject
                        .getValue("properties")
                        .jsonObject
                        .getValue("type")
                        .jsonObject
                        .getValue("enum")
                        .jsonArray
                        .single()
                        .jsonPrimitive
                        .content == "UNRESOLVED_PEER_CONTINUATION"
                }
                .jsonObject
        assertEquals(setOf("type", "reference", "target", "admission", "reason"), requiredFields(peer))
        val admission = peer.getValue("properties").jsonObject.getValue("admission").jsonObject
        assertEquals(setOf("acquisition", "selection"), requiredFields(admission))
        val fields = admission.getValue("properties").jsonObject
        assertEquals(
            setOf("completedBasis", "budget", "examinedWorkUnits", "elapsedNanos"),
            requiredFields(fields.getValue("acquisition").jsonObject),
        )
        assertEquals(setOf("budget", "examinedWorkUnits"), requiredFields(fields.getValue("selection").jsonObject))
        val schema =
            SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12).getSchema(document.toString())
        val raw = Json.encodeToString(ImpactPathTerminalDocument.serializer(), Fixture().peerTerminal)
        assertTrue(schema.validate(raw, InputFormat.JSON).isEmpty(), raw)
        for (malformed in
            listOf(
                raw.replace(",\"reason\":\"PEER_FLOW_NOT_INVESTIGATED\"", ""),
                raw.replace(",\"elapsedNanos\":2", ""),
                raw.replace("PEER_FLOW_NOT_INVESTIGATED", "ASSUMED_PEER_COMPLETE"),
            )) {
            assertTrue(malformed != raw)
            assertTrue(schema.validate(malformed, InputFormat.JSON).isNotEmpty(), malformed)
        }
    }

    private fun requiredFields(schema: kotlinx.serialization.json.JsonObject): Set<String> =
        schema.getValue("required").jsonArray.map { it.jsonPrimitive.content }.toSet()

    private fun discriminatorValues(variants: kotlinx.serialization.json.JsonArray): Set<String> =
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
            .toSet()

    private fun examples(): List<ImpactPathDocument> =
        with(Fixture()) {
            terminals.map {
                ImpactPathDocument(site, bounded(emptyList()), ImpactRepresentationEvidenceDocument.NotModeled, it)
            }
        }

    private inner class Fixture {

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
        val site =
            ImpactValueSiteReferenceDocument(
                declaration,
                ImpactSourceRangeDocument(offset(10), offset(11)),
                ImpactValueRoleDocument.ExpressionResult,
            )
        val boundary =
            ImpactBoundaryPositionDocument(
                site,
                ImpactBoundaryKindDocument.PERSISTENCE,
                ImpactBoundaryContractDocument(id("cache"), ImpactModelVersionDocument.parse(1).refined()),
                id("ciphertext"),
            )
        val model =
            ImpactModelIdentityDocument(
                id("encryption"),
                ImpactModelVersionDocument.parse(1).refined(),
                id("review:914"),
            )
        val reference = ImpactRuleReferenceDocument(model, id("rule"))
        val obligations =
            bounded(
                listOf(
                    ImpactBoundaryObligationDocument(
                        boundary,
                        bounded(
                            listOf(
                                ImpactBoundaryRequiredDocument.RETENTION_POLICY,
                                ImpactBoundaryRequiredDocument.DECODING_COMPATIBILITY,
                                ImpactBoundaryRequiredDocument.MIGRATION_PROOF,
                            )
                        ),
                    )
                )
            )
        val domain =
            QueryRelationDomainDocument(
                QuerySemanticScopeDocument.ExactFile(text("/workspace/File.kt")),
                QueryDiscoverySourcePolicyDocument.PRODUCTION_AND_TEST,
                QueryDiscoveryInclusionPolicyDocument.EXCLUDE,
                QueryDiscoveryInclusionPolicyDocument.EXCLUDE,
                QueryDiscoverySourceSetsDocument.All,
                null,
                null,
                bounded(emptyList()),
            )
        val fingerprint = QueryRelationDomainFingerprint.parse("b".repeat(64)).refined()
        val end =
            ImpactFlowEndObservationDocument(
                site,
                ImpactFlowDomainDocument(
                    declaration,
                    RelationKindDocument.REFERENCES,
                    QueryRelationRequestedDomainDocument.RETAINED_SEED,
                    domain,
                    fingerprint,
                    ImpactFlowBudgetDocument(
                        ElapsedTimeLimitMillis.parse(21).refined(),
                        WorkUnitLimit.parse(13).refined(),
                        ResultLimit.parse(8).refined(),
                        ReturnedByteLimit.parse(1024).refined(),
                    ),
                    ImpactFlowReadPositionDocument.Start,
                ),
                QueryDiscoveryCountDocument.parse(1).refined(),
                QueryDiscoveryCountDocument.parse(2048).refined(),
                ImpactFlowTerminalDocument.SUPPORTED_DOMAIN_EXHAUSTED,
            )
        val peerTerminal =
            ImpactPathTerminalDocument.UnresolvedPeerContinuation(
                reference,
                site.copy(
                    enclosing =
                        declaration.copy(
                            basis =
                                ImpactSemanticBasisDocument.Published(
                                    text("/peer"),
                                    ImpactEvidenceRevisionDocument.parse(9).refined(),
                                ),
                            file = text("/peer/File.kt"),
                        )
                ),
                ImpactPeerSiteAdmissionDocument(
                    ImpactPeerAcquisitionReceiptDocument(
                        ImpactSemanticBasisDocument.Published(
                            text("/peer"),
                            ImpactEvidenceRevisionDocument.parse(9).refined(),
                        ),
                        end.domain.budget,
                        QueryDiscoveryCountDocument.parse(3).refined(),
                        QueryDiscoveryCountDocument.parse(2).refined(),
                    ),
                    ImpactSiteAdmissionDocument(end.domain.budget, QueryDiscoveryCountDocument.parse(1).refined()),
                ),
                ImpactPeerContinuationReasonDocument.PEER_FLOW_NOT_INVESTIGATED,
            )
        val terminals =
            listOf(
                ImpactPathTerminalDocument.Consumer(
                    site,
                    reference,
                    ImpactRepresentationRuleDocument.ConsumerExpectation(
                        id("rule"),
                        ImpactCallablePositionDocument(
                            declaration,
                            ImpactModelValuePositionDocument.Argument(offset(0)),
                        ),
                        id("HIPED"),
                    ),
                    ImpactConsumerOutcomeDocument.UNKNOWN,
                ),
                ImpactPathTerminalDocument.ModeledTerminal(
                    reference,
                    ImpactBoundaryRuleDocument.Terminal(
                        id("rule"),
                        boundary,
                        ImpactBoundaryTerminalDocument.REVIEWED_RETENTION,
                    ),
                    obligations,
                ),
                ImpactPathTerminalDocument.UnresolvedRead(
                    ImpactReadRejectionDocument.Native(
                        site,
                        ImpactRequestedBoundaryDocument.Workspace,
                        ImpactNativeReadRejectionDocument.NESTED_EXECUTION,
                        QueryDiscoveryCountDocument.parse(3).refined(),
                    )
                ),
                ImpactPathTerminalDocument.UnresolvedRead(
                    ImpactReadRejectionDocument.Contract(
                        site,
                        ImpactRequestedBoundaryDocument.Workspace,
                        ImpactReadContractRejectionDocument.DOMAIN_MISMATCH,
                        QueryDiscoveryCountDocument.parse(2).refined(),
                    )
                ),
                ImpactPathTerminalDocument.ExecutionStop(
                    ImpactExecutionStopDocument.CheckpointCapacity(
                        site,
                        QueryDiscoveryCountDocument.parse(2048).refined(),
                        QueryDiscoveryCountDocument.parse(1024).refined(),
                    )
                ),
                ImpactPathTerminalDocument.UnresolvedFlow(site, ImpactFlowUnsupportedDocument.EXTERNAL_CALL),
                ImpactPathTerminalDocument.UnresolvedBoundary(
                    boundary,
                    ImpactBoundaryUnresolvedDocument.MISSING_CONSUMER,
                    obligations.values.single(),
                ),
                ImpactPathTerminalDocument.SupportedDomainEnd(end),
                ImpactPathTerminalDocument.ExplicitScopeExclusion(
                    site,
                    domain,
                    ImpactScopeExclusionDocument.OUTSIDE_EXACT_FILE,
                ),
                peerTerminal,
            )
    }

    private fun text(raw: String) = ProtocolText.parse(raw).refined()

    private fun offset(raw: Int) = ProtocolOffset.parse(raw).refined()

    private fun id(raw: String) = ImpactModelIdentifierDocument.parse(raw).refined()

    private fun <T> bounded(values: List<T>) = BoundedProtocolList.create(values).refined()

    private fun <S, F> Refinement<S, F>.refined(): S =
        when (this) {
            is Refinement.Refined -> value
            is Refinement.Rejected -> error(failure.toString())
        }
}
