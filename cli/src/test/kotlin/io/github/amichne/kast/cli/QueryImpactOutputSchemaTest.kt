package io.github.amichne.kast.cli

import io.github.amichne.kast.kernel.EvidenceBasis
import io.github.amichne.kast.kernel.EvidenceEnvelope
import io.github.amichne.kast.kernel.EvidenceGeneration
import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.BoundedProtocolList
import io.github.amichne.kast.protocol.contract.CanonicalOperation
import io.github.amichne.kast.protocol.contract.ImpactAccountingDocument
import io.github.amichne.kast.protocol.contract.ImpactAccountingStatusDocument
import io.github.amichne.kast.protocol.contract.ImpactAccountingViewDocument
import io.github.amichne.kast.protocol.contract.ImpactClosureDocument
import io.github.amichne.kast.protocol.contract.ImpactDeclarationReferenceDocument
import io.github.amichne.kast.protocol.contract.ImpactEvidenceRevisionDocument
import io.github.amichne.kast.protocol.contract.ImpactFlowSemanticsDocument
import io.github.amichne.kast.protocol.contract.ImpactFlowUnsupportedDocument
import io.github.amichne.kast.protocol.contract.ImpactInvocationReferenceDocument
import io.github.amichne.kast.protocol.contract.ImpactPathDocument
import io.github.amichne.kast.protocol.contract.ImpactPathTerminalDocument
import io.github.amichne.kast.protocol.contract.ImpactRepresentationEvidenceDocument
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
import io.github.amichne.kast.protocol.contract.QueryFromDocument
import io.github.amichne.kast.protocol.contract.QueryImpactSourceFailureCode
import io.github.amichne.kast.protocol.contract.QueryImpactSourceFailureDocument
import io.github.amichne.kast.protocol.contract.QueryKnownMinimum
import io.github.amichne.kast.protocol.contract.QueryLimitationDocument
import io.github.amichne.kast.protocol.contract.QueryOutputDocument
import io.github.amichne.kast.protocol.contract.QueryQualifiedProgressDocument
import io.github.amichne.kast.protocol.contract.QueryQuestionDocument
import io.github.amichne.kast.protocol.contract.QueryResultItemDocument
import io.github.amichne.kast.protocol.contract.QueryRunFailure
import io.github.amichne.kast.protocol.contract.QueryRunQualification
import io.github.amichne.kast.protocol.contract.QueryRunRejection
import io.github.amichne.kast.protocol.contract.QueryRunResult
import io.github.amichne.kast.protocol.contract.QueryTerminalReasonDocument
import io.github.amichne.kast.protocol.contract.ToolOutputDetail
import io.github.amichne.kast.protocol.wire.presentation.CanonicalQueryCliDocuments
import io.github.amichne.kast.protocol.wire.presentation.ProjectedOperationOutcome
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** Actual public projection and advertised schema proof; these fixtures establish no native semantics. */
class QueryImpactOutputSchemaTest {
    @Test
    fun `qualified value paths retain required proof fields under the installed output schema`() {
        val site = site()
        val item =
            QueryResultItemDocument.ValuePath(
                ImpactPathDocument(
                    site,
                    bounded(emptyList()),
                    ImpactRepresentationEvidenceDocument.NotModeled,
                    ImpactPathTerminalDocument.UnresolvedFlow(site, ImpactFlowUnsupportedDocument.EXTERNAL_CALL),
                )
            )
        val document = qualified(result(item, ImpactAccountingDocument.EvidenceOnly(count(1))))
        assertEquals(
            "VALUE_PATH",
            document.getValue("items").jsonArray.single().jsonObject.getValue("type").jsonPrimitive.content,
        )
        assertEquals(
            "EVIDENCE_ONLY",
            document.getValue("impact_accounting").jsonObject.getValue("type").jsonPrimitive.content,
        )
    }

    @Test
    fun `retained producer witness envelope admits its exact evidence and selected closure`() {
        val site = site()
        val item =
            QueryResultItemDocument.ImpactWitness(
                ImpactWitnessItemDocument(
                    ImpactWitnessSectionDocument.PRODUCERS,
                    count(0),
                    ImpactWitnessDocument.Producer(site, ImpactInvocationReferenceDocument(site.range, site.enclosing)),
                )
            )
        val accounting =
            ImpactAccountingDocument.Investigated(
                bounded(listOf(site)),
                ImpactRequestedBoundaryDocument.Workspace,
                ImpactFlowSemanticsDocument.KOTLIN_FORWARD_V1,
                bounded(emptyList()),
                bounded(emptyList()),
                count(0),
                count(0),
                count(1),
                count(0),
                ImpactAccountingStatusDocument.SelectedSubset(
                    ImpactClosureDocument.Unresolved(bounded(listOf(ImpactRequiredObligationDocument.NATIVE_FLOW)))
                ),
                ImpactAccountingViewDocument.Witness(
                    ImpactWitnessSectionDocument.PRODUCERS,
                    firstOrdinal = count(0),
                    nextOrdinal = count(1),
                    sectionCount = count(1),
                ),
            )
        val document = qualified(result(item, accounting))
        assertEquals(
            "IMPACT_WITNESS",
            document.getValue("items").jsonArray.single().jsonObject.getValue("type").jsonPrimitive.content,
        )
        assertEquals(
            "WITNESS",
            document
                .getValue("impact_accounting")
                .jsonObject
                .getValue("view")
                .jsonObject
                .getValue("type")
                .jsonPrimitive
                .content,
        )
    }

    @Test
    fun `every source admission failure retains its finite cause in the installed rejected envelope`() {
        for (cause in QueryImpactSourceFailureCode.entries) {
            val rejection =
                QueryRunRejection.ImpactSourceRejected(QueryImpactSourceFailureDocument.Admission(cause, offset(0)))
            val outcome: OperationOutcome<QueryRunResult, QueryRunQualification, QueryRunFailure> =
                OperationOutcome.Rejected(rejection)
            val projected = CanonicalQueryCliDocuments.project(outcome) as ProjectedOperationOutcome.Rejected
            val document =
                Json.parseToJsonElement(projected.document.present(ToolOutputDetail.VERBOSE).value).jsonObject
            LiveReadOutputSchemaTest().assertAdmits(CanonicalOperation.QUERY_RUN, document)
            assertEquals(
                cause.name,
                document
                    .getValue("rejection")
                    .jsonObject
                    .getValue("cause")
                    .jsonObject
                    .getValue("cause")
                    .jsonPrimitive
                    .content,
            )
        }
    }

    private fun qualified(result: QueryRunResult): kotlinx.serialization.json.JsonObject {
        val outcome: OperationOutcome<QueryRunResult, QueryRunQualification, QueryRunFailure> =
            OperationOutcome.Qualified(
                EvidenceEnvelope(
                    CanonicalOperation.QUERY_RUN.id,
                    EvidenceBasis.Published(EvidenceGeneration.parse(7).value()),
                    result,
                ),
                QueryRunQualification.create(
                        QueryKnownMinimum.parse(1).value(),
                        listOf(QueryLimitationDocument.IMPACT_COVERAGE_UNPROVEN),
                        QueryQualifiedProgressDocument.TerminalIncomplete(
                            QueryTerminalReasonDocument.UPSTREAM_INCOMPLETE
                        ),
                    )
                    .value(),
            )
        val projected = CanonicalQueryCliDocuments.project(outcome) as ProjectedOperationOutcome.Qualified
        val document = Json.parseToJsonElement(projected.document.present(ToolOutputDetail.VERBOSE).value).jsonObject
        LiveReadOutputSchemaTest().assertAdmits(CanonicalOperation.QUERY_RUN, document)
        return document
    }

    private fun result(item: QueryResultItemDocument, accounting: ImpactAccountingDocument) =
        QueryRunResult(
            QueryQuestionDocument(
                QueryFromDocument.Location(text("File.kt"), offset(0)),
                bounded(emptyList()),
                QueryOutputDocument.ValuePaths,
            ),
            bounded(listOf(item)),
            bounded(emptyList()),
            impactAccounting = accounting,
        )

    private fun site() =
        ImpactValueSiteReferenceDocument(
            ImpactDeclarationReferenceDocument(
                ImpactSemanticBasisDocument.Published(
                    text("/workspace"),
                    ImpactEvidenceRevisionDocument.parse(7).value(),
                ),
                text("/workspace/File.kt"),
                ImpactSourceRangeDocument(offset(0), offset(100)),
                text("canonical-signature-sha256-v1|" + "a".repeat(64)),
            ),
            ImpactSourceRangeDocument(offset(10), offset(11)),
            ImpactValueRoleDocument.ExpressionResult,
        )

    private fun count(raw: Long) = QueryDiscoveryCountDocument.parse(raw).value()

    private fun offset(raw: Int) = ProtocolOffset.parse(raw).value()

    private fun text(raw: String) = ProtocolText.parse(raw).value()

    private fun <T> bounded(values: List<T>) = BoundedProtocolList.create(values).value()

    private fun <T> Refinement<T, *>.value(): T = (this as Refinement.Refined).value
}
