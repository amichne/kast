package io.github.amichne.kast.api.contract.skill

import io.github.amichne.kast.api.contract.Location
import io.github.amichne.kast.api.contract.NonNegativeInt
import io.github.amichne.kast.api.contract.NormalizedPath
import io.github.amichne.kast.api.contract.PageInfo
import io.github.amichne.kast.api.contract.SymbolIdentity
import io.github.amichne.kast.api.contract.SymbolKind
import io.github.amichne.kast.api.contract.result.ContainingSymbolEvidence
import io.github.amichne.kast.api.contract.result.ReferenceOccurrence
import io.github.amichne.kast.api.contract.result.RelationCursorInvalidReason
import io.github.amichne.kast.api.contract.result.RelationCursorStaleReason
import io.github.amichne.kast.api.contract.result.ResultCardinality
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class KastReferencesResponseContractTest {
    private val json = Json { explicitNulls = false }

    @Test
    fun `references requests and responses carry one anchored closed contract`() {
        val selector = KastExactSymbolSelector(
            fqName = "sample.Controller.handle",
            declarationFile = "/repo/Controller.kt",
            declarationStartOffset = 10,
            kind = SymbolKind.FUNCTION,
            containingType = "sample.Controller",
        )
        val request = KastReferencesRequest(
            workspaceRoot = "/repo",
            selector = selector,
            includeDeclaration = true,
            includeUsageSiteScope = true,
            maxResults = 4,
            pageToken = "00000000-0000-0000-0000-000000000337",
        )
        assertEquals(selector, request.selector)

        val subject = SymbolIdentity(
            fqName = selector.fqName,
            kind = SymbolKind.FUNCTION,
            declarationFile = NormalizedPath.parse(selector.declarationFile),
            declarationStartOffset = NonNegativeInt(selector.declarationStartOffset),
            containingType = selector.containingType,
        )
        val occurrence = ReferenceOccurrence(
            location = Location(
                filePath = "/repo/Use.kt",
                startOffset = 20,
                endOffset = 26,
                startLine = 2,
                startColumn = 5,
                preview = "handle()",
            ),
            containingSymbol = ContainingSymbolEvidence.TopLevel,
        )
        val responses: Map<String, KastReferencesResponse> = mapOf(
            "AVAILABLE" to KastReferencesAvailableResponse(
                subject = subject,
                references = listOf(occurrence),
                cardinality = ResultCardinality.KnownMinimum(2),
                page = PageInfo(
                    truncated = true,
                    nextPageToken = "00000000-0000-0000-0000-000000000338",
                ),
            ),
            "SUBJECT_NOT_FOUND" to KastReferencesSubjectNotFoundResponse(selector),
            "SUBJECT_IDENTITY_MISMATCH" to KastReferencesSubjectIdentityMismatchResponse(selector, subject),
            "UNSUPPORTED_SUBJECT_KIND" to KastReferencesUnsupportedSubjectKindResponse(selector, subject),
            "DEGRADED" to KastReferencesDegradedResponse(
                selector,
                subject,
                KastReferencesDegradedReason.INDEX_IDENTITY_UNAVAILABLE,
            ),
            "CURSOR_STALE" to KastReferencesCursorStaleResponse(
                selector,
                RelationCursorStaleReason.GENERATION_CHANGED,
            ),
            "CURSOR_INVALID" to KastReferencesCursorInvalidResponse(
                selector,
                RelationCursorInvalidReason.UNKNOWN_HANDLE,
            ),
        )

        responses.forEach { (expectedType, response) ->
            val encoded = json.encodeToString(KastReferencesResponse.serializer(), response)
            assertEquals(expectedType, json.parseToJsonElement(encoded).jsonObject.getValue("type").jsonPrimitive.content)
            assertEquals(response, json.decodeFromString(KastReferencesResponse.serializer(), encoded))
        }
    }
}
