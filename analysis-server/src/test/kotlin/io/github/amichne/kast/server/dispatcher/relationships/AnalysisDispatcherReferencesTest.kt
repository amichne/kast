package io.github.amichne.kast.server

import io.github.amichne.kast.api.contract.*
import io.github.amichne.kast.api.contract.query.*
import io.github.amichne.kast.api.contract.result.*
import io.github.amichne.kast.api.contract.selector.*
import io.github.amichne.kast.api.contract.skill.*
import io.github.amichne.kast.api.protocol.*
import io.github.amichne.kast.api.validation.*
import io.github.amichne.kast.testing.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText

class AnalysisDispatcherReferencesTest : AnalysisDispatcherTestSupport() {
    @Test
    fun `symbol references sends typed bounds to backend and continuation has no duplicates`() {
        val fixture = AnalysisBackendContractFixture.create(tempDir)
        val delegate = FakeAnalysisBackend.contractFixture(fixture)
        val observedQueries = mutableListOf<ParsedReferencesQuery>()
        val backend = object : AnalysisBackend by delegate {
            override suspend fun findReferences(query: ParsedReferencesQuery): ReferencesResult {
                observedQueries += query
                return delegate.findReferences(query)
            }
        }
        val selector = KastExactSymbolSelector(
            fqName = fixture.symbolFqName,
            declarationFile = fixture.declarationLocation.filePath,
            declarationStartOffset = fixture.declarationLocation.startOffset,
            kind = SymbolKind.FUNCTION,
        )

        val firstResult = dispatchSuccessWithBackend<KastReferencesResponse>(
            backend = backend,
            method = "symbol/references",
            params = json.encodeToJsonElement(
                KastReferencesRequest.serializer(),
                KastReferencesRequest(
                    workspaceRoot = tempDir.toString(),
                    selector = selector,
                    maxResults = 1,
                ),
            ),
        )

        val firstPage = assertInstanceOf(KastReferencesAvailableResponse::class.java, firstResult)
        assertEquals(ResultCardinality.KnownMinimum(2), firstPage.cardinality)
        assertEquals(1, firstPage.references.size)
        assertTrue(checkNotNull(firstPage.page).truncated)
        assertTrue(firstPage.page?.nextPageToken != null)

        val secondResult = dispatchSuccessWithBackend<KastReferencesResponse>(
            backend = backend,
            method = "symbol/references",
            params = json.encodeToJsonElement(
                KastReferencesRequest.serializer(),
                KastReferencesRequest(
                    workspaceRoot = tempDir.toString(),
                    selector = selector,
                    maxResults = 1,
                    pageToken = checkNotNull(firstPage.page?.nextPageToken),
                ),
            ),
        )

        val secondPage = assertInstanceOf(KastReferencesAvailableResponse::class.java, secondResult)
        assertEquals(ResultCardinality.Exact(2), secondPage.cardinality)
        assertEquals(1, secondPage.references.size)
        assertEquals(null, secondPage.page)
        assertTrue(firstPage.references.single() !in secondPage.references)
        assertEquals(2, observedQueries.size)
        assertEquals(1, observedQueries[0].maxResults.value)
        assertEquals(null, observedQueries[0].pageToken)
        assertEquals(1, observedQueries[1].maxResults.value)
        assertEquals(firstPage.page?.nextPageToken, observedQueries[1].pageToken?.value)
    }

    @Test
    fun `symbol references preserves an honest paginated result when candidate coverage is complete`() {
        val fixture = AnalysisBackendContractFixture.create(tempDir)
        val delegate = FakeAnalysisBackend.contractFixture(fixture)
        val backend = object : AnalysisBackend by delegate {
            override suspend fun findReferences(query: ParsedReferencesQuery): ReferencesResult =
                delegate.findReferences(query).copy(
                    searchScope = SearchScope(
                        visibility = SymbolVisibility.PUBLIC,
                        scope = SearchScopeKind.DEPENDENT_MODULES,
                        exhaustive = false,
                        candidateCoverage = SearchScope.CandidateCoverage.COMPLETE,
                        candidateFileCount = 2,
                        searchedFileCount = 1,
                    ),
                )
        }
        val selector = KastExactSymbolSelector(
            fqName = fixture.symbolFqName,
            declarationFile = fixture.declarationLocation.filePath,
            declarationStartOffset = fixture.declarationLocation.startOffset,
            kind = SymbolKind.FUNCTION,
        )

        val result = dispatchSuccessWithBackend<KastReferencesResponse>(
            backend = backend,
            method = "symbol/references",
            params = json.encodeToJsonElement(
                KastReferencesRequest.serializer(),
                KastReferencesRequest(
                    workspaceRoot = tempDir.toString(),
                    selector = selector,
                    maxResults = 1,
                ),
            ),
        )

        val available = assertInstanceOf(KastReferencesAvailableResponse::class.java, result)
        assertFalse(checkNotNull(available.searchScope).exhaustive)
        assertEquals(SearchScope.CandidateCoverage.COMPLETE, available.searchScope?.candidateCoverage)
        assertTrue(checkNotNull(available.page).truncated)
    }

    @Test
    fun `symbol references degrades when the underlying candidate search is partial`() {
        val fixture = AnalysisBackendContractFixture.create(tempDir)
        val delegate = FakeAnalysisBackend.contractFixture(fixture)
        val backend = object : AnalysisBackend by delegate {
            override suspend fun findReferences(query: ParsedReferencesQuery): ReferencesResult =
                delegate.findReferences(query).copy(
                    searchScope = SearchScope(
                        visibility = SymbolVisibility.PUBLIC,
                        scope = SearchScopeKind.DEPENDENT_MODULES,
                        exhaustive = false,
                        candidateCoverage = SearchScope.CandidateCoverage.PARTIAL,
                        candidateFileCount = 2,
                        searchedFileCount = 1,
                    ),
                )
        }
        val selector = KastExactSymbolSelector(
            fqName = fixture.symbolFqName,
            declarationFile = fixture.declarationLocation.filePath,
            declarationStartOffset = fixture.declarationLocation.startOffset,
            kind = SymbolKind.FUNCTION,
        )

        val result = dispatchSuccessWithBackend<KastReferencesResponse>(
            backend = backend,
            method = "symbol/references",
            params = json.encodeToJsonElement(
                KastReferencesRequest.serializer(),
                KastReferencesRequest(
                    workspaceRoot = tempDir.toString(),
                    selector = selector,
                    maxResults = 1,
                ),
            ),
        )

        assertInstanceOf(KastReferencesDegradedResponse::class.java, result)
    }

    @Test
    fun `symbol references rejects a selector that does not match the anchored declaration`() {
        val fixture = AnalysisBackendContractFixture.create(tempDir)
        val result = dispatchSuccessWithBackend<KastReferencesResponse>(
            backend = FakeAnalysisBackend.contractFixture(fixture),
            method = "symbol/references",
            params = json.encodeToJsonElement(
                KastReferencesRequest.serializer(),
                KastReferencesRequest(
                    workspaceRoot = tempDir.toString(),
                    selector = KastExactSymbolSelector(
                        fqName = "sample.notGreet",
                        declarationFile = fixture.declarationLocation.filePath,
                        declarationStartOffset = fixture.declarationLocation.startOffset,
                        kind = SymbolKind.FUNCTION,
                    ),
                ),
            ),
        )

        val mismatch = assertInstanceOf(KastReferencesSubjectIdentityMismatchResponse::class.java, result)
        assertEquals(fixture.symbolFqName, mismatch.actual.fqName)
    }

    @Test
    fun `symbol references rejects non positive max results`() {
        val response = dispatchRaw(
            method = "symbol/references",
            params = json.encodeToJsonElement(
                KastReferencesRequest.serializer(),
                KastReferencesRequest(
                    selector = KastExactSymbolSelector(
                        fqName = "sample.greet",
                        declarationFile = tempDir.resolve("Greeter.kt").toString(),
                        declarationStartOffset = 0,
                        kind = SymbolKind.FUNCTION,
                    ),
                    maxResults = 0,
                ),
            ),
        )

        val error = json.decodeFromJsonElement(
            JsonRpcErrorResponse.serializer(),
            response,
        )
        assertEquals("VALIDATION_ERROR", error.error.data?.code)
    }
}
