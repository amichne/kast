package io.github.amichne.kast.relation.intellij

import com.intellij.openapi.application.ReadAction
import io.github.amichne.kast.relation.contract.RelationMeaning
import io.github.amichne.kast.relation.contract.RelationRequest
import io.github.amichne.kast.relation.contract.RelationSearchBoundary
import io.github.amichne.kast.relation.contract.ValueFlowRead
import io.github.amichne.kast.relation.contract.ValueFlowRequest
import io.github.amichne.kast.relation.contract.ValueFlowStep
import io.github.amichne.kast.relation.contract.ValueProducerSeedRead
import io.github.amichne.kast.relation.contract.ValueRole
import io.github.amichne.kast.relation.contract.ValueSite
import io.github.amichne.kast.relation.contract.ValueTransferKind
import io.github.amichne.kast.symbol.contract.ExactDeclarationQualifiedIdentity
import io.github.amichne.kast.symbol.contract.SymbolSourceKindPolicy
import kotlinx.coroutines.runBlocking

/** Source implementations keep the static call and each exact concrete execution separate. */
class NativePacketImplementationTest : NativePacketValueFixtureTest() {
    override val fixtureSdkName = "packet-implementation-jdk"

    fun testV1ProductionEntersTheConcreteImplementation() = v1(SymbolSourceKindPolicy.PRODUCTION_ONLY)

    fun testV1CombinedEntersTheConcreteImplementation() = v1(SymbolSourceKindPolicy.PRODUCTION_AND_TEST)

    fun testDefaultBodyCannotBypassTheConstantOverride() {
        val fixture = packetFixture(listOf(DEFAULT, TEST_ECHO))
        val argument = defaultArgument(fixture)
        knownTestExclusion(fixture, argument)
        val entered = step(fixture, argument)
        assertEquals(
            "Frontier=${entered.obligations.map { it.cause }}",
            listOf(ValueTransferKind.SOURCE_CALL_ENTRY),
            entered.transfers.map { it.kind },
        )
        assertEquals(listOf(CONSTANT), entered.transfers.map { identity(it.target) })
        val formal = entered.transfers.single().target
        assertEquals((argument.role as ValueRole.Argument).call, formal.callStack.frames.single().invocation)
        val stopped = step(fixture, formal)
        assertEquals(emptyList<Any>(), stopped.transfers)
        assertEquals(emptyList<Any>(), stopped.obligations)
    }

    fun testCombinedScopeKeepsEachImplementationFrame() {
        val fixture = packetFixture(listOf(DEFAULT, TEST_ECHO), SymbolSourceKindPolicy.PRODUCTION_AND_TEST)
        val argument = defaultArgument(fixture)
        val entered = step(fixture, argument)
        assertEquals(
            "Frontier=${entered.obligations.map { it.cause }}",
            listOf(ValueTransferKind.SOURCE_CALL_ENTRY, ValueTransferKind.SOURCE_CALL_ENTRY),
            entered.transfers.map { it.kind },
        )
        assertEquals(setOf(CONSTANT, ECHO), entered.transfers.map { identity(it.target) }.toSet())
        val frames = entered.transfers.map { it.target.callStack.frames.single() }
        assertEquals(2, frames.toSet().size)
        assertTrue(frames.all { it.invocation == (argument.role as ValueRole.Argument).call })
    }

    private fun v1(policy: SymbolSourceKindPolicy) {
        val fixture = packetFixture(listOf(V1), policy)
        val argument = argument(fixture, PacketDeclaration(V1, "Feature", "handle"), "ClientRequest")
        val entered = step(fixture, argument)
        assertEquals(
            "Frontier=${entered.obligations.map { it.cause }}",
            listOf(ValueTransferKind.SOURCE_CALL_ENTRY),
            entered.transfers.map { it.kind },
        )
        assertEquals(
            listOf("fixture.variants.v1.BackendClientImpl.fetch"),
            entered.transfers.map { identity(it.target) },
        )
        assertEquals(emptyList<Any>(), entered.obligations)
        val frame = entered.transfers.single().target.callStack.frames.single()
        assertEquals((argument.role as ValueRole.Argument).call, frame.invocation)
        assertEquals(entered.transfers.single().target.executionOwner, frame.callee)
    }

    private fun defaultArgument(fixture: NativePacketValueFixture): ValueSite =
        argument(fixture, PacketDeclaration(DEFAULT, "DefaultIdentityControls", "apply"), "DispatchRequest")

    private fun knownTestExclusion(fixture: NativePacketValueFixture, argument: ValueSite) {
        val knownTest = fixture.callableDeclaration(PacketDeclaration(TEST_ECHO, "TestEchoMapper", "identity"))
        assertEquals(ECHO, (knownTest.qualifiedIdentity as ExactDeclarationQualifiedIdentity.Available).value)
        assertEquals(fixture.lease.identity, knownTest.lease.identity)
        assertEquals(argument.basis, knownTest.lease.identity)
        val domain = RelationRequest.start(argument.enclosing, RelationMeaning.References, fixture.budget)
        val classification =
            ReadAction.compute<RelationProviderScopeAdmission, RuntimeException> {
                val prepared = IntellijValueFlowCompilerAdapter().prepare(project, domain, fixture.model)
                assertTrue(prepared is IntellijValueFlowCompilerAdapter.Prepared.Ready)
                (prepared as IntellijValueFlowCompilerAdapter.Prepared.Ready).scope.admitKnownSource(knownTest.file)
            }
        assertEquals(RelationProviderScopeAdmission.SOURCE_DOMAIN_EXCLUDED, classification)
        println(
            "Known-source policy=PRODUCTION_ONLY callable=$ECHO file=${knownTest.file} " +
                "range=${knownTest.range.startInclusive}:${knownTest.range.endExclusive} " +
                "compilerIdentity=${knownTest.compilerIdentity.value} basis=${knownTest.lease.identity} " +
                "rule=$classification"
        )
    }

    private fun argument(
        fixture: NativePacketValueFixture,
        owner: PacketDeclaration,
        constructor: String,
    ): ValueSite {
        val request =
            fixture.selection(
                owner = owner,
                callee = constructor,
                callable = PacketDeclaration(owner.path, constructor, "<init>"),
            )
        val result = runBlocking {
            IntellijValueFlowCompilerAdapter()
                .seed(project = project, current = fixture.lease, request = request, model = fixture.model)
        }
        assertTrue(result is ValueProducerSeedRead.Seeded)
        var site = (result as ValueProducerSeedRead.Seeded).seed.site
        for (kind in
            listOf(ValueTransferKind.LOCAL_BINDING, ValueTransferKind.LOCAL_READ, ValueTransferKind.ARGUMENT)) {
            val read = step(fixture, site)
            assertEquals(listOf(kind), read.transfers.map { it.kind })
            assertEquals(emptyList<Any>(), read.obligations)
            site = read.transfers.single().target
        }
        return site
    }

    private fun step(fixture: NativePacketValueFixture, site: ValueSite): ValueFlowStep {
        val result = runBlocking {
            IntellijValueFlowCompilerAdapter()
                .read(
                    project = project,
                    current = fixture.lease,
                    request = ValueFlowRequest(site, fixture.budget, RelationSearchBoundary.RETAINED_SUBJECT),
                    model = fixture.model,
                )
        }
        assertTrue("Implementation read failed: $result", result is ValueFlowRead.Observed)
        return (result as ValueFlowRead.Observed).step
    }

    private fun identity(site: ValueSite) =
        (site.enclosing.qualifiedIdentity as ExactDeclarationQualifiedIdentity.Available).value

    private companion object {
        const val V1 = "src/main/kotlin/fixture/variants/v1/V1.kt"
        const val DEFAULT = "src/main/kotlin/fixture/controls/DefaultIdentityControls.kt"
        const val TEST_ECHO = "src/test/kotlin/fixture/controls/TestEchoMapper.kt"
        const val CONSTANT = "fixture.controls.ProductionConstantMapper.identity"
        const val ECHO = "fixture.controls.TestEchoMapper.identity"
    }
}
