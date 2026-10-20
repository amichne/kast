package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.kernel.ElapsedTimeLimitMillis
import io.github.amichne.kast.kernel.EvidenceGeneration
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.ResourceBudget
import io.github.amichne.kast.kernel.ResultLimit
import io.github.amichne.kast.kernel.WorkUnitLimit
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
import io.github.amichne.kast.protocol.contract.ProtocolOffset
import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.protocol.contract.QueryExecutionBudgetDocument
import io.github.amichne.kast.protocol.contract.QueryExecutionDocument
import io.github.amichne.kast.protocol.contract.QueryExecutionKindDocument
import io.github.amichne.kast.protocol.contract.QueryExpansionScopeDocument
import io.github.amichne.kast.protocol.contract.QueryFromDocument
import io.github.amichne.kast.protocol.contract.QueryImpactDeclarationDocument
import io.github.amichne.kast.protocol.contract.QueryImpactFlowDocument
import io.github.amichne.kast.protocol.contract.QueryImpactProducerDocument
import io.github.amichne.kast.protocol.contract.QueryImpactSourceDocument
import io.github.amichne.kast.protocol.contract.QueryImpactSourceFailureDocument
import io.github.amichne.kast.protocol.contract.QueryOutputDocument
import io.github.amichne.kast.protocol.contract.QueryRunRejection
import io.github.amichne.kast.protocol.contract.QueryRunRequest
import io.github.amichne.kast.query.contract.QueryBudget
import io.github.amichne.kast.query.contract.QueryByteLimit
import io.github.amichne.kast.relation.contract.RelationEndpoint
import io.github.amichne.kast.relation.contract.RelationWorkCount
import io.github.amichne.kast.relation.contract.ValueInvocation
import io.github.amichne.kast.relation.contract.ValueInvocationSelection
import io.github.amichne.kast.relation.contract.ValueProducerSeed
import io.github.amichne.kast.relation.contract.ValueProducerSeedRequest
import io.github.amichne.kast.relation.contract.ValueRole
import io.github.amichne.kast.relation.contract.ValueSite
import io.github.amichne.kast.symbol.contract.CanonicalCompilerSignature
import io.github.amichne.kast.symbol.contract.CompilerGroundedSymbolEvidence
import io.github.amichne.kast.symbol.contract.CompilerSymbolKind
import io.github.amichne.kast.symbol.contract.ExactDeclarationTextRange
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryCandidate
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryCandidateLocation
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryKind
import io.github.amichne.kast.symbol.contract.SymbolGeneratedSourcePolicy
import io.github.amichne.kast.symbol.contract.SymbolLibraryPolicy
import io.github.amichne.kast.symbol.contract.SymbolSearchScope
import io.github.amichne.kast.symbol.contract.SymbolSelector
import io.github.amichne.kast.symbol.contract.SymbolSourceKindPolicy
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import io.github.amichne.kast.workspace.contract.SemanticReadLease
import java.nio.file.Path

internal abstract class QueryImpactSourceAdmissionCase {
    protected val owner = endpoint("investigate", 0, 200)
    protected val callable = endpoint("encrypt", 210, 250)
    protected val references = CanonicalQueryReferences()
    protected val budget =
        QueryBudget(
            ResourceBudget(
                ResultLimit.parse(20).refined(),
                WorkUnitLimit.parse(10).refined(),
                ElapsedTimeLimitMillis.parse(1000).refined(),
            ),
            QueryByteLimit.parse(100000).refined(),
        )

    protected fun selectorProof(
        request: ValueProducerSeedRequest,
        selectedRange: ExactDeclarationTextRange,
        expectedCallable: SymbolSelector,
    ): ValueProducerSeed {
        val suppliedRequest =
            ValueProducerSeedRequest.create(
                    request.enclosing,
                    selectedRange,
                    expectedCallable,
                    request.budget,
                    request.boundary,
                )
                .refined()
        val invocation =
            ValueInvocation.fromCompiler(
                    RelationEndpoint.subject(request.enclosing),
                    range(20, 35),
                    RelationEndpoint.subject(expectedCallable),
                )
                .refined()
        val selection = ValueInvocationSelection.fromCompiler(invocation, selectedRange).refined()
        return ValueProducerSeed.fromCompiler(suppliedRequest, invocation.resultSite(), invocation, selection).refined()
    }

    protected fun proof(request: ValueProducerSeedRequest): ValueProducerSeed {
        val call =
            ValueInvocation.fromCompiler(
                    RelationEndpoint.subject(request.enclosing),
                    request.anchor,
                    RelationEndpoint.subject(request.expectedCallable),
                )
                .refined()
        val site = ValueSite.fromCompiler(call.enclosing, request.anchor, ValueRole.ExpressionResult).refined()
        return ValueProducerSeed.fromCompiler(request, site, call).refined()
    }

    protected fun document(
        anchors: List<ImpactSourceRangeDocument> = listOf(anchor(20, 35)),
        model: Boolean = false,
    ): QueryImpactSourceDocument {
        val declaration = declaration(callable)
        val identity =
            ImpactModelIdentityDocument(
                id("encryption"),
                ImpactModelVersionDocument.parse(1).refined(),
                id("review:913"),
            )
        val syntax =
            ImpactModelDocument.Representation(
                ImpactModelFormatDocument.Current,
                identity,
                bounded(listOf(id("HIPED"))),
                bounded(
                    listOf(
                        ImpactRepresentationRuleDocument.Origin(
                            id("origin"),
                            ImpactCallablePositionDocument(declaration, ImpactModelValuePositionDocument.Result),
                            id("HIPED"),
                        )
                    )
                ),
            )
        return QueryImpactSourceDocument(
            bounded(anchors.map { QueryImpactProducerDocument(token(owner), token(callable), it) }),
            bounded(if (model) listOf(QueryImpactDeclarationDocument(token(callable), declaration)) else emptyList()),
            QueryExpansionScopeDocument.Workspace,
            QueryImpactFlowDocument.KOTLIN_FORWARD_V1,
            bounded(if (model) listOf(syntax) else emptyList()),
        )
    }

    protected fun request(source: QueryImpactSourceDocument) =
        QueryRunRequest.Run(
            QueryFromDocument.Impact(source),
            bounded(emptyList()),
            QueryOutputDocument.ValuePaths,
            QueryExecutionDocument(QueryExecutionKindDocument.EXHAUSTIVE, QueryExecutionBudgetDocument.INTERACTIVE),
        )

    protected fun token(endpoint: RelationEndpoint.Resolved): ProtocolText =
        (references.issueExact(SymbolSelector.issue(endpoint.lease, endpoint.scope, endpoint.evidence))
                as ExactSelectorIssuance.Issued)
            .selector

    protected fun declaration(endpoint: RelationEndpoint.Resolved) =
        ImpactDeclarationReferenceDocument(
            ImpactSemanticBasisDocument.Published(
                text("/workspace"),
                ImpactEvidenceRevisionDocument.parse(7).refined(),
            ),
            text(endpoint.file.stableValue),
            anchor(endpoint.range.startInclusive, endpoint.range.endExclusive),
            text(endpoint.compilerIdentity.value),
        )

    protected fun QueryRunRejection.admissionCode() =
        ((this as QueryRunRejection.ImpactSourceRejected).cause as QueryImpactSourceFailureDocument.Admission).cause

    protected fun endpoint(
        name: String,
        start: Int,
        end: Int,
        fileName: String = "File.kt",
    ): RelationEndpoint.Resolved {
        val lease =
            SemanticReadLease(
                CanonicalWorkspaceRoot.fromCanonicalPath(Path.of("/workspace")).refined(),
                EvidenceGeneration.parse(7).refined(),
            )
        val file =
            (SymbolDiscoveryCandidate.fromBoundary(
                        SymbolDiscoveryKind.SYMBOL,
                        name,
                        lease,
                        Path.of("/workspace/$fileName"),
                        "file:///workspace/$fileName",
                        start,
                    )
                    .refined()
                    .location as SymbolDiscoveryCandidateLocation.Declaration)
                .file
        val evidence =
            CompilerGroundedSymbolEvidence.fromBoundary(
                    file,
                    start,
                    end,
                    name,
                    "fixture.$name",
                    CompilerSymbolKind.FUNCTION,
                    CanonicalCompilerSignature.function("fixture.$name", null, emptyList(), emptyList(), 0).refined(),
                )
                .refined()
        return RelationEndpoint.resolve(
                lease,
                SymbolSearchScope.Workspace(
                    SymbolSourceKindPolicy.PRODUCTION_AND_TEST,
                    SymbolGeneratedSourcePolicy.EXCLUDE,
                    SymbolLibraryPolicy.EXCLUDE,
                ),
                evidence,
            )
            .refined() as RelationEndpoint.Resolved
    }

    protected fun anchor(start: Int, end: Int) =
        ImpactSourceRangeDocument(ProtocolOffset.parse(start).refined(), ProtocolOffset.parse(end).refined())

    protected fun range(start: Int, end: Int) = ExactDeclarationTextRange.parse(start, end).refined()

    protected fun work(value: Long) = RelationWorkCount.parse(value).refined()

    protected fun id(value: String) = ImpactModelIdentifierDocument.parse(value).refined()

    protected fun text(value: String) = ProtocolText.parse(value).refined()

    protected fun <T> bounded(values: List<T>) = BoundedProtocolList.create(values).refined()

    protected fun <V, F> Refinement<V, F>.refined(): V =
        when (this) {
            is Refinement.Refined -> value
            is Refinement.Rejected -> error(failure.toString())
        }

    protected fun <V, F> Refinement<V, F>.failure(): F =
        when (this) {
            is Refinement.Refined -> error("expected rejection")
            is Refinement.Rejected -> failure
        }
}
