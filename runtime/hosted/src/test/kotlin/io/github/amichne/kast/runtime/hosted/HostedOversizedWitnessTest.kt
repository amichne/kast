package io.github.amichne.kast.runtime.hosted

import io.github.amichne.kast.kernel.ElapsedTimeLimitMillis
import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.kernel.ReadLimits
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.ResultLimit
import io.github.amichne.kast.kernel.ReturnedByteLimit
import io.github.amichne.kast.kernel.WorkUnitLimit
import io.github.amichne.kast.protocol.contract.BoundedProtocolList
import io.github.amichne.kast.protocol.contract.ImpactAccountingDocument
import io.github.amichne.kast.protocol.contract.ImpactAccountingStatusDocument
import io.github.amichne.kast.protocol.contract.ImpactAccountingViewDocument
import io.github.amichne.kast.protocol.contract.ImpactClosureDocument
import io.github.amichne.kast.protocol.contract.ImpactDeclarationReferenceDocument
import io.github.amichne.kast.protocol.contract.ImpactEvidenceRevisionDocument
import io.github.amichne.kast.protocol.contract.ImpactFlowBudgetDocument
import io.github.amichne.kast.protocol.contract.ImpactFlowDomainDocument
import io.github.amichne.kast.protocol.contract.ImpactFlowReadPositionDocument
import io.github.amichne.kast.protocol.contract.ImpactFlowSemanticsDocument
import io.github.amichne.kast.protocol.contract.ImpactNativeReadReceiptDocument
import io.github.amichne.kast.protocol.contract.ImpactNativeReadRejectionDocument
import io.github.amichne.kast.protocol.contract.ImpactReadRejectionDocument
import io.github.amichne.kast.protocol.contract.ImpactRequestedBoundaryDocument
import io.github.amichne.kast.protocol.contract.ImpactRequiredObligationDocument
import io.github.amichne.kast.protocol.contract.ImpactSemanticBasisDocument
import io.github.amichne.kast.protocol.contract.ImpactSourceRangeDocument
import io.github.amichne.kast.protocol.contract.ImpactValueRoleDocument
import io.github.amichne.kast.protocol.contract.ImpactValueSiteReferenceDocument
import io.github.amichne.kast.protocol.contract.ImpactWitnessDocument
import io.github.amichne.kast.protocol.contract.ImpactWitnessItemDocument
import io.github.amichne.kast.protocol.contract.ImpactWitnessSectionDocument
import io.github.amichne.kast.protocol.contract.ProtocolOffset
import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.protocol.contract.QueryDiscoveryCountDocument
import io.github.amichne.kast.protocol.contract.QueryDiscoveryInclusionPolicyDocument
import io.github.amichne.kast.protocol.contract.QueryDiscoverySourcePolicyDocument
import io.github.amichne.kast.protocol.contract.QueryDiscoverySourceSetsDocument
import io.github.amichne.kast.protocol.contract.QueryEntryDetailSelectorDocument
import io.github.amichne.kast.protocol.contract.QueryExpansionScopeDocument
import io.github.amichne.kast.protocol.contract.QueryFromDocument
import io.github.amichne.kast.protocol.contract.QueryImpactFlowDocument
import io.github.amichne.kast.protocol.contract.QueryImpactProducerDocument
import io.github.amichne.kast.protocol.contract.QueryImpactSourceDocument
import io.github.amichne.kast.protocol.contract.QueryKnownMinimum
import io.github.amichne.kast.protocol.contract.QueryLimitationDocument
import io.github.amichne.kast.protocol.contract.QueryOutputDocument
import io.github.amichne.kast.protocol.contract.QueryQualifiedProgressDocument
import io.github.amichne.kast.protocol.contract.QueryQuestionDocument
import io.github.amichne.kast.protocol.contract.QueryRelationDomainDocument
import io.github.amichne.kast.protocol.contract.QueryRelationDomainFingerprint
import io.github.amichne.kast.protocol.contract.QueryRelationRequestedDomainDocument
import io.github.amichne.kast.protocol.contract.QueryResultItemDocument
import io.github.amichne.kast.protocol.contract.QueryRunQualification
import io.github.amichne.kast.protocol.contract.QueryRunResult
import io.github.amichne.kast.protocol.contract.QuerySemanticScopeDocument
import io.github.amichne.kast.protocol.contract.QueryTerminalReasonDocument
import io.github.amichne.kast.protocol.contract.RelationKindDocument
import io.github.amichne.kast.protocol.contract.presentationPrefix
import io.github.amichne.kast.protocol.contract.validateImpactAccounting
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HostedOversizedWitnessTest {
    @Test
    fun `large witness preserves receipt order and multiplicity through selected detail chunks`() {
        val case = witnessCase()
        val response = encodeHostedQueryResponse(case.semantic, maximumBytes = ReturnedByteLimit.parse(8_000).value())
        assertInstanceOf(HostedResponse.Canonical::class.java, response)
        val fitted =
            ((response as HostedResponse.Canonical<*, *, *>).semantic as OperationOutcome.Qualified).evidence.payload
                as QueryRunResult
        assertEquals(2, fitted.items.values.size)
        val summary = fitted.items.values.first() as QueryResultItemDocument.DeferredDetail
        assertEquals(case.small, fitted.items.values[1])
        assertEquals(case.semantic.qualification, (response.semantic as OperationOutcome.Qualified).qualification)
        assertTrue(summary.detail.meaning.value.contains("READ_REJECTIONS"))
        val expected = Json {
            encodeDefaults = true
        }
            .encodeToString(ExpectedWitness.serializer(), ExpectedWitness(item = case.large.item))
        assertEquals(entryDigest(expected), summary.detail.selector.sha256.value)
        assertEquals(expected, reconstruct(case, summary.detail.selector, expected.length))
        assertEquals(500, case.receipts.size)
    }

    private data class WitnessCase(
        val semantic: OperationOutcome.Qualified<QueryRunResult, QueryRunQualification>,
        val large: QueryResultItemDocument.ImpactWitness,
        val small: QueryResultItemDocument.ImpactWitness,
        val receipts: List<ImpactNativeReadReceiptDocument>,
    )

    private fun witnessCase(): WitnessCase {
        val declaration = declaration()
        val site =
            ImpactValueSiteReferenceDocument(declaration, range(10, 11), ImpactValueRoleDocument.ExpressionResult)
        val domain = domain(declaration)
        val receipts =
            (0 until 500).map {
                ImpactNativeReadReceiptDocument(domain, count(it.toLong() + 1), count(0), count(it.toLong()))
            }
        val rejection =
            ImpactReadRejectionDocument.Native(
                site,
                ImpactRequestedBoundaryDocument.Workspace,
                ImpactNativeReadRejectionDocument.AUTHORITY_MOVED,
                count(125250),
            )
        val large = witness(0, ImpactWitnessDocument.ReadRejection(rejection, bounded(receipts)))
        val small = witness(1, ImpactWitnessDocument.ReadRejection(rejection))
        val base = HostedQueryRetainedPresentationTest().page(0, 2, 2)
        val payload =
            base.evidence.payload.copy(
                question = question(site),
                items = bounded(listOf(large, small)),
                impactAccounting = accounting(site),
                presentationOutput = QueryOutputDocument.ImpactWitness(ImpactWitnessSectionDocument.READ_REJECTIONS),
            )
        assertEquals(Refinement.Refined(Unit), payload.validateImpactAccounting())
        val qualification =
            QueryRunQualification.create(
                    QueryKnownMinimum.parse(3).value(),
                    listOf(QueryLimitationDocument.IMPACT_COVERAGE_UNPROVEN),
                    QueryQualifiedProgressDocument.TerminalIncomplete(QueryTerminalReasonDocument.UPSTREAM_INCOMPLETE),
                )
                .value()
        return WitnessCase(
            OperationOutcome.Qualified(base.evidence.copy(payload = payload), qualification),
            large,
            small,
            receipts,
        )
    }

    private fun declaration() =
        ImpactDeclarationReferenceDocument(
            ImpactSemanticBasisDocument.Published(text("/workspace"), ImpactEvidenceRevisionDocument.parse(7).value()),
            text("/workspace/Fixture.kt"),
            range(0, 100),
            text("canonical-signature-sha256-v1|" + "a".repeat(64)),
        )

    private fun domain(declaration: ImpactDeclarationReferenceDocument) =
        ImpactFlowDomainDocument(
            declaration,
            RelationKindDocument.REFERENCES,
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
                WorkUnitLimit.parse(1000).value(),
                ResultLimit.parse(1000).value(),
                ReturnedByteLimit.parse(1_000_000).value(),
            ),
            ImpactFlowReadPositionDocument.Start,
        )

    private fun witness(ordinal: Long, value: ImpactWitnessDocument) =
        QueryResultItemDocument.ImpactWitness(
            ImpactWitnessItemDocument(ImpactWitnessSectionDocument.READ_REJECTIONS, count(ordinal), value)
        )

    private fun question(site: ImpactValueSiteReferenceDocument) =
        QueryQuestionDocument(
            QueryFromDocument.Impact(
                QueryImpactSourceDocument(
                    bounded(
                        listOf(
                            QueryImpactProducerDocument(
                                text("exact:fixture-owner"),
                                text("exact:fixture-callable"),
                                site.range,
                            )
                        )
                    ),
                    bounded(emptyList()),
                    QueryExpansionScopeDocument.Workspace,
                    QueryImpactFlowDocument.KOTLIN_FORWARD_V1,
                    bounded(emptyList()),
                )
            ),
            bounded(emptyList()),
            QueryOutputDocument.ValuePaths,
        )

    private fun accounting(site: ImpactValueSiteReferenceDocument) =
        ImpactAccountingDocument.Investigated(
            bounded(listOf(site)),
            ImpactRequestedBoundaryDocument.Workspace,
            ImpactFlowSemanticsDocument.KOTLIN_FORWARD_V1,
            bounded(emptyList()),
            bounded(emptyList()),
            count(2),
            count(0),
            count(3),
            count(0),
            ImpactAccountingStatusDocument.SelectedSubset(
                ImpactClosureDocument.Unresolved(bounded(listOf(ImpactRequiredObligationDocument.NATIVE_FLOW)))
            ),
            ImpactAccountingViewDocument.Witness(
                ImpactWitnessSectionDocument.READ_REJECTIONS,
                count(0),
                count(2),
                count(2),
            ),
            bounded(emptyList()),
        )

    private fun reconstruct(case: WitnessCase, selector: QueryEntryDetailSelectorDocument, total: Int): String {
        val selected = case.semantic.evidence.payload.presentationPrefix(1).value()
        val fragments = mutableListOf<String>()
        var offset = 0
        while (offset < total) {
            assertTrue(fragments.size < 1000)
            val request = selector.copy(offset = ProtocolOffset.parse(offset).value())
            val page =
                fitHostedQueryEntryDetail(
                    case.semantic.copy(
                        evidence = case.semantic.evidence.copy(payload = selected.copy(detailSelection = request))
                    ),
                    ReadLimits.Default,
                    ReturnedByteLimit.parse(8_000).value(),
                )
                    as HostedResponse.Canonical<*, *, *>
            val detail = ((page.semantic as OperationOutcome.Qualified).evidence.payload as QueryRunResult).detail!!
            fragments += detail.text.value
            assertTrue(detail.end.value > offset)
            offset = detail.end.value
        }
        return fragments.joinToString("")
    }

    @Serializable
    private data class ExpectedWitness(val type: String = "IMPACT_WITNESS", val item: ImpactWitnessItemDocument)

    private fun text(value: String) = ProtocolText.parse(value).value()

    private fun count(value: Long) = QueryDiscoveryCountDocument.parse(value).value()

    private fun range(start: Int, end: Int) =
        ImpactSourceRangeDocument(ProtocolOffset.parse(start).value(), ProtocolOffset.parse(end).value())

    private fun <T> bounded(values: List<T>) = BoundedProtocolList.create(values).value()

    private fun <T, F> Refinement<T, F>.value(): T =
        when (this) {
            is Refinement.Refined -> value
            is Refinement.Rejected -> error(failure.toString())
        }
}
