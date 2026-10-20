package io.github.amichne.kast.runtime.hosted

import io.github.amichne.kast.protocol.contract.CompilerReceiverWireDocument
import io.github.amichne.kast.protocol.contract.CompilerSignatureWireDocument
import io.github.amichne.kast.protocol.contract.EmptyImpactValuePath
import io.github.amichne.kast.protocol.contract.ImpactCallOccurrenceDocument
import io.github.amichne.kast.protocol.contract.ImpactExecutionOwnerDocument
import io.github.amichne.kast.protocol.contract.ImpactLibraryOriginDocument
import io.github.amichne.kast.protocol.contract.ImpactPathDocument
import io.github.amichne.kast.protocol.contract.ImpactPathStepDocument
import io.github.amichne.kast.protocol.contract.ImpactReceiverKindDocument
import io.github.amichne.kast.protocol.contract.ImpactReviewedValueModelDocument
import io.github.amichne.kast.protocol.contract.ImpactSourceRangeDocument
import io.github.amichne.kast.protocol.contract.ImpactValueModelApplicationDocument
import io.github.amichne.kast.protocol.contract.ImpactValueModelConditionDocument
import io.github.amichne.kast.protocol.contract.ImpactValuePredicateDocument
import io.github.amichne.kast.protocol.contract.ImpactValueRoleDocument
import junit.framework.TestCase.assertEquals
import junit.framework.TestCase.assertTrue

/** This draft asserts only the original C2.8 collection prefix. Matched nullable aliases and C2.9 remain separate. */
internal class NativePacketCompositeSelectionOracle(private val facts: NativePacketCompositeQueryFacts) {
    fun assertOriginalWholeSelectionUnderItsExactSuccessGuard(paths: List<ImpactPathDocument>) {
        val candidates =
            paths
                .filter { it.compositeContiguous() }
                .flatMap { path ->
                    path.steps.values.filterIsInstance<ImpactPathStepDocument.ModeledValue>().map { it.application }
                }
        val selected =
            candidates.filterIsInstance<ImpactValueModelApplicationDocument.CollectionSelection>().filter { application
                ->
                application.source.enclosing == facts.getOfferReturn.enclosing &&
                    application.source.range == operand() &&
                    application.target.range == facts.firstOrNullResult
            }
        assertTrue(
            "The original S2 route needs an actual ModeledValue CollectionSelection at939:953→939:967",
            selected.isNotEmpty(),
        )
        selected.forEach(::assertSelection)
        println("C28_COLLECTION_PREFIX selection=ASSERTED checked_nullable_alias,C2.8_storage,C2.9=NOT_RUN")
    }

    private fun assertSelection(application: ImpactValueModelApplicationDocument.CollectionSelection) {
        val source = application.source
        val target = application.target
        val receiver = source.role as ImpactValueRoleDocument.Receiver
        assertEquals(ImpactReceiverKindDocument.EXTENSION, receiver.kind)
        assertEquals(facts.firstOrNullResult, receiver.invocation.range)
        assertEquals(ImpactCallOccurrenceDocument.Declared(receiver.invocation), application.binding.invocation)
        assertEquals(EmptyImpactValuePath, source.valuePath)
        assertEquals(EmptyImpactValuePath, target.valuePath)
        assertEquals(ImpactValueRoleDocument.ExpressionResult, target.role)
        assertEquals(source.enclosing, target.enclosing)
        assertEquals(source.executionOwner, target.executionOwner)
        assertEquals(facts.sequentialBody, (source.executionOwner as ImpactExecutionOwnerDocument.Anonymous).body.range)
        assertEquals(source.callStack, target.callStack)
        assertEquals(source.predicates, target.predicates)
        assertTrue(
            "The selected original list keeps the original execute-result Success guard",
            source.predicates.values.any { predicate ->
                predicate is ImpactValuePredicateDocument.Subtype &&
                    predicate.subject == facts.executeReceiver.compositeSubject() &&
                    predicate.resolvedType == facts.successType
            },
        )
        val condition = application.condition as ImpactValueModelConditionDocument.SelectedElement
        assertEquals(0, condition.index.value)
        assertEquals(source.copy(valuePath = EmptyImpactValuePath), condition.receiver)
        assertEquals(target.copy(valuePath = EmptyImpactValuePath), condition.result)
        assertBinding(application)
    }

    private fun assertBinding(application: ImpactValueModelApplicationDocument.CollectionSelection) {
        val binding = application.binding
        val model = binding.model as ImpactReviewedValueModelDocument.FirstOrNull
        assertEquals("kotlin-stdlib-value-flow", model.reference.model.id.value)
        assertEquals(1, model.reference.model.version.value)
        assertEquals(
            CompilerSignatureWireDocument.Function(
                qualifiedIdentity = "kotlin.collections.firstOrNull",
                receiver = CompilerReceiverWireDocument.Present("kotlin/collections/List<T>"),
                contextReceivers = emptyList(),
                valueParameters = emptyList(),
                typeParameterCount = 1,
            ),
            model.callable,
        )
        assertEquals("collections.list.firstOrNull.select-zero", model.reference.rule.value)
        assertEquals(BINARY_SHA, binding.artifact.binaryDigest.value)
        assertEquals(BINARY_SHA, model.artifact.binaryDigest.value)
        assertEquals(SOURCE_SHA, model.artifact.sourceDigest.value)
        assertEquals(VERSION, binding.artifact.version.value)
        assertEquals(VERSION, model.artifact.version.value)
        assertEquals("source-sha256:$SOURCE_SHA", model.reference.model.provenance.value)
        assertEquals(ImpactLibraryOriginDocument.LIBRARY, binding.artifact.origin)
        assertEquals(application.source.enclosing.basis, binding.artifact.basis)
        assertEquals(model.callable, binding.callable.compilerTarget.compilerEvidence.signature)
        assertEquals(
            (application.source.role as ImpactValueRoleDocument.Receiver).invocation.callable,
            binding.callable.declaration,
        )
        assertTrue(binding.artifact.root.value.startsWith("jar://") && binding.artifact.root.value.endsWith("!/"))
        assertTrue(binding.callable.declaration.file.value.startsWith(binding.artifact.root.value))
    }

    private fun operand() = ImpactSourceRangeDocument(packetQueryOffset(OPERAND_START), packetQueryOffset(OPERAND_END))

    private companion object {
        const val OPERAND_START = 939
        const val OPERAND_END = 953
        const val BINARY_SHA = "0ae12504a5040ebaf37703908483420d1a5624dd1d93f357665f8c77c848a01e"
        const val SOURCE_SHA = "5c9707bb04725260c2c03d88a218dd4ea87749aefcc4663e71afa9559430f661"
        const val VERSION = "2.3.20-release-208"
    }
}
