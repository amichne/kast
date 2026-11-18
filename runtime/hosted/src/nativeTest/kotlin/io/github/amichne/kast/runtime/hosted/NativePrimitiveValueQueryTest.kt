package io.github.amichne.kast.runtime.hosted

import com.intellij.openapi.module.JavaModuleType
import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.protocol.contract.ImpactExecutionOwnerDocument
import io.github.amichne.kast.protocol.contract.ImpactFlowUnsupportedDocument
import io.github.amichne.kast.protocol.contract.ImpactIntArithmeticInputDocument
import io.github.amichne.kast.protocol.contract.ImpactIntArithmeticRuleDocument
import io.github.amichne.kast.protocol.contract.ImpactIntBuiltinEvidenceDocument
import io.github.amichne.kast.protocol.contract.ImpactInvocationReferenceDocument
import io.github.amichne.kast.protocol.contract.ImpactPathClassificationDocument
import io.github.amichne.kast.protocol.contract.ImpactPathDocument
import io.github.amichne.kast.protocol.contract.ImpactPathStepDocument
import io.github.amichne.kast.protocol.contract.ImpactPathTerminalDocument
import io.github.amichne.kast.protocol.contract.ImpactRelationshipDocument
import io.github.amichne.kast.protocol.contract.ImpactReviewedIntExtremumInputDocument
import io.github.amichne.kast.protocol.contract.ImpactReviewedValueRelationDocument
import io.github.amichne.kast.protocol.contract.ImpactReviewedValueRuleDocument
import io.github.amichne.kast.protocol.contract.ImpactReviewedValueTransferDocument
import io.github.amichne.kast.protocol.contract.ImpactSiteOutcomeDocument
import io.github.amichne.kast.protocol.contract.ImpactTransferEvidenceDocument
import io.github.amichne.kast.protocol.contract.ImpactTransferKindDocument
import io.github.amichne.kast.protocol.contract.ImpactValueRoleDocument
import io.github.amichne.kast.protocol.contract.ImpactValueSiteReferenceDocument
import io.github.amichne.kast.protocol.contract.ImpactWitnessDocument
import io.github.amichne.kast.protocol.contract.ImpactWitnessSectionDocument
import io.github.amichne.kast.protocol.contract.QueryResultItemDocument
import io.github.amichne.kast.protocol.contract.QueryRunRequest
import io.github.amichne.kast.protocol.contract.admitsEvidence
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

class NativePrimitiveValueQueryTest : NativeContainerQueryFixtureTest() {
    override fun getModuleType() = JavaModuleType.getModuleType()

    fun testSubtractionReachesCrossFileConsumer() = runBlocking {
        val files = queryFixture(SUBTRACTION)
        val producer = files.getValue("Producer.kt").source
        val transform = files.getValue("Transform.kt").source
        val consumer = files.getValue("Consumer.kt")
        val source = consumer.source
        val target =
            ImpactValueSiteReferenceDocument(
                enclosing = source.reference(source.declaration("", "runCase")),
                range = source.markedRange("use"),
                role =
                    ImpactValueRoleDocument.Argument(
                        ImpactInvocationReferenceDocument(
                            source.invocation("", "runCase", "consumeWidth"),
                            source.reference(source.declaration("", "consumeWidth")),
                            ImpactExecutionOwnerDocument.Declaration,
                        ),
                        packetQueryOffset(0),
                    ),
            )
        val result =
            NativePacketQueryCapture(consumer.services)
                .capture(
                    producer = source.producer("runCase", "sourceWidth", producer),
                    targets = listOf(target),
                    outgoing = false,
                    declarations =
                        listOf(
                            source.requestedDeclaration("", "runCase"),
                            source.requestedDeclaration("", "consumeWidth"),
                            transform.requestedDeclaration("", "availableWidth"),
                        ),
                )
        assertTrue(
            "No derived path reached the requested consumeWidth argument. " +
                "Terminals: ${result.paths.map { (it.terminal as? ImpactPathTerminalDocument.UnresolvedFlow)?.cause }}",
            result.paths.any { path ->
                path.classification ==
                    ImpactPathClassificationDocument.Established(ImpactRelationshipDocument.DERIVED) &&
                    path.steps.values.filterIsInstance<ImpactPathStepDocument.Compiler>().any { step ->
                        step.transfer.target == target
                    }
            },
        )
    }

    fun testArithmeticSlotsSurviveExplicitAndOperatorCalls() = runBlocking {
        val files =
            queryFixture(
                SUBTRACTION +
                    mapOf(
                        "Transform.kt" to
                            ARITHMETIC_CASES.joinToString("\n") { case ->
                                "fun ${case.name}(width: Int): Int = ${case.expression}"
                            },
                        "Consumer.kt" to
                            consumerSource(ARITHMETIC_CASES.map { it.name to "${it.name}(sourceWidth())" }),
                    )
            )
        for (case in ARITHMETIC_CASES) {
            val captured = captureCase(files, case.name, listOf(case.name))
            val path = captured.derivedPath()
            val proof = path.arithmetic().single()
            assertEquals(case.rule, proof.rule)
            assertEquals(case.input, proof.input)
            assertEquals(ImpactIntBuiltinEvidenceDocument.K2_BUILTINS_METADATA, proof.authority)
        }
    }

    fun testNestedMinMaxRetainsBothReviewedInputsAndDerivedAccounting() =
        runBlocking<Unit> {
            val files =
                queryFixture(
                    sources =
                        SUBTRACTION +
                            mapOf(
                                "Transform.kt" to NESTED_TRANSFORMS,
                                "Consumer.kt" to consumerSource(NESTED_CASES),
                            ),
                    stdlib = NativeContainerQueryStdlib.REVIEWED,
                )
            val shared = listOf("nested")
            val width = captureCase(files, "width", shared).derivedPath()
            assertEquals(ImpactIntArithmeticRuleDocument.INT_MINUS_V1, width.arithmetic().single().rule)
            assertExtrema(width, listOf(MIN to SECOND, MAX to SECOND))
            val margin = captureCase(files, "margin", shared).derivedPath()
            assertEquals(ARGUMENT, margin.arithmetic().single().input)
            assertExtrema(margin, listOf(MIN to SECOND, MAX to SECOND))
            val upper = captureCase(files, "upper", shared).derivedPath()
            assertTrue(upper.arithmetic().isEmpty())
            assertExtrema(upper, listOf(MIN to FIRST, MAX to SECOND))
            val lower = captureCase(files, "lower", shared).derivedPath()
            assertTrue(lower.arithmetic().isEmpty())
            assertExtrema(lower, listOf(MAX to FIRST))

            val constants = captureCase(files, "constants", listOf("nestedConstants"))
            val path = constants.derivedPath()
            assertExtrema(path, listOf(MIN to SECOND, MAX to SECOND))
            assertRetainedAccounting(files.getValue("Consumer.kt").services, constants)
            val encoded = Json.encodeToString(ImpactPathDocument.serializer(), path)
            assertEquals(path, Json.decodeFromString(ImpactPathDocument.serializer(), encoded))
            org.junit.Assert.assertThrows(SerializationException::class.java) {
                Json.decodeFromString(ImpactPathDocument.serializer(), encoded.replace("\"DERIVED\"", "\"EXACT\""))
            }
        }

    fun testUnrelatedOrUnsupportedOperationsDoNotCreatePrimitiveFlow() = runBlocking {
        val files =
            queryFixture(
                sources =
                    SUBTRACTION +
                        mapOf(
                            "Transform.kt" to NEGATIVE_TRANSFORMS,
                            "Consumer.kt" to consumerSource(NEGATIVE_CASES),
                        ),
                errors = mapOf("Transform.kt" to listOf("UNRESOLVED_REFERENCE")),
            )
        val shadow = captureCase(files, "shadow", listOf("subtractShadow", "min"))
        shadow.assertDisconnected()
        assertTrue(shadow.result.paths.single().terminal is ImpactPathTerminalDocument.SupportedDomainEnd)
        assertEquals(
            2,
            shadow.result.paths.single().steps.values.filterIsInstance<ImpactPathStepDocument.Compiler>().count {
                it.transfer.kind == ImpactTransferKindDocument.SOURCE_CALL_ENTRY
            },
        )
        val custom = captureCase(files, "custom", listOf("customPlus"))
        custom.assertDisconnected()
        val overload = captureCase(files, "overload", listOf("longMinus"))
        overload.assertDisconnected()
        assertEquals(ImpactFlowUnsupportedDocument.EXTERNAL_CALL, overload.frontier().cause)
        val unresolved = captureCase(files, "unresolved", listOf("missingTarget"))
        unresolved.assertDisconnected()
        assertEquals(ImpactFlowUnsupportedDocument.UNRESOLVED_REFERENCE, unresolved.frontier().cause)
        val opaque = captureCase(files, "opaque", listOf("opaqueTarget"))
        assertTrue(opaque.result.paths.none { path -> path.reaches(opaque.target) })
        assertEquals(
            ImpactIntArithmeticRuleDocument.INT_MINUS_V1,
            opaque.result.paths.single().arithmetic().single().rule,
        )
        assertEquals(ImpactFlowUnsupportedDocument.EXTERNAL_CALL, opaque.frontier().cause)
        val field = captureCase(files, "field", listOf("unrelatedField"))
        field.assertDisconnected()
    }

    private suspend fun captureCase(
        files: Map<String, NativePacketQueryCase>,
        scenario: String,
        transforms: List<String>,
    ): CapturedPrimitiveCase {
        val consumer = files.getValue("Consumer.kt")
        val source = consumer.source
        val owner = "${scenario}Case"
        val target =
            ImpactValueSiteReferenceDocument(
                enclosing = source.reference(source.declaration("", owner)),
                range = source.markedRange(scenario),
                role =
                    ImpactValueRoleDocument.Argument(
                        invocation =
                            ImpactInvocationReferenceDocument(
                                source.invocation("", owner, "consumeWidth"),
                                source.reference(source.declaration("", "consumeWidth")),
                                ImpactExecutionOwnerDocument.Declaration,
                            ),
                        index = packetQueryOffset(0),
                    ),
            )
        val result =
            NativePacketQueryCapture(consumer.services)
                .capture(
                    producer = source.producer(owner, "sourceWidth", files.getValue("Producer.kt").source),
                    targets = listOf(target),
                    outgoing = false,
                    declarations =
                        listOf(
                            source.requestedDeclaration("", owner),
                            source.requestedDeclaration("", "consumeWidth"),
                        ) + transforms.map { files.getValue("Transform.kt").source.requestedDeclaration("", it) },
                )
        return CapturedPrimitiveCase(result, target)
    }

    private suspend fun assertRetainedAccounting(
        services: NativePacketQueryServices,
        captured: CapturedPrimitiveCase,
    ) {
        val reference = checkNotNull(captured.result.retained.presentationWindow).reference
        val reads = services.nativeFlowReads
        val proofs = services.nativeProofReads
        val compiler = services.observation.snapshot()
        val reader = NativePacketQueryCapture(services)
        val findings =
            reader
                .witnesses(QueryRunRequest.ReadResult.impactWitness(reference, ImpactWitnessSectionDocument.FINDINGS))
                .filterIsInstance<ImpactWitnessDocument.Finding>()
                .map { it.finding }
        val accounting =
            reader
                .witnesses(
                    QueryRunRequest.ReadResult.impactWitness(reference, ImpactWitnessSectionDocument.SITE_ACCOUNTING)
                )
                .filterIsInstance<ImpactWitnessDocument.SiteAccounting>()
                .single()
                .accounting
        assertEquals(reads, services.nativeFlowReads)
        assertEquals(proofs, services.nativeProofReads)
        assertEquals(compiler, services.observation.snapshot())
        assertEquals(captured.target, accounting.site)
        val reached = accounting.outcome as ImpactSiteOutcomeDocument.Reached
        val found = findings.single { it.path in reached.paths.values }
        assertEquals(DERIVED, found.classification)
        assertEquals(listOf(MIN, MAX), found.reviewedRelations.values.map { it.authority.rule })
    }

    private suspend fun NativePacketQueryCapture.witnesses(
        request: QueryRunRequest.ReadResult
    ): List<ImpactWitnessDocument> {
        val page = execute(request) as OperationOutcome.Complete
        return page.evidence.payload.items.values.map { (it as QueryResultItemDocument.ImpactWitness).item.witness }
    }

    private fun assertExtrema(
        path: ImpactPathDocument,
        expected: List<Pair<ImpactReviewedValueRuleDocument, ImpactReviewedIntExtremumInputDocument>>,
    ) {
        val reviewed = path.reviewed()
        assertTrue(reviewed.all(ImpactReviewedValueTransferDocument::admitsEvidence))
        assertEquals(expected, reviewed.map { it.binding.authority.rule to it.extremum().input })
    }

    private data class ArithmeticCase(
        val name: String,
        val expression: String,
        val rule: ImpactIntArithmeticRuleDocument,
        val input: ImpactIntArithmeticInputDocument,
    )

    private data class CapturedPrimitiveCase(
        val result: NativePacketQueryResult,
        val target: ImpactValueSiteReferenceDocument,
    ) {
        fun derivedPath(): ImpactPathDocument {
            assertTrue(
                "Expected completion. Frontiers: ${result.paths.map { it.terminal.frontierName() }}",
                result.outcome is OperationOutcome.Complete,
            )
            val path = result.paths.single { it.reaches(target) }
            assertEquals(DERIVED, path.classification)
            return path
        }

        fun assertDisconnected() {
            assertTrue(result.paths.none { it.reaches(target) })
            assertTrue(result.paths.all { it.arithmetic().isEmpty() && it.reviewed().isEmpty() })
        }

        fun frontier() = result.paths.mapNotNull { it.terminal as? ImpactPathTerminalDocument.UnresolvedFlow }.single()
    }

    private companion object {
        val DERIVED = ImpactPathClassificationDocument.Established(ImpactRelationshipDocument.DERIVED)
        val RECEIVER = ImpactIntArithmeticInputDocument.DISPATCH_RECEIVER
        val ARGUMENT = ImpactIntArithmeticInputDocument.ARGUMENT_0
        val MIN = ImpactReviewedValueRuleDocument.KOTLIN_2_4_21_INT_MIN_V1
        val MAX = ImpactReviewedValueRuleDocument.KOTLIN_2_4_21_INT_MAX_V1
        val FIRST = ImpactReviewedIntExtremumInputDocument.ARGUMENT_0
        val SECOND = ImpactReviewedIntExtremumInputDocument.ARGUMENT_1

        fun consumerSource(cases: List<Pair<String, String>>): String =
            "fun consumeWidth(width: Int) = Unit\n" +
                cases.joinToString("\n") { (name, expression) ->
                    "fun ${name}Case() = consumeWidth(/*<$name>*/$expression/*</$name>*/)"
                }

        val ARITHMETIC_CASES =
            listOf(
                ArithmeticCase(
                    name = "leftMinus",
                    expression = "width.minus(2)",
                    rule = ImpactIntArithmeticRuleDocument.INT_MINUS_V1,
                    input = RECEIVER,
                ),
                ArithmeticCase(
                    name = "rightMinus",
                    expression = "80.minus(width)",
                    rule = ImpactIntArithmeticRuleDocument.INT_MINUS_V1,
                    input = ARGUMENT,
                ),
                ArithmeticCase(
                    name = "rightSubtract",
                    expression = "80 - width",
                    rule = ImpactIntArithmeticRuleDocument.INT_MINUS_V1,
                    input = ARGUMENT,
                ),
                ArithmeticCase(
                    name = "leftPlus",
                    expression = "width + 2",
                    rule = ImpactIntArithmeticRuleDocument.INT_PLUS_V1,
                    input = RECEIVER,
                ),
                ArithmeticCase(
                    name = "rightPlus",
                    expression = "2.plus(width)",
                    rule = ImpactIntArithmeticRuleDocument.INT_PLUS_V1,
                    input = ARGUMENT,
                ),
                ArithmeticCase(
                    name = "leftTimes",
                    expression = "width.times(2)",
                    rule = ImpactIntArithmeticRuleDocument.INT_TIMES_V1,
                    input = RECEIVER,
                ),
                ArithmeticCase(
                    name = "rightTimes",
                    expression = "2 * width",
                    rule = ImpactIntArithmeticRuleDocument.INT_TIMES_V1,
                    input = ARGUMENT,
                ),
            )

        val NESTED_TRANSFORMS =
            """
            fun nested(lower: Int, upper: Int, width: Int, margin: Int): Int =
                kotlin.math.max(lower, kotlin.math.min(upper, width - margin))
            fun nestedConstants(width: Int): Int =
                kotlin.math.max(1, kotlin.math.min(100, width - 2))
            """
                .trimIndent()

        val NESTED_CASES =
            listOf(
                "width" to "nested(1, 100, sourceWidth(), 2)",
                "margin" to "nested(1, 100, 80, sourceWidth())",
                "upper" to "nested(1, sourceWidth(), 80, 2)",
                "lower" to "nested(sourceWidth(), 100, 80, 2)",
                "constants" to "nestedConstants(sourceWidth())",
            )

        val NEGATIVE_TRANSFORMS =
            """
            fun min(a: Int, b: Int): Int = 0
            fun subtractShadow(width: Int): Int = min(width, 2)
            class Custom { operator fun plus(width: Int): Int = 0 }
            fun customPlus(width: Int): Int = Custom() + width
            fun longMinus(width: Int): Int = (width - 2L).toInt()
            fun missingTarget(width: Int): Int = missing(width)
            fun opaqueTarget(width: Int): Int = java.lang.Integer.reverse(width - 2)
            data class Tracked(val width: Int)
            data class Other(val width: Int)
            fun unrelatedField(width: Int): Int {
                val tracked = Tracked(width)
                return Other(0).width
            }
            """
                .trimIndent()

        val NEGATIVE_CASES =
            listOf(
                "shadow" to "subtractShadow(sourceWidth())",
                "custom" to "customPlus(sourceWidth())",
                "overload" to "longMinus(sourceWidth())",
                "unresolved" to "missingTarget(sourceWidth())",
                "opaque" to "opaqueTarget(sourceWidth())",
                "field" to "unrelatedField(sourceWidth())",
            )

        val SUBTRACTION =
            mapOf(
                "Producer.kt" to "fun sourceWidth(): Int = 80",
                "Transform.kt" to "fun availableWidth(width: Int): Int = width - 2",
                "Consumer.kt" to
                    """
                    fun consumeWidth(width: Int) = Unit
                    fun runCase() = consumeWidth(/*<use>*/availableWidth(sourceWidth())/*</use>*/)
                    """
                        .trimIndent(),
            )
    }
}

private fun ImpactPathDocument.reaches(target: ImpactValueSiteReferenceDocument): Boolean =
    steps.values.filterIsInstance<ImpactPathStepDocument.Compiler>().any { it.transfer.target == target }

private fun ImpactPathDocument.arithmetic(): List<ImpactTransferEvidenceDocument.PrimitiveDerivation> =
    steps.values.filterIsInstance<ImpactPathStepDocument.Compiler>().mapNotNull { step ->
        (step.transfer.evidence as? ImpactTransferEvidenceDocument.PrimitiveDerivation)?.also {
            check(step.transfer.admitsEvidence())
        }
    }

private fun ImpactPathDocument.reviewed(): List<ImpactReviewedValueTransferDocument> =
    steps.values.filterIsInstance<ImpactPathStepDocument.ReviewedLibrary>().map { it.transfer }

private fun ImpactReviewedValueTransferDocument.extremum() =
    binding.relation as ImpactReviewedValueRelationDocument.IntExtremum

private fun ImpactPathTerminalDocument.frontierName() =
    when (this) {
        is ImpactPathTerminalDocument.UnresolvedFlow -> cause.name
        else -> javaClass.simpleName
    }
