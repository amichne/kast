package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.protocol.contract.SourceBodyKindDocument
import io.github.amichne.kast.protocol.contract.SourceEntitySelectionDocument
import io.github.amichne.kast.protocol.contract.SourceReadAnchorDocument
import io.github.amichne.kast.protocol.contract.SourceReadFailureDetail
import io.github.amichne.kast.protocol.contract.SourceReadRequest
import io.github.amichne.kast.protocol.contract.SourceReferenceFailure
import io.github.amichne.kast.protocol.contract.SourceReferenceRole
import io.github.amichne.kast.protocol.contract.SourceRegionSelectionDocument
import io.github.amichne.kast.protocol.contract.SourceTextRequestDocument
import io.github.amichne.kast.source.contract.BodyKind
import io.github.amichne.kast.source.contract.RegionSelection
import io.github.amichne.kast.source.contract.SourceReadAnchor
import io.github.amichne.kast.source.contract.SourceReadOperations
import io.github.amichne.kast.source.contract.SourceReadRejection
import io.github.amichne.kast.source.contract.SourceReadResult
import io.github.amichne.kast.source.contract.SourceTextByteLimit
import io.github.amichne.kast.symbol.contract.SymbolSelector
import io.github.amichne.kast.workspace.contract.SemanticReadAuthority
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/** Admission and dispatch proof. The source adapter supplies a finite failure, not compiler evidence. */
class SnapshotSourceReadTest {
    @Test
    fun `snapshot focus preserves changed ambiguous moved and budget failures without acquisition or source effects`() =
        runTest {
            val fixture = RelationPagingFixture.live()
            val failures =
                listOf(
                    CanonicalSelectorDecodingFailure.REVALIDATION_CONTENT_CHANGED to
                        SourceReferenceFailure.REVALIDATION_CONTENT_CHANGED,
                    CanonicalSelectorDecodingFailure.REVALIDATION_AMBIGUOUS to
                        SourceReferenceFailure.REVALIDATION_AMBIGUOUS,
                    CanonicalSelectorDecodingFailure.REVALIDATION_BASIS_MOVED to
                        SourceReferenceFailure.REVALIDATION_BASIS_MOVED,
                    CanonicalSelectorDecodingFailure.REVALIDATION_WORK_LIMIT_REACHED to
                        SourceReferenceFailure.REVALIDATION_WORK_LIMIT_REACHED,
                )
            for ((failure, expected) in failures) {
                var restores = 0
                val authority =
                    object : QueryReferenceAuthority by fixture.references {
                        override fun restoreExact(
                            token: ProtocolText,
                            current: SemanticReadAuthority,
                        ): CanonicalSelectorDecoding<SymbolSelector> {
                            restores++
                            assertEquals(fixture.exact, token)
                            assertEquals(fixture.authority, current)
                            return CanonicalSelectorDecoding.Rejected(failure)
                        }

                        override suspend fun acquireExact(
                            token: ProtocolText,
                            current: SemanticReadAuthority,
                        ): CanonicalSelectorDecoding<SymbolSelector> =
                            error("Snapshot admission must not acquire a current replacement")
                    }
                val result =
                    CanonicalSourceReadProtocol(
                            SourceReadOperations { error("Rejected focus reached the source owner") },
                            authority,
                        )
                        .execute(
                            request(SourceReadAnchorDocument.SnapshotSymbol(fixture.exact)),
                            fixture.authority,
                            budget(fixture),
                        )
                assertEquals(
                    OperationOutcome.Rejected(
                        SourceReadFailureDetail.ReferenceRejected(SourceReferenceRole.SYMBOL, expected)
                    ),
                    result,
                )
                assertEquals(1, restores)
            }
        }

    @Test
    fun `fresh snapshot focus carries the exact selector and requested body without acquisition`() = runTest {
        val fixture = RelationPagingFixture.live()
        var reads = 0
        val authority =
            object : QueryReferenceAuthority by fixture.references {
                override suspend fun acquireExact(
                    token: ProtocolText,
                    current: SemanticReadAuthority,
                ): CanonicalSelectorDecoding<SymbolSelector> = error("A snapshot focus must use strict restoration")
            }
        val result =
            CanonicalSourceReadProtocol(
                    SourceReadOperations { read ->
                        reads++
                        val selected = (read.anchor as SourceReadAnchor.Symbol).selector
                        assertEquals(fixture.selector.fingerprint, selected.fingerprint)
                        assertEquals(fixture.selector.lease, selected.lease)
                        assertEquals(fixture.selector.scope, selected.scope)
                        assertEquals(fixture.selector.constraints, selected.constraints)
                        assertEquals(RegionSelection.Body(BodyKind.CALLABLE), read.region)
                        SourceReadResult.Rejected(SourceReadRejection.DOCUMENT_DIRTY)
                    },
                    authority,
                )
                .execute(
                    request(SourceReadAnchorDocument.SnapshotSymbol(fixture.exact)),
                    fixture.authority,
                    budget(fixture),
                )
        assertEquals(
            OperationOutcome.Rejected(io.github.amichne.kast.protocol.contract.SourceReadRejection.DOCUMENT_DIRTY),
            result,
        )
        assertEquals(1, reads)
    }

    @Test
    fun `legacy exact source reads keep their explicit first page acquisition path`() = runTest {
        val fixture = RelationPagingFixture.live()
        var acquisitions = 0
        var reads = 0
        val authority =
            object : QueryReferenceAuthority by fixture.references {
                override fun restoreExact(
                    token: ProtocolText,
                    current: SemanticReadAuthority,
                ): CanonicalSelectorDecoding<SymbolSelector> =
                    CanonicalSelectorDecoding.Rejected(CanonicalSelectorDecodingFailure.REVALIDATION_CONTENT_CHANGED)

                override suspend fun acquireExact(
                    token: ProtocolText,
                    current: SemanticReadAuthority,
                ): CanonicalSelectorDecoding<SymbolSelector> {
                    acquisitions++
                    return CanonicalSelectorDecoding.Decoded(fixture.selector)
                }
            }
        val result =
            CanonicalSourceReadProtocol(
                    SourceReadOperations {
                        reads++
                        SourceReadResult.Rejected(SourceReadRejection.DOCUMENT_DIRTY)
                    },
                    authority,
                )
                .execute(request(SourceReadAnchorDocument.Symbol(fixture.exact)), fixture.authority, budget(fixture))
        assertEquals(
            OperationOutcome.Rejected(io.github.amichne.kast.protocol.contract.SourceReadRejection.DOCUMENT_DIRTY),
            result,
        )
        assertEquals(1, acquisitions)
        assertEquals(1, reads)
    }

    @Test
    fun `snapshot anchor has a distinct wire discriminator and rejects candidate substitution`() {
        val fixture = RelationPagingFixture.live()
        val request = request(SourceReadAnchorDocument.SnapshotSymbol(fixture.exact))
        val encoded = Json.encodeToJsonElement(SourceReadRequest.serializer(), request)
        val anchor = encoded.jsonObject.getValue("anchor").jsonObject
        assertEquals(setOf("type", "selector"), anchor.keys)
        assertEquals("symbol-snapshot", anchor.getValue("type").jsonPrimitive.content)
        assertEquals(fixture.exact.value, anchor.getValue("selector").jsonPrimitive.content)
        assertEquals(request, Json.decodeFromJsonElement(SourceReadRequest.serializer(), encoded))
        val candidate = (ProtocolText.parse("candidate:v5:AAAAAAAAAAAAAAAAAAAAAA") as Refinement.Refined).value
        assertThrows(SerializationException::class.java) {
            Json.encodeToString(
                SourceReadRequest.serializer(),
                request.copy(anchor = SourceReadAnchorDocument.SnapshotSymbol(candidate)),
            )
        }
    }

    private fun request(anchor: SourceReadAnchorDocument) =
        SourceReadRequest(
            anchor,
            SourceRegionSelectionDocument.Body(SourceBodyKindDocument.CALLABLE),
            SourceEntitySelectionDocument.None,
            SourceTextRequestDocument.Complete,
        )

    private fun budget(fixture: RelationPagingFixture) =
        SourceProtocolBudget(
            fixture.budget.resources,
            (SourceTextByteLimit.parse(10000) as Refinement.Refined).value,
        )
}
