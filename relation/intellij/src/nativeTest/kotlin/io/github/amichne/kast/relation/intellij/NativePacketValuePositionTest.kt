package io.github.amichne.kast.relation.intellij

import io.github.amichne.kast.relation.contract.RelationSearchBoundary
import io.github.amichne.kast.relation.contract.ValueExecutionBody
import io.github.amichne.kast.relation.contract.ValueFlowRead
import io.github.amichne.kast.relation.contract.ValueFlowRejection
import io.github.amichne.kast.relation.contract.ValueFlowRequest
import io.github.amichne.kast.relation.contract.ValueFlowStep
import io.github.amichne.kast.relation.contract.ValueFlowUnsupportedCause
import io.github.amichne.kast.relation.contract.ValueModelSiteRead
import io.github.amichne.kast.relation.contract.ValueProducerSeedRead
import io.github.amichne.kast.relation.contract.ValueProducerSeedRejection
import io.github.amichne.kast.relation.contract.ValueRole
import io.github.amichne.kast.relation.contract.ValueSite
import io.github.amichne.kast.relation.contract.ValueSiteRevalidationRequest
import io.github.amichne.kast.relation.contract.ValueSiteRoleClaim
import io.github.amichne.kast.relation.contract.ValueTransferKind
import kotlinx.coroutines.runBlocking

/** Position controls preserve exact formal indices and distinguish lexical hosts from anonymous execution bodies. */
class NativePacketValuePositionTest : NativePacketValueFixtureTest() {
    override val fixtureSdkName = "packet-value-position-jdk"

    fun testV8ConstructorDestinationKeepsDeclaredPositionOne() {
        val fixture = packetFixture(listOf(V8))
        val expression =
            fixture.argumentExpression(
                PacketDeclaration(V8, "BackendClient", "fetch"),
                "WireRequest",
                "request.destination",
            )
        val step = argumentStep(fixture, expression)
        val role = step.transfers.single().target.role as ValueRole.Argument
        assertEquals(1, role.position.value)
        val parameters = fixture.parameterNames(PacketDeclaration(V8, "WireRequest", "<init>"))
        assertEquals("destination", parameters[role.position.value])
        assertEquals("WireRequest", role.call.callable.name.value)
        assertEquals(emptyList<Any>(), step.obligations)
    }

    fun testV8SendRequestKeepsDeclaredPositionZero() {
        val fixture = packetFixture(listOf(V8))
        val expression =
            fixture.argumentExpression(PacketDeclaration(V8, "BackendClient", "fetch"), "send", "wireRequest")
        val step = argumentStep(fixture, expression)
        val role = step.transfers.single().target.role as ValueRole.Argument
        assertEquals(0, role.position.value)
        assertEquals("request", fixture.parameterNames(PacketDeclaration(V8, "Transport", "send"))[role.position.value])
        assertEquals("send", role.call.callable.name.value)
        assertEquals(emptyList<Any>(), step.obligations)
    }

    fun testWrongConstructorCallableRejects() {
        val fixture = packetFixture(listOf(V0))
        val request =
            fixture.selection(
                PacketDeclaration(V0, "Feature", "handle"),
                "ClientRequest",
                PacketDeclaration(V0, "WireRequest", "<init>"),
            )
        val result = runBlocking {
            IntellijValueFlowCompilerAdapter()
                .seed(project = project, current = fixture.lease, request = request, model = fixture.model)
        }
        assertEquals(ValueProducerSeedRead.Rejected(ValueProducerSeedRejection.CALLABLE_MISMATCH), result)
    }

    fun testFreshReadRejectsTheLexicalHostAsNestedExecutionOwner() {
        val fixture = packetFixture(COMPOSITE)
        val seed = nestedSeed(fixture)
        assertTrue(seed.executionOwner.body is ValueExecutionBody.Anonymous)
        val wrong = ValueSite.fromCompiler(seed.enclosing, seed.range, seed.role).nativeRefined()
        val result = flow(fixture, wrong)
        assertTrue(result is ValueFlowRead.Rejected)
        assertEquals(ValueFlowRejection.STALE_SITE, (result as ValueFlowRead.Rejected).cause)
    }

    fun testFreshRoleReadReacquiresTheAnonymousExecutionOwner() {
        val fixture = packetFixture(COMPOSITE)
        val seed = nestedSeed(fixture)
        val target = observed(fixture, seed).transfers.single().target
        val host = (seed.enclosing as io.github.amichne.kast.relation.contract.RelationEndpoint.Subject).selector
        val request =
            ValueSiteRevalidationRequest.create(
                    enclosing = host,
                    anchor = target.range,
                    role = ValueSiteRoleClaim.LocalBinding,
                    budget = fixture.budget,
                )
                .nativeRefined()
        val result = runBlocking {
            IntellijValueFlowCompilerAdapter()
                .revalidateSite(project = project, current = fixture.lease, request = request, model = fixture.model)
        }
        assertTrue(result is ValueModelSiteRead.Revalidated)
        assertEquals(target.identity, (result as ValueModelSiteRead.Revalidated).position.site.identity)
        assertEquals(seed.executionOwner, target.executionOwner)
    }

    fun testQualifiedReceiverKeepsTheNextCallResultUnproven() {
        val fixture = packetFixture(COMPOSITE)
        val request =
            fixture.selection(
                PacketDeclaration(CLIENT, "OffersClientImpl", "readOffers"),
                "execute",
                PacketDeclaration(WIRE, "Call", "execute"),
            )
        val seeded =
            runBlocking {
                IntellijValueFlowCompilerAdapter()
                    .seed(project = project, current = fixture.lease, request = request, model = fixture.model)
            }
                as ValueProducerSeedRead.Seeded
        val step = observed(fixture, seeded.seed.site)
        val transfer = step.transfers.single()
        assertEquals(ValueTransferKind.RECEIVER, transfer.kind)
        val role = transfer.target.role as ValueRole.Receiver
        assertEquals("map", role.call.callable.name.value)
        assertEquals(seeded.seed.site.range, transfer.target.range)
        assertFalse(transfer.target.identity == role.call.resultSite().identity)
        val next = observed(fixture, transfer.target)
        assertEquals(emptyList<Any>(), next.transfers)
        assertEquals(listOf(ValueFlowUnsupportedCause.UNMODELED_CALL), next.obligations.map { it.cause })
    }

    private fun nestedSeed(fixture: NativePacketValueFixture): ValueSite {
        val request =
            fixture.selection(
                PacketDeclaration(FEATURE, "OffersProcessorImpl", "getOffer"),
                "ClientRequest",
                PacketDeclaration(CLIENT, "ClientRequest", "<init>"),
            )
        val result = runBlocking {
            IntellijValueFlowCompilerAdapter()
                .seed(project = project, current = fixture.lease, request = request, model = fixture.model)
        }
        assertTrue(result is ValueProducerSeedRead.Seeded)
        return (result as ValueProducerSeedRead.Seeded).seed.site
    }

    private fun argumentStep(fixture: NativePacketValueFixture, expression: PacketExpression): ValueFlowStep {
        val result = runBlocking {
            IntellijValueFlowCompilerAdapter()
                .resolve(
                    project = project,
                    current = fixture.lease,
                    enclosing = expression.enclosing,
                    anchor = expression.anchor,
                    domain =
                        io.github.amichne.kast.relation.contract.RelationRequest.start(
                            expression.enclosing,
                            io.github.amichne.kast.relation.contract.RelationMeaning.References,
                            fixture.budget,
                        ),
                    model = fixture.model,
                )
        }
        assertTrue(result is ValueSiteResolution.Found)
        val step = observed(fixture, (result as ValueSiteResolution.Found).site)
        assertEquals(listOf(ValueTransferKind.ARGUMENT), step.transfers.map { it.kind })
        return step
    }

    private fun observed(fixture: NativePacketValueFixture, site: ValueSite): ValueFlowStep {
        val result = flow(fixture, site)
        assertTrue("Native position read failed: $result", result is ValueFlowRead.Observed)
        return (result as ValueFlowRead.Observed).step
    }

    private fun flow(fixture: NativePacketValueFixture, site: ValueSite): ValueFlowRead = runBlocking {
        IntellijValueFlowCompilerAdapter()
            .read(
                project = project,
                current = fixture.lease,
                request = ValueFlowRequest(site, fixture.budget, RelationSearchBoundary.RETAINED_SUBJECT),
                model = fixture.model,
            )
    }

    private companion object {
        const val V0 = "src/main/kotlin/fixture/variants/v0/V0.kt"
        const val V8 = "src/main/kotlin/fixture/variants/v8/V8.kt"
        const val FEATURE = "src/main/kotlin/fixture/feature/OffersProcessor.kt"
        const val CLIENT = "src/main/kotlin/fixture/client/OffersClient.kt"
        const val WIRE = "src/main/kotlin/fixture/wire/OffersApi.kt"
        val COMPOSITE = listOf("src/main/kotlin/fixture/outcome/Outcome.kt", WIRE, CLIENT, FEATURE)
    }
}
