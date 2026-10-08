package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.kernel.ElapsedTimeLimitMillis
import io.github.amichne.kast.kernel.EvidenceGeneration
import io.github.amichne.kast.kernel.OperationOutcome
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
import io.github.amichne.kast.protocol.contract.QueryContainmentDocument
import io.github.amichne.kast.protocol.contract.QueryDirectoryScopeDocument
import io.github.amichne.kast.protocol.contract.QueryDiscoveryInclusionPolicyDocument
import io.github.amichne.kast.protocol.contract.QueryDiscoverySourcePolicyDocument
import io.github.amichne.kast.protocol.contract.QueryExecutionBudgetDocument
import io.github.amichne.kast.protocol.contract.QueryExecutionDocument
import io.github.amichne.kast.protocol.contract.QueryExecutionKindDocument
import io.github.amichne.kast.protocol.contract.QueryExpansionScopeDocument
import io.github.amichne.kast.protocol.contract.QueryFromDocument
import io.github.amichne.kast.protocol.contract.QueryImpactDeclarationDocument
import io.github.amichne.kast.protocol.contract.QueryImpactFlowDocument
import io.github.amichne.kast.protocol.contract.QueryImpactProducerDocument
import io.github.amichne.kast.protocol.contract.QueryImpactSourceDocument
import io.github.amichne.kast.protocol.contract.QueryImpactSourceFailureCode
import io.github.amichne.kast.protocol.contract.QueryImpactSourceFailureDocument
import io.github.amichne.kast.protocol.contract.QueryOutputDocument
import io.github.amichne.kast.protocol.contract.QueryRunRejection
import io.github.amichne.kast.protocol.contract.QueryRunRequest
import io.github.amichne.kast.query.contract.AdmittedQueryPlan
import io.github.amichne.kast.query.contract.QueryBudget
import io.github.amichne.kast.query.contract.QueryByteLimit
import io.github.amichne.kast.query.contract.QueryExecutionRejection
import io.github.amichne.kast.query.contract.QueryExecutionResult
import io.github.amichne.kast.query.contract.QueryImpactFlowSemantics
import io.github.amichne.kast.query.contract.QueryOperations
import io.github.amichne.kast.relation.contract.RelationBudget
import io.github.amichne.kast.relation.contract.RelationEndpoint
import io.github.amichne.kast.relation.contract.RelationSearchBoundary
import io.github.amichne.kast.relation.contract.RelationWorkCount
import io.github.amichne.kast.relation.contract.RepresentationRule
import io.github.amichne.kast.relation.contract.RevalidatedRelationEndpoint
import io.github.amichne.kast.relation.contract.ValueInvocation
import io.github.amichne.kast.relation.contract.ValueModelDeclarationRead
import io.github.amichne.kast.relation.contract.ValueProducerSeed
import io.github.amichne.kast.relation.contract.ValueProducerSeedCompilerPort
import io.github.amichne.kast.relation.contract.ValueProducerSeedRead
import io.github.amichne.kast.relation.contract.ValueProducerSeedRequest
import io.github.amichne.kast.relation.contract.ValueRole
import io.github.amichne.kast.relation.contract.ValueSite
import io.github.amichne.kast.symbol.contract.CanonicalCompilerSignature
import io.github.amichne.kast.symbol.contract.CompilerGroundedSymbolEvidence
import io.github.amichne.kast.symbol.contract.CompilerSymbolKind
import io.github.amichne.kast.symbol.contract.ExactDeclarationTextRange
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryCandidate
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryCandidateLocation
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryContainment
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryDirectory
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryDirectoryConstraint
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryKind
import io.github.amichne.kast.symbol.contract.SymbolDiscoverySourceSets
import io.github.amichne.kast.symbol.contract.SymbolGeneratedSourcePolicy
import io.github.amichne.kast.symbol.contract.SymbolLibraryPolicy
import io.github.amichne.kast.symbol.contract.SymbolSearchScope
import io.github.amichne.kast.symbol.contract.SymbolSelector
import io.github.amichne.kast.symbol.contract.SymbolSourceKindPolicy
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import io.github.amichne.kast.workspace.contract.SemanticReadLease
import io.github.amichne.kast.workspace.contract.WorkspaceSourceSetName
import java.nio.file.Path
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Scripted detached compiler observations establish the production admission rule, not installed K2 behavior. */
class QueryImpactSourceAdmissionTest {
    private val owner = endpoint("investigate", 0, 200)
    private val callable = endpoint("encrypt", 210, 250)
    private val references = CanonicalQueryReferences()
    private val budget =
        QueryBudget(
            ResourceBudget(
                ResultLimit.parse(20).refined(),
                WorkUnitLimit.parse(10).refined(),
                ElapsedTimeLimitMillis.parse(1000).refined(),
            ),
            QueryByteLimit.parse(100000).refined(),
        )

    @Test
    fun `exact invocation producers and fresh models retain one domain and consume successive grants`() = runTest {
        val source = document(listOf(anchor(20, 35), anchor(60, 75)), model = true)
        val grants = mutableListOf<Long>()
        val observed = mutableListOf<ValueProducerSeed>()
        var revalidations = 0
        val compiler =
            object : ValueProducerSeedCompilerPort {
                override suspend fun seed(request: ValueProducerSeedRequest): ValueProducerSeedRead {
                    assertTrue(observed.size < 2, "unexpected seed call")
                    assertEquals(listOf(range(20, 35), range(60, 75))[observed.size], request.anchor)
                    assertEquals(RelationSearchBoundary.WORKSPACE_EXPANSION, request.boundary)
                    grants += request.budget.resources.workUnitLimit.value
                    val seed = proof(request)
                    observed += seed
                    return ValueProducerSeedRead.Seeded(seed, work(3))
                }

                override suspend fun revalidate(
                    selector: SymbolSelector,
                    budget: RelationBudget,
                ): ValueModelDeclarationRead {
                    assertEquals(0, revalidations++, "unexpected revalidation")
                    assertEquals(callable.compilerIdentity, selector.compilerIdentity)
                    grants += budget.resources.workUnitLimit.value
                    return ValueModelDeclarationRead.Revalidated(
                        RevalidatedRelationEndpoint.validate(callable, callable.evidence).refined(),
                        work(1),
                    )
                }
            }
        val admitted = source.admitImpact(owner.lease, references, compiler, budget).refined()
        assertEquals(listOf(10L, 7L, 4L), grants)
        assertEquals(1, revalidations)
        assertEquals(7L, admitted.examinedWork)
        assertEquals(2, admitted.source.producers.size)
        assertNotEquals(admitted.source.producers[0].site.identity, admitted.source.producers[1].site.identity)
        assertEquals(listOf("origin"), admitted.source.representationModels.map { it.reference.rule.value })
        assertEquals(
            callable,
            (admitted.source.representationModels.single() as RepresentationRule.Origin).output.endpoint,
        )
        assertEquals(QueryImpactFlowSemantics.KOTLIN_FORWARD_V1, admitted.source.semantics)
    }

    @Test
    fun `fresh same signature from another file cannot bind the supplied model`() = runTest {
        val other = endpoint("encrypt", 210, 250, fileName = "Other.kt")
        assertEquals(callable.compilerIdentity, other.compilerIdentity)
        var seeds = 0
        var revalidations = 0
        val compiler =
            object : ValueProducerSeedCompilerPort {
                override suspend fun seed(request: ValueProducerSeedRequest): ValueProducerSeedRead {
                    assertEquals(0, seeds++)
                    return ValueProducerSeedRead.Seeded(proof(request), work(3))
                }

                override suspend fun revalidate(
                    selector: SymbolSelector,
                    budget: RelationBudget,
                ): ValueModelDeclarationRead {
                    assertEquals(0, revalidations++)
                    return ValueModelDeclarationRead.Revalidated(
                        RevalidatedRelationEndpoint.validate(other, other.evidence).refined(),
                        work(1),
                    )
                }
            }
        val rejected = document(model = true).admitImpact(owner.lease, references, compiler, budget).failure()
        assertEquals(QueryImpactSourceFailureCode.DECLARATION_MISMATCH, rejected.admissionCode())
        assertEquals(1, seeds)
        assertEquals(1, revalidations)
    }

    @Test
    fun `work receipt cannot exceed grant or run another seed after exhaustion`() = runTest {
        var calls = 0
        val compiler =
            object : ValueProducerSeedCompilerPort {
                override suspend fun seed(request: ValueProducerSeedRequest): ValueProducerSeedRead {
                    assertEquals(0, calls++)
                    return ValueProducerSeedRead.Seeded(proof(request), work(10))
                }

                override suspend fun revalidate(
                    selector: SymbolSelector,
                    budget: RelationBudget,
                ): ValueModelDeclarationRead = error("unexpected revalidation")
            }
        val rejected =
            document(listOf(anchor(20, 35), anchor(60, 75)))
                .admitImpact(owner.lease, references, compiler, budget)
                .failure()
        assertEquals(QueryImpactSourceFailureCode.WORK_LIMIT_REACHED, rejected.admissionCode())
        assertEquals(1, calls)
        val excess =
            object : ValueProducerSeedCompilerPort by compiler {
                override suspend fun seed(request: ValueProducerSeedRequest) =
                    ValueProducerSeedRead.Seeded(proof(request), work(11))
            }
        assertEquals(
            QueryImpactSourceFailureCode.WORK_RECEIPT_EXCEEDS_GRANT,
            document().admitImpact(owner.lease, references, excess, budget).failure().admissionCode(),
        )
    }

    @Test
    fun `unavailable native evidence preserves finite failure without executing query`() = runTest {
        var executions = 0
        val protocol =
            CanonicalQueryProtocol(
                QueryOperations {
                    executions++
                    error("query must not execute")
                },
                references,
            )
        val result = protocol.execute(request(document()), owner.lease, budget) as OperationOutcome.Rejected
        assertEquals(QueryImpactSourceFailureCode.NATIVE_UNAVAILABLE, result.reason.admissionCode())
        assertEquals(0, executions)
    }

    @Test
    fun `invalid model syntax rejects before native acquisition`() = runTest {
        val source = document(model = true)
        val model = source.models.values.single() as ImpactModelDocument.Representation
        val invalid = source.copy(models = bounded(listOf(model.copy(states = bounded(emptyList())))))
        val compiler =
            object : ValueProducerSeedCompilerPort {
                override suspend fun seed(request: ValueProducerSeedRequest): ValueProducerSeedRead =
                    error("unexpected native seed")

                override suspend fun revalidate(
                    selector: SymbolSelector,
                    budget: RelationBudget,
                ): ValueModelDeclarationRead = error("unexpected native declaration")
            }
        assertEquals(
            QueryImpactSourceFailureCode.MODEL_INVALID_STATE_DOMAIN,
            invalid.admitImpact(owner.lease, references, compiler, budget).failure().admissionCode(),
        )
    }

    @Test
    fun `exact producer outside requested expansion directory remains admitted on its retained scope`() = runTest {
        var calls = 0
        val compiler =
            object : ValueProducerSeedCompilerPort {
                override suspend fun seed(request: ValueProducerSeedRequest): ValueProducerSeedRead {
                    assertEquals(0, calls++)
                    assertEquals(owner.scope, request.enclosing.scope)
                    assertEquals("/workspace/File.kt", request.enclosing.file.stableValue)
                    assertInstanceOf(RelationSearchBoundary.Explicit::class.java, request.boundary)
                    return ValueProducerSeedRead.Seeded(proof(request), work(3))
                }

                override suspend fun revalidate(
                    selector: SymbolSelector,
                    budget: RelationBudget,
                ): ValueModelDeclarationRead = error("unexpected declaration")
            }
        val source =
            document()
                .copy(
                    domain =
                        QueryExpansionScopeDocument.Sources(
                            bounded(listOf(text("main"))),
                            QueryDirectoryScopeDocument(text("downstream"), QueryContainmentDocument.DESCENDANTS),
                            QueryDiscoverySourcePolicyDocument.PRODUCTION_AND_TEST,
                            QueryDiscoveryInclusionPolicyDocument.EXCLUDE,
                        )
                )
        val admitted = source.admitImpact(owner.lease, references, compiler, budget).refined().source
        assertEquals(owner.compilerIdentity, admitted.producers.single().site.enclosing.compilerIdentity)
        assertEquals(owner.scope, admitted.producers.single().site.enclosing.scope)
        assertEquals(
            RelationSearchBoundary.Explicit(
                owner.scope,
                SymbolDiscoveryDirectoryConstraint(
                    SymbolDiscoveryDirectory.parse("downstream").refined(),
                    SymbolDiscoveryContainment.DESCENDANTS,
                ),
                SymbolDiscoverySourceSets.Exact.from(setOf(WorkspaceSourceSetName.parse("main").refined())).refined(),
            ),
            admitted.domain,
        )
        assertEquals(1, calls)
    }

    @Test
    fun `plan execution receives the shared grant after producer acquisition`() = runTest {
        var calls = 0
        var executions = 0
        val compiler =
            object : ValueProducerSeedCompilerPort {
                override suspend fun seed(request: ValueProducerSeedRequest): ValueProducerSeedRead {
                    assertEquals(0, calls++)
                    return ValueProducerSeedRead.Seeded(proof(request), work(3))
                }

                override suspend fun revalidate(
                    selector: SymbolSelector,
                    budget: RelationBudget,
                ): ValueModelDeclarationRead = error("unexpected revalidation")
            }
        val protocol =
            CanonicalQueryProtocol(
                QueryOperations {
                    assertEquals(0, executions++)
                    assertEquals(7L, it.budget.resources.workUnitLimit.value)
                    assertInstanceOf(AdmittedQueryPlan.Impact::class.java, it.plan)
                    QueryExecutionResult.Rejected(QueryExecutionRejection.BUDGET_REJECTED)
                },
                references,
                producerSeeds = compiler,
            )
        val result = protocol.execute(request(document()), owner.lease, budget)
        assertInstanceOf(OperationOutcome.Rejected::class.java, result)
        assertEquals(1, calls)
        assertEquals(1, executions)
    }

    @Test
    fun `exactly exhausted admission rejects without execution or manufactured continuation`() = runTest {
        var calls = 0
        var executions = 0
        val compiler =
            object : ValueProducerSeedCompilerPort {
                override suspend fun seed(request: ValueProducerSeedRequest): ValueProducerSeedRead {
                    assertEquals(0, calls++)
                    assertEquals(3L, request.budget.resources.workUnitLimit.value)
                    return ValueProducerSeedRead.Seeded(proof(request), work(3))
                }

                override suspend fun revalidate(
                    selector: SymbolSelector,
                    budget: RelationBudget,
                ): ValueModelDeclarationRead = error("unexpected declaration")
            }
        val protocol =
            CanonicalQueryProtocol(
                QueryOperations {
                    executions++
                    error("query must not execute")
                },
                references,
                producerSeeds = compiler,
            )
        val exhausted = budget.copy(resources = budget.resources.copy(workUnitLimit = WorkUnitLimit.parse(3).refined()))
        val result = protocol.execute(request(document()), owner.lease, exhausted) as OperationOutcome.Rejected
        assertEquals(QueryImpactSourceFailureCode.WORK_LIMIT_REACHED, result.reason.admissionCode())
        assertEquals(1, calls)
        assertEquals(0, executions)
    }

    private fun proof(request: ValueProducerSeedRequest): ValueProducerSeed {
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

    private fun document(
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

    private fun request(source: QueryImpactSourceDocument) =
        QueryRunRequest.Run(
            QueryFromDocument.Impact(source),
            bounded(emptyList()),
            QueryOutputDocument.ValuePaths,
            QueryExecutionDocument(QueryExecutionKindDocument.EXHAUSTIVE, QueryExecutionBudgetDocument.INTERACTIVE),
            completion = io.github.amichne.kast.protocol.contract.QueryCompletionPolicyDocument.Progressive,
        )

    private fun token(endpoint: RelationEndpoint.Resolved): ProtocolText =
        (references.issueExact(SymbolSelector.issue(endpoint.lease, endpoint.scope, endpoint.evidence))
                as ExactSelectorIssuance.Issued)
            .selector

    private fun declaration(endpoint: RelationEndpoint.Resolved) =
        ImpactDeclarationReferenceDocument(
            ImpactSemanticBasisDocument.Published(
                text("/workspace"),
                ImpactEvidenceRevisionDocument.parse(7).refined(),
            ),
            text(endpoint.file.stableValue),
            anchor(endpoint.range.startInclusive, endpoint.range.endExclusive),
            text(endpoint.compilerIdentity.value),
        )

    private fun QueryRunRejection.admissionCode() =
        ((this as QueryRunRejection.ImpactSourceRejected).cause as QueryImpactSourceFailureDocument.Admission).cause

    private fun endpoint(name: String, start: Int, end: Int, fileName: String = "File.kt"): RelationEndpoint.Resolved {
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

    private fun anchor(start: Int, end: Int) =
        ImpactSourceRangeDocument(ProtocolOffset.parse(start).refined(), ProtocolOffset.parse(end).refined())

    private fun range(start: Int, end: Int) = ExactDeclarationTextRange.parse(start, end).refined()

    private fun work(value: Long) = RelationWorkCount.parse(value).refined()

    private fun id(value: String) = ImpactModelIdentifierDocument.parse(value).refined()

    private fun text(value: String) = ProtocolText.parse(value).refined()

    private fun <T> bounded(values: List<T>) = BoundedProtocolList.create(values).refined()

    private fun <V, F> Refinement<V, F>.refined(): V =
        when (this) {
            is Refinement.Refined -> value
            is Refinement.Rejected -> error(failure.toString())
        }

    private fun <V, F> Refinement<V, F>.failure(): F =
        when (this) {
            is Refinement.Refined -> error("expected rejection")
            is Refinement.Rejected -> failure
        }
}
