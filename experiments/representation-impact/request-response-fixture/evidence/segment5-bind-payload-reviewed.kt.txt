package io.github.amichne.kast.relation.intellij

import com.intellij.openapi.application.ReadAction
import io.github.amichne.kast.relation.contract.RelationEndpoint
import io.github.amichne.kast.relation.contract.RelationMeaning
import io.github.amichne.kast.relation.contract.RelationRequest
import io.github.amichne.kast.relation.contract.RelationSearchBoundary
import io.github.amichne.kast.relation.contract.ValueAccessPath
import io.github.amichne.kast.relation.contract.ValueFlowRead
import io.github.amichne.kast.relation.contract.ValueFlowRequest
import io.github.amichne.kast.relation.contract.ValueFlowStep
import io.github.amichne.kast.relation.contract.ValueFormalPosition
import io.github.amichne.kast.relation.contract.ValuePathSegment
import io.github.amichne.kast.relation.contract.ValuePredicate
import io.github.amichne.kast.relation.contract.ValuePredicates
import io.github.amichne.kast.relation.contract.ValueProducerSeedRead
import io.github.amichne.kast.relation.contract.ValueReceiverKind
import io.github.amichne.kast.relation.contract.ValueRole
import io.github.amichne.kast.relation.contract.ValueSite
import io.github.amichne.kast.relation.contract.ValueSourceOperation
import io.github.amichne.kast.relation.contract.ValueSourceTransfer
import io.github.amichne.kast.relation.contract.ValueTransfer
import io.github.amichne.kast.relation.contract.ValueTransferEvidence
import io.github.amichne.kast.relation.contract.ValueTransferKind
import io.github.amichne.kast.symbol.contract.ExactDeclarationTextRange
import kotlinx.coroutines.runBlocking

/** The original S2 prefix supplies the scoped condition. The controlled initializer isolates the later source field. */
class NativePacketBindPayloadTest : NativePacketValueFixtureTest() {
    override val fixtureSdkName = "packet-bind-payload-jdk"

    fun testOriginalBindSuccessFieldKeepsTheOriginalExecuteGuardAndExactSuffix() {
        val fixture = packetFixture(NativePacketCompositeSuccessSource.PATHS)
        val source = NativePacketCompositeSuccessSource(project, getOrCreateProjectBaseDir(), fixture)
        val original = originalGuard(fixture, source)
        val initializer = initializer(fixture, source, original)
        val componentReceiver =
            next(
                    fixture = fixture,
                    source = initializer,
                    kind = ValueTransferKind.RECEIVER,
                    row = "C2.7 component receiver",
                )
                .target
        val component =
            next(
                    fixture = fixture,
                    source = componentReceiver,
                    kind = ValueTransferKind.SOURCE_CALL_ENTRY,
                    row = "C2.7 component formal",
                )
                .target
        val bindReceiver =
            next(
                    fixture = fixture,
                    source = component,
                    kind = ValueTransferKind.RECEIVER,
                    row = "C2.7 implicit bind receiver",
                )
                .target
        val role = bindReceiver.role as ValueRole.Receiver
        assertEquals(ValueReceiverKind.EXTENSION, role.kind)
        assertEquals(exact(BIND_START, BIND_END), role.call.range)
        val bind = fixture.callableDeclaration(PacketDeclaration(OUTCOME, "OutcomeScope", "bind"))
        assertEquals(bind.compilerIdentity, role.call.callable.compilerIdentity)
        val formal =
            next(
                    fixture = fixture,
                    source = bindReceiver,
                    kind = ValueTransferKind.SOURCE_CALL_ENTRY,
                    row = "C2.7 bind formal",
                )
                .target
        assertEquals(ValueRole.Formal(ValueFormalPosition.Receiver(ValueReceiverKind.EXTENSION)), formal.role)
        assertEquals(bind.range, formal.range)
        assertEquals(initializer.valuePath, formal.valuePath)
        assertEquals(initializer.predicates, formal.predicates)
        assertField(fixture = fixture, source = source, original = original, receiver = bindReceiver, formal = formal)
        println("BIND_PAYLOAD_RESULT row=C2.7 stage=guarded-success-value later=bind-return,C2.8,C2.9:NOT_RUN")
    }

    private fun originalGuard(
        fixture: NativePacketValueFixture,
        source: NativePacketCompositeSuccessSource,
    ): ValuePredicate.Subtype {
        val request =
            fixture.selection(
                PacketDeclaration(CLIENT, "OffersClientImpl", "readOffers"),
                "execute",
                PacketDeclaration(WIRE, "Call", "execute"),
            )
        val read = runBlocking {
            IntellijValueFlowCompilerAdapter()
                .seed(
                    project = project,
                    current = fixture.lease,
                    request = request,
                    model = fixture.model,
                )
        }
        assertTrue("The original S2 producer prerequisite did not admit", read is ValueProducerSeedRead.Seeded)
        val seed = (read as ValueProducerSeedRead.Seeded).seed
        assertEquals(request.anchor, seed.site.range)
        val prefix = NativePacketCompositeSuccessRoute(project, fixture, source).enteredCallback(seed.site)
        val tested =
            prefix.receiver
                .withState(valuePath = ValueAccessPath.Whole, predicates = ValuePredicates.Empty)
                .nativeRefined()
        val guards =
            prefix.formal.predicates.entries.filterIsInstance<ValuePredicate.Subtype>().filter { predicate ->
                predicate.subject.occurrence == tested &&
                    predicate.resolvedType.valueIdentity == prefix.successType.valueIdentity
            }
        assertEquals("The initializer input needs the actual original execute Success condition", 1, guards.size)
        return guards.single()
    }

    private fun initializer(
        fixture: NativePacketValueFixture,
        source: NativePacketCompositeSuccessSource,
        original: ValuePredicate.Subtype,
    ): ValueSite {
        val probe = NativePacketDestructuringProbeSource(project, getOrCreateProjectBaseDir(), fixture)
        val expected = ReadAction.compute<DestructuringProbeExpectation, RuntimeException> { probe.expectation() }
        val domain =
            RelationRequest.start(
                RelationEndpoint.subject(expected.enclosing),
                RelationMeaning.References,
                fixture.budget,
            )
        val found = runBlocking {
            IntellijValueFlowCompilerAdapter()
                .resolve(
                    project = project,
                    current = fixture.lease,
                    enclosing = expected.enclosing,
                    anchor = expected.initializer,
                    domain = domain,
                    model = fixture.model,
                )
        }
        assertTrue("The original initializer prerequisite did not admit", found is ValueSiteResolution.Found)
        val path =
            ValueAccessPath.fromSegments(
                    listOf(
                        ValuePathSegment.Field(source.field(OUTCOME, "Success", "value")),
                        ValuePathSegment.Field(source.field(CLIENT, "ClientResponse", "links")),
                    )
                )
                .nativeRefined()
        println(
            "BIND_PAYLOAD_SETUP kind=CONTROLLED_TRACKED_INITIALIZER guard=ORIGINAL_S2_NATIVE_PREFIX " +
                "full_original_route=NOT_ASSERTED"
        )
        return (found as ValueSiteResolution.Found)
            .site
            .withState(
                valuePath = path,
                predicates = ValuePredicates.fromCompiler(listOf(original)).nativeRefined(),
            )
            .nativeRefined()
    }

    private fun assertField(
        fixture: NativePacketValueFixture,
        source: NativePacketCompositeSuccessSource,
        original: ValuePredicate.Subtype,
        receiver: ValueSite,
        formal: ValueSite,
    ) {
        val result = step(fixture, formal)
        val fields =
            result.transfers.filter { transfer ->
                transfer.kind == ValueTransferKind.FIELD_READ && transfer.target.range == exact(VALUE_START, VALUE_END)
            }
        assertEquals(
            "C2.7 needs guarded Outcome.Success.value1227:1232 from the original bind formal. " +
                "Frontier=${result.obligations.map { it.cause }}. Bind return and C2.8/C2.9 remain NOT_REACHED",
            1,
            fields.size,
        )
        val field = fields.single()
        assertTrue(
            "The tracked Success field must not emit the Error.value sibling",
            result.transfers.none { transfer ->
                transfer.kind == ValueTransferKind.FIELD_READ &&
                    transfer.target.range == exact(ERROR_VALUE_START, ERROR_VALUE_END)
            },
        )
        val guarded = field.evidence as ValueTransferEvidence.Guarded
        val operation = (guarded.delegate as ValueSourceTransfer).operation as ValueSourceOperation.ReadField
        assertEquals(source.field(OUTCOME, "Success", "value"), operation.field)
        assertEquals(
            listOf(ValuePathSegment.Field(source.field(CLIENT, "ClientResponse", "links"))),
            field.target.valuePath.segments,
        )
        assertEquals(formal.executionOwner, field.target.executionOwner)
        assertEquals(formal.callStack, field.target.callStack)
        assertTrue(original in field.target.predicates.entries)
        val tested =
            receiver.withState(valuePath = ValueAccessPath.Whole, predicates = ValuePredicates.Empty).nativeRefined()
        val added = guarded.added.entries.filterIsInstance<ValuePredicate.Subtype>()
        assertEquals(listOf(tested), added.map { it.subject.occurrence })
        assertEquals(
            listOf(source.endpoint(OUTCOME, "Success").valueIdentity),
            added.map { it.resolvedType.valueIdentity },
        )
        assertTrue(field.target.predicates.entries.containsAll(formal.predicates.entries))
    }

    private fun next(
        fixture: NativePacketValueFixture,
        source: ValueSite,
        kind: ValueTransferKind,
        row: String,
    ): ValueTransfer {
        val result = step(fixture, source)
        val selected = result.transfers.filter { it.kind == kind }
        assertEquals("$row needs $kind. Frontier=${result.obligations.map { it.cause }}", 1, selected.size)
        return selected.single()
    }

    private fun step(fixture: NativePacketValueFixture, source: ValueSite): ValueFlowStep {
        val read = runBlocking {
            IntellijValueFlowCompilerAdapter()
                .read(
                    project = project,
                    current = fixture.lease,
                    request = ValueFlowRequest(source, fixture.budget, RelationSearchBoundary.RETAINED_SUBJECT),
                    model = fixture.model,
                )
        }
        assertTrue("The exact bind-payload setup did not produce a native read", read is ValueFlowRead.Observed)
        val result = (read as ValueFlowRead.Observed).step
        println(
            "BIND_PAYLOAD_STAGE source=${source.range} pathDepth=${source.valuePath.depth} " +
                "transfers=${result.transfers.map { it.kind to it.target.range }} " +
                "frontier=${result.obligations.map { it.cause }} work=${result.examinedWorkUnits.value}"
        )
        return result
    }

    private fun exact(start: Int, end: Int) = ExactDeclarationTextRange.parse(start, end).nativeRefined()

    private companion object {
        const val OUTCOME = NativePacketCompositeSuccessSource.OUTCOME
        const val CLIENT = NativePacketCompositeSuccessSource.CLIENT
        const val WIRE = NativePacketCompositeSuccessSource.WIRE
        const val BIND_START = 1137
        const val BIND_END = 1143
        const val VALUE_START = 1227
        const val VALUE_END = 1232
        const val ERROR_VALUE_START = 1272
        const val ERROR_VALUE_END = 1277
    }
}
