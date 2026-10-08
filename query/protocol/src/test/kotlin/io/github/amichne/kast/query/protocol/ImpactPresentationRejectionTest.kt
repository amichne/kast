package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.kernel.PositiveLimitFailure
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.BoundedProtocolList
import io.github.amichne.kast.protocol.contract.ImpactModelPrimitiveFailure
import io.github.amichne.kast.protocol.contract.ImpactPresentationBudgetCause
import io.github.amichne.kast.protocol.contract.ImpactPresentationCollectionCause
import io.github.amichne.kast.protocol.contract.ImpactPresentationCountCause
import io.github.amichne.kast.protocol.contract.ImpactPresentationFailureDocument
import io.github.amichne.kast.protocol.contract.ImpactPresentationFingerprintCause
import io.github.amichne.kast.protocol.contract.ImpactPresentationModelCause
import io.github.amichne.kast.protocol.contract.ImpactPresentationOffsetCause
import io.github.amichne.kast.protocol.contract.ImpactPresentationTextCause
import io.github.amichne.kast.protocol.contract.MAX_PROTOCOL_ITEMS
import io.github.amichne.kast.protocol.contract.ProtocolCollectionFailure
import io.github.amichne.kast.protocol.contract.ProtocolOffsetFailure
import io.github.amichne.kast.protocol.contract.ProtocolTextFailure
import io.github.amichne.kast.protocol.contract.QueryDiscoveryMeasureFailure
import io.github.amichne.kast.protocol.contract.QueryExecutionBudgetDocument
import io.github.amichne.kast.protocol.contract.QueryExecutionDocument
import io.github.amichne.kast.protocol.contract.QueryExecutionKindDocument
import io.github.amichne.kast.protocol.contract.QueryFromDocument
import io.github.amichne.kast.protocol.contract.QueryOutputDocument
import io.github.amichne.kast.protocol.contract.QueryReferenceDocument
import io.github.amichne.kast.protocol.contract.QueryRelationDomainFailure
import io.github.amichne.kast.protocol.contract.QueryRunRejection
import io.github.amichne.kast.protocol.contract.QueryRunRequest
import io.github.amichne.kast.protocol.contract.QueryStepDocument
import io.github.amichne.kast.protocol.contract.ReadRecoveryAction
import io.github.amichne.kast.protocol.contract.recoveryAction
import io.github.amichne.kast.query.contract.QueryCount
import io.github.amichne.kast.query.contract.QueryCoverage
import io.github.amichne.kast.query.contract.QueryExecutionResult
import io.github.amichne.kast.query.contract.QueryImpactPath
import io.github.amichne.kast.query.contract.QueryImpactRepresentation
import io.github.amichne.kast.query.contract.QueryImpactStep
import io.github.amichne.kast.query.contract.QueryImpactTerminal
import io.github.amichne.kast.query.contract.QueryLimitation
import io.github.amichne.kast.query.contract.QueryResult
import io.github.amichne.kast.query.contract.QueryRows
import io.github.amichne.kast.relation.contract.RelationEndpoint
import io.github.amichne.kast.relation.contract.ValueFlowObligation
import io.github.amichne.kast.relation.contract.ValueFlowUnsupportedCause
import io.github.amichne.kast.relation.contract.ValueRole
import io.github.amichne.kast.relation.contract.ValueSite
import io.github.amichne.kast.relation.contract.ValueTransfer
import io.github.amichne.kast.relation.contract.ValueTransferKind
import io.github.amichne.kast.symbol.contract.ExactDeclarationTextRange
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ImpactPresentationRejectionTest {
    @Test
    fun `oversized retained path preserves collection cause from item projection to public rejected outcome`() {
        val fixture = RelationPagingFixture.published()
        val path = oversizedPath(fixture)
        val result = QueryResult(QueryRows.ValuePaths.of(listOf(path)), emptyList())
        val expected =
            QueryRunRejection.ImpactPresentationRejected(
                ImpactPresentationFailureDocument.Collection(ImpactPresentationCollectionCause.TOO_LARGE)
            )
        assertEquals(
            QueryProjection.ImpactRejected(ImpactPathProjectionFailure.Collection(ProtocolCollectionFailure.TOO_LARGE)),
            QueryItemProjector(fixture.references).projectItems(QueryOutputDocument.ValuePaths, result.rows),
        )
        assertEquals(
            Refinement.Rejected(expected),
            QueryProjectedEvidence.from(result, QueryOutputDocument.ValuePaths, fixture.references),
        )
        val request =
            QueryRunRequest.Run(
                QueryFromDocument.References(
                    BoundedProtocolList.create(listOf(QueryReferenceDocument.ExactSymbol(fixture.exact))).refined()
                ),
                BoundedProtocolList.create(emptyList<QueryStepDocument>()).refined(),
                QueryOutputDocument.ValuePaths,
                QueryExecutionDocument(QueryExecutionKindDocument.EXHAUSTIVE, QueryExecutionBudgetDocument.INTERACTIVE),
                completion = io.github.amichne.kast.protocol.contract.QueryCompletionPolicyDocument.Progressive,
            )
        val qualified =
            QueryExecutionResult.Qualified(
                result,
                QueryCoverage.Qualified.create(
                        QueryCount.parse(1).refined(),
                        setOf(QueryLimitation.RELATION_INCOMPLETE),
                    )
                    .refined(),
            )
        val store = QueryStateStore(clock = { error("Projection rejection must precede retention effects") })
        assertEquals(
            OperationOutcome.Rejected(expected),
            QueryOutcomeProjection(fixture.references, store).projectExecution(request, fixture.authority, qualified),
        )
        assertEquals(ReadRecoveryAction.ADJUST_BUDGET_OR_SCOPE, expected.recoveryAction())
    }

    @Test
    fun `each local impact failure preserves its exact finite public cause`() {
        val cases =
            listOf(
                ImpactPathProjectionFailure.Text(ProtocolTextFailure.BLANK) to
                    ImpactPresentationFailureDocument.Text(ImpactPresentationTextCause.BLANK),
                ImpactPathProjectionFailure.Text(ProtocolTextFailure.TOO_LONG) to
                    ImpactPresentationFailureDocument.Text(ImpactPresentationTextCause.TOO_LONG),
                ImpactPathProjectionFailure.Offset(ProtocolOffsetFailure.NEGATIVE) to
                    ImpactPresentationFailureDocument.Offset(ImpactPresentationOffsetCause.NEGATIVE),
                ImpactPathProjectionFailure.Model(ImpactModelPrimitiveFailure.INVALID_IDENTIFIER) to
                    ImpactPresentationFailureDocument.Model(ImpactPresentationModelCause.INVALID_IDENTIFIER),
                ImpactPathProjectionFailure.Model(ImpactModelPrimitiveFailure.NOT_POSITIVE) to
                    ImpactPresentationFailureDocument.Model(ImpactPresentationModelCause.NOT_POSITIVE),
                ImpactPathProjectionFailure.Model(ImpactModelPrimitiveFailure.UNSUPPORTED_FORMAT) to
                    ImpactPresentationFailureDocument.Model(ImpactPresentationModelCause.UNSUPPORTED_FORMAT),
                ImpactPathProjectionFailure.Collection(ProtocolCollectionFailure.TOO_LARGE) to
                    ImpactPresentationFailureDocument.Collection(ImpactPresentationCollectionCause.TOO_LARGE),
                ImpactPathProjectionFailure.DomainFingerprint(QueryRelationDomainFailure.INVALID_FINGERPRINT) to
                    ImpactPresentationFailureDocument.DomainFingerprint(
                        ImpactPresentationFingerprintCause.INVALID_FINGERPRINT
                    ),
                ImpactPathProjectionFailure.Count(QueryDiscoveryMeasureFailure.NEGATIVE) to
                    ImpactPresentationFailureDocument.Count(ImpactPresentationCountCause.NEGATIVE),
                ImpactPathProjectionFailure.Budget(PositiveLimitFailure.NOT_POSITIVE) to
                    ImpactPresentationFailureDocument.Budget(ImpactPresentationBudgetCause.NOT_POSITIVE),
                ImpactPathProjectionFailure.DOMAIN_PROJECTION_REJECTED to
                    ImpactPresentationFailureDocument.DomainProjectionRejected,
            )
        for ((failure, expected) in cases) assertEquals(
            QueryRunRejection.ImpactPresentationRejected(expected),
            failure.presentationRejection(),
        )
    }

    private fun oversizedPath(fixture: RelationPagingFixture): QueryImpactPath {
        val site =
            ValueSite.fromCompiler(
                    RelationEndpoint.subject(fixture.selector),
                    ExactDeclarationTextRange.parse(1, 2).refined(),
                    ValueRole.ExpressionResult,
                )
                .refined()
        // Detached alternating compiler arrivals exercise projection capacity; no native provider is invoked.
        val other =
            ValueSite.fromCompiler(
                    RelationEndpoint.subject(fixture.selector),
                    ExactDeclarationTextRange.parse(3, 4).refined(),
                    ValueRole.ExpressionResult,
                )
                .refined()
        val outward = ValueTransfer.fromCompiler(site, other, ValueTransferKind.BRANCH_ALTERNATIVE).refined()
        val inward = ValueTransfer.fromCompiler(other, site, ValueTransferKind.BRANCH_ALTERNATIVE).refined()
        val steps = List(MAX_PROTOCOL_ITEMS + 1) { QueryImpactStep.Compiler(if (it % 2 == 0) outward else inward) }
        return QueryImpactPath.fromEvidence(
                site,
                steps,
                QueryImpactRepresentation.NotModeled,
                QueryImpactTerminal.Unresolved.Flow(
                    ValueFlowObligation(steps.last().target, ValueFlowUnsupportedCause.UNSUPPORTED_EXPRESSION)
                ),
            )
            .refined()
    }

    private fun <S, F> Refinement<S, F>.refined(): S =
        when (this) {
            is Refinement.Refined -> value
            is Refinement.Rejected -> error(failure.toString())
        }
}
