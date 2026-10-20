package io.github.amichne.kast.relation.intellij

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import io.github.amichne.kast.relation.contract.RelationEndpoint
import io.github.amichne.kast.relation.contract.RelationMeaning
import io.github.amichne.kast.relation.contract.RelationRequest
import io.github.amichne.kast.relation.contract.RelationSearchBoundary
import io.github.amichne.kast.relation.contract.ValueAccessPath
import io.github.amichne.kast.relation.contract.ValueFlowRead
import io.github.amichne.kast.relation.contract.ValueFlowRequest
import io.github.amichne.kast.relation.contract.ValueFlowStep
import io.github.amichne.kast.relation.contract.ValuePathSegment
import io.github.amichne.kast.relation.contract.ValuePredicate
import io.github.amichne.kast.relation.contract.ValuePredicates
import io.github.amichne.kast.relation.contract.ValueProducerSeedRead
import io.github.amichne.kast.relation.contract.ValueSite
import io.github.amichne.kast.relation.contract.ValueTransferKind
import io.github.amichne.kast.symbol.contract.ExactDeclarationTextRange
import junit.framework.TestCase.assertEquals
import junit.framework.TestCase.assertTrue
import kotlinx.coroutines.runBlocking

/** The real S2 prefix supplies the guard. Four fixed source hops keep the controlled initializer setup explicit. */
internal class NativePacketBindBranchSource(
    private val project: Project,
    private val base: VirtualFile,
    private val fixture: NativePacketValueFixture,
) {
    private val fields = NativePacketCompositeSuccessSource(project, base, fixture)

    fun successValue(): ValueSite {
        val original = originalGuard()
        val initialized = initializer(original)
        val component = next(initialized, ValueTransferKind.RECEIVER, "component receiver")
        val componentFormal = next(component, ValueTransferKind.SOURCE_CALL_ENTRY, "component formal")
        val bind = next(componentFormal, ValueTransferKind.RECEIVER, "implicit bind receiver")
        val formal = next(bind, ValueTransferKind.SOURCE_CALL_ENTRY, "bind formal")
        val result = read(formal)
        val value =
            result.transfers
                .single { transfer ->
                    transfer.kind == ValueTransferKind.FIELD_READ &&
                        transfer.target.range ==
                            ExactDeclarationTextRange.parse(
                                    VALUE_START,
                                    VALUE_END,
                                )
                                .nativeRefined()
                }
                .target
        assertEquals(
            listOf(ValuePathSegment.Field(fields.field(CLIENT, "ClientResponse", "links"))),
            value.valuePath.segments,
        )
        assertEquals(formal.callStack, value.callStack)
        assertEquals(formal.executionOwner, value.executionOwner)
        assertTrue(original in value.predicates.entries)
        println("BIND_BRANCH_SETUP kind=CONTROLLED_TRACKED_INITIALIZER guard=ORIGINAL_S2_NATIVE_PREFIX")
        return value
    }

    fun read(site: ValueSite): ValueFlowStep {
        val result = runBlocking {
            IntellijValueFlowCompilerAdapter()
                .read(
                    project = project,
                    current = fixture.lease,
                    request = ValueFlowRequest(site, fixture.budget, RelationSearchBoundary.RETAINED_SUBJECT),
                    model = fixture.model,
                )
        }
        assertTrue("The exact bind branch input needs a native read", result is ValueFlowRead.Observed)
        return (result as ValueFlowRead.Observed).step.also { step ->
            println(
                "BIND_BRANCH_STAGE source=${site.range} pathDepth=${site.valuePath.depth} " +
                    "transfers=${step.transfers.map { transfer -> transfer.kind to transfer.target.range }} " +
                    "causes=${step.obligations.map { obligation -> obligation.cause }} " +
                    "work=${step.examinedWorkUnits.value}"
            )
        }
    }

    private fun originalGuard(): ValuePredicate.Subtype {
        val request =
            fixture.selection(
                PacketDeclaration(CLIENT, "OffersClientImpl", "readOffers"),
                "execute",
                PacketDeclaration(NativePacketCompositeSuccessSource.WIRE, "Call", "execute"),
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
        assertTrue("The original S2 seed prerequisite did not admit", read is ValueProducerSeedRead.Seeded)
        val seed = (read as ValueProducerSeedRead.Seeded).seed
        assertEquals(request.anchor, seed.site.range)
        val prefix = NativePacketCompositeSuccessRoute(project, fixture, fields).enteredCallback(seed.site)
        val subject =
            prefix.receiver
                .withState(
                    valuePath = ValueAccessPath.Whole,
                    predicates = ValuePredicates.Empty,
                )
                .nativeRefined()
        return prefix.formal.predicates.entries.filterIsInstance<ValuePredicate.Subtype>().single { predicate ->
            predicate.subject.occurrence == subject &&
                predicate.resolvedType.valueIdentity == prefix.successType.valueIdentity
        }
    }

    private fun initializer(original: ValuePredicate.Subtype): ValueSite {
        val probe = NativePacketDestructuringProbeSource(project, base, fixture)
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
                        ValuePathSegment.Field(fields.field(OUTCOME, "Success", "value")),
                        ValuePathSegment.Field(fields.field(CLIENT, "ClientResponse", "links")),
                    )
                )
                .nativeRefined()
        return (found as ValueSiteResolution.Found)
            .site
            .withState(
                valuePath = path,
                predicates = ValuePredicates.fromCompiler(listOf(original)).nativeRefined(),
            )
            .nativeRefined()
    }

    private fun next(site: ValueSite, kind: ValueTransferKind, stage: String): ValueSite {
        val result = read(site)
        val selected = result.transfers.filter { transfer -> transfer.kind == kind }
        assertEquals(
            "The bind branch setup needs $stage. Causes=${result.obligations.map { it.cause }}",
            1,
            selected.size,
        )
        return selected.single().target
    }

    companion object {
        const val OUTCOME = NativePacketCompositeSuccessSource.OUTCOME
        const val CLIENT = NativePacketCompositeSuccessSource.CLIENT
        const val VALUE_START = 1227
        const val VALUE_END = 1232
    }
}
