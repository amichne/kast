package io.github.amichne.kast.runtime.hosted

import com.intellij.openapi.module.JavaModuleType
import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.protocol.contract.ImpactCallFrameDocument
import io.github.amichne.kast.protocol.contract.ImpactCompilerTransferDocument
import io.github.amichne.kast.protocol.contract.ImpactExecutionReferenceDocument
import io.github.amichne.kast.protocol.contract.ImpactFormalPositionDocument
import io.github.amichne.kast.protocol.contract.ImpactInvocationReferenceDocument
import io.github.amichne.kast.protocol.contract.ImpactPathStepDocument
import io.github.amichne.kast.protocol.contract.ImpactReceiverKindDocument
import io.github.amichne.kast.protocol.contract.ImpactSourceOperationDocument
import io.github.amichne.kast.protocol.contract.ImpactTransferEvidenceDocument
import io.github.amichne.kast.protocol.contract.ImpactTransferKindDocument
import io.github.amichne.kast.protocol.contract.ImpactValueConditionDocument
import io.github.amichne.kast.protocol.contract.ImpactValueConditionOriginDocument
import io.github.amichne.kast.protocol.contract.ImpactValuePathSegmentDocument
import io.github.amichne.kast.protocol.contract.ImpactValueRoleDocument
import io.github.amichne.kast.protocol.contract.ImpactValueSiteReferenceDocument
import junit.framework.TestCase.assertEquals
import junit.framework.TestCase.assertTrue
import kotlinx.coroutines.runBlocking

class NativeCompilerCoverageControlsTest : NativeCompilerCoverageFixtureTest() {
    override fun getModuleType() = JavaModuleType.getModuleType()

    fun testIncompatibleVariantDoesNotAcquireThePredicatePayload() = runBlocking {
        excluded(scenario = "incompatiblePredicate", operation = "predicated")
    }

    fun testContractOnAnotherArgumentDoesNotTransferTheOriginalPayload() = runBlocking {
        excluded(scenario = "wrongArgument", operation = "selectedArgument")
    }

    fun testConstantInheritedResultStopsTheRefinedPayloadBeforeCallbackInput() = runBlocking {
        assertCoverageComposition(prepare(), NativeCoverageComposition.CONSTANT)
    }

    fun testIncompatibleVariantReachesTheGuardButNotInheritedCallbackComposition() = runBlocking {
        assertCoverageComposition(prepare(), NativeCoverageComposition.INCOMPATIBLE)
    }

    private suspend fun excluded(scenario: String, operation: String) {
        val fixture = prepare()
        val source = fixture.source
        val producer = source.producer("", "${scenario}Case", "origin")
        val capture =
            NativePacketQueryCapture(fixture.services)
                .capture(producer = producer, targets = emptyList(), outgoing = false, declarations = emptyList())
        val entered = source.reference(source.declaration("", operation))
        val routes =
            capture.paths.map { path ->
                path.steps.values.filterIsInstance<ImpactPathStepDocument.Compiler>().map { it.transfer }
            }
        assertTrue(
            "Negative control never reached $operation from its original producer",
            routes.any { route ->
                route.isNotEmpty() &&
                    route.first().source.range == producer.anchor &&
                    route.zipWithNext().all { (first, second) -> first.target == second.source } &&
                    route.any {
                        it.kind == ImpactTransferKindDocument.SOURCE_CALL_ENTRY && it.target.enclosing == entered
                    }
            },
        )
        val forbidden = source.reference(source.declaration("", "forbidden"))
        val endpoints =
            routes
                .flatten()
                .map { it.target }
                .filter { site ->
                    val role = site.role
                    role is ImpactValueRoleDocument.Argument && role.invocation.callable == forbidden
                }
        assertTrue("$scenario invented forbidden payload endpoints: $endpoints", endpoints.isEmpty())
        assertTrue(
            "$scenario retained unresolved flow: ${fixture.services.executionSummary()}",
            capture.outcome is OperationOutcome.Complete,
        )
    }
}

internal enum class NativeCoverageComposition(val scenario: String, val operation: String) {
    PAYLOAD("composition", "pack"),
    CONSTANT("constantComposition", "constantPack"),
    INCOMPATIBLE("incompatibleComposition", "pack"),
}

/** These oracles are authored from fixture declarations, never reconstructed from observed path endpoints. */
internal suspend fun assertCoverageComposition(fixture: NativePacketQueryCase, case: NativeCoverageComposition) {
    val source = fixture.source
    val method = "${case.scenario}Case"
    val producer = source.producer("BindingScope", method, "origin")
    val capture =
        NativePacketQueryCapture(fixture.services)
            .capture(producer = producer, targets = emptyList(), outgoing = false, declarations = emptyList())
    assertTrue(capture.paths.all { path -> path.steps.values.all { it is ImpactPathStepDocument.Compiler } })
    val caller = source.reference(source.declaration("BindingScope", method))
    val routes =
        capture.paths.map { path ->
            path.steps.values.filterIsInstance<ImpactPathStepDocument.Compiler>().map { it.transfer }
        }
    val originalRoutes = routes.filter { route ->
        route.firstOrNull()?.source?.range == producer.anchor &&
            route.firstOrNull()?.source?.enclosing == caller &&
            route.zipWithNext().all { (first, second) -> first.target == second.source }
    }
    val predecessor =
        when (case) {
            NativeCoverageComposition.INCOMPATIBLE -> compositionGuardEntry(source, case)
            NativeCoverageComposition.PAYLOAD,
            NativeCoverageComposition.CONSTANT -> compositionPackEntry(source, case)
        }
    assertTrue(
        "${case.scenario} never reached its exact predecessor",
        originalRoutes.any { route ->
            route.any { it.kind == ImpactTransferKindDocument.SOURCE_CALL_ENTRY && it.target == predecessor }
        },
    )
    val expected =
        when (case) {
            NativeCoverageComposition.PAYLOAD -> setOf(compositionConsumer(source, case))
            NativeCoverageComposition.CONSTANT,
            NativeCoverageComposition.INCOMPATIBLE -> emptySet()
        }
    assertCompositionEndpoints(source, routes.flatten(), expected)
    when (case) {
        NativeCoverageComposition.PAYLOAD -> assertCompositionRoute(source, originalRoutes)
        NativeCoverageComposition.CONSTANT,
        NativeCoverageComposition.INCOMPATIBLE -> assertCompositionStopped(source, case, routes.flatten())
    }
    assertTrue(
        "${case.scenario} did not retain complete production evidence: ${fixture.services.executionSummary()}",
        capture.outcome is OperationOutcome.Complete,
    )
}

private suspend fun assertCompositionEndpoints(
    source: NativePacketQuerySource,
    transfers: List<ImpactCompilerTransferDocument>,
    expected: Set<ImpactValueSiteReferenceDocument>,
) {
    val consumers =
        setOf(
            source.reference(source.declaration("", "consume")),
            source.reference(source.declaration("", "forbidden")),
        )
    val endpoints =
        transfers
            .map { it.target }
            .filter { site ->
                (site.role as? ImpactValueRoleDocument.Argument)?.invocation?.callable in consumers
            }
            .toSet()
    assertEquals("Composition admitted a wrong or unqualified consumer", expected, endpoints)
}

private suspend fun assertCompositionStopped(
    source: NativePacketQuerySource,
    case: NativeCoverageComposition,
    transfers: List<ImpactCompilerTransferDocument>,
) {
    assertTrue(
        "Excluded payload acquired a callback argument",
        transfers.none { transfer ->
            transfer.sourceOperation() is ImpactSourceOperationDocument.CallbackArgumentEntry
        },
    )
    if (case == NativeCoverageComposition.INCOMPATIBLE) {
        val inherited = source.reference(source.declaration("Scope", "pack"))
        assertTrue(
            "Incompatible payload entered the inherited operation",
            transfers.none { transfer ->
                transfer.kind == ImpactTransferKindDocument.SOURCE_CALL_ENTRY && transfer.target.enclosing == inherited
            },
        )
    }
}

private suspend fun compositionConsumer(source: NativePacketQuerySource, case: NativeCoverageComposition) =
    ImpactValueSiteReferenceDocument(
        enclosing = source.reference(source.declaration("BindingScope", "${case.scenario}Case")),
        range = source.markedRange("${case.scenario}-use"),
        role =
            ImpactValueRoleDocument.Argument(
                ImpactInvocationReferenceDocument(
                    source.invocation("BindingScope", "${case.scenario}Case", "consume"),
                    source.reference(source.declaration("", "consume")),
                ),
                packetQueryOffset(0),
            ),
        conditions = packetQueryList(listOf(compositionCondition(source, case))),
    )

private suspend fun compositionCondition(
    source: NativePacketQuerySource,
    case: NativeCoverageComposition,
): ImpactValueConditionDocument {
    val callee = source.reference(source.declaration("", "predicated"))
    val caller = source.reference(source.declaration("BindingScope", "${case.scenario}Case"))
    val execution = ImpactExecutionReferenceDocument(callee)
    val invocation =
        ImpactInvocationReferenceDocument(
            source.invocation("BindingScope", "${case.scenario}Case", "predicated"),
            callee,
        )
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
                    packetQueryList(listOf(ImpactValuePathSegmentDocument.Field(source.field("Selected", "payload")))),
                callStack = packetQueryList(listOf(ImpactCallFrameDocument(caller, invocation, execution))),
            ),
        useRange = source.expressionRange("", "predicated", "payload"),
        kind = ImpactReceiverKindDocument.EXTENSION,
        originalType = packetQueryText("fixture.coverage.Choice"),
        refinedType = packetQueryText("fixture.coverage.Selected"),
    )
}

private suspend fun compositionPackEntry(
    source: NativePacketQuerySource,
    case: NativeCoverageComposition,
): ImpactValueSiteReferenceDocument {
    val caller = source.reference(source.declaration("BindingScope", "${case.scenario}Case"))
    val callee = source.reference(source.declaration("Scope", case.operation))
    val invocation =
        ImpactInvocationReferenceDocument(
            source.invocation("BindingScope", "${case.scenario}Case", case.operation),
            callee,
        )
    return ImpactValueSiteReferenceDocument(
        enclosing = callee,
        range = callee.range,
        role =
            ImpactValueRoleDocument.Formal(ImpactFormalPositionDocument.Receiver(ImpactReceiverKindDocument.EXTENSION)),
        callStack =
            packetQueryList(
                listOf(ImpactCallFrameDocument(caller, invocation, ImpactExecutionReferenceDocument(callee)))
            ),
        conditions = packetQueryList(listOf(compositionCondition(source, case))),
    )
}

private suspend fun compositionGuardEntry(
    source: NativePacketQuerySource,
    case: NativeCoverageComposition,
): ImpactValueSiteReferenceDocument {
    val caller = source.reference(source.declaration("BindingScope", "${case.scenario}Case"))
    val operation = source.reference(source.declaration("", "predicated"))
    val guard = source.reference(source.declaration("", "isSelected"))
    val operationCall =
        ImpactInvocationReferenceDocument(
            source.invocation("BindingScope", "${case.scenario}Case", "predicated"),
            operation,
        )
    val guardCall = ImpactInvocationReferenceDocument(source.invocation("", "predicated", "isSelected"), guard)
    return ImpactValueSiteReferenceDocument(
        enclosing = guard,
        range = guard.range,
        role =
            ImpactValueRoleDocument.Formal(ImpactFormalPositionDocument.Receiver(ImpactReceiverKindDocument.EXTENSION)),
        valuePath = packetQueryList(listOf(ImpactValuePathSegmentDocument.Field(source.field("Other", "payload")))),
        callStack =
            packetQueryList(
                listOf(
                    ImpactCallFrameDocument(caller, operationCall, ImpactExecutionReferenceDocument(operation)),
                    ImpactCallFrameDocument(operation, guardCall, ImpactExecutionReferenceDocument(guard)),
                )
            ),
    )
}

private suspend fun assertCompositionRoute(
    source: NativePacketQuerySource,
    routes: List<List<ImpactCompilerTransferDocument>>,
) {
    val case = NativeCoverageComposition.PAYLOAD
    val packEntry = compositionPackEntry(source, case)
    val conditions = packetQueryList(listOf(compositionCondition(source, case)))
    val field = source.field("Selected", "payload")
    val supplier = source.reference(source.declaration("", "invokeParts"))
    val callback = compositionCallbackWitness(source)
    val witnesses: List<(ImpactCompilerTransferDocument) -> Boolean> =
        listOf<(ImpactCompilerTransferDocument) -> Boolean>(
            { transfer ->
                transfer.kind == ImpactTransferKindDocument.FIELD_READ &&
                    transfer.sourceOperation() == ImpactSourceOperationDocument.ReadField(field) &&
                    transfer.target.conditions == conditions
            },
            { transfer ->
                transfer.kind == ImpactTransferKindDocument.SOURCE_CALL_ENTRY && transfer.target == packEntry
            },
            { transfer ->
                transfer.kind == ImpactTransferKindDocument.SOURCE_CALL_ENTRY && transfer.target.enclosing == supplier
            },
            callback,
        ) + compositionBodyWitnesses(source)
    assertTrue(
        "No original-producer route crossed refinement, inherited body, callback input, component, binding and use",
        routes.any { route ->
            val indices = witnesses.map { witness -> route.indexOfFirst(witness) }
            indices.all { it >= 0 } &&
                indices.zipWithNext().all { (first, second) -> first < second } &&
                route.drop(indices.first()).all { it.target.conditions == conditions }
        },
    )
}

private suspend fun compositionBodyWitnesses(
    source: NativePacketQuerySource
): List<(ImpactCompilerTransferDocument) -> Boolean> {
    val supplier = source.reference(source.declaration("", "invokeParts"))
    val supply =
        ImpactInvocationReferenceDocument(source.invocation("BindingScope", "compositionCase", "invokeParts"), supplier)
    val component = source.reference(source.declaration("SwappedParts", "component1"))
    val binding = source.markedRange("composition-binding")
    val use = source.markedRange("composition-callback-use")
    val consumer = compositionConsumer(source, NativeCoverageComposition.PAYLOAD)
    return listOf(
        { transfer ->
            transfer.kind == ImpactTransferKindDocument.SOURCE_CALL_ENTRY &&
                transfer.target.enclosing == component &&
                (transfer.source.role as? ImpactValueRoleDocument.Receiver)?.invocation?.range == binding
        },
        { transfer -> transfer.kind == ImpactTransferKindDocument.LOCAL_BINDING && transfer.target.range == binding },
        { transfer -> transfer.kind == ImpactTransferKindDocument.LOCAL_READ && transfer.target.range == use },
        { transfer ->
            (transfer.sourceOperation() as? ImpactSourceOperationDocument.CallbackReturn)?.invocation?.supply == supply
        },
        { transfer -> transfer.target == consumer },
    )
}

private suspend fun compositionCallbackWitness(
    source: NativePacketQuerySource
): (ImpactCompilerTransferDocument) -> Boolean {
    val supplier = source.reference(source.declaration("", "invokeParts"))
    val supply =
        ImpactInvocationReferenceDocument(source.invocation("BindingScope", "compositionCase", "invokeParts"), supplier)
    val argument = source.expressionRange("", "invokeParts", "value")
    val invocation = source.invocation("", "invokeParts", "action")
    val parameter = source.markedRange("composition-parameter")
    val body = source.markedRange("composition-callback")
    val path = packetQueryList(listOf(ImpactValuePathSegmentDocument.Field(source.field("SwappedParts", "second"))))
    return { transfer ->
        val entry = transfer.sourceOperation() as? ImpactSourceOperationDocument.CallbackArgumentEntry
        if (entry == null) false
        else
            listOf(
                    transfer.kind == ImpactTransferKindDocument.SOURCE_CALL_ENTRY,
                    entry.argumentRange == argument,
                    entry.invocation.supply == supply,
                    entry.invocation.invocation == invocation,
                    entry.invocation.body.range == body,
                    entry.parameter.index == packetQueryOffset(0),
                    entry.parameter.range == parameter,
                    transfer.target.valuePath == path,
                )
                .all { it }
    }
}

private fun ImpactCompilerTransferDocument.sourceOperation() =
    (evidence as? ImpactTransferEvidenceDocument.Source)?.operation
