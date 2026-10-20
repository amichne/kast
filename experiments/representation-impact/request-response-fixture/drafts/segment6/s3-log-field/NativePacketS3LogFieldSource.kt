package io.github.amichne.kast.relation.intellij

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import io.github.amichne.kast.relation.contract.ValueFlowStep
import io.github.amichne.kast.relation.contract.ValueFormalPosition
import io.github.amichne.kast.relation.contract.ValueReceiverKind
import io.github.amichne.kast.relation.contract.ValueRole
import io.github.amichne.kast.relation.contract.ValueSite
import io.github.amichne.kast.relation.contract.ValueSourceOperation
import io.github.amichne.kast.relation.contract.ValueSourceTransfer
import io.github.amichne.kast.relation.contract.ValueTransferKind
import junit.framework.TestCase.assertEquals
import junit.framework.TestCase.assertTrue

/** The original adapt seed enters its real log callback and the actual source helper. */
internal class NativePacketS3LogFieldSource(
    project: Project,
    base: VirtualFile,
    private val fixture: NativePacketValueFixture,
) {
    private val source = NativePacketS3SeedCallbackSource(project, base, fixture)

    fun entry(): S3LogFieldEntry {
        val argument = source.argument()
        val callback = next(argument, ValueTransferKind.SOURCE_CALL_ENTRY)
        val read = next(callback, ValueTransferKind.LOCAL_READ)
        assertEquals(exact(LOG_IT_START, LOG_IT_END), read.range)
        val receiver = next(read, ValueTransferKind.RECEIVER)
        assertEquals(read.range, receiver.range)
        val role = receiver.role as ValueRole.Receiver
        assertEquals(ValueReceiverKind.EXTENSION, role.kind)
        assertEquals(exact(LOG_IT_START, LOG_HELPER_END), role.call.range)
        val helper = fixture.callableDeclaration(PacketDeclaration(CLIENT, "", "toLogLine"))
        assertEquals(helper.compilerIdentity, role.call.callable.compilerIdentity)
        val formal = next(receiver, ValueTransferKind.SOURCE_CALL_ENTRY)
        assertEquals(ValueRole.Formal(ValueFormalPosition.Receiver(ValueReceiverKind.EXTENSION)), formal.role)
        assertEquals(helper.compilerIdentity, formal.enclosing.compilerIdentity)
        assertEquals(helper.range, formal.range)
        assertEquals(receiver.callStack.frames + formal.callStack.frames.last(), formal.callStack.frames)
        assertEquals(role.call, formal.callStack.frames.last().invocation)
        assertEquals(formal.executionOwner, formal.callStack.frames.last().callee)
        assertEquals(receiver.valuePath, formal.valuePath)
        assertEquals(receiver.predicates, formal.predicates)
        return S3LogFieldEntry(receiver, formal)
    }

    fun read(site: ValueSite): ValueFlowStep = source.read(site)

    fun scalar(entry: S3LogFieldEntry, expected: S3LogBranchFacts): ValueSite {
        val fields = read(entry.formal)
        val payload =
            fields.transfers.single { transfer ->
                transfer.kind == ValueTransferKind.FIELD_READ && transfer.target.range == expected.valueRange
            }
        val selected = read(payload.target)
        assertEquals(
            "Original guarded payload needs its actual scalar field. Causes=${selected.obligations.map { it.cause }}",
            1,
            selected.transfers.size,
        )
        val field = selected.transfers.single()
        assertEquals(ValueTransferKind.FIELD_READ, field.kind)
        assertEquals(expected.scalarRange, field.target.range)
        val operation = (field.evidence as ValueSourceTransfer).operation as ValueSourceOperation.ReadField
        assertEquals(expected.field.compilerIdentity, operation.field.declaration.compilerIdentity)
        assertEquals(expected.field.range, operation.field.declaration.range)
        assertEquals(payload.target.predicates, field.target.predicates)
        assertEquals(payload.target.callStack, field.target.callStack)
        assertEquals(payload.target.executionOwner, field.target.executionOwner)
        assertTrue(selected.applications.isEmpty())
        return field.target
    }

    private fun next(site: ValueSite, kind: ValueTransferKind): ValueSite {
        val step = read(site)
        val selected = step.transfers.filter { it.kind == kind }
        assertEquals(
            "Original S3 prefix needs $kind at ${site.range}. Causes=${step.obligations.map { it.cause }}",
            1,
            selected.size,
        )
        return selected.single().target
    }

    private fun exact(start: Int, end: Int) =
        io.github.amichne.kast.symbol.contract.ExactDeclarationTextRange.parse(start, end).nativeRefined()

    private companion object {
        const val CLIENT = NativePacketCompositeSuccessSource.CLIENT
        const val LOG_IT_START = 927
        const val LOG_IT_END = 929
        const val LOG_HELPER_END = 941
    }
}

internal data class S3LogFieldEntry(val receiver: ValueSite, val formal: ValueSite)
