package io.github.amichne.kast.relation.intellij

import com.intellij.openapi.project.Project
import io.github.amichne.kast.relation.contract.CallbackBindingEvidence
import io.github.amichne.kast.relation.contract.RelationMeaning
import io.github.amichne.kast.relation.contract.RelationRequest
import io.github.amichne.kast.relation.contract.RelationSearchBoundary
import io.github.amichne.kast.relation.contract.ValueAccessPath
import io.github.amichne.kast.relation.contract.ValueCallbackTarget
import io.github.amichne.kast.relation.contract.ValueExecutionBody
import io.github.amichne.kast.relation.contract.ValueFlowRead
import io.github.amichne.kast.relation.contract.ValueFlowRequest
import io.github.amichne.kast.relation.contract.ValueFlowStep
import io.github.amichne.kast.relation.contract.ValueFormalOrigin
import io.github.amichne.kast.relation.contract.ValueFormalPosition
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

/** Fixed one-hop steps isolate the renamed carrier and the constant callback. */
internal class NativePacketConditionalPayloadRoute(
    private val project: Project,
    private val fixture: NativePacketValueFixture,
    private val source: NativePacketConditionalPayloadSource,
) {
    fun payload(method: String): ConditionalPayloadPrefix {
        val input = resolve(source.input(method))
        val fieldPath =
            ValueAccessPath.fromSegments(listOf(ValuePathSegment.Field(source.acceptedField()))).nativeRefined()
        val selected = input.withState(valuePath = fieldPath).nativeRefined()
        val receiver = next(selected, ValueTransferKind.RECEIVER, "convert receiver").target
        val formal = next(receiver, ValueTransferKind.SOURCE_CALL_ENTRY, "convert formal").target
        assertEquals(source.convertSelector().compilerIdentity, formal.enclosing.compilerIdentity)
        assertEquals(source.convertSelector().range, formal.range)
        assertEquals((receiver.role as ValueRole.Receiver).call, formal.callStack.frames.last().invocation)
        assertEquals(fieldPath, formal.valuePath)
        val fields = step(formal, "Accepted.payload selection")
        val projected =
            fields.transfers.filter {
                it.kind == ValueTransferKind.FIELD_READ && it.target.range == source.acceptedRead()
            }
        assertEquals(
            "Accepted.payload is absent. Frontier=${fields.obligations.map { it.cause }}. " +
                "Callback stages are NOT_REACHED",
            1,
            projected.size,
        )
        val selectedField = projected.single()
        val prefix =
            ConditionalPayloadPrefix(receiver = receiver, formal = formal, payload = selectedField, fields = fields)
        assertTrue(selectedField.evidence is ValueTransferEvidence.Guarded)
        assertEquals(source.acceptedField(), (operation(selectedField) as ValueSourceOperation.ReadField).field)
        assertEquals(ValueAccessPath.Whole, selectedField.target.valuePath)
        assertEquals(formal.callStack, selectedField.target.callStack)
        guard(prefix, selectedField.target)
        return prefix
    }

    fun callback(prefix: ConditionalPayloadPrefix, method: String): ValueSite {
        val argument = next(prefix.payload.target, ValueTransferKind.ARGUMENT, "builtin operation argument").target
        val call = (argument.role as ValueRole.FunctionArgument).call
        assertEquals("kotlin.Function1.invoke", call.signature.qualifiedIdentity.value)
        assertEquals(source.invoke(), call.range)
        assertEquals(prefix.formal.executionOwner, call.executionOwner)
        val entered = next(argument, ValueTransferKind.SOURCE_CALL_ENTRY, "supplied implicit formal").target
        assertEquals(source.callback(method), entered.executionOwner.range)
        assertTrue(entered.executionOwner.body is ValueExecutionBody.Anonymous)
        val parameter = ((entered.role as ValueRole.Formal).position as ValueFormalPosition.Parameter).parameter
        assertEquals(ValueFormalOrigin.Implicit, parameter.origin)
        assertEquals("it", parameter.name.value)
        assertEquals(0, parameter.position.value)
        assertEquals(source.callback(method), parameter.range)
        val frame = entered.callStack.frames.last()
        assertEquals(call, frame.invocation)
        assertEquals(argument.callStack.frames + frame, entered.callStack.frames)
        val target = frame.target as ValueCallbackTarget
        assertEquals(prefix.formal.callStack.frames.last(), target.supplyingFrame)
        val binding = target.flow.binding as CallbackBindingEvidence.Bound
        assertEquals((prefix.receiver.role as ValueRole.Receiver).call, binding.binding.invocation)
        assertEquals(source.invoke(), target.selected.occurrence.range)
        guard(prefix, entered)
        return entered
    }

    fun identityResult(prefix: ConditionalPayloadPrefix, formal: ValueSite): ValueSite {
        val read = next(formal, ValueTransferKind.LOCAL_READ, "identity implicit read").target
        assertEquals(source.identityRead(), read.range)
        assertEquals(formal.executionOwner, read.executionOwner)
        val returned = next(read, ValueTransferKind.RETURN, "identity anonymous result").target
        val result = next(returned, ValueTransferKind.SOURCE_CALL_RETURN, "matched builtin return")
        val frame = formal.callStack.frames.last()
        assertEquals(frame, (operation(result) as ValueSourceOperation.Return).frame)
        assertEquals(frame.invocation.range, result.target.range)
        assertEquals(formal.callStack.frames.dropLast(1), result.target.callStack.frames)
        assertEquals(ValueAccessPath.Whole, result.target.valuePath)
        guard(prefix, result.target)
        return result.target
    }

    fun replacement(prefix: ConditionalPayloadPrefix, result: ValueSite): ValueSite {
        val argument = next(result, ValueTransferKind.ARGUMENT, "accepted factory argument").target
        val formal = next(argument, ValueTransferKind.SOURCE_CALL_ENTRY, "accepted factory formal").target
        val read = next(formal, ValueTransferKind.LOCAL_READ, "accepted factory payload").target
        val actual = next(read, ValueTransferKind.ARGUMENT, "replacement constructor argument").target
        val stored = next(actual, ValueTransferKind.CONSTRUCTOR_FIELD, "replacement Accepted.payload")
        val storage = (operation(stored) as ValueSourceOperation.ConstructField).storage
        assertEquals(source.acceptedField(), storage.field)
        assertEquals("payload", storage.parameter.name.value)
        assertEquals(0, storage.parameter.position.value)
        assertEquals(listOf(ValuePathSegment.Field(source.acceptedField())), stored.target.valuePath.segments)
        guard(prefix, stored.target)
        return stored.target
    }

    fun step(site: ValueSite, row: String): ValueFlowStep {
        val result = runBlocking {
            IntellijValueFlowCompilerAdapter()
                .read(
                    project = project,
                    current = fixture.lease,
                    request = ValueFlowRequest(site, fixture.budget, RelationSearchBoundary.RETAINED_SUBJECT),
                    model = fixture.model,
                )
        }
        assertTrue("$row native setup failed: $result", result is ValueFlowRead.Observed)
        return (result as ValueFlowRead.Observed).step.also { read ->
            println(
                "CONDITIONAL_PAYLOAD_STAGE row=$row source=${site.range} " +
                    "transfers=${read.transfers.map { it.kind to it.target.range }} " +
                    "frontier=${read.obligations.map { it.cause }}"
            )
        }
    }

    private fun next(site: ValueSite, kind: ValueTransferKind, row: String): ValueTransfer {
        val result = step(site, row)
        assertEquals(
            "$row needs $kind. Frontier=${result.obligations.map { it.cause }}. Later stages are NOT_REACHED",
            listOf(kind),
            result.transfers.map { it.kind },
        )
        return result.transfers.single()
    }

    private fun guard(prefix: ConditionalPayloadPrefix, site: ValueSite) {
        val subject =
            prefix.receiver
                .withState(valuePath = ValueAccessPath.Whole, predicates = ValuePredicates.Empty)
                .nativeRefined()
        val predicates = site.predicates.entries.filterIsInstance<ValuePredicate.Subtype>()
        val expected = predicates.filter { it.subject.occurrence == subject }
        assertEquals(listOf(source.acceptedType().valueIdentity), expected.map { it.resolvedType.valueIdentity })
        assertTrue("A field path must not substitute for the whole carrier guard subject", subject != prefix.receiver)
    }

    private fun resolve(expression: PacketExpression): ValueSite {
        val result = runBlocking {
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
        assertTrue("The exact source receiver must pass native admission: $result", result is ValueSiteResolution.Found)
        return (result as ValueSiteResolution.Found).site.also { site ->
            assertEquals(expression.anchor, site.range)
            assertEquals(expression.enclosing.compilerIdentity, site.enclosing.compilerIdentity)
            assertEquals(ValueAccessPath.Whole, site.valuePath)
        }
    }

    private fun operation(transfer: ValueTransfer): ValueSourceOperation {
        val evidence = transfer.evidence
        val source = if (evidence is ValueTransferEvidence.Guarded) evidence.delegate else evidence
        return (source as ValueSourceTransfer).operation
    }
}

internal data class ConditionalPayloadPrefix(
    val receiver: ValueSite,
    val formal: ValueSite,
    val payload: ValueTransfer,
    val fields: ValueFlowStep,
)
