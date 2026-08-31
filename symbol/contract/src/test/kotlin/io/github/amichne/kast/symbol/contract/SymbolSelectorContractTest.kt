package io.github.amichne.kast.symbol.contract

import io.github.amichne.kast.symbol.contract.SymbolNameDiscoveryKind

import io.github.amichne.kast.kernel.ElapsedTimeLimitMillis
import io.github.amichne.kast.kernel.EvidenceGeneration
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.ResourceBudget
import io.github.amichne.kast.kernel.ResultLimit
import io.github.amichne.kast.kernel.WorkUnitLimit
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import io.github.amichne.kast.workspace.contract.SemanticReadLease
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.nio.file.Path

class SymbolSelectorContractTest {
    @Test
    fun `compiler evidence retains canonical signature and rejects mismatched restored identity`() {
        val selection = selection()
        val location = selection.candidate.location as SymbolDiscoveryCandidateLocation.Declaration
        val signature = CanonicalCompilerSignature.function(
            rawQualifiedIdentity = "sample.Service.call",
            rawReceiverType = "sample.Service",
            rawContextReceiverTypes = listOf("sample.Context"),
            rawValueParameterTypes = listOf("kotlin.Int"),
            rawTypeParameterCount = 1,
        ).refined()
        val evidence = CompilerGroundedSymbolEvidence.fromBoundary(
            file = location.file,
            rawStartInclusive = location.offset.value,
            rawEndExclusive = location.offset.value + 10,
            rawName = selection.candidate.name.value,
            rawQualifiedIdentity = "sample.Service.call",
            kind = CompilerSymbolKind.FUNCTION,
            signature = signature,
        ).refined()
        val function = evidence.signature as CanonicalCompilerSignature.Function

        assertEquals("sample.Service.call", function.qualifiedIdentity.value)
        assertEquals(
            CanonicalCompilerReceiver.Present(CanonicalCompilerType("sample.Service")),
            function.receiver,
        )
        assertEquals(listOf(CanonicalCompilerType("sample.Context")), function.contextReceivers)
        assertEquals(listOf(CanonicalCompilerType("kotlin.Int")), function.valueParameters)
        assertEquals(CanonicalTypeParameterCount(1), function.typeParameterCount)
        assertEquals(
            CompilerSymbolIdentity.fromCanonicalSignature(signature),
            evidence.compilerIdentity,
        )
        assertEquals(
            CompilerGroundedSymbolEvidenceFailure.COMPILER_IDENTITY_MISMATCH,
            CompilerGroundedSymbolEvidence.restoreBoundary(
                file = location.file,
                rawStartInclusive = location.offset.value,
                rawEndExclusive = location.offset.value + 10,
                rawName = selection.candidate.name.value,
                rawQualifiedIdentity = "sample.Service.call",
                kind = CompilerSymbolKind.FUNCTION,
                signature = signature,
                compilerIdentity = CompilerSymbolIdentity.fromCanonicalSignature(
                    CanonicalCompilerSignature.function(
                        rawQualifiedIdentity = "sample.Service.other",
                        rawReceiverType = null,
                        rawContextReceiverTypes = emptyList(),
                        rawValueParameterTypes = emptyList(),
                        rawTypeParameterCount = 0,
                    ).refined(),
                ),
            ).rejected(),
        )
    }

    @Test
    fun `canonical signatures produce bounded deterministic compiler identities`() {
        val longType = "sample.DeepType<${"kotlin.String,".repeat(500)}kotlin.Int>"
        val signature = CanonicalCompilerSignature.function(
            rawQualifiedIdentity = "sample.Service.call",
            rawReceiverType = null,
            rawContextReceiverTypes = emptyList(),
            rawValueParameterTypes = listOf(longType),
            rawTypeParameterCount = 0,
        ).refined()
        val same = CompilerSymbolIdentity.fromCanonicalSignature(signature)
        val changed = CompilerSymbolIdentity.fromCanonicalSignature(
            CanonicalCompilerSignature.function(
                rawQualifiedIdentity = "sample.Service.call",
                rawReceiverType = null,
                rawContextReceiverTypes = emptyList(),
                rawValueParameterTypes = listOf("kotlin.Long"),
                rawTypeParameterCount = 0,
            ).refined(),
        )

        assertEquals(same, CompilerSymbolIdentity.fromCanonicalSignature(signature))
        assertNotEquals(same, changed)
        assertEquals(94, same.value.length)
        assertEquals(true, same.value.startsWith("canonical-signature-sha256-v1|"))
        assertEquals(same, CompilerSymbolIdentity.parse(same.value).refined())
    }

    @Test
    fun `canonical signature collections cannot mutate after identity derivation`() {
        val signature = CanonicalCompilerSignature.function(
            rawQualifiedIdentity = "sample.Service.call",
            rawReceiverType = null,
            rawContextReceiverTypes = listOf("sample.Context"),
            rawValueParameterTypes = listOf("kotlin.Int"),
            rawTypeParameterCount = 0,
        ).refined() as CanonicalCompilerSignature.Function
        val identity = CompilerSymbolIdentity.fromCanonicalSignature(signature)

        assertThrows(UnsupportedOperationException::class.java) {
            @Suppress("UNCHECKED_CAST")
            (signature.contextReceivers as MutableList<CanonicalCompilerType>).clear()
        }
        assertThrows(UnsupportedOperationException::class.java) {
            @Suppress("UNCHECKED_CAST")
            (signature.valueParameters as MutableList<CanonicalCompilerType>).clear()
        }
        assertEquals(listOf(CanonicalCompilerType("sample.Context")), signature.contextReceivers)
        assertEquals(listOf(CanonicalCompilerType("kotlin.Int")), signature.valueParameters)
        assertEquals(identity, CompilerSymbolIdentity.fromCanonicalSignature(signature))
    }

    @Test
    fun `property identities retain extension and context receivers`() {
        val stringReceiver = CanonicalCompilerSignature.property(
            rawQualifiedIdentity = "sample.tag",
            rawReceiverType = "kotlin.String",
            rawContextReceiverTypes = listOf("sample.Context"),
            rawReturnType = "kotlin.Int",
        ).refined() as CanonicalCompilerSignature.Property
        val intReceiver = CanonicalCompilerSignature.property(
            rawQualifiedIdentity = "sample.tag",
            rawReceiverType = "kotlin.Int",
            rawContextReceiverTypes = listOf("sample.Context"),
            rawReturnType = "kotlin.Int",
        ).refined()
        val otherContext = CanonicalCompilerSignature.property(
            rawQualifiedIdentity = "sample.tag",
            rawReceiverType = "kotlin.String",
            rawContextReceiverTypes = listOf("sample.OtherContext"),
            rawReturnType = "kotlin.Int",
        ).refined()

        assertEquals(
            CanonicalCompilerReceiver.Present(CanonicalCompilerType("kotlin.String")),
            stringReceiver.receiver,
        )
        assertEquals(
            listOf(CanonicalCompilerType("sample.Context")),
            stringReceiver.contextReceivers,
        )
        assertNotEquals(
            CompilerSymbolIdentity.fromCanonicalSignature(stringReceiver),
            CompilerSymbolIdentity.fromCanonicalSignature(intReceiver),
        )
        assertNotEquals(
            CompilerSymbolIdentity.fromCanonicalSignature(stringReceiver),
            CompilerSymbolIdentity.fromCanonicalSignature(otherContext),
        )
        assertEquals(
            stringReceiver,
            CanonicalCompilerSignature.restoreCanonicalEncoding(
                stringReceiver.canonicalEncoding().value,
            ).refined(),
        )
        assertThrows(UnsupportedOperationException::class.java) {
            @Suppress("UNCHECKED_CAST")
            (stringReceiver.contextReceivers as MutableList<CanonicalCompilerType>).clear()
        }
    }

    @Test
    fun `compiler evidence rejects a qualified identity that disagrees with its signature`() {
        val selection = selection()
        val location = selection.candidate.location as SymbolDiscoveryCandidateLocation.Declaration
        val signature = CanonicalCompilerSignature.function(
            rawQualifiedIdentity = "sample.Service.call",
            rawReceiverType = null,
            rawContextReceiverTypes = emptyList(),
            rawValueParameterTypes = emptyList(),
            rawTypeParameterCount = 0,
        ).refined()

        assertEquals(
            "QUALIFIED_IDENTITY_MISMATCH",
            CompilerGroundedSymbolEvidence.fromBoundary(
                file = location.file,
                rawStartInclusive = location.offset.value,
                rawEndExclusive = location.offset.value + 10,
                rawName = selection.candidate.name.value,
                rawQualifiedIdentity = "sample.Service.other",
                kind = CompilerSymbolKind.FUNCTION,
                signature = signature,
            ).rejected().name,
        )
        assertEquals(
            "QUALIFIED_IDENTITY_MISMATCH",
            CompilerGroundedSymbolEvidence.fromBoundary(
                file = location.file,
                rawStartInclusive = location.offset.value,
                rawEndExclusive = location.offset.value + 10,
                rawName = selection.candidate.name.value,
                rawQualifiedIdentity = null,
                kind = CompilerSymbolKind.FUNCTION,
                signature = signature,
            ).rejected().name,
        )
    }

    @Test
    fun `canonical signatures reject invalid components`() {
        assertEquals(
            CanonicalCompilerSignatureFailure.INVALID_VALUE_PARAMETER_TYPE,
            CanonicalCompilerSignature.function(
                rawQualifiedIdentity = "sample.Service.call",
                rawReceiverType = null,
                rawContextReceiverTypes = emptyList(),
                rawValueParameterTypes = listOf(" \n "),
                rawTypeParameterCount = 0,
            ).rejected(),
        )
        assertEquals(
            CanonicalCompilerSignatureFailure.INVALID_TYPE_PARAMETER_COUNT,
            CanonicalCompilerSignature.function(
                rawQualifiedIdentity = "sample.Service.call",
                rawReceiverType = null,
                rawContextReceiverTypes = emptyList(),
                rawValueParameterTypes = emptyList(),
                rawTypeParameterCount = -1,
            ).rejected(),
        )
    }

    @Test
    fun `compiler identity parsing fails closed`() {
        assertEquals(
            CompilerSymbolIdentityFailure.BLANK,
            CompilerSymbolIdentity.parse(" ").rejected(),
        )
        assertEquals(
            CompilerSymbolIdentityFailure.CONTROL_CHARACTER,
            CompilerSymbolIdentity.parse("sample.Service.call\n").rejected(),
        )
        assertEquals(
            CompilerSymbolIdentityFailure.TOO_LONG,
            CompilerSymbolIdentity.parse("x".repeat(4097)).rejected(),
        )
    }

    @Test
    fun `compiler identity is sealed into exact selection and revalidation`() {
        val selection = selection()
        val firstEvidence = evidence(selection, "kotlin.Int")
        val secondEvidence = evidence(selection, "kotlin.String")
        val first = SymbolSelector.issue(selection, firstEvidence).refined()
        val second = SymbolSelector.issue(selection, secondEvidence).refined()

        assertNotEquals(first.fingerprint, second.fingerprint)
        assertEquals(
            first,
            RevalidatedSymbolSelector.validate(first, firstEvidence).refined().selector,
        )
        assertEquals(
            SymbolSelectorRevalidationFailure.DECLARATION_MOVED_OR_CHANGED,
            RevalidatedSymbolSelector.validate(first, secondEvidence).rejected(),
        )
    }

    @Test
    fun `compiler evidence cannot escape the selected file name or offset`() {
        val selection = selection()
        val location = selection.candidate.location as SymbolDiscoveryCandidateLocation.Declaration
        val signature = CanonicalCompilerSignature.function(
            rawQualifiedIdentity = "sample.Service.call",
            rawReceiverType = null,
            rawContextReceiverTypes = emptyList(),
            rawValueParameterTypes = listOf("kotlin.Int"),
            rawTypeParameterCount = 0,
        ).refined()
        val otherFile = SymbolDiscoveryFileIdentity.fromBoundary(
            selection.lease.workspaceRoot,
            Path.of("/workspace/src/Other.kt"),
            "file:///workspace/src/Other.kt",
        ).refined()

        assertEquals(
            SymbolSelectorIssueFailure.FILE_MISMATCH,
            SymbolSelector.issue(
                selection,
                CompilerGroundedSymbolEvidence.fromBoundary(
                    otherFile,
                    location.offset.value,
                    location.offset.value + 10,
                    selection.candidate.name.value,
                    "sample.Service.call",
                    CompilerSymbolKind.FUNCTION,
                    signature,
                ).refined(),
            ).rejected(),
        )
        assertEquals(
            SymbolSelectorIssueFailure.NAME_MISMATCH,
            SymbolSelector.issue(
                selection,
                CompilerGroundedSymbolEvidence.fromBoundary(
                    location.file,
                    location.offset.value,
                    location.offset.value + 10,
                    "other",
                    "sample.Service.call",
                    CompilerSymbolKind.FUNCTION,
                    signature,
                ).refined(),
            ).rejected(),
        )
        assertEquals(
            SymbolSelectorIssueFailure.START_OFFSET_MISMATCH,
            SymbolSelector.issue(
                selection,
                CompilerGroundedSymbolEvidence.fromBoundary(
                    location.file,
                    location.offset.value + 1,
                    location.offset.value + 10,
                    selection.candidate.name.value,
                    "sample.Service.call",
                    CompilerSymbolKind.FUNCTION,
                    signature,
                ).refined(),
            ).rejected(),
        )
    }

    private fun evidence(
        selection: SymbolDiscoverySelection,
        parameterType: String,
    ): CompilerGroundedSymbolEvidence {
        val location = selection.candidate.location as SymbolDiscoveryCandidateLocation.Declaration
        val signature = CanonicalCompilerSignature.function(
            rawQualifiedIdentity = "sample.Service.call",
            rawReceiverType = null,
            rawContextReceiverTypes = emptyList(),
            rawValueParameterTypes = listOf(parameterType),
            rawTypeParameterCount = 0,
        ).refined()
        return CompilerGroundedSymbolEvidence.fromBoundary(
            location.file,
            location.offset.value,
            location.offset.value + 10,
            selection.candidate.name.value,
            "sample.Service.call",
            CompilerSymbolKind.FUNCTION,
            signature,
        ).refined()
    }

    private fun selection(): SymbolDiscoverySelection {
        val lease = SemanticReadLease(
            CanonicalWorkspaceRoot.fromCanonicalPath(Path.of("/workspace")).refined(),
            EvidenceGeneration.parse(7L).refined(),
        )
        val request = SymbolDiscoveryRequest(
            SymbolSearchScopeRequest(
                lease,
                SymbolSearchScope.Workspace(
                    SymbolSourceKindPolicy.PRODUCTION_AND_TEST,
                    SymbolGeneratedSourcePolicy.INCLUDE,
                    SymbolLibraryPolicy.EXCLUDE,
                ),
            ),
            SymbolDiscoveryTarget.Name(
                SymbolNameDiscoveryKind.SYMBOL,
                SymbolDiscoveryPattern.parse("call").refined(),
                SymbolDiscoveryMatch.FUZZY,
            ),
            SymbolDiscoveryBudget(
                ResourceBudget(
                    ResultLimit.parse(1).refined(),
                    WorkUnitLimit.parse(10L).refined(),
                    ElapsedTimeLimitMillis.parse(1_000L).refined(),
                ),
                SymbolDiscoveryByteLimit.parse(10_000L).refined(),
            ),
        )
        val candidate = SymbolDiscoveryCandidate.fromBoundary(
            SymbolDiscoveryKind.SYMBOL,
            "call",
            lease,
            Path.of("/workspace/src/Service.kt"),
            "file:///workspace/src/Service.kt",
            7,
        ).refined()
        val batch = SymbolDiscoveryBatch.create(
            request,
            listOf(candidate),
            candidate.projectedUtf8Size(),
            SymbolDiscoveryWorkCount.parse(1L).refined(),
            SymbolDiscoveryTimings(
                SymbolDiscoveryElapsedNanoseconds.parse(1L).refined(),
                SymbolDiscoveryElapsedNanoseconds.parse(1L).refined(),
            ),
        ).refined()
        return SymbolDiscoverySelection.select(batch, 0).refined()
    }

    private fun <Strong, Failure> Refinement<Strong, Failure>.refined(): Strong = when (this) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> error(failure.toString())
    }

    private fun <Strong, Failure> Refinement<Strong, Failure>.rejected(): Failure = when (this) {
        is Refinement.Refined -> error("expected rejection")
        is Refinement.Rejected -> failure
    }
}
