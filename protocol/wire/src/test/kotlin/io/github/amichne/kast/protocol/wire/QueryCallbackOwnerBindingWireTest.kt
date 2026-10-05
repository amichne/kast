package io.github.amichne.kast.protocol.wire

import io.github.amichne.kast.protocol.contract.ImpactDeclarationReferenceDocument
import io.github.amichne.kast.protocol.contract.ImpactInvocationReferenceDocument
import io.github.amichne.kast.protocol.contract.ImpactSourceRangeDocument
import io.github.amichne.kast.protocol.contract.QueryCallbackCallableDocument
import io.github.amichne.kast.protocol.contract.QueryCallbackFlowCauseDocument
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class QueryCallbackOwnerBindingWireTest {
    private val fixture = QueryCallbackWireFixture()

    @Test
    fun `body supply wire has closed independently authored invocation and stored shapes`() {
        val supplied = QueryCallbackBodySupplyWireDocument.Invocation(occurrence("Boundary.kt", 9, 26))
        assertEquals(
            expected("callback-body-supply-invocation.json"),
            wireJson.encodeToJsonElement(QueryCallbackBodySupplyWireDocument.serializer(), supplied),
        )
        assertEquals(
            expected("callback-body-supply-stored.json"),
            wireJson.encodeToJsonElement(
                QueryCallbackBodySupplyWireDocument.serializer(),
                QueryCallbackBodySupplyWireDocument.Stored,
            ),
        )
        assertThrows(SerializationException::class.java) {
            wireJson.decodeFromString(
                QueryCallbackBodySupplyWireDocument.serializer(),
                expected("callback-body-supply-unknown.json").toString(),
            )
        }
    }

    @Test
    fun `nested owner binding retains exact supplying call formal mapping and incomplete activation`() {
        val wire = withOwnerBinding()
        val admitted = wire.toContract()
        assertTrue(admitted is WireDocumentConversion.Converted)
        val raw = wireJson.encodeToJsonElement(QueryCallbackObservationWireDocument.serializer(), wire).jsonObject
        val flow = raw.getValue("flow").jsonObject
        val owner = flow.getValue("owner_bindings").jsonArray.single().jsonObject
        assertEquals("INVOCATION", owner.getValue("supply").jsonObject.getValue("type").jsonPrimitive.content)
        assertEquals("BOUND", owner.getValue("binding").jsonObject.getValue("type").jsonPrimitive.content)
        assertEquals(
            "9",
            owner
                .getValue("supply")
                .jsonObject
                .getValue("occurrence")
                .jsonObject
                .getValue("range")
                .jsonObject
                .getValue("startInclusive")
                .jsonPrimitive
                .content,
        )
        assertEquals(
            listOf("NESTED_CALLBACK_EXECUTION", "OUTSIDE_DOMAIN"),
            owner.getValue("obligations").jsonArray.map { it.jsonPrimitive.content },
        )
    }

    @Test
    fun `unknown and stored owner supplies retain their finite mapping obligations`() {
        val wire = withOwnerBinding()
        val flow = wire.flow as QueryCallbackFlowWireDocument.Observed
        val owner = flow.ownerBindings.single()
        val unresolved = QueryCallbackFlowCauseDocument.UNRESOLVED_ARGUMENT_MAPPING
        val stored = QueryCallbackFlowCauseDocument.STORED_CALLBACK
        val unknownOwner =
            owner.copy(
                binding = QueryCallbackBindingWireDocument.Unavailable(unresolved),
                obligations = listOf(QueryCallbackFlowCauseDocument.NESTED_CALLBACK_EXECUTION, unresolved),
            )
        val storedOwner =
            owner.copy(
                supply = QueryCallbackBodySupplyWireDocument.Stored,
                binding = QueryCallbackBindingWireDocument.Unavailable(stored),
                obligations = listOf(QueryCallbackFlowCauseDocument.NESTED_CALLBACK_EXECUTION, stored),
            )
        for (qualified in listOf(unknownOwner, storedOwner)) {
            val qualifiedWire =
                wire.copy(flow = flow.copy(ownerBindings = listOf(qualified), obligations = qualified.obligations))
            val encoded = wireJson.encodeToString(QueryCallbackObservationWireDocument.serializer(), qualifiedWire)
            val decoded = wireJson.decodeFromString(QueryCallbackObservationWireDocument.serializer(), encoded)
            assertTrue(decoded.toContract() is WireDocumentConversion.Converted)
            assertEquals(qualified, (decoded.flow as QueryCallbackFlowWireDocument.Observed).ownerBindings.single())
        }
    }

    @Test
    fun `returned and unsupported supplies cannot become stored callback evidence`() {
        val wire = withOwnerBinding()
        val flow = wire.flow as QueryCallbackFlowWireDocument.Observed
        val original = flow.ownerBindings.single()
        val cases =
            listOf(
                QueryCallbackBodySupplyWireDocument.Returned(occurrence("Boundary.kt", 9, 26)) to
                    QueryCallbackFlowCauseDocument.RETURNED_CALLBACK,
                QueryCallbackBodySupplyWireDocument.Unsupported to
                    QueryCallbackFlowCauseDocument.UNSUPPORTED_CALLBACK_SUPPLY,
                original.supply to QueryCallbackFlowCauseDocument.UNSUPPORTED_CALLBACK_SUPPLY,
            )
        for ((supply, cause) in cases) {
            val owner =
                original.copy(
                    supply = supply,
                    binding = QueryCallbackBindingWireDocument.Unavailable(cause),
                    obligations = listOf(QueryCallbackFlowCauseDocument.NESTED_CALLBACK_EXECUTION, cause),
                )
            val suppliedWire =
                wire.copy(flow = flow.copy(ownerBindings = listOf(owner), obligations = owner.obligations))
            val encoded = wireJson.encodeToString(QueryCallbackObservationWireDocument.serializer(), suppliedWire)
            assertTrue(
                wireJson.decodeFromString(QueryCallbackObservationWireDocument.serializer(), encoded).toContract()
                    is WireDocumentConversion.Converted
            )
            val falseStorage =
                suppliedWire.copy(
                    flow =
                        (suppliedWire.flow as QueryCallbackFlowWireDocument.Observed).copy(
                            ownerBindings = listOf(owner.copy(supply = QueryCallbackBodySupplyWireDocument.Stored))
                        )
                )
            assertEquals(WireDocumentConversion.Rejected, encodedContract(falseStorage))
        }
    }

    @Test
    fun `decoded owner proof rejects unrelated duplicate truncated and obligation erased supplies`() {
        val wire = withOwnerBinding()
        val flow = wire.flow as QueryCallbackFlowWireDocument.Observed
        val owner = flow.ownerBindings.single()
        val supply = owner.supply as QueryCallbackBodySupplyWireDocument.Invocation
        val bound = owner.binding as QueryCallbackBindingWireDocument.Bound
        val wrongs =
            listOf(
                withOwnerBinding("jar://stdlib/readAction.kt").flow as QueryCallbackFlowWireDocument.Observed,
                flow.copy(ownerBindings = listOf(owner, owner)),
                flow.copy(ownerBindings = listOf(owner.copy(body = anonymous("Other.kt", 10, 25)))),
                flow.copy(
                    ownerBindings =
                        listOf(owner.copy(supply = supply.copy(occurrence = occurrence("Boundary.kt", 20, 26))))
                ),
                flow.copy(ownerBindings = listOf(owner.copy(obligations = emptyList()))),
                flow.copy(obligations = listOf(QueryCallbackFlowCauseDocument.NESTED_CALLBACK_EXECUTION)),
                flow.copy(ownerBindings = listOf(owner.copy(binding = bound.copy(position = 1)))),
                flow.copy(
                    ownerBindings = listOf(owner.copy(binding = bound.copy(parameter = occurrence("Other.kt", 2, 9))))
                ),
                flow.copy(
                    ownerBindings =
                        listOf(
                            owner.copy(binding = bound.copy(invocationOccurrence = occurrence("Boundary.kt", 8, 26)))
                        )
                ),
            )
        for (wrong in wrongs) {
            val encoded =
                wireJson.encodeToString(QueryCallbackObservationWireDocument.serializer(), wire.copy(flow = wrong))
            val decoded = wireJson.decodeFromString(QueryCallbackObservationWireDocument.serializer(), encoded)
            assertEquals(WireDocumentConversion.Rejected, decoded.toContract())
        }
    }

    private fun withOwnerBinding(targetFile: String = "Supplier.kt"): QueryCallbackObservationWireDocument {
        val wire = fixture.callbackDocument().toWireDocument()
        val flow = wire.flow as QueryCallbackFlowWireDocument.Observed
        val body = anonymous("Boundary.kt", 10, 25)
        val supplied = occurrence("Boundary.kt", 9, 26)
        val targetDocument =
            fixture.callable(targetFile, 0, 30, "sample.readAction", listOf("kotlin.Function0<kotlin.Unit>"))
        val supplyingRange = fixture.occurrence("Boundary.kt", 9, 26).range
        val targetRange = targetDocument.declaration.range
        val call =
            ImpactInvocationReferenceDocument(
                ImpactSourceRangeDocument(supplyingRange.startInclusive, supplyingRange.endExclusive),
                ImpactDeclarationReferenceDocument(
                    flow.basis,
                    targetDocument.compilerTarget.file,
                    ImpactSourceRangeDocument(targetRange.startInclusive, targetRange.endExclusive),
                    targetDocument.compilerTarget.compilerEvidence.identity,
                ),
            )
        val binding =
            QueryCallbackBindingWireDocument.Bound(
                call,
                supplied,
                QueryCallbackBodyWireDocument.Named((flow.binding as QueryCallbackBindingWireDocument.Bound).callable),
                callable(targetDocument),
                0,
                occurrence(targetFile, 2, 9),
            )
        val obligations =
            listOf(
                QueryCallbackFlowCauseDocument.NESTED_CALLBACK_EXECUTION,
                QueryCallbackFlowCauseDocument.OUTSIDE_DOMAIN,
            )
        val owner =
            QueryCallbackBodyBindingWireDocument(
                body,
                QueryCallbackBodySupplyWireDocument.Invocation(supplied),
                binding,
                obligations,
            )
        return wire.copy(
            flow =
                flow.copy(
                    invocations = listOf(flow.invocations.single().copy(owner = body)),
                    obligations = obligations,
                    ownerBindings = listOf(owner),
                )
        )
    }

    private fun anonymous(file: String, start: Int, end: Int) =
        QueryCallbackBodyWireDocument.Anonymous(
            occurrence(file, start, end),
            fixture.signature("anonymous@$file#$start:$end", emptyList()).toWireDocument(),
        )

    private fun occurrence(file: String, start: Int, end: Int): RelationOccurrenceWireDocument {
        val value = fixture.occurrence(file, start, end)
        return RelationOccurrenceWireDocument(
            value.candidateSelector.value,
            value.file.value,
            value.range.toWireDocument(),
        )
    }

    private fun callable(value: QueryCallbackCallableDocument) =
        QueryCallbackCallableWireDocument(
            RelationOccurrenceWireDocument(
                value.declaration.candidateSelector.value,
                value.declaration.file.value,
                value.declaration.range.toWireDocument(),
            ),
            QueryExcludedCompilerTargetWireDocument(
                value.compilerTarget.file.value,
                value.compilerTarget.range.toWireDocument(),
                value.compilerTarget.name.value,
                value.compilerTarget.kind.toWireDocument(),
                value.compilerTarget.compilerEvidence.toWireDocument(),
            ),
        )

    private fun encodedContract(wire: QueryCallbackObservationWireDocument) =
        wireJson
            .decodeFromString(
                QueryCallbackObservationWireDocument.serializer(),
                wireJson.encodeToString(QueryCallbackObservationWireDocument.serializer(), wire),
            )
            .toContract()

    private fun expected(name: String) =
        wireJson.parseToJsonElement(checkNotNull(javaClass.getResource("/query/$name")).readText())
}
