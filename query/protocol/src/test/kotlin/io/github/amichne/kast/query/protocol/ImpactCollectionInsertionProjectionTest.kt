package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.protocol.contract.CompilerReceiverWireDocument
import io.github.amichne.kast.protocol.contract.CompilerSignatureWireDocument
import io.github.amichne.kast.protocol.contract.ImpactReviewedValueModelDocument
import io.github.amichne.kast.protocol.contract.ImpactValueModelApplicationDocument
import io.github.amichne.kast.protocol.contract.ImpactValuePathSegmentDocument
import io.github.amichne.kast.relation.contract.RelationEndpoint
import io.github.amichne.kast.relation.contract.ReviewedLibraryBinding
import io.github.amichne.kast.relation.contract.ReviewedValueModel
import io.github.amichne.kast.relation.contract.ValueAccessPath
import io.github.amichne.kast.relation.contract.ValueArgumentPosition
import io.github.amichne.kast.relation.contract.ValueInvocation
import io.github.amichne.kast.relation.contract.ValueModelApplication
import io.github.amichne.kast.relation.contract.ValuePathSegment
import io.github.amichne.kast.relation.contract.ValueRole
import io.github.amichne.kast.relation.contract.ValueSite
import io.github.amichne.kast.symbol.contract.CanonicalCompilerSignature
import io.github.amichne.kast.symbol.contract.CompilerGroundedSymbolEvidence
import io.github.amichne.kast.symbol.contract.CompilerSymbolKind
import io.github.amichne.kast.symbol.contract.DetachedVirtualFileUrl
import io.github.amichne.kast.symbol.contract.ExactDeclarationTextRange
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryFileIdentity
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ImpactCollectionInsertionProjectionTest {
    @Test
    fun `insertion projection preserves full binding anonymous execution and argument suffix`() {
        val f = CollectionInsertionProjectionCase()
        val document =
            f.application.impactDocument(f.base.references).modelValue()
                as ImpactValueModelApplicationDocument.CollectionInsertion
        assertContext(f, document)
        assertBinding(f, document)
        assertEncodedShape(document)
    }

    private fun assertContext(
        f: CollectionInsertionProjectionCase,
        document: ImpactValueModelApplicationDocument.CollectionInsertion,
    ) {
        assertTrue(document.source.matchesSite(f.source, f.base.references))
        assertTrue(document.target.matchesSite(f.application.target, f.base.references))
        assertEquals(f.source.callStack.frames.size, document.target.callStack.values.size)
        assertEquals(document.source.predicates, document.target.predicates)
        assertEquals(1, document.source.predicates.values.size)
        assertEquals(
            listOf(ImpactValuePathSegmentDocument.Element::class, ImpactValuePathSegmentDocument.AnyElement::class),
            document.target.valuePath.values.map { it::class },
        )
        assertEquals(0, document.index.value)
    }

    private fun assertBinding(
        f: CollectionInsertionProjectionCase,
        document: ImpactValueModelApplicationDocument.CollectionInsertion,
    ) {
        val binding = document.binding
        assertEquals(f.binding.impactDocument(f.base.references).modelValue(), binding)
        val model = binding.model as ImpactReviewedValueModelDocument.SingleElementListOf
        assertEquals(
            CompilerSignatureWireDocument.Function(
                "kotlin.collections.listOf",
                CompilerReceiverWireDocument.Absent,
                emptyList(),
                listOf("T"),
                1,
            ),
            model.callable,
        )
        assertEquals("collections.listOf.insert-zero", model.reference.rule.value)
        assertEquals(ImpactValueModelProjectionCase.BINARY_SHA, binding.artifact.binaryDigest.value)
        assertEquals(ImpactValueModelProjectionCase.SOURCE_SHA, model.artifact.sourceDigest.value)
        assertEquals(ImpactValueModelProjectionCase.VERSION, binding.artifact.version.value)
    }

    private fun assertEncodedShape(document: ImpactValueModelApplicationDocument.CollectionInsertion) {
        val encoded = Json.encodeToJsonElement(ImpactValueModelApplicationDocument.serializer(), document).jsonObject
        assertEquals(setOf("type", "source", "target", "binding", "index"), encoded.keys)
        assertEquals("COLLECTION_INSERTION", encoded.getValue("type").jsonPrimitive.content)
        assertEquals(
            "ANONYMOUS",
            encoded
                .getValue("binding")
                .jsonObject
                .getValue("execution")
                .jsonObject
                .getValue("type")
                .jsonPrimitive
                .content,
        )
        assertEquals(
            "SINGLE_ELEMENT_LIST_OF",
            encoded.getValue("binding").jsonObject.getValue("model").jsonObject.getValue("type").jsonPrimitive.content,
        )
    }
}

private class CollectionInsertionProjectionCase {
    val base = ImpactValueModelProjectionCase()
    private val invocation =
        ValueInvocation.fromCompiler(base.host, range(44, 70), library(), base.callback.formal.executionOwner)
            .modelValue()
    val source =
        ValueSite.fromCompiler(
                base.host,
                range(46, 50),
                ValueRole.Argument(invocation, ValueArgumentPosition.parse(0).modelValue()),
                base.callback.formal.executionOwner,
                valuePath = ValueAccessPath.fromSegments(listOf(ValuePathSegment.AnyElement)).modelValue(),
                callStack = base.source.callStack,
                predicates = base.predicates,
            )
            .modelValue()
    val binding =
        ReviewedLibraryBinding.fromCompiler(invocation, ReviewedValueModel.SingleElementListOf, base.artifact)
            .modelValue()
    val application = ValueModelApplication.CollectionInsertion.insertSingle(source, binding).modelValue()

    private fun library(): RelationEndpoint {
        val signature =
            CanonicalCompilerSignature.function("kotlin.collections.listOf", null, emptyList(), listOf("T"), 1)
                .modelValue()
        val evidence =
            CompilerGroundedSymbolEvidence.fromBoundary(
                    SymbolDiscoveryFileIdentity.External(
                        DetachedVirtualFileUrl.parse(
                                ImpactValueModelProjectionCase.ROOT + "kotlin/collections/CollectionsKt.class"
                            )
                            .modelValue()
                    ),
                    20000,
                    20100,
                    "listOf",
                    "kotlin.collections.listOf",
                    CompilerSymbolKind.FUNCTION,
                    signature,
                )
                .modelValue()
        return RelationEndpoint.resolve(base.host.lease, base.binding.invocation.callable.scope, evidence).modelValue()
    }

    private fun range(start: Int, end: Int) = ExactDeclarationTextRange.parse(start, end).modelValue()
}
