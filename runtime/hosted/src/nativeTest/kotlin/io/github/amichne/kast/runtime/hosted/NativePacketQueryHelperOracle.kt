package io.github.amichne.kast.runtime.hosted

import io.github.amichne.kast.protocol.contract.ImpactCompilerTransferDocument
import io.github.amichne.kast.protocol.contract.ImpactDeclarationReferenceDocument
import io.github.amichne.kast.protocol.contract.ImpactExecutionReferenceDocument
import io.github.amichne.kast.protocol.contract.ImpactFormalPositionDocument
import io.github.amichne.kast.protocol.contract.ImpactInvocationReferenceDocument
import io.github.amichne.kast.protocol.contract.ImpactReceiverKindDocument
import io.github.amichne.kast.protocol.contract.ImpactSourceOperationDocument
import io.github.amichne.kast.protocol.contract.ImpactTransferEvidenceDocument
import io.github.amichne.kast.protocol.contract.ImpactTransferKindDocument
import io.github.amichne.kast.protocol.contract.hasSameCallStackIdentity

/** V2's source helpers must preserve receiver entry, field provenance, and their exact matched return. */
internal class NativePacketQueryHelperOracle(
    private val wire: NativePacketQueryFrameOracle,
    private val client: NativePacketQueryFrameOracle,
) {
    fun outgoing(
        route: List<ImpactCompilerTransferDocument>,
        input: ImpactDeclarationReferenceDocument,
        output: ImpactDeclarationReferenceDocument,
    ) = matches(route = route, frame = wire, input = input, output = output)

    fun incoming(
        route: List<ImpactCompilerTransferDocument>,
        input: ImpactDeclarationReferenceDocument,
        output: ImpactDeclarationReferenceDocument,
    ) = matches(route = route, frame = client, input = input, output = output)

    private fun matches(
        route: List<ImpactCompilerTransferDocument>,
        frame: NativePacketQueryFrameOracle,
        input: ImpactDeclarationReferenceDocument,
        output: ImpactDeclarationReferenceDocument,
    ): Boolean {
        val entry = route.singleOrNull { it.entersReceiver(frame) } ?: return false
        val projected = route.any { transfer ->
            transfer.kind == ImpactTransferKindDocument.FIELD_READ &&
                (transfer.helperOperation() as? ImpactSourceOperationDocument.ReadField)?.field == input &&
                transfer.source.callStack.hasSameCallStackIdentity(entry.target.callStack)
        }
        val stored = route.any { transfer ->
            transfer.kind == ImpactTransferKindDocument.CONSTRUCTOR_FIELD &&
                (transfer.helperOperation() as? ImpactSourceOperationDocument.ConstructField)?.storage?.field ==
                    output &&
                transfer.source.callStack.hasSameCallStackIdentity(entry.target.callStack)
        }
        return projected && stored && route.any { it.returns(frame, entry) }
    }

    private fun ImpactCompilerTransferDocument.entersReceiver(frame: NativePacketQueryFrameOracle): Boolean {
        val operation = helperOperation() as? ImpactSourceOperationDocument.Enter ?: return false
        if (kind != ImpactTransferKindDocument.SOURCE_CALL_ENTRY || !frame.matches(operation.frame)) return false
        return operation.position == ImpactFormalPositionDocument.Receiver(ImpactReceiverKindDocument.EXTENSION)
    }

    private fun ImpactCompilerTransferDocument.returns(
        frame: NativePacketQueryFrameOracle,
        entry: ImpactCompilerTransferDocument,
    ): Boolean {
        val operation = helperOperation() as? ImpactSourceOperationDocument.Return ?: return false
        if (kind != ImpactTransferKindDocument.SOURCE_CALL_RETURN || !frame.matches(operation.frame)) return false
        if (source.enclosing != frame.callee.enclosing || target.enclosing != frame.enclosing) return false
        if (target.range != frame.invocation.range || source.valuePath != target.valuePath) return false
        return source.callStack.hasSameCallStackIdentity(entry.target.callStack) &&
            target.callStack.hasSameCallStackIdentity(entry.source.callStack)
    }

    companion object {
        suspend fun from(source: NativePacketQuerySource): NativePacketQueryHelperOracle? {
            if (source.variant != NativePacketQueryVariant.V2) return null
            return NativePacketQueryHelperOracle(frame(source, "toWireRequest"), frame(source, "toClientResponse"))
        }

        private suspend fun frame(source: NativePacketQuerySource, helper: String): NativePacketQueryFrameOracle =
            NativePacketQueryFrameOracle(
                enclosing = source.reference(source.declaration("BackendClient", "fetch")),
                invocation =
                    ImpactInvocationReferenceDocument(
                        source.invocation("BackendClient", "fetch", helper),
                        source.reference(source.declaration("", helper)),
                    ),
                callee = ImpactExecutionReferenceDocument(source.reference(source.declaration("", helper))),
                ownership = null,
            )
    }
}

private fun ImpactCompilerTransferDocument.helperOperation() =
    (evidence as? ImpactTransferEvidenceDocument.Source)?.operation
