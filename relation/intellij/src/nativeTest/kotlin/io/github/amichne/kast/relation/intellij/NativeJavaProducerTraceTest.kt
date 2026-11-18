package io.github.amichne.kast.relation.intellij

import io.github.amichne.kast.relation.contract.RelationSearchBoundary
import io.github.amichne.kast.relation.contract.ValueFlowRead
import io.github.amichne.kast.relation.contract.ValueFlowSemantics
import io.github.amichne.kast.relation.contract.ValueProducerSeedRead
import io.github.amichne.kast.relation.contract.ValueProducerSeedRejection
import io.github.amichne.kast.relation.contract.ValueProducerSeedRequest
import io.github.amichne.kast.relation.contract.ValueRole
import io.github.amichne.kast.relation.contract.ValueTransferKind
import io.github.amichne.kast.symbol.contract.ExactDeclarationTextRange

class NativeJavaProducerTraceTest : NativePacketValueFixtureTest() {
    override val fixtureSdkName = "java-producer-trace-jdk"

    fun testStaticTraceReturnsOnlyThroughSelectedCall() {
        val fixture = packetFixture(listOf(PATH), readSource = { SOURCE.toByteArray() })
        val run =
            ImpactBoundaryTrace(project, fixture, ValueFlowSemantics.KOTLIN_JAVA_FORWARD_V2).run(selection(fixture))
        assertTrue(run.summary(), run.seed is ValueProducerSeedRead.Seeded)
        assertTrue(run.summary(), run.failures.isEmpty())
        val steps = run.reads.map { (it.read as ValueFlowRead.Observed).step }
        val returned = steps.flatMap { it.transfers }.single { it.kind == ValueTransferKind.SOURCE_CALL_RETURN }
        assertEquals(site("relay(source())"), returned.target.range)
        val consumer = fixture.javaCallable(PATH, "sink")
        val arrival =
            steps
                .map { it.source }
                .single {
                    (it.role as? ValueRole.Argument)?.call?.callable?.compilerIdentity == consumer.compilerIdentity
                }
        assertEquals(site("relay(source())"), arrival.range)
        assertTrue(steps.flatMap { it.obligations }.toString(), steps.flatMap { it.obligations }.isEmpty())
    }

    fun testDifferentOverloadRejected() {
        val fixture = packetFixture(listOf(PATH), readSource = { SOURCE.toByteArray() })
        val run =
            ImpactBoundaryTrace(project, fixture, ValueFlowSemantics.KOTLIN_JAVA_FORWARD_V2)
                .run(selection(fixture, expectedArity = 1))
        assertEquals(ValueProducerSeedRead.Rejected(ValueProducerSeedRejection.CALLABLE_MISMATCH), run.seed)
    }

    fun testNativeHelperBodyStaysUnavailable() {
        val fixture = packetFixture(listOf(PATH), readSource = { SOURCE.toByteArray() })
        val run =
            ImpactBoundaryTrace(project, fixture, ValueFlowSemantics.KOTLIN_JAVA_FORWARD_V2)
                .run(selection(fixture, expectedArity = 1, anchor = "source(10)"))
        assertTrue(run.summary(), run.seed is ValueProducerSeedRead.Seeded && run.failures.isEmpty())
        val steps = run.reads.map { (it.read as ValueFlowRead.Observed).step }
        val missing = steps.flatMap { it.obligations }.single()
        assertEquals(io.github.amichne.kast.relation.contract.ValueFlowUnsupportedCause.EXTERNAL_CALL, missing.cause)
        assertEquals(
            fixture.javaCallable(PATH, "missing").compilerIdentity,
            (missing.site.role as ValueRole.Argument).call.callable.compilerIdentity,
        )
        assertTrue(steps.flatMap { it.transfers }.none { it.kind == ValueTransferKind.SOURCE_CALL_RETURN })
    }

    private fun selection(fixture: NativePacketValueFixture, expectedArity: Int = 0, anchor: String = "source()") =
        ValueProducerSeedRequest.create(
                enclosing = fixture.javaCallable(PATH, "host"),
                anchor = site(anchor),
                expectedCallable = fixture.javaCallable(PATH, "source", expectedArity),
                budget = fixture.budget,
                boundary = RelationSearchBoundary.RETAINED_SUBJECT,
            )
            .nativeRefined()

    private fun site(text: String): ExactDeclarationTextRange {
        val start = SOURCE.indexOf(text, SOURCE.indexOf("void host()"))
        return ExactDeclarationTextRange.parse(start, start + text.length).nativeRefined()
    }

    private companion object {
        const val PATH = "src/main/kotlin/fixture/controls/JavaOrigin.java"
        val SOURCE =
            checkNotNull(NativeJavaProducerTraceTest::class.java.getResource("/impact-boundary/JavaOrigin.java"))
                .readText()
    }
}
