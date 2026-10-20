package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.kernel.WorkUnitLimit
import io.github.amichne.kast.protocol.contract.ImpactModelDocument
import io.github.amichne.kast.protocol.contract.QueryContainmentDocument
import io.github.amichne.kast.protocol.contract.QueryDirectoryScopeDocument
import io.github.amichne.kast.protocol.contract.QueryDiscoveryInclusionPolicyDocument
import io.github.amichne.kast.protocol.contract.QueryDiscoverySourcePolicyDocument
import io.github.amichne.kast.protocol.contract.QueryExpansionScopeDocument
import io.github.amichne.kast.protocol.contract.QueryImpactSourceFailureCode
import io.github.amichne.kast.query.contract.AdmittedQueryPlan
import io.github.amichne.kast.query.contract.QueryExecutionRejection
import io.github.amichne.kast.query.contract.QueryExecutionResult
import io.github.amichne.kast.query.contract.QueryImpactFlowSemantics
import io.github.amichne.kast.query.contract.QueryOperations
import io.github.amichne.kast.relation.contract.RelationBudget
import io.github.amichne.kast.relation.contract.RelationSearchBoundary
import io.github.amichne.kast.relation.contract.RepresentationRule
import io.github.amichne.kast.relation.contract.RevalidatedRelationEndpoint
import io.github.amichne.kast.relation.contract.ValueInvocationSelectionKind
import io.github.amichne.kast.relation.contract.ValueModelDeclarationRead
import io.github.amichne.kast.relation.contract.ValueProducerSeed
import io.github.amichne.kast.relation.contract.ValueProducerSeedCompilerPort
import io.github.amichne.kast.relation.contract.ValueProducerSeedRead
import io.github.amichne.kast.relation.contract.ValueProducerSeedRequest
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryContainment
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryDirectory
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryDirectoryConstraint
import io.github.amichne.kast.symbol.contract.SymbolDiscoverySourceSets
import io.github.amichne.kast.symbol.contract.SymbolSelector
import io.github.amichne.kast.workspace.contract.WorkspaceSourceSetName
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Scripted detached compiler observations establish the production admission rule, not installed K2 behavior. */
internal class QueryImpactSourceAdmissionTest : QueryImpactSourceAdmissionCase() {
    @Test
    fun `native selector proof keeps the canonical origin during source acquisition`() = runTest {
        var calls = 0
        val compiler =
            object : ValueProducerSeedCompilerPort {
                override suspend fun seed(request: ValueProducerSeedRequest): ValueProducerSeedRead {
                    assertEquals(0, calls++)
                    assertEquals(range(25, 35), request.anchor)
                    val supplied = selectorProof(request, request.anchor, request.expectedCallable)
                    assertEquals(ValueInvocationSelectionKind.QUALIFIED_SELECTOR, supplied.selection.kind)
                    return ValueProducerSeedRead.Seeded(supplied, work(3))
                }

                override suspend fun revalidate(
                    selector: SymbolSelector,
                    budget: RelationBudget,
                ): ValueModelDeclarationRead = error("unexpected declaration")
            }
        val admitted = document(listOf(anchor(25, 35))).admitImpact(owner.lease, references, compiler, budget).refined()
        val producer = admitted.source.producers.single()
        assertEquals(range(20, 35), producer.site.range)
        assertEquals(producer.site.range, producer.invocation.range)
        assertEquals(producer.site.executionOwner, producer.invocation.executionOwner)
        assertEquals(3L, admitted.examinedWork)
        assertEquals(1, calls)
    }

    @Test
    fun `native selector proof from another request anchor or callable cannot replace the selected seed`() = runTest {
        val other = endpoint("decrypt", 260, 300)
        val cases =
            listOf(
                QueryImpactSourceFailureCode.ANCHOR_MISMATCH to
                    (range(26, 35) to SymbolSelector.issue(callable.lease, callable.scope, callable.evidence)),
                QueryImpactSourceFailureCode.CALLABLE_MISMATCH to
                    (range(25, 35) to SymbolSelector.issue(other.lease, other.scope, other.evidence)),
            )
        for ((expected, substitute) in cases) {
            var calls = 0
            val compiler =
                object : ValueProducerSeedCompilerPort {
                    override suspend fun seed(request: ValueProducerSeedRequest): ValueProducerSeedRead {
                        assertEquals(0, calls++)
                        return ValueProducerSeedRead.Seeded(
                            selectorProof(request, substitute.first, substitute.second),
                            work(3),
                        )
                    }

                    override suspend fun revalidate(
                        selector: SymbolSelector,
                        budget: RelationBudget,
                    ): ValueModelDeclarationRead = error("unexpected declaration")
                }
            val result = document(listOf(anchor(25, 35))).admitImpact(owner.lease, references, compiler, budget)
            assertEquals(expected, result.failure().admissionCode())
            assertEquals(1, calls)
        }
    }

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
        val result = protocol.executePage(request(document()), owner.lease, budget) as OperationOutcome.Rejected
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
        val result = protocol.executePage(request(document()), owner.lease, budget)
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
        val result = protocol.executePage(request(document()), owner.lease, exhausted) as OperationOutcome.Rejected
        assertEquals(QueryImpactSourceFailureCode.WORK_LIMIT_REACHED, result.reason.admissionCode())
        assertEquals(1, calls)
        assertEquals(0, executions)
    }
}
