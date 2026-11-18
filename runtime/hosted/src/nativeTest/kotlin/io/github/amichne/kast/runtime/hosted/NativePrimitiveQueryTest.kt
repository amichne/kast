package io.github.amichne.kast.runtime.hosted

import com.intellij.openapi.module.JavaModuleType
import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.protocol.contract.ImpactFlowUnsupportedDocument
import io.github.amichne.kast.protocol.contract.ImpactInvocationReferenceDocument
import io.github.amichne.kast.protocol.contract.ImpactPathClassificationDocument
import io.github.amichne.kast.protocol.contract.ImpactPathDocument
import io.github.amichne.kast.protocol.contract.ImpactPathStepDocument
import io.github.amichne.kast.protocol.contract.ImpactPathTerminalDocument
import io.github.amichne.kast.protocol.contract.ImpactPrimitiveInputDocument
import io.github.amichne.kast.protocol.contract.ImpactPrimitiveInputDocument.ARGUMENT_ONE
import io.github.amichne.kast.protocol.contract.ImpactPrimitiveInputDocument.ARGUMENT_ZERO
import io.github.amichne.kast.protocol.contract.ImpactPrimitiveInputDocument.DISPATCH_RECEIVER
import io.github.amichne.kast.protocol.contract.ImpactRelationshipDocument
import io.github.amichne.kast.protocol.contract.ImpactReviewedValueRelationDocument
import io.github.amichne.kast.protocol.contract.ImpactReviewedValueRuleDocument
import io.github.amichne.kast.protocol.contract.ImpactReviewedValueRuleDocument.KOTLIN_2_3_0_INT_MAX_V1 as MAX_V1
import io.github.amichne.kast.protocol.contract.ImpactReviewedValueRuleDocument.KOTLIN_2_3_0_INT_MINUS_V1 as MINUS_V1
import io.github.amichne.kast.protocol.contract.ImpactReviewedValueRuleDocument.KOTLIN_2_3_0_INT_MIN_V1 as MIN_V1
import io.github.amichne.kast.protocol.contract.ImpactReviewedValueRuleDocument.KOTLIN_2_3_0_INT_PLUS_V1 as PLUS_V1
import io.github.amichne.kast.protocol.contract.ImpactReviewedValueRuleDocument.KOTLIN_2_3_0_INT_TIMES_V1 as TIMES_V1
import io.github.amichne.kast.protocol.contract.ImpactSiteOutcomeDocument
import io.github.amichne.kast.protocol.contract.ImpactValueRoleDocument
import io.github.amichne.kast.protocol.contract.ImpactValueSiteReferenceDocument
import io.github.amichne.kast.protocol.contract.ImpactWitnessDocument
import io.github.amichne.kast.protocol.contract.ImpactWitnessSectionDocument
import io.github.amichne.kast.protocol.contract.QueryImpactProducerDocument
import io.github.amichne.kast.protocol.contract.QueryResultItemDocument
import io.github.amichne.kast.protocol.contract.QueryRunRequest
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import org.junit.Assert.assertThrows

class NativePrimitiveQueryTest : NativeContainerQueryFixtureTest() {
    override fun getModuleType() = JavaModuleType.getModuleType()

    fun testSubtractionReachesConsumer() = reachesConsumer("width - 2", MINUS_V1 to DISPATCH_RECEIVER)

    fun testBuiltinSubtractionWithoutAttachedSources() =
        reachesConsumer("width - 2", MINUS_V1 to DISPATCH_RECEIVER, library = PrimitiveFixtureLibrary.REVIEWED_BINARY)

    fun testRightSubtractionReachesConsumer() = reachesConsumer("2 - width", MINUS_V1 to ARGUMENT_ZERO)

    fun testExplicitSubtractionReachesConsumer() = reachesConsumer("width.minus(2)", MINUS_V1 to DISPATCH_RECEIVER)

    fun testExplicitSubtractionArgumentReachesConsumer() = reachesConsumer("2.minus(width)", MINUS_V1 to ARGUMENT_ZERO)

    fun testPlusReceiver() = reachesConsumer("width + 2", PLUS_V1 to DISPATCH_RECEIVER)

    fun testPlusArgument() = reachesConsumer("2.plus(width)", PLUS_V1 to ARGUMENT_ZERO)

    fun testTimesReceiver() = reachesConsumer("width.times(2)", TIMES_V1 to DISPATCH_RECEIVER)

    fun testTimesArgument() = reachesConsumer("2 * width", TIMES_V1 to ARGUMENT_ZERO)

    fun testNestedSecondInputs() =
        reachesConsumer(
            "kotlin.math.max(1, kotlin.math.min(100, width - 2))",
            MINUS_V1 to DISPATCH_RECEIVER,
            MIN_V1 to ARGUMENT_ONE,
            MAX_V1 to ARGUMENT_ONE,
        )

    fun testNestedFirstInputs() =
        reachesConsumer(
            "kotlin.math.max(kotlin.math.min(width, 100), 1)",
            MIN_V1 to ARGUMENT_ZERO,
            MAX_V1 to ARGUMENT_ZERO,
        )

    fun testSourceMinReturningConstant() = notReached("min(width, 2)", "fun min(a: Int, b: Int) = 1")

    fun testCustomPlusReturningConstant() =
        notReached("Other() + width", "class Other { operator fun plus(n: Int) = 7 }")

    fun testSameNamedPropertyOnAnotherDeclaration() =
        notReached(
            "choose(Left(width), Right(3))",
            "class Left(val width: Int)\nclass Right(val width: Int)\nfun choose(a: Left, b: Right) = b.width - 2",
        )

    fun testUnsupportedOverload() = notReached("(width + 2L).toInt()")

    fun testUnresolvedCallKeepsItsObligation() = runBlocking {
        val fixture = capture("missing(width)", expectedErrors = listOf("UNRESOLVED_REFERENCE"))
        assertEquals(ImpactSiteOutcomeDocument.RelationshipUnproven, fixture.site().outcome)
        assertTrue(
            fixture.result.paths.any { path ->
                (path.terminal as? ImpactPathTerminalDocument.UnresolvedFlow)?.cause ==
                    ImpactFlowUnsupportedDocument.UNRESOLVED_REFERENCE
            }
        )
    }

    fun testOpaqueCallAfterSubtraction() = runBlocking {
        val fixture = capture("Integer.reverse(width - 2)")
        assertEquals(ImpactSiteOutcomeDocument.RelationshipUnproven, fixture.site().outcome)
        assertTrue(
            fixture.result.paths.any { path ->
                path.steps.values.filterIsInstance<ImpactPathStepDocument.ReviewedLibrary>().any {
                    it.transfer.binding.relation is ImpactReviewedValueRelationDocument.PrimitiveResult
                } &&
                    (path.terminal as? ImpactPathTerminalDocument.UnresolvedFlow)?.cause ==
                        ImpactFlowUnsupportedDocument.EXTERNAL_CALL
            }
        )
    }

    private fun notReached(expression: String, declarations: String = "") = runBlocking {
        val fixture = capture(expression, declarations)
        assertEquals(ImpactSiteOutcomeDocument.RelationshipUnproven, fixture.site().outcome)
        assertTrue(
            fixture.result.paths
                .flatMap { it.steps.values }
                .filterIsInstance<ImpactPathStepDocument.ReviewedLibrary>()
                .none { it.transfer.binding.relation is ImpactReviewedValueRelationDocument.PrimitiveResult }
        )
    }

    private fun reachesConsumer(
        expression: String,
        vararg expected: Pair<ImpactReviewedValueRuleDocument, ImpactPrimitiveInputDocument>,
        library: PrimitiveFixtureLibrary = PrimitiveFixtureLibrary.REVIEWED_WITH_SOURCES,
    ) = runBlocking {
        val fixture = capture(expression, library = library)
        val site = fixture.site()
        assertEquals(fixture.target, site.site)
        val terminals = fixture.result.paths.map { it.terminal }
        assertTrue(
            "Expected consumeWidth argument, got ${site.outcome}; terminals=$terminals",
            site.outcome is ImpactSiteOutcomeDocument.Reached,
        )
        val reached = site.outcome as ImpactSiteOutcomeDocument.Reached
        val derived = ImpactPathClassificationDocument.Established(ImpactRelationshipDocument.DERIVED)
        val findings =
            fixture.witnesses(ImpactWitnessSectionDocument.FINDINGS).filterIsInstance<ImpactWitnessDocument.Finding>()
        for (reference in reached.paths.values) {
            assertEquals(derived, findings.single { it.finding.path == reference }.finding.classification)
        }
        val path =
            fixture.result.paths.first { candidate ->
                candidate.steps.values.any { step ->
                    step is ImpactPathStepDocument.ReviewedLibrary &&
                        step.transfer.binding.relation is ImpactReviewedValueRelationDocument.PrimitiveResult
                }
            }
        assertEquals(derived, path.classification)
        val operations =
            path.steps.values.filterIsInstance<ImpactPathStepDocument.ReviewedLibrary>().map { step ->
                val binding = step.transfer.binding
                binding.authority.rule to
                    (binding.relation as ImpactReviewedValueRelationDocument.PrimitiveResult).input
            }
        assertEquals(expected.toList(), operations)
        val encoded = Json.encodeToString(ImpactPathDocument.serializer(), path)
        assertEquals(path, Json.decodeFromString(ImpactPathDocument.serializer(), encoded))
        assertThrows(SerializationException::class.java) {
            Json.decodeFromString(ImpactPathDocument.serializer(), encoded.replace("\"DERIVED\"", "\"EXACT\""))
        }
        Unit
    }

    private suspend fun capture(
        expression: String,
        declarations: String = "",
        expectedErrors: List<String> = emptyList(),
        library: PrimitiveFixtureLibrary = PrimitiveFixtureLibrary.REVIEWED_WITH_SOURCES,
    ): Captured {
        val files =
            containerFixtures(
                mapOf(
                    "Producer.kt" to "fun sourceWidth(): Int = 80",
                    "Transform.kt" to "fun availableWidth(width: Int): Int = $expression\n$declarations",
                    "Consumer.kt" to
                        """
                        fun consumeWidth(width: Int) = Unit
                        fun runCase() = consumeWidth(availableWidth(sourceWidth()))
                        """
                            .trimIndent(),
                ),
                reviewedStdlib = library,
                expectedErrors = mapOf("Transform.kt" to expectedErrors),
            )
        val (services, consumer) = files.getValue("Consumer.kt")
        val producer = files.getValue("Producer.kt").source
        val seed =
            QueryImpactProducerDocument(
                consumer.requestedDeclaration("", "runCase").reference,
                producer.requestedDeclaration("", "sourceWidth").reference,
                consumer.invocation("", "runCase", "sourceWidth"),
            )
        val target =
            ImpactValueSiteReferenceDocument(
                consumer.reference(consumer.declaration("", "runCase")),
                consumer.invocation("", "runCase", "availableWidth"),
                ImpactValueRoleDocument.Argument(
                    ImpactInvocationReferenceDocument(
                        consumer.invocation("", "runCase", "consumeWidth"),
                        consumer.reference(consumer.declaration("", "consumeWidth")),
                    ),
                    packetQueryOffset(0),
                ),
            )
        val reader = NativePacketQueryCapture(services)
        val result =
            reader.capture(
                producer = seed,
                targets = listOf(target),
                outgoing = false,
                declarations =
                    listOf(
                        consumer.requestedDeclaration("", "runCase"),
                        consumer.requestedDeclaration("", "consumeWidth"),
                    ),
            )
        return Captured(services, result, target)
    }

    private class Captured(
        val services: NativePacketQueryServices,
        val result: NativePacketQueryResult,
        val target: ImpactValueSiteReferenceDocument,
    )

    private suspend fun Captured.site() =
        (witnesses(ImpactWitnessSectionDocument.SITE_ACCOUNTING).single() as ImpactWitnessDocument.SiteAccounting)
            .accounting

    private suspend fun Captured.witnesses(section: ImpactWitnessSectionDocument): List<ImpactWitnessDocument> {
        val reader = NativePacketQueryCapture(services)
        val reference = checkNotNull(result.retained.presentationWindow).reference
        val counts = services.observation.snapshot()
        val flows = services.nativeFlowReads
        val proofs = services.nativeProofReads
        val read = reader.execute(QueryRunRequest.ReadResult.impactWitness(reference, section))
        val page =
            when (read) {
                is OperationOutcome.Complete -> read.evidence.payload
                is OperationOutcome.Qualified -> read.evidence.payload
                is OperationOutcome.Rejected -> error(read.reason.toString())
            }
        assertEquals(counts, services.observation.snapshot())
        assertEquals(flows, services.nativeFlowReads)
        assertEquals(proofs, services.nativeProofReads)
        return page.items.values.map { (it as QueryResultItemDocument.ImpactWitness).item.witness }
    }
}
