package io.github.amichne.kast.symbol.contract

import io.github.amichne.kast.kernel.Refinement
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LocalDeclarationIdentityTest {
    @Test
    fun `anonymous objects retain distinct compiler owners and reject invalid type or address facts`() {
        val first = address(20, 40, ownerName = "sample.first", kind = LocalDeclarationKind.ANONYMOUS_OBJECT)
        val second = address(20, 40, ownerName = "sample.second", kind = LocalDeclarationKind.ANONYMOUS_OBJECT)
        val a = CanonicalCompilerSignature.anonymousObject(first, listOf("sample.Collector<kotlin.String>")).value()
        val b = CanonicalCompilerSignature.anonymousObject(second, listOf("sample.Collector<kotlin.String>")).value()
        assertNotEquals(
            CompilerSymbolIdentity.fromCanonicalSignature(a),
            CompilerSymbolIdentity.fromCanonicalSignature(b),
        )
        assertEquals(a, CanonicalCompilerSignature.restoreCanonicalEncoding(a.canonicalEncoding().value).value())
        assertEquals(
            ExactRevalidationPolicy.ORIGINAL_DOCUMENT,
            ExactRevalidationPolicy.CURRENT_DECLARATION.forDeclaration(a),
        )
        assertEquals(
            CanonicalCompilerSignatureFailure.LOCAL_ADDRESS_KIND_MISMATCH,
            (CanonicalCompilerSignature.anonymousObject(address(20, 40), listOf("sample.Collector"))
                    as Refinement.Rejected)
                .failure,
        )
        for (types in listOf(emptyList(), listOf(" "), listOf("sample.Collector\u0000"))) {
            assertEquals(
                CanonicalCompilerSignatureFailure.INVALID_SUPERTYPE,
                (CanonicalCompilerSignature.anonymousObject(first, types) as Refinement.Rejected).failure,
            )
        }
        val member =
            LocalDeclarationAddress.create(
                    first.file,
                    LocalDeclarationKind.FUNCTION,
                    range(25, 35),
                    CompilerSymbolIdentity.fromCanonicalSignature(a),
                    first.range,
                    emptyList(),
                )
                .value()
        val emit =
            CanonicalCompilerSignature.localFunction(
                    member,
                    null,
                    emptyList(),
                    listOf("kotlin.String"),
                    0,
                    "kotlin.Unit",
                )
                .value()
        assertEquals(CompilerSymbolIdentity.fromCanonicalSignature(a), member.ownerIdentity)
        assertEquals(emit, CanonicalCompilerSignature.restoreCanonicalEncoding(emit.canonicalEncoding().value).value())
    }

    @Test
    fun `local compiler owner identity rejects noncanonical or malformed digests`() {
        val file = LocalDeclarationAddress.restoreFile("workspace", "/workspace/Probe.kt").value()
        for (raw in
            listOf(
                "owner",
                "canonical-signature-sha256-v1|" + "a".repeat(63),
                "canonical-signature-sha256-v1|" + "A".repeat(64),
                "canonical-signature-sha256-v1|" + "g".repeat(64),
            )) {
            val owner = CompilerSymbolIdentity.parse(raw).value()
            val admitted =
                LocalDeclarationAddress.create(
                    file,
                    LocalDeclarationKind.PROPERTY,
                    range(20, 30),
                    owner,
                    range(0, 100),
                    emptyList(),
                ) as Refinement.Rejected
            assertEquals(LocalDeclarationAddressFailure.INVALID_OWNER_IDENTITY, admitted.failure)
        }
    }

    @Test
    fun `local signature factories reject a contradictory compiler declaration kind`() {
        val property = address(20, 30)
        val function = address(20, 30, kind = LocalDeclarationKind.FUNCTION)
        assertEquals(
            CanonicalCompilerSignatureFailure.LOCAL_ADDRESS_KIND_MISMATCH,
            (CanonicalCompilerSignature.localFunction(property, null, emptyList(), emptyList(), 0, "kotlin.Int")
                    as Refinement.Rejected)
                .failure,
        )
        assertEquals(
            CanonicalCompilerSignatureFailure.LOCAL_ADDRESS_KIND_MISMATCH,
            (CanonicalCompilerSignature.localProperty(function, "kotlin.Int", LocalPropertyMutability.VAL)
                    as Refinement.Rejected)
                .failure,
        )
    }

    @Test
    fun `qualified encoding remains exact and local encodings round trip`() {
        val qualified = CanonicalCompilerSignature.function("sample.owner", null, emptyList(), emptyList(), 0).value()
        assertEquals(
            "22:canonical-signature-v18:function12:sample.owner15:receiver-absent1:01:01:0",
            qualified.canonicalEncoding().value,
        )
        val local =
            CanonicalCompilerSignature.localProperty(address(20, 30), "kotlin.String", LocalPropertyMutability.VAL)
                .value()
        assertEquals(
            local,
            CanonicalCompilerSignature.restoreCanonicalEncoding(local.canonicalEncoding().value).value(),
        )
        assertEquals(
            ExactRevalidationPolicy.ORIGINAL_DOCUMENT,
            ExactRevalidationPolicy.CURRENT_DECLARATION.forDeclaration(local),
        )
        assertEquals(
            ExactRevalidationPolicy.CURRENT_DECLARATION,
            ExactRevalidationPolicy.CURRENT_DECLARATION.forDeclaration(qualified),
        )
        val callable =
            CanonicalCompilerSignature.localFunction(
                    address(35, 45, kind = LocalDeclarationKind.FUNCTION),
                    null,
                    emptyList(),
                    listOf("kotlin.Int"),
                    0,
                    "kotlin.String",
                )
                .value()
        assertEquals(
            callable,
            CanonicalCompilerSignature.restoreCanonicalEncoding(callable.canonicalEncoding().value).value(),
        )
    }

    @Test
    fun `local identity distinguishes scopes files owners signatures and mutability`() {
        val first =
            CanonicalCompilerSignature.localProperty(address(20, 30), "kotlin.String", LocalPropertyMutability.VAL)
                .value()
        val variants =
            listOf(
                CanonicalCompilerSignature.localProperty(address(40, 50), "kotlin.String", LocalPropertyMutability.VAL)
                    .value(),
                CanonicalCompilerSignature.localProperty(
                        address(20, 30, "/workspace/Other.kt"),
                        "kotlin.String",
                        LocalPropertyMutability.VAL,
                    )
                    .value(),
                CanonicalCompilerSignature.localProperty(
                        address(20, 30, ownerName = "sample.other"),
                        "kotlin.String",
                        LocalPropertyMutability.VAL,
                    )
                    .value(),
                CanonicalCompilerSignature.localProperty(address(20, 30), "kotlin.Int", LocalPropertyMutability.VAL)
                    .value(),
                CanonicalCompilerSignature.localProperty(address(20, 30), "kotlin.String", LocalPropertyMutability.VAR)
                    .value(),
            )
        variants.forEach {
            assertNotEquals(
                CompilerSymbolIdentity.fromCanonicalSignature(first),
                CompilerSymbolIdentity.fromCanonicalSignature(it),
            )
        }
    }

    @Test
    fun `local evidence admits unavailable qualified name but rejects changed address and kind`() {
        val address = address(20, 30)
        val signature =
            CanonicalCompilerSignature.localProperty(address, "kotlin.Int", LocalPropertyMutability.VAL).value()
        fun evidence(start: Int, name: String?, kind: CompilerSymbolKind) =
            CompilerGroundedSymbolEvidence.fromBoundary(address.file, start, 30, "value", name, kind, signature)
        assertEquals(signature, evidence(20, null, CompilerSymbolKind.PROPERTY).value().signature)
        assertEquals(
            CompilerGroundedSymbolEvidenceFailure.LOCAL_ADDRESS_MISMATCH,
            (evidence(21, null, CompilerSymbolKind.PROPERTY) as Refinement.Rejected).failure,
        )
        assertEquals(
            CompilerGroundedSymbolEvidenceFailure.QUALIFIED_IDENTITY_MISMATCH,
            (evidence(20, "sample.value", CompilerSymbolKind.PROPERTY) as Refinement.Rejected).failure,
        )
        assertEquals(
            CompilerGroundedSymbolEvidenceFailure.SIGNATURE_KIND_MISMATCH,
            (evidence(20, null, CompilerSymbolKind.FUNCTION) as Refinement.Rejected).failure,
        )
    }

    @Test
    fun `malformed lexical chains and oversized owner depth reject`() {
        val file = LocalDeclarationAddress.restoreFile("workspace", "/workspace/Probe.kt").value()
        val owner =
            CompilerSymbolIdentity.fromCanonicalSignature(
                CanonicalCompilerSignature.function("sample.owner", null, emptyList(), emptyList(), 0).value()
            )
        fun create(owners: List<ExactDeclarationTextRange>) =
            LocalDeclarationAddress.create(
                file,
                LocalDeclarationKind.PROPERTY,
                range(20, 30),
                owner,
                range(0, 100),
                owners,
            )
        assertTrue(create(listOf(range(10, 90), range(5, 95))) is Refinement.Rejected)
        assertTrue(create(listOf(range(25, 40))) is Refinement.Rejected)
        assertTrue(create(List(33) { range(10, 90) }) is Refinement.Rejected)
        assertTrue(
            CanonicalCompilerSignature.restoreCanonicalEncoding("22:canonical-signature-v117:local-property-v1")
                is Refinement.Rejected
        )
    }

    private fun address(
        start: Int,
        end: Int,
        file: String = "/workspace/Probe.kt",
        ownerName: String = "sample.owner",
        kind: LocalDeclarationKind = LocalDeclarationKind.PROPERTY,
    ) =
        LocalDeclarationAddress.create(
                LocalDeclarationAddress.restoreFile("workspace", file).value(),
                kind,
                range(start, end),
                CompilerSymbolIdentity.fromCanonicalSignature(
                    CanonicalCompilerSignature.function(ownerName, null, emptyList(), emptyList(), 0).value()
                ),
                range(0, 100),
                listOf(range(10, 90)),
            )
            .value()

    private fun range(start: Int, end: Int) = ExactDeclarationTextRange.parse(start, end).value()

    private fun <V, F> Refinement<V, F>.value(): V =
        when (this) {
            is Refinement.Refined -> value
            is Refinement.Rejected -> error("Rejected: $failure")
        }
}
