package io.github.amichne.kast.runtime.hosted

import com.intellij.openapi.module.JavaModuleType
import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.protocol.contract.EmptyImpactConditions
import io.github.amichne.kast.protocol.contract.ImpactCallFrameDocument
import io.github.amichne.kast.protocol.contract.ImpactCastKindDocument
import io.github.amichne.kast.protocol.contract.ImpactExecutionReferenceDocument
import io.github.amichne.kast.protocol.contract.ImpactFormalPositionDocument
import io.github.amichne.kast.protocol.contract.ImpactInvocationReferenceDocument
import io.github.amichne.kast.protocol.contract.ImpactLocalLifetimeEndDocument
import io.github.amichne.kast.protocol.contract.ImpactLocalVersionDocument
import io.github.amichne.kast.protocol.contract.ImpactPathStepDocument
import io.github.amichne.kast.protocol.contract.ImpactReceiverKindDocument
import io.github.amichne.kast.protocol.contract.ImpactTransferKindDocument
import io.github.amichne.kast.protocol.contract.ImpactValueConditionDocument
import io.github.amichne.kast.protocol.contract.ImpactValueConditionOriginDocument
import io.github.amichne.kast.protocol.contract.ImpactValuePathSegmentDocument
import io.github.amichne.kast.protocol.contract.ImpactValueRoleDocument
import io.github.amichne.kast.protocol.contract.ImpactValueSiteReferenceDocument
import io.github.amichne.kast.protocol.contract.QueryRunRejection
import io.github.amichne.kast.relation.contract.ValueCondition
import io.github.amichne.kast.relation.contract.ValueTransferKind
import junit.framework.TestCase.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Neutral scenarios, not a replay of the unavailable incident inputs. */
class NativeCompilerCoverageQueryTest : NativeCompilerCoverageFixtureTest() {
    override fun getModuleType() = JavaModuleType.getModuleType()

    fun testImplicitSubjectReachesConsumerThroughSelectedBody() = runBlocking {
        complete("implicit", "", "mapError")
    }

    fun testSealedOverrideReachesConsumerThroughExactComponent() = runBlocking {
        complete("component", "Selected", "component1")
    }

    fun testNamedSubjectReachesConsumerThroughAlias() = runBlocking {
        complete("named", "", "named")
    }

    fun testTypeCheckAndCastReachConsumer() = runBlocking {
        complete("cast", "", "casted")
    }

    fun testUnknownCastRetainsItsConditionThroughTheConsumer() = runBlocking {
        complete("unknownCast", "", "force")
    }

    fun testContractPredicateReachesConsumer() = runBlocking {
        complete("predicate", "", "predicated")
    }

    fun testFalseReturnContractReachesOnlyItsRefinedConsumer() = runBlocking {
        complete("predicateFalse", "", "predicatedFalse")
    }

    fun testNullReturnContractReachesOnlyItsRefinedConsumer() = runBlocking {
        complete("predicateNull", "", "predicatedNull")
    }

    fun testNonNullReturnContractReachesOnlyItsRefinedConsumer() = runBlocking {
        complete("predicateNonNull", "", "predicatedNonNull")
    }

    fun testNormalReturnContractReachesOnlyItsRefinedConsumer() = runBlocking {
        complete("predicateNormal", "", "predicatedNormal")
    }

    fun testHoldsInContractReachesConsumerThroughItsSelectedCallback() = runBlocking {
        complete("holdsIn", "", "held")
    }

    fun testGuardClauseRetainsItsConditionalConsumer() = runBlocking {
        complete("guard", "", "guarded")
    }

    fun testShortCircuitRetainsItsConditionalConsumer() = runBlocking {
        complete("shortCircuit", "", "shortCircuited")
    }

    fun testWhenGuardRetainsItsConditionalConsumer() = runBlocking {
        complete("whenGuard", "", "whenGuarded")
    }

    fun testStableLocalRetainsItsDefinitionAndUseConditionThroughTheConsumer() = runBlocking {
        complete("stableLocal", "", "widen")
    }

    fun testStableLocalSurvivesSemanticCheckpointAndProtocolResume() = runBlocking {
        val fixture = prepare()
        val checkpoint = NativePacketQueryCheckpoint { step ->
            step.transfers.any { transfer ->
                transfer.target.conditions.facts.any { it is ValueCondition.StableLocal }
            }
        }
        assertCoverageResumed(
            fixture = fixture,
            scenario = "stableLocal",
            expected = consumer(fixture.source, "stableLocal", ""),
            checkpoint = checkpoint,
        )
    }

    fun testConfiguredGetterSurvivesSemanticCheckpointAndProtocolResume() = runBlocking {
        val fixture = prepareConfigured()
        val getter = fixture.source.declaration("", "getter")
        val checkpoint = NativePacketQueryCheckpoint { step ->
            step.source.enclosing.file == getter.file &&
                step.source.enclosing.range == getter.range &&
                step.transfers.any { it.kind == ValueTransferKind.RETURN }
        }
        assertCoverageResumed(
            fixture = fixture,
            scenario = "namedGetter",
            expected = consumer(fixture.source, "namedGetter", ""),
            checkpoint = checkpoint,
        )
    }

    fun testOrderedComponentsReachOnlyTheExactSelectedConsumer() = runBlocking {
        complete("orderedComponents", "SwappedParts", "component1")
    }

    fun testSideEffectBeforeComponentBlockReturnDoesNotReleaseBindings() = runBlocking {
        incompleteComponents(prepare(), "sideEffectComponents", "SideEffectParts")
    }

    fun testLaterThrowingComponentBlockReturnDoesNotReleaseBindings() = runBlocking {
        incompleteComponents(prepare(), "laterThrowingComponents", "LaterThrowingParts")
    }

    fun testLambdaArgumentReachesTheDestructuredConsumerFromItsOriginalProducer() = runBlocking {
        complete("lambdaInput", "SwappedParts", "component1")
    }

    fun testIteratorResultReachesTheDestructuredConsumerFromItsOriginalProducer() = runBlocking {
        complete("loopInput", "SwappedParts", "component1")
    }

    fun testConfiguredGetterReachesTheConsumerThroughItsActualBody() = runBlocking {
        complete(scenario = "namedGetter", operationOwner = "", operation = "getter", fixture = prepareConfigured())
    }

    fun testConfiguredSquareFormReachesOnlyTheSelectedConsumer() = runBlocking {
        complete(scenario = "squareLocal", operationOwner = "", operation = "square", fixture = prepareConfigured())
    }

    fun testConfiguredLambdaNamesPreserveTheActualArgumentPayload() = runBlocking {
        complete(scenario = "namedLambda", operationOwner = "", operation = "withValue", fixture = prepareConfigured())
    }

    fun testConfiguredLoopNamesPreserveTheActualNextResult() = runBlocking {
        complete(
            scenario = "namedLoop",
            operationOwner = "Repeating",
            operation = "next",
            fixture = prepareConfigured(),
        )
    }

    fun testExplicitBindReachesConsumerThroughInheritedBody() = runBlocking {
        complete(scenario = "bind", operationOwner = "Scope", operation = "bind", owner = "BindingScope")
    }

    fun testRefinementInheritedCallAndCallbackDestructuringComposeToExactConsumer() = runBlocking {
        assertCoverageComposition(prepare(), NativeCoverageComposition.PAYLOAD)
    }

    fun testContractDescriptionDoesNotExecuteItsReceiverReferences() = runBlocking {
        val fixture = prepare()
        val source = fixture.source
        val producer = source.producer("", "predicateDeclarationCase", "origin")
        val capture =
            NativePacketQueryCapture(fixture.services)
                .capture(producer = producer, targets = emptyList(), outgoing = false, declarations = emptyList())
        val predicate = source.reference(source.declaration("", "isSelected"))
        val routes =
            capture.paths.map { path ->
                path.steps.values.filterIsInstance<ImpactPathStepDocument.Compiler>().map { it.transfer }
            }
        assertTrue(
            "The original payload did not reach the predicate receiver",
            routes.any { route ->
                route.isNotEmpty() &&
                    route.first().source.range == producer.anchor &&
                    route.zipWithNext().all { (first, second) -> first.target == second.source } &&
                    route.any {
                        it.kind == ImpactTransferKindDocument.SOURCE_CALL_ENTRY && it.target.enclosing == predicate
                    }
            },
        )
        assertTrue(
            "Contract declaration retained executable obligations: ${fixture.services.executionSummary()}",
            capture.outcome is OperationOutcome.Complete,
        )
    }

    private suspend fun complete(
        scenario: String,
        operationOwner: String,
        operation: String,
        owner: String = "",
        fixture: NativePacketQueryCase = prepare(),
    ) {
        val source = fixture.source
        val method = "${scenario}Case"
        val expected = consumer(source, scenario, owner)
        val producer = source.producer(owner, method, "origin")
        val capture =
            NativePacketQueryCapture(fixture.services)
                .capture(producer = producer, targets = emptyList(), outgoing = false, declarations = emptyList())
        val entered = source.reference(source.declaration(operationOwner, operation))
        val routes =
            capture.paths.map { path ->
                path.steps.values.filterIsInstance<ImpactPathStepDocument.Compiler>().map { it.transfer }
            }
        println("COMPILER_COVERAGE_EXPECTED $scenario ${Json.encodeToString(expected)}")
        println("COMPILER_COVERAGE_TERMINALS $scenario ${Json.encodeToString(capture.paths.map { it.terminal })}")
        assertCoverageEndpoints(source = source, scenario = scenario, expected = expected, transfers = routes.flatten())
        assertTrue(
            "No contiguous producer-to-consumer route crossed $operation: " +
                routes.map { route ->
                    "seed=${route.firstOrNull()?.source?.range == producer.anchor} " +
                        "contiguous=${route.zipWithNext().all { (first, second) -> first.target == second.source }} " +
                        "entered=${route.filter { it.target.enclosing == entered }.map { it.kind }} " +
                        "consumer=${route.any { it.target == expected }}"
                },
            routes.any { route ->
                route.isNotEmpty() &&
                    route.first().source.range == producer.anchor &&
                    route.zipWithNext().all { (first, second) -> first.target == second.source } &&
                    route.any {
                        it.kind == ImpactTransferKindDocument.SOURCE_CALL_ENTRY && it.target.enclosing == entered
                    } &&
                    route.any { it.target == expected }
            },
        )
        assertTrue(
            "$scenario did not complete: ${fixture.services.executionSummary()}",
            capture.outcome is OperationOutcome.Complete,
        )
    }

    private suspend fun consumer(source: NativePacketQuerySource, scenario: String, owner: String) =
        ImpactValueSiteReferenceDocument(
            enclosing = source.reference(source.declaration(owner, "${scenario}Case")),
            range = source.markedRange("$scenario-use"),
            role =
                ImpactValueRoleDocument.Argument(
                    ImpactInvocationReferenceDocument(
                        source.invocation(owner, "${scenario}Case", "consume"),
                        source.reference(source.declaration("", "consume")),
                    ),
                    packetQueryOffset(0),
                ),
            conditions =
                when (scenario) {
                    "predicate" -> predicateConditions(source, scenario, "predicated")
                    "predicateFalse" -> predicateConditions(source, scenario, "predicatedFalse")
                    "predicateNull" -> predicateConditions(source, scenario, "predicatedNull")
                    "predicateNonNull" -> predicateConditions(source, scenario, "predicatedNonNull")
                    "predicateNormal" -> predicateConditions(source, scenario, "predicatedNormal")
                    "guard" -> predicateConditions(source, scenario, "guarded")
                    "shortCircuit" -> predicateConditions(source, scenario, "shortCircuited")
                    "whenGuard" -> predicateConditions(source, scenario, "whenGuarded")
                    "unknownCast" -> castConditions(source)
                    "stableLocal" -> stableLocalConditions(source)
                    else -> EmptyImpactConditions
                },
        )

    private suspend fun stableLocalConditions(source: NativePacketQuerySource) =
        packetQueryList(
            listOf<ImpactValueConditionDocument>(
                ImpactValueConditionDocument.StableLocal(
                    origin =
                        ImpactValueConditionOriginDocument(
                            execution =
                                ImpactExecutionReferenceDocument(
                                    source.reference(source.declaration("", "stableLocalCase"))
                                ),
                            range = source.expressionRange("", "stableLocalCase", "var value: Any = widen(origin())"),
                            role = ImpactValueRoleDocument.LocalBinding,
                        ),
                    useRange = source.markedRange("stableLocal-use"),
                    version =
                        ImpactLocalVersionDocument.Defined(
                            declaration =
                                source.expressionRange("", "stableLocalCase", "var value: Any = widen(origin())"),
                            block = source.markedRange("stableLocal-block"),
                            definition =
                                source.expressionRange("", "stableLocalCase", "var value: Any = widen(origin())"),
                            rhs = source.expressionRange("", "stableLocalCase", "widen(origin())"),
                            end = ImpactLocalLifetimeEndDocument.BlockEnd(source.markedRange("stableLocal-block")),
                        ),
                    originalType = packetQueryText("kotlin.Any"),
                    refinedType = packetQueryText("fixture.coverage.Payload"),
                )
            )
        )

    private suspend fun castConditions(source: NativePacketQuerySource) = packetQueryList(listOf(castCondition(source)))

    private suspend fun castCondition(source: NativePacketQuerySource): ImpactValueConditionDocument {
        val callee = source.reference(source.declaration("", "force"))
        val caller = source.reference(source.declaration("", "unknownCastCase"))
        val execution = ImpactExecutionReferenceDocument(callee)
        val invocation = ImpactInvocationReferenceDocument(source.invocation("", "unknownCastCase", "force"), callee)
        return ImpactValueConditionDocument.CastSuccess(
            origin =
                ImpactValueConditionOriginDocument(
                    execution = execution,
                    range = source.expressionRange("", "force", "value"),
                    role = ImpactValueRoleDocument.LocalRead,
                    callStack = packetQueryList(listOf(ImpactCallFrameDocument(caller, invocation, execution))),
                ),
            useRange = source.expressionRange("", "force", "value as Payload"),
            kind = ImpactCastKindDocument.UNSAFE,
            originalType = packetQueryText("kotlin.Any"),
            targetType = packetQueryText("fixture.coverage.Payload"),
        )
    }

    private suspend fun predicateConditions(source: NativePacketQuerySource, scenario: String, operation: String) =
        packetQueryList(listOf(predicateCondition(source, scenario, operation)))

    private suspend fun predicateCondition(
        source: NativePacketQuerySource,
        scenario: String,
        operation: String,
    ): ImpactValueConditionDocument {
        val callee = source.reference(source.declaration("", operation))
        val caller = source.reference(source.declaration("", "${scenario}Case"))
        val execution = ImpactExecutionReferenceDocument(callee)
        val invocation = ImpactInvocationReferenceDocument(source.invocation("", "${scenario}Case", operation), callee)
        return ImpactValueConditionDocument.StableReceiver(
            origin =
                ImpactValueConditionOriginDocument(
                    execution = execution,
                    range = callee.range,
                    role =
                        ImpactValueRoleDocument.Formal(
                            ImpactFormalPositionDocument.Receiver(ImpactReceiverKindDocument.EXTENSION)
                        ),
                    valuePath =
                        packetQueryList(
                            listOf(ImpactValuePathSegmentDocument.Field(source.field("Selected", "payload")))
                        ),
                    callStack = packetQueryList(listOf(ImpactCallFrameDocument(caller, invocation, execution))),
                ),
            useRange = source.expressionRange("", operation, "payload"),
            kind = ImpactReceiverKindDocument.EXTENSION,
            originalType = packetQueryText("fixture.coverage.Choice"),
            refinedType = packetQueryText("fixture.coverage.Selected"),
        )
    }
}

private suspend fun incompleteComponents(fixture: NativePacketQueryCase, scenario: String, componentOwner: String) {
    val source = fixture.source
    val method = "${scenario}Case"
    val producer = source.producer("", method, "origin")
    val capture =
        NativePacketQueryCapture(fixture.services)
            .capture(producer = producer, targets = emptyList(), outgoing = false, declarations = emptyList())
    val predecessor =
        ImpactValueSiteReferenceDocument(
            enclosing = source.reference(source.declaration("", method)),
            range = source.markedRange("$scenario-input"),
            role = ImpactValueRoleDocument.LocalRead,
            valuePath =
                packetQueryList(listOf(ImpactValuePathSegmentDocument.Field(source.field(componentOwner, "payload")))),
        )
    val routes =
        capture.paths.map { path ->
            path.steps.values.filterIsInstance<ImpactPathStepDocument.Compiler>().map { it.transfer }
        }
    assertTrue(
        "$scenario never reached the exact stored-field receiver before ordered admission",
        routes.any { route ->
            route.isNotEmpty() &&
                route.first().source.range == producer.anchor &&
                route.zipWithNext().all { (first, second) -> first.target == second.source } &&
                route.any { it.target == predecessor }
        },
    )
    val forbidden = source.reference(source.declaration("", "forbidden"))
    assertTrue(
        "$scenario released a binding before all invoked components completed",
        routes.flatten().none { transfer ->
            (transfer.target.role as? ImpactValueRoleDocument.Argument)?.invocation?.callable == forbidden ||
                transfer.target.range == source.markedRange("$scenario-forbidden")
        },
    )
    val outcome = capture.outcome
    assertTrue(
        "$scenario lost its unresolved component obligation: ${fixture.services.executionSummary()}",
        outcome is OperationOutcome.Rejected && outcome.reason is QueryRunRejection.CompletionUnproven,
    )
}
