package io.github.amichne.kast.relation.intellij

import io.github.amichne.kast.relation.contract.RelationSearchBoundary
import io.github.amichne.kast.relation.contract.ValueElementIndex
import io.github.amichne.kast.relation.contract.ValuePathSegment
import io.github.amichne.kast.relation.contract.ValueRole
import io.github.amichne.kast.relation.contract.ValueSite
import io.github.amichne.kast.symbol.contract.SymbolSourceKindPolicy

/** The original Echo path and isolated overload controls distinguish metadata admission from reviewed insertion. */
class NativePacketSingleListInsertionTest : NativePacketValueFixtureTest() {
    override val fixtureSdkName = "packet-single-list-insertion-jdk"

    fun testOriginalS1EchoDestinationAdmitsItsExactListOfArgumentUnderExclude() {
        val fixture = echoFixture()
        val source = source(fixture)
        val input = echoExpression(source)
        val facts = source.facts(input)
        val destination = source.originalEchoDestination()
        assertEquals(input.anchor, destination.range)
        val argument = source.argument(destination, RelationSearchBoundary.RETAINED_SUBJECT)
        assertCall(argument, facts)
        assertEquals(destination.executionOwner, argument.executionOwner)
        assertEquals(destination.callStack, argument.callStack)
        assertEquals(destination.predicates, argument.predicates)
        println("SINGLE_LIST_ORIGINAL admission=ASSERTED model_application=NOT_RUN original_seed=UNCHANGED")
    }

    fun testActualEchoLibraryArgumentGetsElementZeroInsertion() {
        val fixture = echoFixture()
        val source = source(fixture)
        val input = echoExpression(source)
        val facts = source.facts(input)
        val position = source.resolve(input)
        val argument = source.argument(position, NativePacketSingleListInsertionSource.libraries)
        assertInsertion(source, argument, facts)
        println("SINGLE_LIST_SUPPLEMENTAL actual_echo_argument=ASSERTED original_seed_route=NOT_ASSERTED")
    }

    fun testSingleTInsertionKeepsTheIndependentlyResolvedFieldSuffix() {
        val fixture = controlsFixture()
        val source = source(fixture)
        val input = source.expression(owner("fixed"), "value")
        val facts = source.facts(input)
        val resolved = source.resolve(input)
        val field = NativePacketSingleListInsertionFacts(project, getOrCreateProjectBaseDir()).payload(fixture)
        val tracked =
            resolved
                .withState(
                    valuePath =
                        io.github.amichne.kast.relation.contract.ValueAccessPath.fromSegments(
                                listOf(ValuePathSegment.Field(field))
                            )
                            .nativeRefined()
                )
                .nativeRefined()
        val argument = source.argument(tracked, NativePacketSingleListInsertionSource.libraries)
        assertInsertion(source, argument, facts)
        println("SINGLE_LIST_FIELD_SUFFIX setup=CONTROLLED_TRACKED_FIELD scalar_or_object_rendering=NOT_ASSERTED")
    }

    fun testTwoElementVarargDoesNotUseTheSingleTModel() =
        assertNoInsertion(method = "varied", text = "value", expectedVararg = true, sourceDefined = false)

    fun testSpreadArrayDoesNotUseTheSingleTModel() =
        assertNoInsertion(method = "spread", text = "values", expectedVararg = true, sourceDefined = false)

    fun testSourceDefinedSameNameDoesNotUseTheLibraryModel() =
        assertNoInsertion(method = "source", text = "value", expectedVararg = false, sourceDefined = true)

    fun testSelectedArgumentCannotSupplyTheDistinctConstantSiblingCall() {
        val fixture = controlsFixture()
        val source = source(fixture)
        val selected = source.expression(owner("siblings"), "value")
        val sibling = source.expression(owner("siblings"), "\"constant\"")
        val selectedFacts = source.facts(selected)
        val siblingFacts = source.facts(sibling)
        assertFalse(selectedFacts.invocationRange == siblingFacts.invocationRange)
        val argument = source.argument(source.resolve(selected), NativePacketSingleListInsertionSource.libraries)
        val result = source.read(argument, NativePacketSingleListInsertionSource.libraries)
        assertTrue(result.applications.none { it.target.range == siblingFacts.invocationRange })
        assertTrue(result.transfers.none { it.target.range == siblingFacts.invocationRange })
        println("SINGLE_LIST_SIBLING no_emitted_witness=ASSERTED completion=NOT_ASSERTED")
    }

    private fun assertNoInsertion(method: String, text: String, expectedVararg: Boolean, sourceDefined: Boolean) {
        val fixture = controlsFixture()
        val source = source(fixture)
        val input = source.expression(owner(method), text)
        source.facts(input, expectedVararg, sourceDefined)
        val argument = source.argument(source.resolve(input), NativePacketSingleListInsertionSource.libraries)
        val result = source.read(argument, NativePacketSingleListInsertionSource.libraries)
        assertTrue(result.applications.isEmpty())
        println("SINGLE_LIST_REJECTED method=$method no_reviewed_application=ASSERTED source_flow=NOT_FORBIDDEN")
    }

    private fun assertInsertion(
        source: NativePacketSingleListInsertionSource,
        argument: ValueSite,
        expected: SingleListInsertionFacts,
    ) {
        assertCall(argument, expected)
        val result = source.read(argument, NativePacketSingleListInsertionSource.libraries)
        val applications = result.applications.filter { it.target.range == expected.invocationRange }
        assertEquals(
            "The exact single-T call needs reviewed Element0 insertion. Causes=${result.obligations.map { it.cause }}",
            1,
            applications.size,
        )
        val application = applications.single()
        assertEquals(argument, application.source)
        assertEquals(expected.invocationRange, application.target.range)
        assertEquals(ValueRole.ExpressionResult, application.target.role)
        val zero = ValuePathSegment.Element(ValueElementIndex.parse(0).nativeRefined())
        assertEquals(listOf(zero) + argument.valuePath.segments, application.target.valuePath.segments)
        assertEquals(argument.callStack, application.target.callStack)
        assertEquals(argument.predicates, application.target.predicates)
        assertEquals(argument.enclosing, application.target.enclosing)
        assertEquals(argument.executionOwner, application.target.executionOwner)
        val call = (argument.role as ValueRole.Argument).call
        assertEquals(call, application.binding.invocation)
        assertEquals(call.enclosing, application.binding.invocation.enclosing)
        assertEquals(call.callable, application.binding.invocation.callable)
        assertEquals(call.executionOwner, application.binding.invocation.executionOwner)
        assertEquals(expected.signature, application.binding.model.callable)
        assertEquals(
            NativePacketSingleListInsertionFacts.BINARY_SHA256,
            application.binding.artifact.binaryDigest.value,
        )
        assertEquals(
            NativePacketSingleListInsertionFacts.SOURCE_SHA256,
            application.binding.model.artifact.sourceDigest.value,
        )
        assertEquals(NativePacketSingleListInsertionFacts.VERSION, application.binding.artifact.version.value)
        assertEquals(argument.basis, application.binding.artifact.basis)
        assertEquals(
            io.github.amichne.kast.relation.contract.LibraryModelDeclarationOrigin.LIBRARY,
            application.binding.artifact.origin,
        )
        assertEquals(
            "source-sha256:" + NativePacketSingleListInsertionFacts.SOURCE_SHA256,
            application.binding.model.reference.model.provenance.value,
        )
        assertEquals(expected.artifactRoot, application.binding.artifact.root.value)
        assertTrue(result.transfers.none { it.target == application.target })
    }

    private fun assertCall(argument: ValueSite, expected: SingleListInsertionFacts) {
        val role = argument.role as ValueRole.Argument
        assertEquals(expected.invocationRange, role.call.range)
        assertEquals(expected.identity, role.call.callable.compilerIdentity)
        assertEquals(expected.signature, role.call.signature)
        assertEquals(0, role.position.value)
        val file =
            role.call.callable.file as io.github.amichne.kast.symbol.contract.SymbolDiscoveryFileIdentity.External
        assertEquals(expected.declarationUrl, file.url.value)
    }

    private fun echoExpression(source: NativePacketSingleListInsertionSource) =
        source.expression(
            PacketDeclaration(NativePacketSingleListInsertionSource.ECHO, "EchoOffersClient", "readOffers"),
            "request.destination",
        )

    private fun owner(method: String) =
        PacketDeclaration(NativePacketSingleListInsertionSource.CONTROL, "SingleListInsertionControls", method)

    private fun echoFixture() =
        packetFixture(NativePacketSingleListInsertionSource.ECHO_PATHS, SymbolSourceKindPolicy.PRODUCTION_AND_TEST)

    private fun controlsFixture() = packetFixture(listOf(NativePacketSingleListInsertionSource.CONTROL))

    private fun source(fixture: NativePacketValueFixture) =
        NativePacketSingleListInsertionSource(project, getOrCreateProjectBaseDir(), fixture)
}
