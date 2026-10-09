@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package io.github.amichne.kast.protocol.wire

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.CompilerCallableSignatureDocument
import io.github.amichne.kast.protocol.contract.CompilerSignatureDocument
import io.github.amichne.kast.protocol.contract.CompilerSymbolEvidenceDocument
import io.github.amichne.kast.protocol.contract.LocalPropertyMutabilityDocument
import io.github.amichne.kast.protocol.contract.ProtocolStringConstraint
import io.github.amichne.kast.protocol.wire.presentation.CompilerSignatureCliDocument
import io.github.amichne.kast.protocol.wire.presentation.LocalDeclarationAddressCliDocument
import io.github.amichne.kast.protocol.wire.presentation.toCliDocument
import kotlinx.serialization.SerializationException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LocalCompilerSignatureWireTest {
    @Test
    fun `anonymous object wire shape preserves compiler owner and rejects forged address or supertype facts`() {
        val signature =
            CompilerSignatureWireDocument.AnonymousObject(
                address(LocalDeclarationKindWireDocument.ANONYMOUS_OBJECT),
                listOf("sample.Collector<kotlin.String>"),
            )
        assertEquals(
            expected("anonymous-object.json"),
            wireJson.encodeToJsonElement(CompilerSignatureWireDocument.serializer(), signature),
        )
        val contract = signature.toContract().converted()
        assertEquals(
            expected("anonymous-object.json"),
            wireJson.encodeToJsonElement(CompilerSignatureCliDocument.serializer(), contract.toCliDocument()),
        )
        val evidence = CompilerSymbolEvidenceDocument.fromSignature(contract).refined().toWireDocument()
        assertEquals(WireDocumentConversion.Rejected, evidence.copy(identity = "forged").toContract())
        assertEquals(
            WireDocumentConversion.Rejected,
            signature.copy(address = address(LocalDeclarationKindWireDocument.PROPERTY)).toContract(),
        )
        assertEquals(
            WireDocumentConversion.Rejected,
            signature
                .copy(address = signature.address.copy(ownerRange = SourceRangeWireDocument(25, 100)))
                .toContract(),
        )
        // Wire conversion alone does not confer compiler evidence; canonical admission rejects empty types.
        val empty = signature.copy(supertypes = emptyList()).toContract().converted()
        assertTrue(CompilerSymbolEvidenceDocument.fromSignature(empty) is Refinement.Rejected)
    }

    @Test
    fun `local wire and CLI signatures preserve full address and independent expected shape`() {
        for ((signature, resource) in
            listOf(function() to "local-function.json", property() to "local-property.json")) {
            assertEquals(
                expected(resource),
                wireJson.encodeToJsonElement(CompilerSignatureWireDocument.serializer(), signature),
            )
            val contract = signature.toContract().converted()
            assertEquals(
                expected(resource),
                wireJson.encodeToJsonElement(CompilerSignatureCliDocument.serializer(), contract.toCliDocument()),
            )
            val evidence = CompilerSymbolEvidenceDocument.fromSignature(contract).refined()
            assertEquals(WireDocumentConversion.Converted(evidence), evidence.toWireDocument().toContract())
            when (contract) {
                is CompilerSignatureDocument.LocalFunction -> {
                    assertTrue(contract is CompilerCallableSignatureDocument)
                    assertEquals(listOf("kotlin.String"), contract.valueParameters.values.map { it.value })
                }
                is CompilerSignatureDocument.LocalProperty ->
                    assertEquals(LocalPropertyMutabilityDocument.VAR, contract.mutability)
                else -> error("Unexpected signature")
            }
        }
    }

    @Test
    fun `local wire admission rejects invalid ownership and identity`() {
        val local = function()
        assertEquals(
            WireDocumentConversion.Rejected,
            local.copy(address = local.address.copy(ownerRange = SourceRangeWireDocument(25, 100))).toContract(),
        )
        assertEquals(
            WireDocumentConversion.Rejected,
            local
                .copy(address = local.address.copy(lexicalOwners = List(33) { SourceRangeWireDocument(10, 90) }))
                .toContract(),
        )
        assertEquals(
            WireDocumentConversion.Rejected,
            local
                .copy(
                    address = local.address.copy(file = LocalDeclarationFileWireDocument.Workspace("relative/Seed.kt"))
                )
                .toContract(),
        )
        val wrongKind = local.copy(address = local.address.copy(kind = LocalDeclarationKindWireDocument.PROPERTY))
        assertEquals(WireDocumentConversion.Rejected, wrongKind.toContract())
        assertEquals(
            WireDocumentConversion.Rejected,
            property().copy(address = address(LocalDeclarationKindWireDocument.FUNCTION)).toContract(),
        )
        val admitted = local.toContract().converted()
        val evidence = CompilerSymbolEvidenceDocument.fromSignature(admitted).refined().toWireDocument()
        assertEquals(WireDocumentConversion.Rejected, evidence.copy(identity = "forged").toContract())
        val symbol =
            SymbolWireDocument(
                "selector",
                SymbolKindWireDocument.FUNCTION,
                "local",
                null,
                "/workspace/Other.kt",
                SourceRangeWireDocument(20, 40),
                evidence,
            )
        assertEquals(WireDocumentConversion.Rejected, symbol.toContract())
    }

    @Test
    fun `local wire decoder rejects missing discriminators unknown variants and extra fields`() {
        for (raw in
            listOf(
                expected("local-missing-discriminator.json").toString(),
                expected("local-unknown-variant.json").toString(),
                expected("local-property.json").toString().dropLast(1) + ",\"extra\":true}",
                expected("local-property.json").toString().replace("\"VAR\"", "\"UNKNOWN\""),
                expected("local-property.json").toString().replace("\"kind\":\"PROPERTY\",", ""),
                expected("local-property.json").toString().replace("\"kind\":\"PROPERTY\"", "\"kind\":\"PARAMETER\""),
            )) assertThrows(SerializationException::class.java) {
            wireJson.decodeFromString(CompilerSignatureWireDocument.serializer(), raw)
        }
    }

    @Test
    fun `local owner digest is constrained in wire and CLI descriptors and admission`() {
        val expectedPattern = "^canonical-signature-sha256-v1\\|[0-9a-f]{64}$"
        for (descriptor in
            listOf(
                LocalDeclarationAddressWireDocument.serializer().descriptor,
                LocalDeclarationAddressCliDocument.serializer().descriptor,
            )) {
            val ownerIndex = descriptor.getElementIndex("ownerIdentity")
            val constraint =
                descriptor.getElementAnnotations(ownerIndex).filterIsInstance<ProtocolStringConstraint>().single()
            assertEquals(expectedPattern, constraint.pattern)
            assertTrue(Regex(constraint.pattern).matches("canonical-signature-sha256-v1|${"a".repeat(64)}"))
            assertEquals(false, Regex(constraint.pattern).matches("sample.owner"))
        }
        for (owner in listOf("sample.owner", "canonical-signature-sha256-v1|${"A".repeat(64)}")) assertEquals(
            WireDocumentConversion.Rejected,
            function().copy(address = address().copy(ownerIdentity = owner)).toContract(),
        )
    }

    private fun function() =
        CompilerSignatureWireDocument.LocalFunction(
            address(),
            CompilerReceiverWireDocument.Absent,
            emptyList(),
            listOf("kotlin.String"),
            0,
            "kotlin.Int",
        )

    private fun property() =
        CompilerSignatureWireDocument.LocalProperty(
            address(LocalDeclarationKindWireDocument.PROPERTY),
            "kotlin.Int",
            LocalPropertyMutabilityWireDocument.VAR,
        )

    private fun address(kind: LocalDeclarationKindWireDocument = LocalDeclarationKindWireDocument.FUNCTION) =
        LocalDeclarationAddressWireDocument(
            LocalDeclarationFileWireDocument.Workspace("/workspace/Seed.kt"),
            kind,
            SourceRangeWireDocument(20, 40),
            "canonical-signature-sha256-v1|${"a".repeat(64)}",
            SourceRangeWireDocument(0, 100),
            listOf(SourceRangeWireDocument(10, 90)),
        )

    private fun expected(name: String) =
        wireJson.parseToJsonElement(checkNotNull(javaClass.getResource("/symbol/$name")).readText())

    private fun <T> WireDocumentConversion<T>.converted(): T =
        when (this) {
            is WireDocumentConversion.Converted -> value
            WireDocumentConversion.Rejected -> error("Unexpected rejection")
        }

    private fun <T, F> Refinement<T, F>.refined(): T =
        when (this) {
            is Refinement.Refined -> value
            is Refinement.Rejected -> error(failure.toString())
        }
}
