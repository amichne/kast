@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package io.github.amichne.kast.protocol.wire

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.BoundedProtocolList
import io.github.amichne.kast.protocol.contract.CompilerReceiverDocument
import io.github.amichne.kast.protocol.contract.CompilerSignatureDocument
import io.github.amichne.kast.protocol.contract.CompilerSymbolEvidenceDocument
import io.github.amichne.kast.protocol.contract.ImpactEvidenceRevisionDocument
import io.github.amichne.kast.protocol.contract.ImpactSemanticBasisDocument
import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.protocol.contract.QueryCallbackAccessorKindDocument
import io.github.amichne.kast.protocol.contract.QueryCallbackDependencyInvocationKindDocument
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonClassDiscriminator
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/** Authored sites constrain execution regions and a two-step route independently of production projection. */
class QueryDeferredSupplierExecutionWireTest {
    private val fixture = DeferredSupplierExecutionWireFixture()
    private val assertion = QueryCallbackRefinementWireFixture()

    @Test
    fun `declaration encodes an explicit execution discriminator`() {
        val wire = fixture.base()
        assertion.assertAdmitted(wire)
        val raw = encoded(fixture.binding(wire).execution)
        assertEquals(setOf("type"), raw.keys)
        assertEquals("DECLARATION", raw.getValue("type").jsonPrimitive.content)
    }

    @Test
    fun `getter encodes its exact accessor region`() =
        assertAccessor(QueryCallbackAccessorKindDocument.GETTER, "GETTER")

    @Test
    fun `setter encodes its exact accessor region`() =
        assertAccessor(QueryCallbackAccessorKindDocument.SETTER, "SETTER")

    @Test
    fun `initializer encodes its exact class initialization region`() {
        val execution =
            QueryDeferredSupplierExecutionWireDocument.Initializer(assertion.occurrence(FILE, 1, REGION_END))
        assertion.assertAdmitted(fixture.owned(execution, fixture.classOwner()))
        val raw = encoded(execution)
        assertEquals(setOf("type", "occurrence"), raw.keys)
        assertEquals("INITIALIZER", raw.getValue("type").jsonPrimitive.content)
        assertOccurrence(raw.getValue("occurrence").jsonObject, 1, REGION_END)
    }

    @Test
    fun `invoked encodes both invocation steps and their actual owner chain`() {
        val wire = fixture.invoked()
        assertion.assertAdmitted(wire)
        val raw = encoded(fixture.binding(wire).execution)
        assertEquals(setOf("type", "steps"), raw.keys)
        assertEquals("INVOKED", raw.getValue("type").jsonPrimitive.content)
        val steps = raw.getValue("steps").jsonArray
        assertEquals(2, steps.size)
        steps.forEach { step ->
            assertEquals(setOf("basis", "occurrence", "owner", "body"), step.jsonObject.keys)
            assertBasis(step.jsonObject.getValue("basis").jsonObject)
        }
        val outer = steps[0].jsonObject
        val inner = steps[1].jsonObject
        assertOccurrence(outer.getValue("occurrence").jsonObject, 0, FIRST_CALL_END)
        assertOccurrence(inner.getValue("occurrence").jsonObject, 1, SECOND_CALL_END)
        assertNamedOwner(outer.getValue("owner").jsonObject)
        val outerBody = outer.getValue("body").jsonObject
        val innerOwner = inner.getValue("owner").jsonObject
        assertAnonymousBody(outerBody, 1, FIRST_BODY_END)
        assertAnonymousOwner(innerOwner, 1, FIRST_BODY_END)
        assertAnonymousBody(inner.getValue("body").jsonObject, 2, SECOND_BODY_END)
        assertEquals(outerBody.getValue("occurrence"), innerOwner.getValue("occurrence"))
        assertEquals(outerBody.getValue("compiler_evidence"), innerOwner.getValue("compiler_evidence"))
    }

    @Test
    fun `direct route rejects an empty route wrong parent changed basis and displaced supply`() {
        val wire = fixture.invoked()
        assertion.assertAdmitted(wire)
        val initial = fixture.binding(wire)
        val proof = initial.execution as QueryDeferredSupplierExecutionWireDocument.Invoked
        val first = proof.steps.first()
        val second = proof.steps.last()
        val wrongBasis =
            (first.basis as ImpactSemanticBasisDocument.Published).copy(
                generation = ImpactEvidenceRevisionDocument.parse(2L).wireFixtureRefined()
            )
        val controls =
            listOf(
                initial.copy(execution = proof.copy(steps = emptyList())),
                initial.copy(execution = proof.copy(steps = listOf(first, second.copy(owner = first.owner)))),
                initial.copy(execution = proof.copy(steps = listOf(first, second.copy(basis = wrongBasis)))),
                initial.copy(occurrence = assertion.occurrence(FILE, 0, SUPPLY_END)),
            )
        controls.forEach { wrong -> assertion.assertRejected(fixture.replace(wire, wrong)) }
    }

    @Test
    fun `accessor and initializer reject an occurrence outside the region and the wrong signature`() {
        val accessor =
            QueryDeferredSupplierExecutionWireDocument.Accessor(
                QueryCallbackAccessorKindDocument.GETTER,
                assertion.occurrence(FILE, 1, REGION_END),
            )
        val initializer =
            QueryDeferredSupplierExecutionWireDocument.Initializer(assertion.occurrence(FILE, 1, REGION_END))
        assertion.assertAdmitted(fixture.owned(accessor, fixture.propertyOwner()))
        assertion.assertAdmitted(fixture.owned(initializer, fixture.classOwner()))
        assertion.assertRejected(fixture.owned(accessor, fixture.binding(fixture.base()).owner))
        assertion.assertRejected(fixture.owned(initializer, fixture.propertyOwner()))
        val displaced = assertion.occurrence(FILE, OUTSIDE_REGION_START, REGION_END)
        assertion.assertRejected(fixture.owned(accessor.copy(occurrence = displaced), fixture.propertyOwner()))
        assertion.assertRejected(fixture.owned(initializer.copy(occurrence = displaced), fixture.classOwner()))
        assertion.assertRejected(
            fixture.owned(QueryDeferredSupplierExecutionWireDocument.Declaration, fixture.classOwner())
        )
    }

    @Test
    fun `legacy deferred binding defaults missing execution to declaration and reencodes it explicitly`() {
        val decoded = decodeLegacy(fixture.binding(fixture.base()))
        assertEquals(QueryDeferredSupplierExecutionWireDocument.Declaration, decoded.execution)
        assertion.assertAdmitted(fixture.replace(fixture.base(), decoded))
        val raw = wireJson.encodeToJsonElement(QueryCallbackBindingWireDocument.serializer(), decoded).jsonObject
        val execution = raw.getValue("execution").jsonObject
        assertEquals(setOf("type"), execution.keys)
        assertEquals("DECLARATION", execution.getValue("type").jsonPrimitive.content)
    }

    @Test
    fun `legacy missing execution cannot borrow class initializer authority`() {
        val wire = fixture.owned(QueryDeferredSupplierExecutionWireDocument.Declaration, fixture.classOwner())
        val decoded = decodeLegacy(fixture.binding(wire))
        assertEquals(QueryDeferredSupplierExecutionWireDocument.Declaration, decoded.execution)
        assertion.assertRejected(fixture.replace(wire, decoded))
    }

    @Test
    fun `malformed unknown execution discriminator fails decoding`() {
        val malformed = wireJson.encodeToString(MalformedDeferredExecution.serializer(), MalformedDeferredExecution())
        assertThrows(SerializationException::class.java) {
            wireJson.decodeFromString(QueryDeferredSupplierExecutionWireDocument.serializer(), malformed)
        }
    }

    @Test
    fun `malformed unknown declared effect enum fails decoding`() {
        val original =
            wireJson.encodeToString(
                QueryCallbackDependencyInvocationKindDocument.serializer(),
                QueryCallbackDependencyInvocationKindDocument.UNKNOWN,
            )
        for (unknown in listOf("ZERO", "MORE_THAN_ONCE", "UNSUPPORTED")) {
            val malformed = original.replace("UNKNOWN", unknown)
            assertThrows(SerializationException::class.java) {
                wireJson.decodeFromString(QueryCallbackDependencyInvocationKindDocument.serializer(), malformed)
            }
        }
    }

    private fun assertAccessor(kind: QueryCallbackAccessorKindDocument, literal: String) {
        val execution =
            QueryDeferredSupplierExecutionWireDocument.Accessor(kind, assertion.occurrence(FILE, 1, REGION_END))
        assertion.assertAdmitted(fixture.owned(execution, fixture.propertyOwner()))
        val raw = encoded(execution)
        assertEquals(setOf("type", "kind", "occurrence"), raw.keys)
        assertEquals("ACCESSOR", raw.getValue("type").jsonPrimitive.content)
        assertEquals(literal, raw.getValue("kind").jsonPrimitive.content)
        assertOccurrence(raw.getValue("occurrence").jsonObject, 1, REGION_END)
    }

    private fun assertBasis(raw: JsonObject) {
        assertEquals(setOf("type", "root", "generation"), raw.keys)
        assertEquals("PUBLISHED", raw.getValue("type").jsonPrimitive.content)
        assertEquals("/workspace", raw.getValue("root").jsonPrimitive.content)
        assertEquals(1L, raw.getValue("generation").jsonPrimitive.content.toLong())
    }

    private fun assertNamedOwner(raw: JsonObject) {
        assertEquals(setOf("type", "callable"), raw.keys)
        assertEquals("NAMED", raw.getValue("type").jsonPrimitive.content)
        val callable = raw.getValue("callable").jsonObject
        assertEquals(setOf("declaration", "compiler_target"), callable.keys)
        assertOccurrence(callable.getValue("declaration").jsonObject, 0, OWNER_END)
        val target = callable.getValue("compiler_target").jsonObject
        assertEquals(setOf("file", "range", "name", "kind", "compiler_evidence"), target.keys)
        assertEquals(FILE, target.getValue("file").jsonPrimitive.content)
        assertEquals("read", target.getValue("name").jsonPrimitive.content)
        assertEquals("function", target.getValue("kind").jsonPrimitive.content)
        val signature = target.getValue("compiler_evidence").jsonObject.getValue("signature").jsonObject
        assertEquals("sample.read", signature.getValue("qualifiedIdentity").jsonPrimitive.content)
    }

    private fun assertAnonymousOwner(raw: JsonObject, start: Int, end: Int) {
        assertEquals(setOf("type", "occurrence", "compiler_evidence"), raw.keys)
        assertEquals("ANONYMOUS", raw.getValue("type").jsonPrimitive.content)
        assertAnonymousSite(raw, start, end)
    }

    private fun assertAnonymousBody(raw: JsonObject, start: Int, end: Int) {
        assertEquals(setOf("occurrence", "compiler_evidence"), raw.keys)
        assertAnonymousSite(raw, start, end)
    }

    private fun assertAnonymousSite(raw: JsonObject, start: Int, end: Int) {
        assertOccurrence(raw.getValue("occurrence").jsonObject, start, end)
        val signature = raw.getValue("compiler_evidence").jsonObject.getValue("signature").jsonObject
        assertEquals("anonymous@Seed.kt#$start:$end", signature.getValue("qualifiedIdentity").jsonPrimitive.content)
    }

    private fun assertOccurrence(raw: JsonObject, start: Int, end: Int) {
        assertEquals(setOf("candidateSelector", "file", "range"), raw.keys)
        assertEquals("candidate:Seed.kt:$start:$end", raw.getValue("candidateSelector").jsonPrimitive.content)
        assertEquals(FILE, raw.getValue("file").jsonPrimitive.content)
        val range = raw.getValue("range").jsonObject
        assertEquals(setOf("startInclusive", "endExclusive"), range.keys)
        assertEquals(start, range.getValue("startInclusive").jsonPrimitive.content.toInt())
        assertEquals(end, range.getValue("endExclusive").jsonPrimitive.content.toInt())
    }

    private fun decodeLegacy(
        binding: QueryCallbackBindingWireDocument.DeferredDependency
    ): QueryCallbackBindingWireDocument.DeferredDependency {
        val old =
            LegacyDeferredExecutionBinding.Deferred(
                basis = binding.basis,
                occurrence = binding.occurrence,
                owner = binding.owner,
                target = binding.target,
                position = binding.position,
                binary = binding.binary,
            )
        val raw = wireJson.encodeToJsonElement(LegacyDeferredExecutionBinding.serializer(), old).jsonObject
        assertEquals(setOf("type", "basis", "occurrence", "owner", "target", "position", "binary"), raw.keys)
        return wireJson.decodeFromString(QueryCallbackBindingWireDocument.serializer(), raw.toString())
            as QueryCallbackBindingWireDocument.DeferredDependency
    }

    private fun encoded(execution: QueryDeferredSupplierExecutionWireDocument) =
        wireJson.encodeToJsonElement(QueryDeferredSupplierExecutionWireDocument.serializer(), execution).jsonObject
}

private class DeferredSupplierExecutionWireFixture {
    private val source = QueryCallbackWireFixture()
    private val fixture = QueryCallbackRefinementWireFixture()

    fun base() = QueryCallbackDeferredDependencyWireTest().observation()

    fun binding(wire: QueryCallbackObservationWireDocument) =
        fixture.flow(wire).binding as QueryCallbackBindingWireDocument.DeferredDependency

    fun invoked(): QueryCallbackObservationWireDocument {
        val initial = binding(base())
        val outerBody = fixture.anonymous(FILE, 1, FIRST_BODY_END)
        val innerBody = fixture.anonymous(FILE, 2, SECOND_BODY_END)
        return owned(
            QueryDeferredSupplierExecutionWireDocument.Invoked(
                listOf(
                    QueryDeferredDirectInvocationWireDocument(
                        basis = initial.basis,
                        occurrence = fixture.occurrence(FILE, 0, FIRST_CALL_END),
                        owner = QueryCallbackBodyWireDocument.Named(initial.owner),
                        body = outerBody,
                    ),
                    QueryDeferredDirectInvocationWireDocument(
                        basis = initial.basis,
                        occurrence = fixture.occurrence(FILE, 1, SECOND_CALL_END),
                        owner = outerBody,
                        body = innerBody,
                    ),
                )
            ),
            initial.owner,
        )
    }

    fun owned(
        execution: QueryDeferredSupplierExecutionWireDocument,
        owner: QueryCallbackCallableWireDocument,
    ): QueryCallbackObservationWireDocument {
        val wire = base()
        return replace(wire.copy(lexicalOwner = owner), binding(wire).copy(owner = owner, execution = execution))
    }

    fun propertyOwner(): QueryCallbackCallableWireDocument =
        owner(
            CompilerSignatureDocument.Property(
                qualifiedIdentity = source.text("sample.property"),
                receiver = CompilerReceiverDocument.Absent,
                contextReceivers = BoundedProtocolList.create(emptyList<ProtocolText>()).wireFixtureRefined(),
                returnType = source.text("kotlin.Lazy<kotlin.Int>"),
            ),
            SymbolKindWireDocument.PROPERTY,
            "property",
        )

    fun classOwner(): QueryCallbackCallableWireDocument =
        owner(
            CompilerSignatureDocument.ClassLike(source.text("sample.Owner")),
            SymbolKindWireDocument.CLASSLIKE,
            "Owner",
        )

    fun replace(
        wire: QueryCallbackObservationWireDocument,
        binding: QueryCallbackBindingWireDocument.DeferredDependency,
    ) = wire.copy(flow = fixture.flow(wire).copy(binding = binding))

    private fun owner(
        signature: CompilerSignatureDocument,
        kind: SymbolKindWireDocument,
        name: String,
    ): QueryCallbackCallableWireDocument {
        val initial = binding(base()).owner
        val evidence = CompilerSymbolEvidenceDocument.fromSignature(signature).wireFixtureRefined().toWireDocument()
        return initial.copy(
            compilerTarget = initial.compilerTarget.copy(kind = kind, name = name, compilerEvidence = evidence)
        )
    }
}

/** A typed legacy fixture deliberately omits the newly optional execution field. */
@Serializable
@JsonClassDiscriminator("type")
private sealed interface LegacyDeferredExecutionBinding {
    @Serializable
    @SerialName("DEFERRED_DEPENDENCY")
    data class Deferred(
        val basis: ImpactSemanticBasisDocument,
        val occurrence: RelationOccurrenceWireDocument,
        val owner: QueryCallbackCallableWireDocument,
        val target: QueryExcludedCompilerTargetWireDocument,
        val position: Int,
        val binary: QueryKotlinLazyBinaryWireDocument,
    ) : LegacyDeferredExecutionBinding
}

/** Deliberately incompatible discriminator fixture proves rejection without handwritten JSON. */
@Serializable private data class MalformedDeferredExecution(val type: String = "ESCAPED")

private fun <Value, Failure> Refinement<Value, Failure>.wireFixtureRefined(): Value =
    when (this) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> error("Invalid authored execution fixture: $failure")
    }

private const val FILE = "Seed.kt"
private const val OWNER_END = 40
private const val REGION_END = 30
private const val FIRST_CALL_END = 39
private const val FIRST_BODY_END = 38
private const val SECOND_CALL_END = 37
private const val SECOND_BODY_END = 36
private const val OUTSIDE_REGION_START = 21
private const val SUPPLY_END = 20
