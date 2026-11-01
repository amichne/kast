package io.github.amichne.kast.protocol.contract

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ImpactCallbackReturnTest {
    @Test
    fun `callback return preserves invocation field and supplier through encoding`() {
        val transfer = callbackReturn()
        assertTrue(transfer.admitsEvidence())
        val encoded = Json.encodeToString(transfer)
        val operation =
            Json.parseToJsonElement(encoded).jsonObject.getValue("evidence").jsonObject.getValue("operation").jsonObject
        assertEquals(setOf("type", "invocation", "frame"), operation.keys)
        assertEquals("CALLBACK_RETURN", operation.getValue("type").jsonPrimitive.content)
        assertEquals(
            "anonymous-inner",
            operation
                .getValue("invocation")
                .jsonObject
                .getValue("body")
                .jsonObject
                .getValue("compilerIdentity")
                .jsonPrimitive
                .content,
        )
        val decoded = Json.decodeFromString<ImpactCompilerTransferDocument>(encoded)
        assertEquals(transfer, decoded)
        assertTrue(decoded.admitsEvidence())
        assertEquals(transfer.source.valuePath, decoded.target.valuePath)
        val expectedConditions =
            Json.parseToJsonElement(
                requireNotNull(javaClass.getResource("/impact-conditions.expected.json")).readText()
            )
        val document = Json.parseToJsonElement(encoded).jsonObject
        assertEquals(expectedConditions, document.getValue("source").jsonObject.getValue("conditions"))
        assertEquals(expectedConditions, document.getValue("target").jsonObject.getValue("conditions"))
    }

    @Test
    fun `callback return preserves an already active exact supplier frame`() {
        val transfer = callbackReturn()
        val active = transfer.copy(source = transfer.source.copy(callStack = transfer.target.callStack))
        val decoded = Json.decodeFromString<ImpactCompilerTransferDocument>(Json.encodeToString(active))
        assertTrue(decoded.admitsEvidence())
        assertEquals(active.source.callStack, decoded.target.callStack)
        assertEquals(1, decoded.target.callStack.values.size)
        assertFalse(decoded.copy(target = decoded.target.copy(callStack = EmptyImpactCallStack)).admitsEvidence())
    }

    @Test
    fun `callback return rejects wrong invocation supplier frame and lost field`() {
        val transfer = callbackReturn()
        val fixture = ImpactFindingFixture()
        val evidence = transfer.evidence as ImpactTransferEvidenceDocument.Source
        val operation = evidence.operation as ImpactSourceOperationDocument.CallbackReturn
        val supplied = (operation.frame.invocation as ImpactCallOccurrenceDocument.Declared).invocation
        val wrongSupplier =
            operation.copy(
                frame =
                    operation.frame.copy(
                        invocation =
                            ImpactCallOccurrenceDocument.Declared(supplied.copy(range = range(fixture, 21, 81)))
                    )
            )
        val invalid =
            listOf(
                transfer.copy(target = transfer.target.copy(range = range(fixture, 132, 139))),
                transfer.copy(target = transfer.target.copy(valuePath = EmptyImpactValuePath)),
                transfer.copy(target = transfer.target.copy(callStack = EmptyImpactCallStack)),
                transfer.copy(target = transfer.target.copy(conditions = EmptyImpactConditions)),
                transfer.copy(evidence = ImpactTransferEvidenceDocument.Source(wrongSupplier)),
                transfer.copy(evidence = ImpactTransferEvidenceDocument.Direct),
            )
        for (candidate in invalid) {
            val decoded = Json.decodeFromString<ImpactCompilerTransferDocument>(Json.encodeToString(candidate))
            assertFalse(decoded.admitsEvidence())
        }
    }

    private fun callbackReturn(): ImpactCompilerTransferDocument {
        val fixture = ImpactFindingFixture()
        val host = fixture.declaration
        val wrapper =
            host.copy(
                range = range(fixture, 100, 180),
                compilerIdentity = fixture.text("canonical-signature-sha256-v1|" + "b".repeat(64)),
            )
        val body = ImpactCapturedBodyDocument(host.file, range(fixture, 40, 70), fixture.text("anonymous-inner"))
        val invocation =
            ImpactCaptureInvocationDocument(
                body = body,
                enclosing = host,
                supply = ImpactInvocationReferenceDocument(range(fixture, 20, 80), wrapper),
                supplyOwner = ImpactCapturedBodyDocument(host.file, host.range, host.compilerIdentity),
                parameter = range(fixture, 110, 120),
                position = fixture.offset(0),
                invocation = range(fixture, 130, 137),
                invocationOwner = ImpactCapturedBodyDocument(wrapper.file, wrapper.range, wrapper.compilerIdentity),
            )
        val frame = ImpactCallFrameDocument(host, invocation.supply, ImpactExecutionReferenceDocument(wrapper))
        val field = resultField(fixture)
        val conditions = receiverConditions(fixture)
        val source =
            ImpactValueSiteReferenceDocument(
                enclosing = host,
                range = range(fixture, 50, 60),
                role = ImpactValueRoleDocument.Return,
                executionOwner = ImpactExecutionOwnerDocument.Callback(body, fixture.bounded(listOf(invocation))),
                valuePath = field,
                conditions = conditions,
            )
        val target =
            ImpactValueSiteReferenceDocument(
                enclosing = wrapper,
                range = invocation.invocation,
                role = ImpactValueRoleDocument.ExpressionResult,
                valuePath = field,
                callStack = fixture.bounded(listOf(frame)),
                conditions = conditions,
            )
        return ImpactCompilerTransferDocument(
            source = source,
            target = target,
            kind = ImpactTransferKindDocument.SOURCE_CALL_RETURN,
            evidence =
                ImpactTransferEvidenceDocument.Source(ImpactSourceOperationDocument.CallbackReturn(invocation, frame)),
        )
    }

    private fun receiverConditions(fixture: ImpactFindingFixture) =
        fixture.bounded<ImpactValueConditionDocument>(
            listOf(
                ImpactValueConditionDocument.StableReceiver(
                    ImpactValueConditionOriginDocument(
                        ImpactExecutionReferenceDocument(fixture.declaration),
                        fixture.producer.range,
                        ImpactValueRoleDocument.Formal(
                            ImpactFormalPositionDocument.Receiver(ImpactReceiverKindDocument.EXTENSION)
                        ),
                    ),
                    fixture.destination.range,
                    ImpactReceiverKindDocument.EXTENSION,
                    fixture.text("kotlin/Any?"),
                    fixture.text("fixture/Request"),
                )
            )
        )

    private fun resultField(fixture: ImpactFindingFixture) =
        fixture.bounded<ImpactValuePathSegmentDocument>(
            listOf(
                ImpactValuePathSegmentDocument.Field(
                    fixture.declaration.copy(
                        range = range(fixture, 200, 210),
                        compilerIdentity = fixture.text("canonical-signature-sha256-v1|" + "c".repeat(64)),
                    )
                )
            )
        )

    private fun range(fixture: ImpactFindingFixture, start: Int, end: Int) =
        ImpactSourceRangeDocument(fixture.offset(start), fixture.offset(end))
}
