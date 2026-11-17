package io.github.amichne.kast.relation.intellij

import com.intellij.openapi.application.ApplicationManager
import io.github.amichne.kast.relation.contract.RelationCompilation
import io.github.amichne.kast.relation.contract.RelationEndpoint
import io.github.amichne.kast.relation.contract.RelationMeaning
import io.github.amichne.kast.relation.contract.RelationSearchBoundary
import io.github.amichne.kast.relation.contract.ValueProducerSeedRead
import io.github.amichne.kast.relation.contract.ValueProducerSeedRequest
import io.github.amichne.kast.symbol.contract.SymbolSelector
import kotlinx.coroutines.runBlocking

class NativeProducerInvocationDiscoveryTest : NativeReferenceFixtureTest() {
    override val fixtureSdkName = "producer-invocation-jdk"

    override fun runInDispatchThread() = false

    fun testTwoNestedQualifiedCallsIssueDistinctWholeInvocationSeeds() {
        val caller = "package consumer\n/* 🍋 */ fun caller() = proof.target(proof.target(1))\n"
        lateinit var fixture: NativeReferenceFixture
        ApplicationManager.getApplication().invokeAndWait {
            fixture = prepareReferenceFixture("package proof\nfun target(value: Int) = value\n", caller)
        }
        runBlocking {
            val read = fixture.read(RelationMeaning.Callers)
            assertTrue("Expected both call sites: $read", read is RelationCompilation.Complete)
            val facts =
                (read as RelationCompilation.Complete).batch.facts.sortedBy { it.occurrence.range.startInclusive }
            assertEquals(2, facts.size)
            val calls = listOf("proof.target(proof.target(1))", "proof.target(1)")
            for ((fact, text) in facts.zip(calls)) {
                val range = requireNotNull(fact.producerRange)
                assertEquals(caller.indexOf(text), range.startInclusive)
                assertEquals(caller.indexOf(text) + text.length, range.endExclusive)
                val owner = fact.source as RelationEndpoint.Resolved
                val selection =
                    ValueProducerSeedRequest.create(
                            enclosing = SymbolSelector.issue(
                        lease = owner.lease,
                        scope = owner.scope,
                        evidence = owner.evidence,
                        constraints = owner.constraints,
                    ),
                            anchor = range,
                            expectedCallable = (fact.target as RelationEndpoint.Subject).selector,
                            budget = fixture.request.budget,
                            boundary = RelationSearchBoundary.WORKSPACE_EXPANSION,
                        )
                        .nativeRefined()
                val seed = IntellijValueFlowCompilerAdapter().seed(
                    project = project,
                    current = owner.lease,
                    request = selection,
                    model = fixture.model,
                )
                assertTrue(
                    "Issued range must select its exact compiler call: $seed",
                    seed is ValueProducerSeedRead.Seeded,
                )
                assertEquals(range, (seed as ValueProducerSeedRead.Seeded).seed.site.range)
            }
        }
    }
}
