package io.github.amichne.kast.protocol.contract

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ImpactCallbackArgumentEntryTest {
    @Test
    fun `callback actual entry encodes supplier invocation formal and exact argument`() {
        val transfer = entry()
        assertTrue(transfer.admitsEvidence())
        val encoded = Json.encodeToString(transfer)
        val operation =
            Json.parseToJsonElement(encoded).jsonObject.getValue("evidence").jsonObject.getValue("operation").jsonObject
        assertEquals(setOf("type", "invocation", "supplierFrame", "parameter", "argumentRange"), operation.keys)
        assertEquals("CALLBACK_ARGUMENT_ENTRY", operation.getValue("type").jsonPrimitive.content)
        assertEquals("first", operation.getValue("parameter").jsonObject.getValue("name").jsonPrimitive.content)
        assertEquals("131", operation.getValue("argumentRange").jsonObject.getValue("start").jsonPrimitive.content)
        val decoded = Json.decodeFromString<ImpactCompilerTransferDocument>(encoded)
        assertEquals(transfer, decoded)
        assertTrue(decoded.admitsEvidence())
        assertEquals(decoded.source.callStack, decoded.target.callStack)
    }

    @Test
    fun `entry rejects swapped actual formal supplier body and erased state after wire decode`() {
        val transfer = entry()
        val f = ImpactFindingFixture()
        val operation =
            (transfer.evidence as ImpactTransferEvidenceDocument.Source).operation
                as ImpactSourceOperationDocument.CallbackArgumentEntry
        val supplied = (operation.supplierFrame.invocation as ImpactCallOccurrenceDocument.Declared).invocation
        val wrong =
            listOf(
                    operation.copy(argumentRange = range(f, 134, 136)),
                    operation.copy(parameter = operation.parameter.copy(index = f.offset(1), name = f.text("second"))),
                    operation.copy(
                        supplierFrame =
                            operation.supplierFrame.copy(
                                invocation =
                                    ImpactCallOccurrenceDocument.Declared(supplied.copy(range = range(f, 21, 81)))
                            )
                    ),
                    operation.copy(
                        invocation =
                            operation.invocation.copy(body = operation.invocation.body.copy(range = range(f, 41, 71)))
                    ),
                )
                .map { transfer.copy(evidence = ImpactTransferEvidenceDocument.Source(it)) } +
                listOf(
                    transfer.copy(evidence = ImpactTransferEvidenceDocument.Direct),
                    transfer.copy(target = transfer.target.copy(callStack = EmptyImpactCallStack)),
                    transfer.copy(target = transfer.target.copy(valuePath = EmptyImpactValuePath)),
                    transfer.copy(
                        source = transfer.source.copy(callStack = EmptyImpactCallStack),
                        target = transfer.target.copy(callStack = EmptyImpactCallStack),
                    ),
                )
        for (candidate in wrong) {
            assertFalse(
                Json.decodeFromString<ImpactCompilerTransferDocument>(Json.encodeToString(candidate)).admitsEvidence()
            )
        }
    }

    @Test
    fun `repeated callback executions encode exact selections and reject crossed returns`() {
        val returns = listOf(repeatedReturn(0), repeatedReturn(1))
        assertNotEquals(returns[0].source, returns[1].source)
        for (index in returns.indices) {
            val transfer = returns[index]
            val encoded = Json.encodeToString(transfer)
            val execution =
                Json.parseToJsonElement(encoded)
                    .jsonObject
                    .getValue("source")
                    .jsonObject
                    .getValue("executionOwner")
                    .jsonObject
            assertEquals("EXACT", execution.getValue("selection").jsonObject.getValue("type").jsonPrimitive.content)
            val expectedStart = if (index == 0) "130" else "150"
            assertEquals(
                expectedStart,
                execution
                    .getValue("selection")
                    .jsonObject
                    .getValue("invocation")
                    .jsonObject
                    .getValue("invocation")
                    .jsonObject
                    .getValue("start")
                    .jsonPrimitive
                    .content,
            )
            val decoded = Json.decodeFromString<ImpactCompilerTransferDocument>(encoded)
            assertEquals(transfer, decoded)
            assertTrue(decoded.admitsEvidence())
            assertFalse(decoded.copy(source = returns[1 - index].source).admitsEvidence())
        }
    }

    @Test
    fun `unselected repeated and forged exact selection fail closed while unique wire stays compatible`() {
        val transfer = repeatedReturn(0)
        val execution = transfer.source.executionOwner as ImpactExecutionOwnerDocument.Callback
        val selected = (execution.selection as ImpactCallbackSelectionDocument.Exact).invocation
        val f = ImpactFindingFixture()
        for (selection in
            listOf(
                ImpactCallbackSelectionDocument.Unique,
                ImpactCallbackSelectionDocument.Exact(selected.copy(invocation = range(f, 160, 167))),
                ImpactCallbackSelectionDocument.Exact(
                    selected.copy(supply = selected.supply.copy(range = range(f, 21, 81)))
                ),
            )) {
            val forged =
                transfer.copy(source = transfer.source.copy(executionOwner = execution.copy(selection = selection)))
            assertFalse(
                Json.decodeFromString<ImpactCompilerTransferDocument>(Json.encodeToString(forged)).admitsEvidence()
            )
        }
        val unique = entry()
        val encoded =
            Json.parseToJsonElement(Json.encodeToString(unique))
                .jsonObject
                .getValue("target")
                .jsonObject
                .getValue("executionOwner")
                .jsonObject
        assertFalse("selection" in encoded)
        assertTrue(unique.admitsEvidence())
    }

    private fun repeatedReturn(index: Int): ImpactCompilerTransferDocument {
        val f = ImpactFindingFixture()
        val entry = entry()
        val operation =
            (entry.evidence as ImpactTransferEvidenceDocument.Source).operation
                as ImpactSourceOperationDocument.CallbackArgumentEntry
        val invocations = listOf(operation.invocation, operation.invocation.copy(invocation = range(f, 150, 157)))
        val selected = invocations[index]
        val execution =
            ImpactExecutionOwnerDocument.Callback(
                selected.body,
                f.bounded(invocations),
                ImpactCallbackSelectionDocument.Exact(selected),
            )
        val source =
            entry.target.copy(
                range = range(f, 50, 60),
                role = ImpactValueRoleDocument.Return,
                executionOwner = execution,
            )
        val target = entry.source.copy(range = selected.invocation)
        return ImpactCompilerTransferDocument(
            source,
            target,
            ImpactTransferKindDocument.SOURCE_CALL_RETURN,
            ImpactTransferEvidenceDocument.Source(
                ImpactSourceOperationDocument.CallbackReturn(selected, operation.supplierFrame)
            ),
        )
    }

    private fun entry(): ImpactCompilerTransferDocument {
        val f = ImpactFindingFixture()
        val host = f.declaration
        val wrapper =
            host.copy(
                range = range(f, 100, 180),
                compilerIdentity = f.text("canonical-signature-sha256-v1|" + "b".repeat(64)),
            )
        val body = ImpactCapturedBodyDocument(host.file, range(f, 40, 70), f.text("anonymous-input"))
        val invocation = invocation(f = f, host = host, wrapper = wrapper, body = body)
        val frame = ImpactCallFrameDocument(host, invocation.supply, ImpactExecutionReferenceDocument(wrapper))
        val execution = ImpactExecutionOwnerDocument.Callback(body, f.bounded(listOf(invocation)))
        val parameter = ImpactFormalParameterDocument(host, f.offset(0), f.text("first"), range(f, 41, 45), execution)
        val path = f.bounded<ImpactValuePathSegmentDocument>(listOf(ImpactValuePathSegmentDocument.Element))
        val stack = f.bounded(listOf(frame))
        val source =
            ImpactValueSiteReferenceDocument(
                wrapper,
                range(f, 131, 133),
                ImpactValueRoleDocument.ExpressionResult,
                valuePath = path,
                callStack = stack,
            )
        val target =
            ImpactValueSiteReferenceDocument(
                host,
                parameter.range,
                ImpactValueRoleDocument.Formal(ImpactFormalPositionDocument.Parameter(parameter)),
                execution,
                path,
                stack,
            )
        return ImpactCompilerTransferDocument(
            source,
            target,
            ImpactTransferKindDocument.SOURCE_CALL_ENTRY,
            ImpactTransferEvidenceDocument.Source(
                ImpactSourceOperationDocument.CallbackArgumentEntry(
                    invocation,
                    frame,
                    parameter,
                    source.range,
                )
            ),
        )
    }

    private fun invocation(
        f: ImpactFindingFixture,
        host: ImpactDeclarationReferenceDocument,
        wrapper: ImpactDeclarationReferenceDocument,
        body: ImpactCapturedBodyDocument,
    ): ImpactCaptureInvocationDocument =
        ImpactCaptureInvocationDocument(
            body,
            host,
            ImpactInvocationReferenceDocument(range(f, 20, 80), wrapper),
            ImpactCapturedBodyDocument(host.file, host.range, host.compilerIdentity),
            range(f, 110, 120),
            f.offset(0),
            range(f, 130, 137),
            ImpactCapturedBodyDocument(wrapper.file, wrapper.range, wrapper.compilerIdentity),
        )

    private fun range(f: ImpactFindingFixture, start: Int, end: Int) =
        ImpactSourceRangeDocument(f.offset(start), f.offset(end))
}
