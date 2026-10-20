package io.github.amichne.kast.relation.intellij

import io.github.amichne.kast.kernel.EvidenceGeneration
import io.github.amichne.kast.relation.contract.ValueFlowRead
import io.github.amichne.kast.relation.contract.ValueFlowRequest
import io.github.amichne.kast.relation.contract.ValueInvocationSelectionKind
import io.github.amichne.kast.relation.contract.ValueProducerSeedRead
import io.github.amichne.kast.relation.contract.ValueProducerSeedRejection
import io.github.amichne.kast.relation.contract.ValueProducerSeedRequest
import io.github.amichne.kast.relation.contract.ValueTransferKind
import io.github.amichne.kast.workspace.contract.SemanticReadLease
import kotlinx.coroutines.runBlocking

/** Original packet seeds run through the production native seed and one-hop adapters. */
class NativePacketValueFlowTest : NativePacketValueFixtureTest() {
    override val fixtureSdkName = "packet-value-case-jdk"

    fun testV0ConstructorSeedAndFirstTransfer() {
        val fixture = packetFixture(listOf(V0))
        val request =
            fixture.selection(
                PacketDeclaration(V0, "Feature", "handle"),
                "ClientRequest",
                PacketDeclaration(V0, "ClientRequest", "<init>"),
            )
        val seed = admitted("V0 outgoing constructor", fixture, request)
        val step = runBlocking {
            IntellijValueFlowCompilerAdapter()
                .read(
                    project = project,
                    current = fixture.lease,
                    request = ValueFlowRequest(seed.seed.site, fixture.budget, request.boundary),
                    model = fixture.model,
                )
        }
        assertTrue("V0 first transfer failed: $step", step is ValueFlowRead.Observed)
        step as ValueFlowRead.Observed
        assertEquals(listOf(ValueTransferKind.LOCAL_BINDING), step.step.transfers.map { it.kind })
        assertEquals(emptyList<Any>(), step.step.obligations)
    }

    fun testS1NestedConstructorSeedAndFirstTransfer() {
        val fixture = packetFixture(COMPOSITE)
        val request =
            fixture.selection(
                PacketDeclaration(FEATURE, "OffersProcessorImpl", "getOffer"),
                "ClientRequest",
                PacketDeclaration(CLIENT, "ClientRequest", "<init>"),
            )
        val seed = admitted("S1 constructor inside sequential", fixture, request)
        val step = runBlocking {
            IntellijValueFlowCompilerAdapter()
                .read(
                    project = project,
                    current = fixture.lease,
                    request = ValueFlowRequest(seed.seed.site, fixture.budget, request.boundary),
                    model = fixture.model,
                )
        }
        assertTrue("S1 first transfer failed: $step", step is ValueFlowRead.Observed)
        step as ValueFlowRead.Observed
        assertEquals(listOf(ValueTransferKind.LOCAL_BINDING), step.step.transfers.map { it.kind })
        assertEquals(emptyList<Any>(), step.step.obligations)
    }

    fun testS2QualifiedSeedAndFirstFrontier() {
        val fixture = packetFixture(COMPOSITE)
        val request = executeRequest(fixture)
        val seed = admitted("S2 full qualified execute", fixture, request)
        val step = runBlocking {
            IntellijValueFlowCompilerAdapter()
                .read(
                    project = project,
                    current = fixture.lease,
                    request = ValueFlowRequest(seed.seed.site, fixture.budget, request.boundary),
                    model = fixture.model,
                )
        }
        assertTrue("S2 first frontier failed: $step", step is ValueFlowRead.Observed)
        step as ValueFlowRead.Observed
        assertTrue(
            "S2 needs its first transfer. Selected=${request.anchor}. " +
                "Canonical=${seed.seed.invocation.range}. Frontier=${step.step.obligations.map { it.cause }}",
            step.step.transfers.isNotEmpty(),
        )
    }

    fun testS2SelectorOnlyRangeSharesTheCanonicalInvocation() {
        val fixture = packetFixture(COMPOSITE)
        val full = admitted("S2 full selection", fixture, executeRequest(fixture)).seed
        val request = executeRequest(fixture, selectorOnly = true)
        val selected = admitted("S2 selector selection", fixture, request).seed
        assertEquals(full.invocation.identity, selected.invocation.identity)
        assertEquals(full.site.identity, selected.site.identity)
        assertEquals(request.anchor, selected.selection.selectedRange)
        assertEquals(ValueInvocationSelectionKind.QUALIFIED_SELECTOR, selected.selection.kind)
        assertEquals(ValueInvocationSelectionKind.EXACT, full.selection.kind)
    }

    fun testS2PartialCalleeRangeRejects() {
        val fixture = packetFixture(COMPOSITE)
        val request =
            fixture.selection(
                owner = PacketDeclaration(CLIENT, "OffersClientImpl", "readOffers"),
                callee = "execute",
                callable = PacketDeclaration(WIRE, "Call", "execute"),
                syntax = PacketSeedSyntax.CALLEE_ONLY,
            )
        assertEquals(
            ValueProducerSeedRead.Rejected(ValueProducerSeedRejection.UNSUPPORTED_INVOCATION),
            seed(fixture, request),
        )
    }

    fun testS2SelectorWithWrongCallableRejects() {
        val fixture = packetFixture(COMPOSITE)
        val request =
            fixture.selection(
                owner = PacketDeclaration(CLIENT, "OffersClientImpl", "readOffers"),
                callee = "execute",
                callable = PacketDeclaration(WIRE, "OffersApi", "readOffers"),
                syntax = PacketSeedSyntax.SELECTOR_ONLY,
            )
        assertEquals(
            ValueProducerSeedRead.Rejected(ValueProducerSeedRejection.CALLABLE_MISMATCH),
            seed(fixture, request),
        )
    }

    fun testS2WrongCallableRejects() {
        val fixture = packetFixture(COMPOSITE)
        val request =
            fixture.selection(
                PacketDeclaration(CLIENT, "OffersClientImpl", "readOffers"),
                "execute",
                PacketDeclaration(WIRE, "OffersApi", "readOffers"),
            )
        assertEquals(
            ValueProducerSeedRead.Rejected(ValueProducerSeedRejection.CALLABLE_MISMATCH),
            seed(fixture, request),
        )
    }

    fun testS2WrongOwnerRejects() {
        val fixture = packetFixture(COMPOSITE)
        val request =
            fixture.selection(
                owner = PacketDeclaration(CLIENT, "OffersClientImpl", "readOffers"),
                callee = "execute",
                callable = PacketDeclaration(WIRE, "Call", "execute"),
                hostKind = PacketHostKind.CLASS,
            )
        assertEquals(ValueProducerSeedRead.Rejected(ValueProducerSeedRejection.OWNER_MISMATCH), seed(fixture, request))
    }

    fun testS2StaleBasisRejects() {
        val fixture = packetFixture(COMPOSITE)
        val current = SemanticReadLease(fixture.lease.workspaceRoot, EvidenceGeneration.parse(2L).nativeRefined())
        val request = executeRequest(fixture)
        val result = runBlocking {
            IntellijValueFlowCompilerAdapter()
                .seed(project = project, current = current, request = request, model = fixture.model)
        }
        record(request, result)
        assertEquals(ValueProducerSeedRead.Rejected(ValueProducerSeedRejection.AUTHORITY_MOVED), result)
    }

    private fun admitted(
        label: String,
        fixture: NativePacketValueFixture,
        request: ValueProducerSeedRequest,
    ): ValueProducerSeedRead.Seeded {
        val result = seed(fixture, request)
        assertTrue(
            "$label seed admission failed: $result. " +
                "Owner=${request.enclosing.qualifiedIdentity}:${request.enclosing.range}. " +
                "Callable=${request.expectedCallable.qualifiedIdentity}:${request.expectedCallable.range}. " +
                "Selected=${request.anchor}. First transfer=NOT_REACHED. First frontier=NOT_REACHED",
            result is ValueProducerSeedRead.Seeded,
        )
        return result as ValueProducerSeedRead.Seeded
    }

    private fun seed(fixture: NativePacketValueFixture, request: ValueProducerSeedRequest): ValueProducerSeedRead {
        val result = runBlocking {
            IntellijValueFlowCompilerAdapter()
                .seed(project = project, current = fixture.lease, request = request, model = fixture.model)
        }
        record(request, result)
        return result
    }

    private fun record(request: ValueProducerSeedRequest, result: ValueProducerSeedRead) {
        val outcome =
            when (result) {
                is ValueProducerSeedRead.Seeded -> "SEEDED canonical=${result.seed.invocation.range}"
                is ValueProducerSeedRead.Rejected -> "REJECTED ${result.cause}"
                is ValueProducerSeedRead.ContractRejected -> "CONTRACT_REJECTED ${result.cause}"
            }
        println(
            "PACKET_SEED\towner=${request.enclosing.qualifiedIdentity}\townerRange=${request.enclosing.range}" +
                "\tcallable=${request.expectedCallable.qualifiedIdentity}" +
                "\tcallableRange=${request.expectedCallable.range}" +
                "\tselected=${request.anchor}\toutcome=$outcome"
        )
    }

    private fun executeRequest(
        fixture: NativePacketValueFixture,
        selectorOnly: Boolean = false,
    ): ValueProducerSeedRequest =
        fixture.selection(
            owner = PacketDeclaration(CLIENT, "OffersClientImpl", "readOffers"),
            callee = "execute",
            callable = PacketDeclaration(WIRE, "Call", "execute"),
            syntax = if (selectorOnly) PacketSeedSyntax.SELECTOR_ONLY else PacketSeedSyntax.QUALIFIED,
        )

    private companion object {
        const val V0 = "src/main/kotlin/fixture/variants/v0/V0.kt"
        const val FEATURE = "src/main/kotlin/fixture/feature/OffersProcessor.kt"
        const val CLIENT = "src/main/kotlin/fixture/client/OffersClient.kt"
        const val WIRE = "src/main/kotlin/fixture/wire/OffersApi.kt"
        val COMPOSITE = listOf("src/main/kotlin/fixture/outcome/Outcome.kt", WIRE, CLIENT, FEATURE)
    }
}
