package io.github.amichne.kast.relation.intellij

import com.intellij.openapi.project.Project
import io.github.amichne.kast.relation.contract.RelationEndpoint
import io.github.amichne.kast.relation.contract.RelationSearchBoundary
import io.github.amichne.kast.relation.contract.ValueAccessPath
import io.github.amichne.kast.relation.contract.ValueExecutionBody
import io.github.amichne.kast.relation.contract.ValueField
import io.github.amichne.kast.relation.contract.ValueFlowRead
import io.github.amichne.kast.relation.contract.ValueFlowRequest
import io.github.amichne.kast.relation.contract.ValueFlowStep
import io.github.amichne.kast.relation.contract.ValuePathSegment
import io.github.amichne.kast.relation.contract.ValuePredicate
import io.github.amichne.kast.relation.contract.ValuePredicates
import io.github.amichne.kast.relation.contract.ValueRole
import io.github.amichne.kast.relation.contract.ValueSite
import io.github.amichne.kast.relation.contract.ValueSourceOperation
import io.github.amichne.kast.relation.contract.ValueSourceTransfer
import io.github.amichne.kast.relation.contract.ValueTransfer
import io.github.amichne.kast.relation.contract.ValueTransferEvidence
import io.github.amichne.kast.relation.contract.ValueTransferKind
import junit.framework.TestCase.assertEquals
import junit.framework.TestCase.assertTrue
import kotlinx.coroutines.runBlocking

/** Each step names one source operation. No search or alternative route runs here. */
internal class NativePacketCompositeSuccessRoute(
    private val project: Project,
    private val fixture: NativePacketValueFixture,
    private val source: NativePacketCompositeSuccessSource,
) {
    fun enteredCallback(seed: ValueSite): CompositeSuccessPrefix {
        val receiver = next(seed, ValueTransferKind.RECEIVER, "C2.1 receiver").target
        val formal = next(receiver, ValueTransferKind.SOURCE_CALL_ENTRY, "C2.1 map formal").target
        val value = fixture.argumentExpression(PacketDeclaration(OUTCOME, "Outcome", "map"), "transform", "value")
        val fields = read(formal, "C2.1 Success.value")
        val selected =
            fields.transfers.filter {
                it.kind == ValueTransferKind.FIELD_READ && it.target.range == value.anchor
            }
        assertEquals(
            "C2.1 Success.value field is absent. Frontier=${fields.obligations.map { it.cause }}",
            1,
            selected.size,
        )
        val argument = next(selected.single().target, ValueTransferKind.ARGUMENT, "C2.1 builtin argument").target
        val entered = next(argument, ValueTransferKind.SOURCE_CALL_ENTRY, "C2.1 callback formal").target
        assertTrue(entered.executionOwner.body is ValueExecutionBody.Anonymous)
        assertEquals(source.implicitIt().body, entered.executionOwner.range)
        val prefix = CompositeSuccessPrefix(receiver, entered, source.endpoint(OUTCOME, "Success"))
        guard(prefix, entered)
        return prefix
    }

    fun clientResponse(prefix: CompositeSuccessPrefix): ValueSite {
        val receiver = bodyReceiver(prefix.formal)
        val helper = (receiver.role as ValueRole.Receiver).call
        val selected = fixture.callableDeclaration(PacketDeclaration(CLIENT, "", "toClientResponse"))
        assertEquals(selected.compilerIdentity, helper.callable.compilerIdentity)
        val formal = next(receiver, ValueTransferKind.SOURCE_CALL_ENTRY, "C2.2 mapper formal").target
        val stored = responseStorage(formal)
        val returned = next(stored, ValueTransferKind.RETURN, "C2.2 mapper return").target
        val result = next(returned, ValueTransferKind.SOURCE_CALL_RETURN, "C2.2 matched mapper return").target
        assertEquals(helper.range, result.range)
        assertEquals(prefix.formal.executionOwner, result.executionOwner)
        assertEquals(prefix.formal.callStack, result.callStack)
        path(result, source.field(CLIENT, "ClientResponse", "links"))
        guard(prefix, result)
        return result
    }

    private fun bodyReceiver(formal: ValueSite): ValueSite {
        val read = next(formal, ValueTransferKind.LOCAL_READ, "C2.2 implicit it read")
        assertEquals(source.implicitIt().reference, read.target.range)
        assertEquals(formal.executionOwner, read.target.executionOwner)
        val body = next(read.target, ValueTransferKind.FIELD_READ, "C2.2 Envelope.body")
        assertEquals(source.field(WIRE, "Envelope", "body"), (operation(body) as ValueSourceOperation.ReadField).field)
        return next(body.target, ValueTransferKind.RECEIVER, "C2.2 mapper receiver").target
    }

    private fun responseStorage(formal: ValueSite): ValueSite {
        val links = next(formal, ValueTransferKind.FIELD_READ, "C2.2 WireResponse.links")
        assertEquals(
            source.field(WIRE, "WireResponse", "links"),
            (operation(links) as ValueSourceOperation.ReadField).field,
        )
        val argument = next(links.target, ValueTransferKind.ARGUMENT, "C2.2 ClientResponse argument").target
        val stored = next(argument, ValueTransferKind.CONSTRUCTOR_FIELD, "C2.2 ClientResponse.links")
        val storage = (operation(stored) as ValueSourceOperation.ConstructField).storage
        assertEquals(source.field(CLIENT, "ClientResponse", "links"), storage.field)
        assertEquals(0, storage.parameter.position.value)
        return stored.target
    }

    fun callbackReturn(prefix: CompositeSuccessPrefix, mapped: ValueSite): ValueSite {
        val frame = prefix.formal.callStack.frames.last()
        val returned = next(mapped, ValueTransferKind.RETURN, "C2.5 anonymous normal return").target
        assertEquals(prefix.formal.executionOwner, returned.executionOwner)
        val transfer = next(returned, ValueTransferKind.SOURCE_CALL_RETURN, "C2.5 matched builtin return")
        assertEquals(frame, (operation(transfer) as ValueSourceOperation.Return).frame)
        assertEquals(frame.invocation.range, transfer.target.range)
        assertEquals(prefix.formal.callStack.frames.dropLast(1), transfer.target.callStack.frames)
        assertEquals(mapped.valuePath, transfer.target.valuePath)
        guard(prefix, transfer.target)
        return transfer.target
    }

    fun reconstructedSuccess(prefix: CompositeSuccessPrefix, returned: ValueSite) {
        val argument = next(returned, ValueTransferKind.ARGUMENT, "C2.5 success factory argument").target
        val formal = next(argument, ValueTransferKind.SOURCE_CALL_ENTRY, "C2.5 success factory formal").target
        val read = next(formal, ValueTransferKind.LOCAL_READ, "C2.5 success factory value").target
        val actual = next(read, ValueTransferKind.ARGUMENT, "C2.5 replacement carrier argument").target
        val stored = next(actual, ValueTransferKind.CONSTRUCTOR_FIELD, "C2.5 replacement carrier storage")
        val storage = (operation(stored) as ValueSourceOperation.ConstructField).storage
        val value = source.field(OUTCOME, "Success", "value")
        assertEquals(value, storage.field)
        assertEquals(0, storage.parameter.position.value)
        path(stored.target, value, source.field(CLIENT, "ClientResponse", "links"))
        guard(prefix, stored.target)
    }

    private fun guard(prefix: CompositeSuccessPrefix, site: ValueSite) {
        val subject =
            prefix.receiver
                .withState(valuePath = ValueAccessPath.Whole, predicates = ValuePredicates.Empty)
                .nativeRefined()
        val facts =
            site.predicates.entries.filterIsInstance<ValuePredicate.Subtype>().filter {
                it.subject.occurrence == subject
            }
        assertEquals(listOf(prefix.successType.valueIdentity), facts.map { it.resolvedType.valueIdentity })
    }

    private fun path(site: ValueSite, vararg fields: ValueField) {
        assertEquals(fields.map(ValuePathSegment::Field), site.valuePath.segments)
    }

    private fun operation(transfer: ValueTransfer): ValueSourceOperation {
        val evidence = transfer.evidence
        val source = if (evidence is ValueTransferEvidence.Guarded) evidence.delegate else evidence
        return (source as ValueSourceTransfer).operation
    }

    private fun next(source: ValueSite, kind: ValueTransferKind, row: String): ValueTransfer {
        val result = read(source, row)
        assertEquals(
            "$row needs $kind at ${source.range}. Frontier=${result.obligations.map { it.cause }}. " +
                "Later C2.2/C2.5-C2.9 stages remain NOT_REACHED",
            listOf(kind),
            result.transfers.map { it.kind },
        )
        return result.transfers.single()
    }

    private fun read(source: ValueSite, row: String): ValueFlowStep {
        val result = runBlocking {
            IntellijValueFlowCompilerAdapter()
                .read(
                    project = project,
                    current = fixture.lease,
                    request = ValueFlowRequest(source, fixture.budget, RelationSearchBoundary.RETAINED_SUBJECT),
                    model = fixture.model,
                )
        }
        assertTrue("$row native setup failed: $result", result is ValueFlowRead.Observed)
        val step = (result as ValueFlowRead.Observed).step
        println(
            "COMPOSITE_SUCCESS_STAGE row=$row source=${source.range} " +
                "transfers=${step.transfers.map { it.kind to it.target.range }} " +
                "frontier=${step.obligations.map { it.cause }}"
        )
        return step
    }

    private companion object {
        const val CLIENT = NativePacketCompositeSuccessSource.CLIENT
        const val OUTCOME = NativePacketCompositeSuccessSource.OUTCOME
        const val WIRE = NativePacketCompositeSuccessSource.WIRE
    }
}

internal data class CompositeSuccessPrefix(
    val receiver: ValueSite,
    val formal: ValueSite,
    val successType: RelationEndpoint,
)
