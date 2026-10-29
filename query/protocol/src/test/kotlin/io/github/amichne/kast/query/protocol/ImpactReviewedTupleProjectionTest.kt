package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.protocol.contract.ImpactPairSlotDocument
import io.github.amichne.kast.protocol.contract.ImpactReviewedValueRelationDocument
import io.github.amichne.kast.protocol.contract.admitsEvidence
import io.github.amichne.kast.relation.contract.RelationEndpoint
import io.github.amichne.kast.relation.contract.ReviewedCallValueBinding
import io.github.amichne.kast.relation.contract.ReviewedLibraryArtifact
import io.github.amichne.kast.relation.contract.ReviewedOrdinaryLibraryCallable
import io.github.amichne.kast.relation.contract.ReviewedValueTransfer
import io.github.amichne.kast.relation.contract.ValueArgumentPosition
import io.github.amichne.kast.relation.contract.ValueInvocation
import io.github.amichne.kast.relation.contract.ValueReceiverKind
import io.github.amichne.kast.relation.contract.ValueRole
import io.github.amichne.kast.relation.contract.ValueSite
import io.github.amichne.kast.symbol.contract.CanonicalCompilerSignature
import io.github.amichne.kast.symbol.contract.CompilerGroundedSymbolEvidence
import io.github.amichne.kast.symbol.contract.CompilerSymbolKind
import io.github.amichne.kast.symbol.contract.DetachedVirtualFileUrl
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryFileIdentity
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ImpactReviewedTupleProjectionTest {
    @Test
    fun `Pair constructor preserves argument slots and rejects a same typed slot swap`() {
        val f = TupleProjectionFixture()
        for (first in listOf(true, false)) {
            val transfer = f.construct(first)
            val document = transfer.impactDocument().componentValue()
            val relation = document.binding.relation as ImpactReviewedValueRelationDocument.TupleConstruction
            val other = if (first) ImpactPairSlotDocument.SECOND else ImpactPairSlotDocument.FIRST
            assertFalse(
                document.copy(binding = document.binding.copy(relation = relation.copy(slot = other))).admitsEvidence()
            )
            assertTrue(document.admitsEvidence())
            assertEquals(if (first) ImpactPairSlotDocument.FIRST else ImpactPairSlotDocument.SECOND, relation.slot)
            assertEquals(document.source.valuePath.values, document.target.valuePath.values.drop(1))
        }
    }

    @Test
    fun `Pair property projection removes only its matching slot and carries property authority`() {
        val f = TupleProjectionFixture()
        for (first in listOf(true, false)) {
            val document = f.project(f.construct(first), first).impactDocument().componentValue()
            val relation = document.binding.relation as ImpactReviewedValueRelationDocument.TupleProjection
            val other = if (first) ImpactPairSlotDocument.SECOND else ImpactPairSlotDocument.FIRST
            assertFalse(
                document.copy(binding = document.binding.copy(relation = relation.copy(slot = other))).admitsEvidence()
            )
            assertTrue(document.admitsEvidence())
            assertEquals(document.source.valuePath.values.drop(1), document.target.valuePath.values)
            val encoded = Json.parseToJsonElement(Json.encodeToString(document)).jsonObject
            val selected =
                encoded.getValue("binding").jsonObject.getValue("authority").jsonObject.getValue("selected").jsonObject
            assertEquals(
                setOf("type", "declaration", "qualifiedIdentity", "receiver", "contextReceivers", "returnType"),
                selected.keys,
            )
            assertEquals("PROPERTY", selected.getValue("type").jsonPrimitive.content)
        }
    }
}

private class TupleProjectionFixture {
    private val f = ImpactReviewedComponentFixture()

    fun construct(first: Boolean): ReviewedValueTransfer {
        val binding = binding("kotlin.Pair.<init>", false)
        val source =
            ValueSite.fromCompiler(
                    f.owner,
                    componentRange(50, 55),
                    ValueRole.Argument(
                        binding.invocation,
                        ValueArgumentPosition.parse(if (first) 0 else 1).componentValue(),
                    ),
                    binding.invocation.executionOwner,
                    valuePath = f.suffix,
                )
                .componentValue()
        return ReviewedValueTransfer.tupleConstruct(source, binding).componentValue()
    }

    fun project(construction: ReviewedValueTransfer, first: Boolean): ReviewedValueTransfer {
        val binding = binding(if (first) "kotlin.Pair.first" else "kotlin.Pair.second", true)
        val source =
            ValueSite.fromCompiler(
                    f.owner,
                    componentRange(75, 80),
                    ValueRole.Receiver(binding.invocation, ValueReceiverKind.DISPATCH),
                    binding.invocation.executionOwner,
                    valuePath = construction.target.valuePath,
                )
                .componentValue()
        return ReviewedValueTransfer.tupleProject(source, binding).componentValue()
    }

    private fun binding(identity: String, getter: Boolean): ReviewedCallValueBinding {
        val signature =
            if (getter)
                CanonicalCompilerSignature.property(
                        identity,
                        null,
                        emptyList(),
                        if (identity.endsWith("first")) "A" else "B",
                    )
                    .componentValue()
            else
                CanonicalCompilerSignature.function(
                        identity,
                        null,
                        emptyList(),
                        listOf("A", "B"),
                        0,
                    )
                    .componentValue()
        val evidence =
            CompilerGroundedSymbolEvidence.fromBoundary(
                    SymbolDiscoveryFileIdentity.External(
                        DetachedVirtualFileUrl.parse("jar:///stdlib.jar!/kotlin/Pair.class").componentValue()
                    ),
                    1000,
                    1100,
                    identity.substringAfterLast('.'),
                    identity,
                    if (getter) CompilerSymbolKind.PROPERTY else CompilerSymbolKind.CONSTRUCTOR,
                    signature,
                )
                .componentValue()
        val selected = ReviewedOrdinaryLibraryCallable.admit(evidence).componentValue()
        val endpoint = RelationEndpoint.resolve(f.owner.lease, f.owner.scope, evidence).componentValue()
        val call =
            if (getter) ValueInvocation.getterFromCompiler(f.owner, componentRange(75, 85), endpoint).componentValue()
            else ValueInvocation.fromCompiler(f.owner, componentRange(50, 70), endpoint).componentValue()
        val artifact =
            ReviewedLibraryArtifact.admit(
                    selected,
                    f.association.artifact.binaryArtifact,
                    f.association.artifact.sourceArtifact,
                )
                .componentValue()
        return ReviewedCallValueBinding.admit(call, selected, artifact).componentValue()
    }
}
