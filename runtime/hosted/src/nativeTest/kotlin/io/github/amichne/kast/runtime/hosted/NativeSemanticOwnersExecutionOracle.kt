package io.github.amichne.kast.runtime.hosted

import io.github.amichne.kast.protocol.contract.CompilerReceiverDocument
import io.github.amichne.kast.protocol.contract.CompilerSignatureDocument
import io.github.amichne.kast.protocol.contract.QueryCallbackAccessorKindDocument
import io.github.amichne.kast.protocol.contract.QueryCallbackBindingDocument
import io.github.amichne.kast.protocol.contract.QueryCallbackBodyDocument
import io.github.amichne.kast.protocol.contract.QueryCallbackFlowDocument
import io.github.amichne.kast.protocol.contract.QueryDeferredSupplierExecutionDocument
import io.github.amichne.kast.protocol.contract.RelationOccurrenceDocument
import io.github.amichne.kast.protocol.contract.SymbolKindDocument
import java.nio.file.Path
import junit.framework.TestCase.assertEquals
import junit.framework.TestCase.assertTrue

/** The ordered execution proof preserves every compiler body and direct parent. */
internal class NativeSemanticOwnersExecutionOracle(private val root: Path) {
    fun from(
        flow: QueryCallbackFlowDocument.Observed,
        binding: QueryCallbackBindingDocument.DeferredDependency,
    ): NativeSemanticOwnersExecution {
        assertFlowRoutes(flow, binding)
        return execution(binding)
    }

    private fun execution(binding: QueryCallbackBindingDocument.DeferredDependency): NativeSemanticOwnersExecution =
        when (val proof = binding.execution) {
            QueryDeferredSupplierExecutionDocument.Declaration ->
                NativeSemanticOwnersExecution(
                    binding.owner.declaration.range.semanticOwnersSpan(),
                    signatureExecutionKind(
                        binding.owner.compilerTarget.compilerEvidence.signature,
                        binding.owner.compilerTarget.kind,
                    ),
                    emptyList(),
                )
            is QueryDeferredSupplierExecutionDocument.Accessor ->
                NativeSemanticOwnersExecution(
                    sourceOccurrence(proof.occurrence),
                    when (proof.kind) {
                        QueryCallbackAccessorKindDocument.GETTER -> NativeSemanticOwnersExecutionKind.GETTER
                        QueryCallbackAccessorKindDocument.SETTER -> NativeSemanticOwnersExecutionKind.SETTER
                    },
                    emptyList(),
                )
            is QueryDeferredSupplierExecutionDocument.Initializer ->
                NativeSemanticOwnersExecution(
                    sourceOccurrence(proof.occurrence),
                    NativeSemanticOwnersExecutionKind.INIT_BLOCK,
                    emptyList(),
                )
            is QueryDeferredSupplierExecutionDocument.Invoked -> invokedExecution(binding, proof)
        }

    private fun invokedExecution(
        binding: QueryCallbackBindingDocument.DeferredDependency,
        proof: QueryDeferredSupplierExecutionDocument.Invoked,
    ): NativeSemanticOwnersExecution =
        NativeSemanticOwnersExecution(
            sourceOccurrence(proof.steps.values.last().body.occurrence),
            NativeSemanticOwnersExecutionKind.ANONYMOUS_BODY,
            proof.steps.values.map { step ->
                assertEquals(binding.basis, step.binding.basis)
                NativeSemanticOwnersExecutionStep(
                    call = sourceOccurrence(step.binding.occurrence),
                    body = sourceOccurrence(step.body.occurrence),
                    bodyIdentity = anonymousIdentity(step.body),
                    owner = executionParent(step.binding.owner),
                )
            },
        )

    private fun executionParent(owner: QueryCallbackBodyDocument): NativeSemanticOwnersExecutionParent =
        when (owner) {
            is QueryCallbackBodyDocument.Named -> {
                normalizeSourceFile(owner.callable.declaration.file.value)
                NativeSemanticOwnersExecutionParent.Named(
                    semanticOwnersIdentity(owner.callable.compilerTarget.compilerEvidence.signature),
                    owner.callable.declaration.range.semanticOwnersSpan(),
                    owner.callable.compilerTarget.compilerEvidence.identity.value,
                )
            }
            is QueryCallbackBodyDocument.Anonymous ->
                NativeSemanticOwnersExecutionParent.Anonymous(
                    sourceOccurrence(owner.occurrence),
                    anonymousIdentity(owner),
                )
        }

    private fun anonymousIdentity(body: QueryCallbackBodyDocument.Anonymous): String {
        val file = normalizeSourceFile(body.occurrence.file.value)
        val range = body.occurrence.range.semanticOwnersSpan()
        val expected = "anonymous@${root.resolve(file)}#${range.start}:${range.end}"
        val signature = body.compilerEvidence.signature as CompilerSignatureDocument.Function
        assertEquals(expected, signature.qualifiedIdentity.value)
        assertEquals(CompilerReceiverDocument.Absent, signature.receiver)
        assertTrue(signature.contextReceivers.values.isEmpty())
        assertTrue(signature.valueParameters.values.isEmpty())
        assertEquals(0, signature.typeParameterCount.value)
        assertEquals(
            semanticOwnersCanonicalIdentity(
                listOf("canonical-signature-v1", "function", expected, "receiver-absent", "0", "0", "0")
            ),
            body.compilerEvidence.identity.value,
        )
        return "anonymous@$file#${range.start}:${range.end}"
    }

    private fun assertFlowRoutes(
        flow: QueryCallbackFlowDocument.Observed,
        binding: QueryCallbackBindingDocument.DeferredDependency,
    ) {
        val route =
            when (val proof = binding.execution) {
                QueryDeferredSupplierExecutionDocument.Declaration,
                is QueryDeferredSupplierExecutionDocument.Accessor,
                is QueryDeferredSupplierExecutionDocument.Initializer -> emptyList()
                is QueryDeferredSupplierExecutionDocument.Invoked -> proof.steps.values
            }
        flow.ownerBindings.values.forEach { owner ->
            assertTrue(
                "No extra route can enter the semantic result",
                route.any { it.binding == owner.binding && it.body == owner.body },
            )
        }
    }

    private fun sourceOccurrence(occurrence: RelationOccurrenceDocument): NativeSemanticOwnersRange {
        normalizeSourceFile(occurrence.file.value)
        return occurrence.range.semanticOwnersSpan()
    }

    private fun normalizeSourceFile(raw: String): String {
        val file = Path.of(raw)
        val expected = root.resolve("src/backend-service-clients/Owners.kt")
        assertEquals("The exact owned source must precede root normalization", expected, file)
        return root.relativize(file).toString()
    }
}

private fun signatureExecutionKind(
    signature: CompilerSignatureDocument,
    kind: SymbolKindDocument,
): NativeSemanticOwnersExecutionKind =
    if (kind == SymbolKindDocument.CONSTRUCTOR) NativeSemanticOwnersExecutionKind.CONSTRUCTOR_BODY
    else
        when (signature) {
            is CompilerSignatureDocument.Function -> NativeSemanticOwnersExecutionKind.NAMED_FUNCTION
            is CompilerSignatureDocument.LocalFunction -> NativeSemanticOwnersExecutionKind.LOCAL_FUNCTION
            is CompilerSignatureDocument.Property -> NativeSemanticOwnersExecutionKind.PROPERTY
            is CompilerSignatureDocument.LocalProperty,
            is CompilerSignatureDocument.ClassLike,
            is CompilerSignatureDocument.TypeAlias,
            is CompilerSignatureDocument.AnonymousObject -> NativeSemanticOwnersExecutionKind.UNPROVEN
        }

internal data class NativeSemanticOwnersExecution(
    val owner: NativeSemanticOwnersRange,
    val kind: NativeSemanticOwnersExecutionKind,
    val route: List<NativeSemanticOwnersExecutionStep>,
)
