package io.github.amichne.kast.api

import io.github.amichne.kast.api.contract.query.DiagnosticsQuery
import io.github.amichne.kast.api.contract.result.RelationshipResultEvidence
import io.github.amichne.kast.api.contract.result.ResultCardinality
import io.github.amichne.kast.api.protocol.ConflictException
import io.github.amichne.kast.api.validation.FileHashing
import io.github.amichne.kast.api.validation.parsed
import io.github.amichne.kast.testing.AnalysisBackendContractFixture
import io.github.amichne.kast.testing.FakeAnalysisBackend
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class FakeAnalysisBackendContinuationTest {
    @TempDir
    lateinit var workspaceRoot: Path

    @Test
    fun `closing the fake backend rejects outstanding continuations`(): TestResult = runTest {
        val fixture = AnalysisBackendContractFixture.create(workspaceRoot)
        val backend = FakeAnalysisBackend.contractFixture(fixture)
        val referenceQuery = fixture.referencesQuery.copy(maxResults = 1)
        val referencePage = backend.findReferences(referenceQuery.parsed())
        val referencePageToken = checkNotNull(referencePage.page?.nextPageToken)
        val diagnosticQuery = duplicateDiagnosticQuery(fixture)
        val diagnosticPage = backend.diagnostics(diagnosticQuery.parsed())
        val diagnosticPageToken = checkNotNull(diagnosticPage.page?.nextPageToken)

        backend.close()

        assertConflict {
            backend.findReferences(referenceQuery.copy(pageToken = referencePageToken).parsed())
        }
        assertConflict {
            backend.diagnostics(diagnosticQuery.copy(pageToken = diagnosticPageToken).parsed())
        }
    }

    @Test
    fun `reference continuations are single use and query bound`(): TestResult = runTest {
        val fixture = AnalysisBackendContractFixture.create(workspaceRoot)
        val backend = FakeAnalysisBackend.contractFixture(fixture)
        val query = fixture.referencesQuery.copy(maxResults = 1)
        val firstToken = checkNotNull(
            backend.findReferences(query.parsed()).page?.nextPageToken,
        )

        val mismatched = assertConflict {
            backend.findReferences(
                query.copy(
                    includeDeclaration = false,
                    pageToken = firstToken,
                ).parsed(),
            )
        }
        assertEquals("Reference continuation token belongs to another query", mismatched.message)
        assertEquals(
            "Unknown or consumed reference continuation token",
            assertConflict {
                backend.findReferences(query.copy(pageToken = firstToken).parsed())
            }.message,
        )

        val firstPage = backend.findReferences(query.parsed())
        val nextToken = checkNotNull(firstPage.page?.nextPageToken)
        val resumable = assertInstanceOf(RelationshipResultEvidence.Resumable::class.java, firstPage.evidence)
        assertEquals(ResultCardinality.KnownMinimum(2), resumable.cardinality)
        val finalPage = backend.findReferences(query.copy(pageToken = nextToken).parsed())
        assertNull(finalPage.page)
        val complete = assertInstanceOf(RelationshipResultEvidence.Complete::class.java, finalPage.evidence)
        assertEquals(ResultCardinality.Exact(2), complete.cardinality)
        assertEquals(
            "Unknown or consumed reference continuation token",
            assertConflict {
                backend.findReferences(query.copy(pageToken = nextToken).parsed())
            }.message,
        )
        backend.close()
    }

    @Test
    fun `diagnostic continuations are single use and query bound`(): TestResult = runTest {
        val fixture = AnalysisBackendContractFixture.create(workspaceRoot)
        val backend = FakeAnalysisBackend.contractFixture(fixture)
        val query = duplicateDiagnosticQuery(fixture)
        val firstToken = checkNotNull(
            backend.diagnostics(query.parsed()).page?.nextPageToken,
        )

        val mismatched = assertConflict {
            backend.diagnostics(
                query.copy(
                    maxResults = 2,
                    pageToken = firstToken,
                ).parsed(),
            )
        }
        assertEquals("Diagnostic continuation token belongs to another query", mismatched.message)
        assertEquals(
            "Unknown or consumed diagnostic continuation token",
            assertConflict {
                backend.diagnostics(query.copy(pageToken = firstToken).parsed())
            }.message,
        )

        val firstPage = backend.diagnostics(query.parsed())
        val nextToken = checkNotNull(firstPage.page?.nextPageToken)
        val snapshotHashes = firstPage.fileHashes
        Files.writeString(fixture.brokenFile, "package changed\n\nfun changed() = Unit\n")
        val finalPage = backend.diagnostics(query.copy(pageToken = nextToken).parsed())
        assertNull(finalPage.page)
        assertEquals(snapshotHashes, finalPage.fileHashes)
        assertNotEquals(
            FileHashing.sha256(Files.readString(fixture.brokenFile)),
            finalPage.fileHashes.first().hash,
        )
        assertEquals(
            "Unknown or consumed diagnostic continuation token",
            assertConflict {
                backend.diagnostics(query.copy(pageToken = nextToken).parsed())
            }.message,
        )
        backend.close()
    }

    private fun duplicateDiagnosticQuery(fixture: AnalysisBackendContractFixture): DiagnosticsQuery =
        DiagnosticsQuery(
            filePaths = listOf(
                fixture.brokenFile.toString(),
                fixture.brokenFile.toString(),
            ),
            maxResults = 1,
        )

    private suspend fun assertConflict(action: suspend () -> Unit): ConflictException {
        val failure = runCatching { action() }.exceptionOrNull()
        return assertInstanceOf(ConflictException::class.java, failure)
    }
}
