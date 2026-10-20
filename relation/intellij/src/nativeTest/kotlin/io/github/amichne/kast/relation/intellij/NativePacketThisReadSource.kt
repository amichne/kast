package io.github.amichne.kast.relation.intellij

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import io.github.amichne.kast.relation.contract.RelationEndpoint
import io.github.amichne.kast.relation.contract.RelationMeaning
import io.github.amichne.kast.relation.contract.RelationRequest
import io.github.amichne.kast.relation.contract.ReviewedValueModel
import io.github.amichne.kast.relation.contract.ValueAccessPath
import io.github.amichne.kast.relation.contract.ValueCallFrame
import io.github.amichne.kast.relation.contract.ValueExecutionOwner
import io.github.amichne.kast.relation.contract.ValueFlowStep
import io.github.amichne.kast.relation.contract.ValueFormalPosition
import io.github.amichne.kast.relation.contract.ValueModelApplication
import io.github.amichne.kast.relation.contract.ValuePathSegment
import io.github.amichne.kast.relation.contract.ValuePredicate
import io.github.amichne.kast.relation.contract.ValuePredicates
import io.github.amichne.kast.relation.contract.ValueReceiverKind
import io.github.amichne.kast.relation.contract.ValueRole
import io.github.amichne.kast.relation.contract.ValueSite
import io.github.amichne.kast.relation.contract.ValueTransferKind
import io.github.amichne.kast.symbol.contract.ExactDeclarationTextRange
import io.github.amichne.kast.symbol.contract.SymbolLibraryPolicy
import io.github.amichne.kast.symbol.contract.SymbolSearchScope
import io.github.amichne.kast.symbol.contract.SymbolSelector
import junit.framework.TestCase.assertEquals
import junit.framework.TestCase.assertTrue
import kotlinx.coroutines.runBlocking

/** Fixed isolated positions keep the actual original guard. They do not replace the full S2 seed or route. */
internal class NativePacketThisReadSource(
    private val project: Project,
    private val base: VirtualFile,
    private val fixture: NativePacketValueFixture,
) {
    private val fields = NativePacketCompositeSuccessSource(project, base, fixture)
    private val native = NativePacketBindBranchSource(project, base, fixture)
    private val proof = NativePacketThisReadProof(project, base)

    fun requiredFormal(): ValueSite {
        proof.requireNotNull()
        val expected =
            NativePacketReviewedReceiverSource(project, base, fixture).expectation(ReceiverAdmissionCase.REVIEWED)
        val initial = resolve(expected.host, expected.receiver).withState(predicates = originalGuard()).nativeRefined()
        assertEquals(expected.owner, initial.executionOwner)
        assertEquals(ValueAccessPath.Whole, initial.valuePath)
        val receiver = next(initial, ValueTransferKind.RECEIVER)
        val role = receiver.role as ValueRole.Receiver
        assertEquals(ValueReceiverKind.EXTENSION, role.kind)
        assertEquals(expected.identity, role.call.callable.compilerIdentity)
        assertEquals(expected.signature, role.call.signature)
        val actual = next(selection(receiver), ValueTransferKind.RECEIVER)
        val required = fixture.callableDeclaration(PacketDeclaration(OUTCOME, "", "requireNotNull"))
        val call = (actual.role as ValueRole.Receiver).call
        assertEquals(ValueReceiverKind.EXTENSION, (actual.role as ValueRole.Receiver).kind)
        assertEquals(exact(FIRST_START, REQUIRE_END), call.range)
        assertEquals(required.compilerIdentity, call.callable.compilerIdentity)
        val formal = next(actual, ValueTransferKind.SOURCE_CALL_ENTRY)
        assertFormal(actual = actual, formal = formal, expected = required, kind = ValueReceiverKind.EXTENSION)
        assertEquals(initial.valuePath, formal.valuePath)
        assertEquals(initial.predicates, formal.predicates)
        println("THIS_READ_SETUP kind=ISOLATED_ORIGINAL_EXCLUDE_POSITION model=ACTUAL_SELECTION source_entry=ACTUAL")
        return formal
    }

    fun bindFormal(kind: ValueReceiverKind): ValueSite {
        proof.bind()
        val expected =
            ReadAction.compute<DestructuringProbeExpectation, RuntimeException> {
                NativePacketDestructuringProbeSource(project, base, fixture).expectation()
            }
        val path =
            ValueAccessPath.fromSegments(
                    listOf(
                        ValuePathSegment.Field(fields.field(OUTCOME, "Success", "value")),
                        ValuePathSegment.Field(fields.field(CLIENT, "ClientResponse", "links")),
                    )
                )
                .nativeRefined()
        val initializer =
            resolve(expected.enclosing, expected.initializer)
                .withState(valuePath = path, predicates = originalGuard())
                .nativeRefined()
        val componentReceiver = next(initializer, ValueTransferKind.RECEIVER)
        val component = next(componentReceiver, ValueTransferKind.SOURCE_CALL_ENTRY)
        val extension = next(component, ValueTransferKind.RECEIVER)
        val actual = if (kind == ValueReceiverKind.EXTENSION) extension else dispatch(extension)
        val formal = next(actual, ValueTransferKind.SOURCE_CALL_ENTRY)
        val bind = fixture.callableDeclaration(PacketDeclaration(OUTCOME, "OutcomeScope", "bind"))
        assertFormal(actual = actual, formal = formal, expected = bind, kind = kind)
        assertEquals(actual.valuePath, formal.valuePath)
        assertEquals(actual.predicates, formal.predicates)
        println("THIS_READ_SETUP kind=CONTROLLED_ORIGINAL_INITIALIZER slot=$kind guard=ORIGINAL_S2_NATIVE_PREFIX")
        return formal
    }

    fun read(source: ValueSite): ValueFlowStep = native.read(source)

    private fun selection(receiver: ValueSite): ValueSite {
        val application = read(receiver).applications.single() as ValueModelApplication.CollectionSelection
        assertEquals(receiver, application.source)
        assertEquals(exact(FIRST_START, FIRST_END), application.target.range)
        assertEquals(ValueRole.ExpressionResult, application.target.role)
        assertEquals(ValueAccessPath.Whole, application.target.valuePath)
        assertEquals(receiver.enclosing, application.target.enclosing)
        assertEquals(receiver.executionOwner, application.target.executionOwner)
        assertEquals(receiver.predicates, application.target.predicates)
        assertEquals(receiver.callStack, application.target.callStack)
        assertEquals((receiver.role as ValueRole.Receiver).call, application.binding.invocation)
        assertEquals(ReviewedValueModel.FirstOrNull, application.binding.model)
        assertEquals(receiver, application.condition.receiver)
        assertEquals(application.target, application.condition.result)
        assertEquals(0, application.condition.index.value)
        return application.target
    }

    private fun dispatch(extension: ValueSite): ValueSite {
        val role = extension.role as ValueRole.Receiver
        assertEquals(ValueReceiverKind.EXTENSION, role.kind)
        assertEquals(exact(BIND_START, BIND_END), role.call.range)
        return ValueSite.fromCompiler(
                enclosing = extension.enclosing,
                range = extension.range,
                role = ValueRole.Receiver(role.call, ValueReceiverKind.DISPATCH),
                executionOwner = extension.executionOwner,
                valuePath = ValueAccessPath.Whole,
                callStack = extension.callStack,
                predicates = extension.predicates,
            )
            .nativeRefined()
    }

    private fun originalGuard(): ValuePredicates {
        val actual = native.successValue()
        val predicates =
            actual.predicates.entries.filterIsInstance<ValuePredicate.Subtype>().filter { predicate ->
                predicate.subject.occurrence.range == exact(EXECUTE_START, EXECUTE_END) &&
                    predicate.resolvedType.valueIdentity == fields.endpoint(OUTCOME, "Success").valueIdentity
            }
        assertEquals("The isolated position needs the exact original execute Success guard", 1, predicates.size)
        val subject = predicates.single().subject.occurrence
        assertEquals(ValueAccessPath.Whole, subject.valuePath)
        assertEquals(ValueReceiverKind.DISPATCH, (subject.role as ValueRole.Receiver).kind)
        return ValuePredicates.fromCompiler(predicates).nativeRefined()
    }

    private fun resolve(host: SymbolSelector, anchor: ExactDeclarationTextRange): ValueSite {
        val domain = RelationRequest.start(RelationEndpoint.subject(host), RelationMeaning.References, fixture.budget)
        assertEquals(SymbolLibraryPolicy.EXCLUDE, (domain.searchScope as SymbolSearchScope.Workspace).libraries)
        val result = runBlocking {
            IntellijValueFlowCompilerAdapter()
                .resolve(
                    project = project,
                    current = fixture.lease,
                    enclosing = host,
                    anchor = anchor,
                    domain = domain,
                    model = fixture.model,
                )
        }
        assertTrue("The isolated original source position must resolve", result is ValueSiteResolution.Found)
        return (result as ValueSiteResolution.Found).site
    }

    private fun next(source: ValueSite, kind: ValueTransferKind): ValueSite {
        val result = read(source)
        val selected = result.transfers.filter { transfer -> transfer.kind == kind }
        assertEquals("The fixed prefix needs $kind. Causes=${result.obligations.map { it.cause }}", 1, selected.size)
        return selected.single().target
    }

    private fun assertFormal(
        actual: ValueSite,
        formal: ValueSite,
        expected: SymbolSelector,
        kind: ValueReceiverKind,
    ) {
        val owner = ValueExecutionOwner.declaration(RelationEndpoint.subject(expected))
        val frame = ValueCallFrame.direct((actual.role as ValueRole.Receiver).call, owner).nativeRefined()
        assertEquals(ValueRole.Formal(ValueFormalPosition.Receiver(kind)), formal.role)
        assertEquals(expected.range, formal.range)
        assertEquals(expected.compilerIdentity, formal.enclosing.compilerIdentity)
        assertEquals(owner, formal.executionOwner)
        assertEquals(actual.callStack.frames + frame, formal.callStack.frames)
    }

    private fun exact(start: Int, end: Int) = ExactDeclarationTextRange.parse(start, end).nativeRefined()

    private companion object {
        const val OUTCOME = NativePacketCompositeSuccessSource.OUTCOME
        const val CLIENT = NativePacketCompositeSuccessSource.CLIENT
        const val EXECUTE_START = 769
        const val EXECUTE_END = 954
        const val FIRST_START = 939
        const val FIRST_END = 967
        const val REQUIRE_END = 1020
        const val BIND_START = 1137
        const val BIND_END = 1143
    }
}
