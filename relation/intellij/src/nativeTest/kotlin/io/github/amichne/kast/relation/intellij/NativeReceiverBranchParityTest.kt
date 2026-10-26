package io.github.amichne.kast.relation.intellij

import io.github.amichne.kast.relation.contract.RelationEndpoint
import io.github.amichne.kast.relation.contract.RelationMeaning
import io.github.amichne.kast.relation.contract.RelationRequest
import io.github.amichne.kast.relation.contract.RelationSearchBoundary
import io.github.amichne.kast.relation.contract.ValueFlowRead
import io.github.amichne.kast.relation.contract.ValueFlowRequest
import io.github.amichne.kast.relation.contract.ValueFlowStep
import io.github.amichne.kast.relation.contract.ValueFlowUnsupportedCause
import io.github.amichne.kast.relation.contract.ValueFormalPosition
import io.github.amichne.kast.relation.contract.ValueReceiverKind
import io.github.amichne.kast.relation.contract.ValueRole
import io.github.amichne.kast.relation.contract.ValueSite
import io.github.amichne.kast.relation.contract.ValueSiteIdentity
import io.github.amichne.kast.relation.contract.ValueTransferKind
import kotlinx.coroutines.runBlocking

/** Compiler branch facts preserve exact receiver and stored-field identities. */
class NativeReceiverBranchParityTest : NativePacketValueFixtureTest() {
    override val fixtureSdkName = "receiver-branch-parity-jdk"

    fun testImplicitSubjectReachesOnlyTheCompatibleStoredField() = assertField("implicitCaller", "implicit")

    fun testNamedSubjectReachesTheSameStoredField() = assertField("namedCaller", "named")

    fun testImmutableAliasReachesTheSameStoredField() = assertField("aliasedCaller", "aliased")

    fun testFinalDisjointArmCannotAcquireAReceiverRead() {
        val fixture = packetFixture(listOf(CONTROLS))
        val read = step(fixture, formal(fixture, "wholeCaller"))
        assertTrue("Unexpected branch frontier: ${read.obligations}", read.obligations.isEmpty())
        val allowed = fixture.markedExpression(owner("whole"), "selected")
        val forbidden = fixture.markedExpression(owner("whole"), "other")
        assertTrue(read.transfers.any { it.target.range == allowed.anchor && it.kind == ValueTransferKind.LOCAL_READ })
        assertTrue(read.transfers.none { it.target.range == forbidden.anchor })
    }

    fun testOpenOwnerDoesNotProveDisjointnessFromAnInterface() {
        val fixture = packetFixture(listOf(CONTROLS))
        val read = step(fixture, formal(fixture, "overlapCaller", "Open"))
        assertTrue(read.obligations.any { it.cause == ValueFlowUnsupportedCause.UNSUPPORTED_EXPRESSION })
        assertTrue(read.transfers.none { it.kind == ValueTransferKind.FIELD_READ })
    }

    fun testMissingConcreteFieldEvidenceKeepsTheConditionalFrontier() {
        val fixture = packetFixture(listOf(CONTROLS))
        val callable = fixture.callableDeclaration(owner("implicit"))
        val source =
            ValueSite.fromCompiler(
                    RelationEndpoint.subject(callable),
                    callable.range,
                    ValueRole.Formal(ValueFormalPosition.Receiver(ValueReceiverKind.EXTENSION)),
                )
                .nativeRefined()
        val read = step(fixture, source)
        assertTrue(read.obligations.any { it.cause == ValueFlowUnsupportedCause.UNSUPPORTED_EXPRESSION })
        assertTrue(read.transfers.none { it.kind == ValueTransferKind.FIELD_READ })
    }

    fun testDispatchReceiverCannotReplaceTheRefinedExtensionReceiver() {
        val fixture = packetFixture(listOf(CONTROLS))
        val read = step(fixture, formal(fixture, "shadowCaller", ownerName = "Holder"))
        assertTrue("Unexpected shadow frontier: ${read.obligations}", read.obligations.isEmpty())
        val forbidden = fixture.markedExpression(PacketDeclaration(CONTROLS, "Holder", "shadow"), "shadow")
        assertTrue(read.transfers.none { it.target.range == forbidden.anchor })
        assertTrue(read.transfers.none { it.kind == ValueTransferKind.FIELD_READ })
    }

    fun testUnsupportedAliasDepthRetainsAnExplicitFrontier() {
        val fixture = packetFixture(listOf(CONTROLS))
        val read = step(fixture, formal(fixture, "deepAliasCaller"))
        assertTrue(read.obligations.any { it.cause == ValueFlowUnsupportedCause.UNSUPPORTED_EXPRESSION })
        assertTrue(read.transfers.none { it.kind == ValueTransferKind.FIELD_READ })
    }

    fun testFinalDisjointArmCannotAcquireAnImplicitReceiverCall() {
        val fixture = packetFixture(listOf(CONTROLS))
        val read = step(fixture, formal(fixture, "calledCaller"))
        val forbidden = fixture.markedExpression(owner("called"), "other")
        assertTrue(read.transfers.none { it.target.range == forbidden.anchor })
        assertEquals(
            setOf(ValueFlowUnsupportedCause.UNSUPPORTED_EXPRESSION),
            read.obligations.map { it.cause }.toSet(),
        )
    }

    fun testStarProjectedFinalVariantsPreserveTheirDistinctPayloadFields() =
        assertField("genericCaller", "generic", "GenericSelected")

    fun testUnprovedConcreteTypeArgumentsCannotExcludeACompatibleVariant() {
        val fixture = packetFixture(listOf(CONTROLS))
        val read = step(fixture, formal(fixture, "concreteCaller", "GenericSelected"))
        assertTrue(read.obligations.any { it.cause == ValueFlowUnsupportedCause.UNSUPPORTED_EXPRESSION })
        assertTrue(read.transfers.none { it.kind == ValueTransferKind.FIELD_READ })
    }

    fun testPayloadLocalInsideAnAdmittedArmKeepsItsEstablishedIdentity() {
        val fixture = packetFixture(listOf(CONTROLS))
        val pending = ArrayDeque<ValueSite>()
        val visited = linkedSetOf<ValueSiteIdentity>()
        val sites = mutableListOf<ValueSite>()
        pending.addLast(formal(fixture, "localPayloadCaller"))
        while (pending.isNotEmpty()) {
            val site = pending.removeFirst()
            if (!visited.add(site.identity)) continue
            assertTrue("Branch fixture exceeded its finite site limit", visited.size <= MAX_SITES)
            sites += site
            val read = step(fixture, site)
            assertTrue("Payload local lost its branch facts: ${read.obligations}", read.obligations.isEmpty())
            read.transfers.forEach { pending.addLast(it.target) }
        }
        val allowed = fixture.markedExpression(owner("localPayload"), "selected")
        val forbidden = fixture.markedExpression(owner("localPayload"), "other")
        val reached = sites.filter {
            it.range == allowed.anchor && it.enclosing.compilerIdentity == allowed.enclosing.compilerIdentity
        }
        assertTrue("The selected payload never reached its local use", reached.isNotEmpty())
        assertTrue(
            "The marked use has no local-read identity: ${reached.map { it.role }}",
            reached.any { it.role == ValueRole.LocalRead },
        )
        assertTrue("The payload local retained a wrapper field", reached.all { it.valuePath.segments.isEmpty() })
        val arguments = reached.mapNotNull { it.role as? ValueRole.Argument }
        val consumer = fixture.callableDeclaration(owner("consume"))
        assertTrue("The payload never entered its marked consumer", arguments.isNotEmpty())
        assertTrue(arguments.all { it.call.callable.compilerIdentity == consumer.compilerIdentity })
        assertTrue(
            sites.none {
                it.range == forbidden.anchor && it.enclosing.compilerIdentity == forbidden.enclosing.compilerIdentity
            }
        )
    }

    fun testUnknownSameSubjectCannotLoseItsConditionalFrontier() {
        val fixture = packetFixture(listOf(CONTROLS))
        var site = resolve(fixture, fixture.markedExpression(owner("unknownCaller"), "source"))
        for (kind in listOf(ValueTransferKind.ARGUMENT, ValueTransferKind.SOURCE_CALL_ENTRY)) {
            val read = step(fixture, site)
            assertTrue(read.obligations.isEmpty())
            assertEquals(listOf(kind), read.transfers.map { it.kind })
            site = read.transfers.single().target
        }
        val read = step(fixture, site)
        assertEquals(setOf(ValueFlowUnsupportedCause.UNSUPPORTED_EXPRESSION), read.obligations.map { it.cause }.toSet())
        for (marker in listOf("selected", "other")) {
            val forbidden = fixture.markedExpression(owner("unknownSubject"), marker)
            assertTrue(read.transfers.none { it.target.range == forbidden.anchor })
        }
    }

    fun testNegatedTypeArmAndElsePreserveTheExactFinalVariant() {
        val fixture = packetFixture(listOf(CONTROLS))
        val read = step(fixture, formal(fixture, "negatedCaller"))
        assertTrue("Negated type test retained a frontier: ${read.obligations}", read.obligations.isEmpty())
        val allowed = fixture.markedExpression(owner("negated"), "selected")
        val forbidden = fixture.markedExpression(owner("negated"), "other")
        assertTrue(read.transfers.any { it.target.range == allowed.anchor && it.kind == ValueTransferKind.LOCAL_READ })
        assertTrue(read.transfers.none { it.target.range == forbidden.anchor })
    }

    fun testStarProjectedOuterQualifierPreservesTheExactInnerField() =
        assertField("innerStarCaller", "innerStar", "Inner")

    fun testConcreteOuterQualifierKeepsItsUnprovedTypeArguments() {
        val fixture = packetFixture(listOf(CONTROLS))
        val read = step(fixture, formal(fixture, "innerConcreteCaller", "Inner"))
        assertEquals(setOf(ValueFlowUnsupportedCause.UNSUPPORTED_EXPRESSION), read.obligations.map { it.cause }.toSet())
        assertTrue(
            "Concrete outer arguments invented a field transfer",
            read.transfers.none { it.kind == ValueTransferKind.FIELD_READ },
        )
    }

    private fun assertField(caller: String, function: String, constructor: String = "Selected") {
        val fixture = packetFixture(listOf(CONTROLS))
        val read = step(fixture, formal(fixture, caller, constructor))
        assertTrue("Unexpected branch frontier: ${read.obligations}", read.obligations.isEmpty())
        val allowed = fixture.markedExpression(owner(function), "selected")
        val forbidden = fixture.markedExpression(owner(function), "other")
        val fields = read.transfers.filter { it.kind == ValueTransferKind.FIELD_READ }
        assertEquals(listOf(allowed.anchor), fields.map { it.target.range })
        assertTrue(fields.single().target.valuePath.segments.isEmpty())
        assertTrue(read.transfers.none { it.target.range == forbidden.anchor })
    }

    private fun formal(
        fixture: NativePacketValueFixture,
        caller: String,
        constructor: String = "Selected",
        ownerName: String = "",
    ): ValueSite {
        val expression =
            fixture.argumentExpression(PacketDeclaration(CONTROLS, ownerName, caller), constructor, "\"tracked\"")
        var site = resolve(fixture, expression)
        for (kind in
            listOf(
                ValueTransferKind.ARGUMENT,
                ValueTransferKind.CONSTRUCTOR_FIELD,
                ValueTransferKind.RECEIVER,
                ValueTransferKind.SOURCE_CALL_ENTRY,
            )) {
            val read = step(fixture, site)
            assertTrue("Unexpected entry frontier: ${read.obligations}", read.obligations.isEmpty())
            assertEquals(listOf(kind), read.transfers.map { it.kind })
            site = read.transfers.single().target
        }
        return site
    }

    private fun resolve(fixture: NativePacketValueFixture, expression: PacketExpression): ValueSite {
        val resolved = runBlocking {
            IntellijValueFlowCompilerAdapter()
                .resolve(
                    project = project,
                    current = fixture.lease,
                    enclosing = expression.enclosing,
                    anchor = expression.anchor,
                    domain = RelationRequest.start(expression.enclosing, RelationMeaning.References, fixture.budget),
                    model = fixture.model,
                )
        }
        assertTrue("Native payload did not resolve: $resolved", resolved is ValueSiteResolution.Found)
        return (resolved as ValueSiteResolution.Found).site
    }

    private fun step(fixture: NativePacketValueFixture, site: ValueSite): ValueFlowStep {
        val read = runBlocking {
            IntellijValueFlowCompilerAdapter()
                .read(
                    project = project,
                    current = fixture.lease,
                    request = ValueFlowRequest(site, fixture.budget, RelationSearchBoundary.RETAINED_SUBJECT),
                    model = fixture.model,
                )
        }
        assertTrue("Native branch read failed: $read", read is ValueFlowRead.Observed)
        return (read as ValueFlowRead.Observed).step
    }

    private fun owner(name: String) = PacketDeclaration(CONTROLS, "", name)

    private companion object {
        const val MAX_SITES = 64
        const val CONTROLS = "src/main/kotlin/fixture/controls/BranchReceiverControls.kt"
    }
}
