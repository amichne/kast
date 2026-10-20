package io.github.amichne.kast.relation.intellij

import io.github.amichne.kast.relation.contract.ValueAccessPath
import io.github.amichne.kast.relation.contract.ValueModelSiteRead
import io.github.amichne.kast.relation.contract.ValuePathSegment
import io.github.amichne.kast.relation.contract.ValuePredicate
import io.github.amichne.kast.relation.contract.ValuePredicates
import io.github.amichne.kast.relation.contract.ValueReceiverKind
import io.github.amichne.kast.relation.contract.ValueRole
import io.github.amichne.kast.relation.contract.ValueSite
import io.github.amichne.kast.relation.contract.ValueSiteRevalidationRequest
import io.github.amichne.kast.relation.contract.ValueSiteRoleClaim
import io.github.amichne.kast.relation.contract.ValueTransferKind
import io.github.amichne.kast.symbol.contract.ExactDeclarationTextRange
import kotlinx.coroutines.runBlocking

/** The original response entry keeps its matched result and permits only reads of its actual immutable local symbol. */
class NativePacketEntryBindingTest : NativePacketValueFixtureTest() {
    override val fixtureSdkName = "packet-entry-binding-jdk"

    fun testOriginalResponseAndLinkReferencesHaveDistinctImmutableLocalSymbols() {
        val fixture = packetFixture(NativePacketCompositeSuccessSource.PATHS)
        val source = NativePacketEntryBindingSource(project, getOrCreateProjectBaseDir(), fixture)
        val facts = source.facts()
        assertFalse(facts.response == facts.link)
        assertFalse(facts.responseRead == facts.linkRead)
        println("ENTRY_BINDING_PREREQUISITE status=ACTUAL_K2_DISTINCT_IMMUTABLE_LOCALS transfers=NOT_RUN")
    }

    fun testOriginalMatchedComponentResultBindsAndReadsOnlyResponseWithItsCompleteState() {
        val fixture = packetFixture(NativePacketCompositeSuccessSource.PATHS)
        val source = NativePacketEntryBindingSource(project, getOrCreateProjectBaseDir(), fixture)
        val facts = source.facts()
        val result = source.responseResult()
        assertEquals(facts.response, result.range)
        assertEquals(ValueRole.ExpressionResult, result.role)
        assertEquals(facts.owner, result.executionOwner)
        assertOriginalState(fixture, result)
        val bindingStep = source.read(result)
        val bindings = bindingStep.transfers.filter { transfer -> transfer.kind == ValueTransferKind.LOCAL_BINDING }
        assertEquals(
            "C2.7 needs returned component result806:814 to immutable response LocalBinding. " +
                "Causes=${bindingStep.obligations.map { it.cause }}. Response read and C2.8/C2.9 remain NOT_REACHED",
            1,
            bindings.size,
        )
        val binding = bindings.single()
        assertEquals(result, binding.source)
        assertEquals(facts.response, binding.target.range)
        assertEquals(ValueRole.LocalBinding, binding.target.role)
        assertSameState(result, binding.target)
        assertTrue(bindingStep.transfers.none { transfer -> transfer.target.range == facts.link })
        assertResponseRead(source = source, facts = facts, binding = binding.target)
    }

    fun testSupplementalEntryBindingRoleReadmissionReacquiresTheGenuineSequentialOwner() {
        val fixture = packetFixture(NativePacketCompositeSuccessSource.PATHS)
        val facts = NativePacketEntryBindingSource(project, getOrCreateProjectBaseDir(), fixture).facts()
        val site =
            readmit(fixture = fixture, facts = facts, anchor = facts.response, role = ValueSiteRoleClaim.LocalBinding)
        assertEquals(ValueRole.LocalBinding, site.role)
        assertEquals(facts.owner, site.executionOwner)
        println("ENTRY_BINDING_SUPPLEMENTAL stage=ROLE_READMISSION full_S2_route=NOT_ASSERTED")
    }

    fun testSupplementalEntryReferenceRoleReadmissionUsesItsActualLocalSymbol() {
        val fixture = packetFixture(NativePacketCompositeSuccessSource.PATHS)
        val facts = NativePacketEntryBindingSource(project, getOrCreateProjectBaseDir(), fixture).facts()
        val site =
            readmit(fixture = fixture, facts = facts, anchor = facts.responseRead, role = ValueSiteRoleClaim.LocalRead)
        assertEquals(ValueRole.LocalRead, site.role)
        assertEquals(facts.owner, site.executionOwner)
        println("ENTRY_BINDING_SUPPLEMENTAL stage=LOCAL_READ_READMISSION full_S2_route=NOT_ASSERTED")
    }

    private fun assertResponseRead(
        source: NativePacketEntryBindingSource,
        facts: EntryBindingFacts,
        binding: ValueSite,
    ) {
        val read = source.read(binding)
        val references = read.transfers.filter { transfer -> transfer.kind == ValueTransferKind.LOCAL_READ }
        assertEquals(
            "C2.7 needs the K2-confirmed response reference939:947. Causes=${read.obligations.map { it.cause }}",
            1,
            references.size,
        )
        val reference = references.single()
        assertEquals(binding, reference.source)
        assertEquals(facts.responseRead, reference.target.range)
        assertEquals(ValueRole.LocalRead, reference.target.role)
        assertSameState(binding, reference.target)
        assertTrue(
            "The independently proved neighboring link symbol must receive no response LOCAL_READ witness",
            read.transfers.none { transfer ->
                transfer.kind == ValueTransferKind.LOCAL_READ && transfer.target.range == facts.linkRead
            },
        )
        println(
            "ENTRY_BINDING_RESULT row=C2.7 stage=matched-result,immutable-binding,exact-reference C2.8,C2.9=NOT_RUN"
        )
    }

    private fun assertOriginalState(fixture: NativePacketValueFixture, source: ValueSite) {
        val fields = NativePacketCompositeSuccessSource(project, getOrCreateProjectBaseDir(), fixture)
        val links = fields.field(NativePacketCompositeSuccessSource.CLIENT, "ClientResponse", "links")
        assertEquals(listOf(ValuePathSegment.Field(links)), source.valuePath.segments)
        val original =
            source.predicates.entries.filterIsInstance<ValuePredicate.Subtype>().single { predicate ->
                predicate.subject.occurrence.range ==
                    ExactDeclarationTextRange.parse(EXECUTE_START, EXECUTE_END).nativeRefined()
            }
        assertEquals(
            fields.endpoint(NativePacketCompositeSuccessSource.OUTCOME, "Success").valueIdentity,
            original.resolvedType.valueIdentity,
        )
        assertEquals(ValueAccessPath.Whole, original.subject.occurrence.valuePath)
        assertEquals(ValuePredicates.Empty, original.subject.occurrence.predicates)
        assertEquals(ValueReceiverKind.DISPATCH, (original.subject.occurrence.role as ValueRole.Receiver).kind)
    }

    private fun assertSameState(source: ValueSite, target: ValueSite) {
        assertEquals(source.basis, target.basis)
        assertEquals(source.enclosing, target.enclosing)
        assertEquals(source.executionOwner, target.executionOwner)
        assertEquals(source.callStack, target.callStack)
        assertEquals(source.valuePath, target.valuePath)
        assertEquals(source.predicates, target.predicates)
    }

    private fun readmit(
        fixture: NativePacketValueFixture,
        facts: EntryBindingFacts,
        anchor: ExactDeclarationTextRange,
        role: ValueSiteRoleClaim,
    ): ValueSite {
        val request =
            ValueSiteRevalidationRequest.create(
                    enclosing = facts.enclosing,
                    anchor = anchor,
                    role = role,
                    budget = fixture.budget,
                )
                .nativeRefined()
        val read = runBlocking {
            IntellijValueFlowCompilerAdapter()
                .revalidateSite(project = project, current = fixture.lease, request = request, model = fixture.model)
        }
        assertTrue(
            "The exact original local position needs native role readmission: ${siteReadStatus(read)}",
            read is ValueModelSiteRead.Revalidated,
        )
        val site = (read as ValueModelSiteRead.Revalidated).position.site
        assertEquals(anchor, site.range)
        return site
    }

    private fun siteReadStatus(read: ValueModelSiteRead): String =
        when (read) {
            is ValueModelSiteRead.Revalidated -> "REVALIDATED"
            is ValueModelSiteRead.Rejected -> "REJECTED:${read.cause}"
            is ValueModelSiteRead.ContractRejected -> "CONTRACT_REJECTED:${read.cause}"
            is ValueModelSiteRead.Unsupported -> "UNSUPPORTED:${read.cause}"
            is ValueModelSiteRead.Limited -> "LIMITED:${read.cause}"
        }

    private companion object {
        const val EXECUTE_START = 769
        const val EXECUTE_END = 954
    }
}
