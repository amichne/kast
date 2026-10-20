package io.github.amichne.kast.protocol.contract

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.symbol.contract.CanonicalCompilerSignature
import io.github.amichne.kast.symbol.contract.CompilerSymbolIdentity
import io.github.amichne.kast.symbol.contract.fromCanonicalSignature
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ImpactCollectionInsertionContractTest {
    @Test
    fun `single element insertion encodes index zero without a selection condition`() {
        val f = CollectionInsertionWireCase()
        assertTrue(f.application.admitsValueModel(f.basis))
        val encoded =
            Json.encodeToJsonElement(ImpactValueModelApplicationDocument.serializer(), f.application).jsonObject
        assertEquals(setOf("type", "source", "target", "binding", "index"), encoded.keys)
        assertEquals("COLLECTION_INSERTION", encoded.getValue("type").jsonPrimitive.content)
        assertEquals("0", encoded.getValue("index").jsonPrimitive.content)
        val target = encoded.getValue("target").jsonObject
        assertEquals(1, target.getValue("valuePath").jsonArray.size)
        val element = target.getValue("valuePath").jsonArray.single().jsonObject
        assertEquals(setOf("type", "index"), element.keys)
        assertEquals("ELEMENT", element.getValue("type").jsonPrimitive.content)
        assertEquals("0", element.getValue("index").jsonPrimitive.content)
        assertTrue(target.getValue("predicates").jsonArray.isEmpty())
        val model = encoded.getValue("binding").jsonObject.getValue("model").jsonObject
        assertEquals(setOf("type", "reference", "artifact", "callable"), model.keys)
        assertEquals("SINGLE_ELEMENT_LIST_OF", model.getValue("type").jsonPrimitive.content)
        assertEquals("collections.listOf.insert-zero", f.model.reference.rule.value)
        assertEquals(
            CompilerSignatureWireDocument.Function(
                "kotlin.collections.listOf",
                CompilerReceiverWireDocument.Absent,
                emptyList(),
                listOf("T"),
                1,
            ),
            f.model.callable,
        )
    }

    @Test
    fun `insertion prepends zero to the complete input path and preserves exact context`() {
        val f = CollectionInsertionWireCase(listOf(ImpactValuePathSegmentDocument.Element(offset(1))))
        assertTrue(f.application.admitsValueModel(f.basis))
        assertEquals(
            listOf(0, 1),
            f.application.target.valuePath.values.map { (it as ImpactValuePathSegmentDocument.Element).index.value },
        )
        assertFalse(
            f.application
                .copy(target = f.application.target.copy(valuePath = f.source.valuePath))
                .admitsValueModel(f.basis)
        )
        assertTrue(
            CollectionInsertionWireCase(List(7) { ImpactValuePathSegmentDocument.AnyElement }).let {
                it.application.admitsValueModel(it.basis)
            }
        )
        assertFalse(
            CollectionInsertionWireCase(List(8) { ImpactValuePathSegmentDocument.AnyElement }).let {
                it.application.admitsValueModel(it.basis)
            }
        )
        val frame =
            ImpactCallFrameDocument(f.source.enclosing, f.call, ImpactExecutionReferenceDocument(f.call.callable))
        assertFalse(
            f.application
                .copy(target = f.application.target.copy(callStack = bounded(listOf(frame))))
                .admitsValueModel(f.basis)
        )
        val subject = ImpactPredicateSubjectDocument(f.source.enclosing, f.source.range, f.source.role)
        val nonNull = ImpactValuePredicateDocument.NonNull(subject)
        assertFalse(
            f.application
                .copy(target = f.application.target.copy(predicates = bounded(listOf(nonNull))))
                .admitsValueModel(f.basis)
        )
    }

    @Test
    fun `wrong argument call review and artifact cannot borrow insertion output`() {
        val f = CollectionInsertionWireCase()
        val actual = f.source.role as ImpactValueRoleDocument.Argument
        assertFalse(
            f.application.copy(source = f.source.copy(role = actual.copy(index = offset(1)))).admitsValueModel(f.basis)
        )
        assertFalse(f.application.copy(source = f.source.copy(range = range(80, 85))).admitsValueModel(f.basis))
        val otherCall = actual.copy(invocation = f.call.copy(range = range(43, 70)))
        assertFalse(f.application.copy(source = f.source.copy(role = otherCall)).admitsValueModel(f.basis))
        val receiver = ImpactValueRoleDocument.Receiver(f.call, ImpactReceiverKindDocument.EXTENSION)
        assertFalse(f.application.copy(source = f.source.copy(role = receiver)).admitsValueModel(f.basis))
        val wrongKind =
            ImpactReviewedValueModelDocument.FirstOrNull(f.model.reference, f.model.artifact, f.model.callable)
        assertFalse(f.application.copy(binding = f.binding.copy(model = wrongKind)).admitsValueModel(f.basis))
        val digest = ImpactLibraryDigestDocument.parse("f".repeat(64)).insertedValue()
        val artifact = f.binding.artifact.copy(binaryDigest = digest)
        assertFalse(f.application.copy(binding = f.binding.copy(artifact = artifact)).admitsValueModel(f.basis))
        val foreign =
            (f.basis as ImpactSemanticBasisDocument.Published).copy(
                generation = ImpactEvidenceRevisionDocument.parse(8).insertedValue()
            )
        val foreignArtifact = f.binding.artifact.copy(basis = foreign)
        assertFalse(f.application.copy(binding = f.binding.copy(artifact = foreignArtifact)).admitsValueModel(f.basis))
    }

    @Test
    fun `unknown insertion kinds and nonzero inserted indices fail typed decoding`() {
        val f = CollectionInsertionWireCase()
        val text = Json.encodeToString(ImpactValueModelApplicationDocument.serializer(), f.application)
        for (malformed in
            listOf(
                text.replace("\"COLLECTION_INSERTION\"", "\"OPAQUE_INSERTION\""),
                text.replace("\"SINGLE_ELEMENT_LIST_OF\"", "\"OPAQUE_REVIEW\""),
                text.replace("\"index\":0", "\"index\":1"),
            )) {
            assertThrows(SerializationException::class.java) {
                Json.decodeFromString(ImpactValueModelApplicationDocument.serializer(), malformed)
            }
        }
        assertEquals(
            Refinement.Rejected(ImpactValueModelPrimitiveFailure.INVALID_INSERTED_ELEMENT),
            ImpactInsertedElementDocument.parse(1),
        )
        assertEquals(
            Refinement.Rejected(ImpactValueModelPrimitiveFailure.INVALID_INSERTED_ELEMENT),
            ImpactInsertedElementDocument.parse(-1),
        )
    }
}

private class CollectionInsertionWireCase(suffix: List<ImpactValuePathSegmentDocument> = emptyList()) {
    private val base = ValueModelStructureCase()
    val basis = base.basis
    private val signature =
        CanonicalCompilerSignature.function(
                "kotlin.collections.listOf",
                null,
                emptyList(),
                listOf("T"),
                1,
            )
            .insertedValue()
    private val identity = CompilerSymbolIdentity.fromCanonicalSignature(signature).value
    private val wire =
        CompilerSignatureWireDocument.Function(
            "kotlin.collections.listOf",
            CompilerReceiverWireDocument.Absent,
            emptyList(),
            listOf("T"),
            1,
        )
    private val callable =
        base.binding.callable.copy(
            declaration = base.binding.callable.declaration.copy(compilerIdentity = text(identity)),
            compilerTarget =
                base.binding.callable.compilerTarget.copy(
                    name = "listOf",
                    compilerEvidence = CompilerSymbolEvidenceWireDocument(identity, wire),
                ),
        )
    val call = base.call.copy(callable = callable.declaration)
    val model =
        ImpactReviewedValueModelDocument.SingleElementListOf(
            base.model.reference.copy(
                rule = ImpactModelIdentifierDocument.parse("collections.listOf.insert-zero").insertedValue()
            ),
            base.model.artifact,
            wire,
        )
    val binding =
        base.binding.copy(invocation = ImpactCallOccurrenceDocument.Declared(call), callable = callable, model = model)
    val source = base.source.copy(role = ImpactValueRoleDocument.Argument(call, offset(0)), valuePath = bounded(suffix))
    private val target =
        source.copy(
            range = call.range,
            role = ImpactValueRoleDocument.ExpressionResult,
            valuePath = bounded(listOf(ImpactValuePathSegmentDocument.Element(offset(0))) + suffix),
        )
    val application =
        ImpactValueModelApplicationDocument.CollectionInsertion(
            source,
            target,
            binding,
            ImpactInsertedElementDocument.parse(0).insertedValue(),
        )
}

private fun offset(value: Int) = ProtocolOffset.parse(value).insertedValue()

private fun text(value: String) = ProtocolText.parse(value).insertedValue()

private fun range(start: Int, end: Int) = ImpactSourceRangeDocument(offset(start), offset(end))

private fun <T> bounded(values: List<T>) = BoundedProtocolList.create(values).insertedValue()

private fun <T> Refinement<T, *>.insertedValue(): T =
    when (this) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> error("Insertion wire prerequisite rejected: $failure")
    }
