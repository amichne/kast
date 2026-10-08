package io.github.amichne.kast.protocol.contract

import io.github.amichne.kast.kernel.Refinement
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LocalCompilerSignatureDocumentTest {
    @Test
    fun `local signatures admit positive evidence without a qualified name`() {
        for (signature in
            listOf(
                function(),
                property(LocalPropertyMutabilityDocument.VAL),
                property(LocalPropertyMutabilityDocument.VAR),
            )) {
            val evidence = CompilerSymbolEvidenceDocument.fromSignature(signature).refined()
            assertTrue(evidence.identity.value.startsWith("canonical-signature-sha256-v1|"))
            assertTrue(
                SymbolDocument.create(
                    text("selector"),
                    signatureKind(signature),
                    text("local"),
                    SymbolQualifiedIdentityDocument.Unavailable,
                    text("/workspace/Seed.kt"),
                    range(20, 40),
                    evidence,
                ) is Refinement.Refined
            )
            assertEquals(
                SymbolDocumentFailure.QUALIFIED_IDENTITY_MISMATCH,
                SymbolDocument.create(
                        text("selector"),
                        signatureKind(signature),
                        text("local"),
                        SymbolQualifiedIdentityDocument.Available(text("sample.local")),
                        text("/workspace/Seed.kt"),
                        range(20, 40),
                        evidence,
                    )
                    .rejected(),
            )
        }
        assertNotEquals(
            CompilerSymbolEvidenceDocument.fromSignature(property(LocalPropertyMutabilityDocument.VAL))
                .refined()
                .identity,
            CompilerSymbolEvidenceDocument.fromSignature(property(LocalPropertyMutabilityDocument.VAR))
                .refined()
                .identity,
        )
    }

    @Test
    fun `local evidence rejects mismatched source and declaration kind`() {
        val evidence = CompilerSymbolEvidenceDocument.fromSignature(function()).refined()
        for ((file, sourceRange) in
            listOf(text("/workspace/Other.kt") to range(20, 40), text("/workspace/Seed.kt") to range(21, 40))) {
            assertEquals(
                SymbolDocumentFailure.LOCAL_ADDRESS_MISMATCH,
                SymbolDocument.create(
                        text("selector"),
                        SymbolKindDocument.FUNCTION,
                        text("local"),
                        SymbolQualifiedIdentityDocument.Unavailable,
                        file,
                        sourceRange,
                        evidence,
                    )
                    .rejected(),
            )
        }
        assertEquals(
            SymbolDocumentFailure.SIGNATURE_KIND_MISMATCH,
            SymbolDocument.create(
                    text("selector"),
                    SymbolKindDocument.CONSTRUCTOR,
                    text("local"),
                    SymbolQualifiedIdentityDocument.Unavailable,
                    text("/workspace/Seed.kt"),
                    range(20, 40),
                    evidence,
                )
                .rejected(),
        )
        assertEquals(
            CompilerSymbolEvidenceDocumentFailure.IDENTITY_MISMATCH,
            CompilerSymbolEvidenceDocument.restore(text("compiler-symbol-v1:${"f".repeat(64)}"), function()).rejected(),
        )
    }

    @Test
    fun `local addresses reject overlapping or unbounded lexical ownership`() {
        for ((owner, lexical, expectedFailure) in
            listOf(
                Triple(range(25, 90), emptyList(), LocalDeclarationAddressDocumentFailure.INVALID_OWNER_RANGE),
                Triple(
                    range(0, 100),
                    listOf(range(10, 90), range(5, 95)),
                    LocalDeclarationAddressDocumentFailure.INVALID_LEXICAL_OWNER,
                ),
                Triple(
                    range(0, 100),
                    listOf(range(10, 30)),
                    LocalDeclarationAddressDocumentFailure.INVALID_LEXICAL_OWNER,
                ),
                Triple(
                    range(0, 100),
                    List(33) { range(10, 90) },
                    LocalDeclarationAddressDocumentFailure.OWNER_DEPTH_EXCEEDED,
                ),
            )) {
            assertEquals(
                expectedFailure,
                LocalDeclarationAddressDocument.create(
                        LocalDeclarationFileDocument.Workspace(text("/workspace/Seed.kt")),
                        LocalDeclarationKindDocument.FUNCTION,
                        range(20, 40),
                        text("canonical-signature-sha256-v1|${"a".repeat(64)}"),
                        owner,
                        list(lexical),
                    )
                    .rejected(),
            )
        }
        assertEquals(
            LocalDeclarationAddressDocumentFailure.INVALID_FILE,
            LocalDeclarationAddressDocument.create(
                    LocalDeclarationFileDocument.Workspace(text("relative/Seed.kt")),
                    LocalDeclarationKindDocument.FUNCTION,
                    range(20, 40),
                    text("canonical-signature-sha256-v1|${"a".repeat(64)}"),
                    range(0, 100),
                    list(emptyList()),
                )
                .rejected(),
        )
    }

    @Test
    fun `local signature admission rejects an address with another declaration kind`() {
        val propertyAddress = address(LocalDeclarationKindDocument.PROPERTY)
        val wrongFunction =
            CompilerSignatureDocument.LocalFunction(
                propertyAddress,
                CompilerReceiverDocument.Absent,
                list(emptyList()),
                list(emptyList()),
                CompilerTypeParameterCountDocument.parse(0).refined(),
                text("kotlin.Int"),
            )
        val wrongProperty =
            CompilerSignatureDocument.LocalProperty(
                address(LocalDeclarationKindDocument.FUNCTION),
                text("kotlin.Int"),
                LocalPropertyMutabilityDocument.VAL,
            )
        for (signature in listOf(wrongFunction, wrongProperty)) assertEquals(
            CompilerSymbolEvidenceDocumentFailure.INVALID_SIGNATURE,
            CompilerSymbolEvidenceDocument.fromSignature(signature).rejected(),
        )
    }

    @Test
    fun `local owner identity requires exact canonical digest rather than arbitrary text`() {
        for (owner in
            listOf(
                "sample.owner",
                "compiler-symbol-v1:${"a".repeat(64)}",
                "canonical-signature-sha256-v1|${"A".repeat(64)}",
                "canonical-signature-sha256-v1|${"a".repeat(63)}",
                "canonical-signature-sha256-v1|${"a".repeat(65)}",
            )) assertEquals(
            LocalDeclarationAddressDocumentFailure.INVALID_OWNER_IDENTITY,
            LocalDeclarationAddressDocument.create(
                    LocalDeclarationFileDocument.Workspace(text("/workspace/Seed.kt")),
                    LocalDeclarationKindDocument.FUNCTION,
                    range(20, 40),
                    text(owner),
                    range(0, 100),
                    list(listOf(range(10, 90))),
                )
                .rejected(),
        )
    }

    private fun function() =
        CompilerSignatureDocument.LocalFunction(
            address(),
            CompilerReceiverDocument.Absent,
            list(emptyList()),
            list(listOf(text("kotlin.String"))),
            CompilerTypeParameterCountDocument.parse(0).refined(),
            text("kotlin.Int"),
        )

    private fun property(mutability: LocalPropertyMutabilityDocument) =
        CompilerSignatureDocument.LocalProperty(
            address(LocalDeclarationKindDocument.PROPERTY),
            text("kotlin.Int"),
            mutability,
        )

    private fun address(kind: LocalDeclarationKindDocument = LocalDeclarationKindDocument.FUNCTION) =
        LocalDeclarationAddressDocument.create(
                LocalDeclarationFileDocument.Workspace(text("/workspace/Seed.kt")),
                kind,
                range(20, 40),
                text("canonical-signature-sha256-v1|${"a".repeat(64)}"),
                range(0, 100),
                list(listOf(range(10, 90))),
            )
            .refined()

    private fun signatureKind(signature: CompilerSignatureDocument) =
        when (signature) {
            is CompilerSignatureDocument.LocalFunction -> SymbolKindDocument.FUNCTION
            is CompilerSignatureDocument.LocalProperty -> SymbolKindDocument.PROPERTY
            else -> error("Unexpected fixture signature")
        }

    private fun text(raw: String) = ProtocolText.parse(raw).refined()

    private fun range(start: Int, end: Int) =
        SourceRangeDocument.create(ProtocolOffset.parse(start).refined(), ProtocolOffset.parse(end).refined()).refined()

    private fun <T> list(values: List<T>) = BoundedProtocolList.create(values).refined()

    private fun <T, F> Refinement<T, F>.refined(): T =
        when (this) {
            is Refinement.Refined -> value
            is Refinement.Rejected -> error(failure.toString())
        }

    private fun <T, F> Refinement<T, F>.rejected(): F =
        when (this) {
            is Refinement.Refined -> error("Expected rejection")
            is Refinement.Rejected -> failure
        }
}
