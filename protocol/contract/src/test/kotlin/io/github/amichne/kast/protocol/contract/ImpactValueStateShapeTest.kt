package io.github.amichne.kast.protocol.contract

import io.github.amichne.kast.kernel.Refinement
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ImpactValueStateShapeTest {
    private val basis =
        ImpactSemanticBasisDocument.Published(text("/workspace"), ImpactEvidenceRevisionDocument.parse(7).value())
    private val caller = declaration("Caller.kt", 0, 100, 'a')
    private val callee = declaration("Mapper.kt", 0, 100, 'b')
    private val field = declaration("Values.kt", 10, 20, 'c')
    private val sibling = declaration("Values.kt", 30, 40, 'd')
    private val invocation = ImpactInvocationReferenceDocument(range(30, 50), callee)
    private val frame = ImpactCallFrameDocument(caller, invocation, ImpactExecutionReferenceDocument(callee))
    private val parameter = ImpactFormalParameterDocument(callee, offset(0), text("request"), range(10, 20))
    private val position = ImpactFormalPositionDocument.Parameter(parameter)
    private val path: BoundedProtocolList<ImpactValuePathSegmentDocument> =
        bounded(listOf(ImpactValuePathSegmentDocument.Field(field)))

    @Test
    fun `source entry and matched return preserve field and exact invocation context`() {
        val argument =
            ImpactValueSiteReferenceDocument(
                caller,
                range(35, 40),
                ImpactValueRoleDocument.Argument(invocation, offset(0)),
                valuePath = path,
            )
        val formal =
            ImpactValueSiteReferenceDocument(
                callee,
                parameter.range,
                ImpactValueRoleDocument.Formal(position),
                valuePath = path,
                callStack = bounded(listOf(frame)),
            )
        assertTrue(
            transfer(
                    argument,
                    formal,
                    ImpactTransferKindDocument.SOURCE_CALL_ENTRY,
                    ImpactSourceOperationDocument.Enter(frame, position),
                )
                .admitsEvidence()
        )
        val returned = formal.copy(range = range(80, 90), role = ImpactValueRoleDocument.Return)
        val result = argument.copy(range = invocation.range, role = ImpactValueRoleDocument.ExpressionResult)
        val edge =
            transfer(
                returned,
                result,
                ImpactTransferKindDocument.SOURCE_CALL_RETURN,
                ImpactSourceOperationDocument.Return(frame),
            )
        assertTrue(edge.admitsEvidence())
        val otherCall = invocation.copy(range = range(60, 80))
        val otherFrame = frame.copy(invocation = ImpactCallOccurrenceDocument.Declared(otherCall))
        assertFalse(
            transfer(
                    returned,
                    result.copy(range = otherCall.range),
                    edge.kind,
                    ImpactSourceOperationDocument.Return(otherFrame),
                )
                .admitsEvidence()
        )
        assertFormalShape(formal)
    }

    private fun assertFormalShape(formal: ImpactValueSiteReferenceDocument) {
        val encoded = Json.encodeToJsonElement(ImpactValueSiteReferenceDocument.serializer(), formal).jsonObject
        assertEquals(
            setOf("enclosing", "range", "role", "executionOwner", "valuePath", "callStack", "predicates"),
            encoded.keys,
        )
        assertEquals("FORMAL", encoded.getValue("role").jsonObject.getValue("type").jsonPrimitive.content)
        assertEquals(
            "request",
            encoded
                .getValue("role")
                .jsonObject
                .getValue("position")
                .jsonObject
                .getValue("parameter")
                .jsonObject
                .getValue("name")
                .jsonPrimitive
                .content,
        )
        assertEquals(1, encoded.getValue("valuePath").jsonArray.size)
        assertEquals(1, encoded.getValue("callStack").jsonArray.size)
    }

    @Test
    fun `field reads reject another field and a field from another semantic basis`() {
        val source =
            ImpactValueSiteReferenceDocument(
                caller,
                range(10, 11),
                ImpactValueRoleDocument.ExpressionResult,
                valuePath = path,
            )
        val target = source.copy(range = range(12, 13), valuePath = EmptyImpactValuePath)
        assertTrue(
            transfer(
                    source,
                    target,
                    ImpactTransferKindDocument.FIELD_READ,
                    ImpactSourceOperationDocument.ReadField(field),
                )
                .admitsEvidence()
        )
        assertFalse(
            transfer(
                    source,
                    target,
                    ImpactTransferKindDocument.FIELD_READ,
                    ImpactSourceOperationDocument.ReadField(sibling),
                )
                .admitsEvidence()
        )
        val foreign =
            field.copy(
                basis =
                    ImpactSemanticBasisDocument.Published(
                        text("/workspace"),
                        ImpactEvidenceRevisionDocument.parse(8).value(),
                    )
            )
        assertFalse(
            transfer(
                    source.copy(valuePath = EmptyImpactValuePath),
                    target,
                    ImpactTransferKindDocument.FIELD_READ,
                    ImpactSourceOperationDocument.ReadField(foreign),
                )
                .admitsEvidence()
        )
        assertFalse(
            source
                .copy(valuePath = bounded(listOf(ImpactValuePathSegmentDocument.Field(foreign))))
                .admitsExecutionOwner()
        )
    }

    @Test
    fun `constructor storage names its declared formal and rejects a sibling field`() {
        val constructorField =
            callee.copy(
                range = parameter.range,
                compilerIdentity = text("canonical-signature-sha256-v1|" + "e".repeat(64)),
            )
        val storage = ImpactConstructorStorageDocument(callee, parameter, constructorField)
        val source =
            ImpactValueSiteReferenceDocument(
                caller,
                range(35, 40),
                ImpactValueRoleDocument.Argument(invocation, offset(0)),
            )
        val target =
            source.copy(
                range = invocation.range,
                role = ImpactValueRoleDocument.ExpressionResult,
                valuePath = bounded(listOf(ImpactValuePathSegmentDocument.Field(constructorField))),
            )
        assertTrue(
            transfer(
                    source,
                    target,
                    ImpactTransferKindDocument.CONSTRUCTOR_FIELD,
                    ImpactSourceOperationDocument.ConstructField(invocation, storage),
                )
                .admitsEvidence()
        )
        assertFalse(
            transfer(
                    source,
                    target,
                    ImpactTransferKindDocument.CONSTRUCTOR_FIELD,
                    ImpactSourceOperationDocument.ConstructField(invocation, storage.copy(field = sibling)),
                )
                .admitsEvidence()
        )
        assertFalse(
            ImpactCompilerTransferDocument(source, target, ImpactTransferKindDocument.CONSTRUCTOR_FIELD)
                .admitsEvidence()
        )
    }

    @Test
    fun `bounded state rejects excess depth repeated call frames and foreign callee basis`() {
        val source = ImpactValueSiteReferenceDocument(caller, range(10, 11), ImpactValueRoleDocument.ExpressionResult)
        assertFalse(
            source
                .copy(valuePath = bounded(List(9) { ImpactValuePathSegmentDocument.AnyElement }))
                .admitsExecutionOwner()
        )
        assertFalse(source.copy(callStack = bounded(listOf(frame, frame))).admitsExecutionOwner())
        val foreign =
            callee.copy(
                basis =
                    ImpactSemanticBasisDocument.Published(
                        text("/workspace"),
                        ImpactEvidenceRevisionDocument.parse(8).value(),
                    )
            )
        assertFalse(
            source
                .copy(callStack = bounded(listOf(frame.copy(callee = ImpactExecutionReferenceDocument(foreign)))))
                .admitsExecutionOwner()
        )
    }

    private fun transfer(
        source: ImpactValueSiteReferenceDocument,
        target: ImpactValueSiteReferenceDocument,
        kind: ImpactTransferKindDocument,
        operation: ImpactSourceOperationDocument,
    ) = ImpactCompilerTransferDocument(source, target, kind, ImpactTransferEvidenceDocument.Source(operation))

    private fun declaration(file: String, start: Int, end: Int, hash: Char) =
        ImpactDeclarationReferenceDocument(
            basis,
            text("/workspace/$file"),
            range(start, end),
            text("canonical-signature-sha256-v1|" + hash.toString().repeat(64)),
        )

    private fun text(value: String) = ProtocolText.parse(value).value()

    private fun offset(value: Int) = ProtocolOffset.parse(value).value()

    private fun range(start: Int, end: Int) = ImpactSourceRangeDocument(offset(start), offset(end))

    private fun <T> bounded(values: List<T>) = BoundedProtocolList.create(values).value()
}

private fun <T> Refinement<T, *>.value(): T = (this as Refinement.Refined).value
