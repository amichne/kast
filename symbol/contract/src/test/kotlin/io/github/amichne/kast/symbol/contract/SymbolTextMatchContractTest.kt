package io.github.amichne.kast.symbol.contract

import io.github.amichne.kast.kernel.EvidenceGeneration
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import io.github.amichne.kast.workspace.contract.SemanticReadLease
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SymbolTextMatchContractTest {
    @Test
    fun `indexed word admission excludes unsupported search grammar`() {
        for (word in listOf("launchd", "AppServerAction", "_bootstrap", "a1")) {
            assertEquals(word, SymbolDiscoveryWord.parse(word).refined().value)
        }
        for (word in
            listOf("", " launchd", "restart lifecycle", "a|b", "AppServerAction.Disable", "3d", "é", "a".repeat(257))) {
            check(SymbolDiscoveryWord.parse(word) is Refinement.Rejected) { word }
        }
    }

    @Test
    fun `lexical evidence verifies occurrence context and enclosing owner`() {
        val match = match().refined()
        assertEquals(
            "launchd",
            match.context.substring(
                match.range.startInclusive.value - match.contextRange.startInclusive.value,
                match.range.endExclusive.value - match.contextRange.startInclusive.value,
            ),
        )
        assertEquals(SymbolTextMatchFailure.WORD_MISMATCH, match(context = "prefix Launchd suffix").rejected())
        assertEquals(SymbolTextMatchFailure.OWNER_RANGE_MISMATCH, match(ownerEnd = 12).rejected())
        assertEquals(SymbolTextMatchFailure.INVALID_CONTEXT, match(context = "a".repeat(513)).rejected())
        assertEquals(SymbolTextMatchFailure.INVALID_CONTEXT, match(context = "prefix launchd\nsuffix").rejected())
        assertEquals(SymbolTextMatchFailure.INVALID_LINE, match(line = 0).rejected())
    }

    @Test
    fun `candidate binds lexical evidence to its lease file and owner offset`() {
        val evidence = match().refined()
        assertEquals(evidence, candidate(evidence).refined().textMatch)
        assertEquals(
            SymbolDiscoveryCandidateFailure.TEXT_MATCH_OWNER_MISMATCH,
            candidate(evidence, offset = 1).rejected(),
        )
        assertEquals(
            SymbolDiscoveryCandidateFailure.TEXT_MATCH_OWNER_MISMATCH,
            candidate(evidence, path = "Other.kt").rejected(),
        )
        assertEquals(
            SymbolDiscoveryCandidateFailure.TEXT_MATCH_OWNER_MISMATCH,
            candidate(evidence, generation = 2).rejected(),
        )
    }

    @Test
    fun `another lexical exemplar cannot manufacture another discovery identity`() {
        val first = candidate(match().refined()).refined()
        val secondMatch =
            SymbolTextMatch.fromBoundary(
                    SymbolDiscoveryWord.parse("launchd").refined(),
                    lease(),
                    CanonicalWorkspaceFilePath.fromCanonicalPath(lease().workspaceRoot, Path.of("/workspace/App.kt"))
                        .refined(),
                    25,
                    32,
                    0,
                    40,
                    "prefix launchd launchd",
                    10,
                    1,
                )
                .refined()
        val second = candidate(secondMatch).refined()
        assertEquals(first.identity, second.identity)
        assertEquals(1, setOf(first.identity, second.identity).size)
        val result =
            SymbolDiscoveryBatch.create(
                request(),
                listOf(first, second),
                SymbolDiscoveryByteCount.parse(first.projectedUtf8Size().value + second.projectedUtf8Size().value)
                    .refined(),
                SymbolDiscoveryWorkCount.parse(2).refined(),
                SymbolDiscoveryTimings(
                    SymbolDiscoveryElapsedNanoseconds.Zero,
                    SymbolDiscoveryElapsedNanoseconds.Zero,
                ),
            )
        assertEquals(SymbolDiscoveryBatchFailure.NON_DETERMINISTIC_ORDER, result.rejected())
    }

    @Test
    fun `candidate identities retain each admitted owner fact`() {
        val candidates =
            listOf(
                detached(),
                detached(generation = 2),
                detached(kind = SymbolDiscoveryKind.CLASS),
                detached(name = "other"),
                detached(path = "Other.kt"),
                detached(offset = 1),
            )
        assertEquals(6, candidates.map { it.identity }.toSet().size)
        assertEquals(detached().identity, candidates.first().identity)
    }

    private fun detached(
        kind: SymbolDiscoveryKind = SymbolDiscoveryKind.SYMBOL,
        name: String = "subject",
        generation: Long = 1,
        path: String = "App.kt",
        offset: Int = 0,
    ) =
        SymbolDiscoveryCandidate.fromBoundary(
                kind = kind,
                rawName = name,
                lease = lease(generation),
                nativePath = Path.of("/workspace/$path"),
                virtualFileUrl = "file:///workspace/$path",
                rawOffset = offset,
            )
            .refined()

    private fun request() =
        SymbolDiscoveryRequest(
            SymbolSearchScopeRequest(
                lease(),
                SymbolSearchScope.Workspace(
                    SymbolSourceKindPolicy.PRODUCTION_AND_TEST,
                    SymbolGeneratedSourcePolicy.EXCLUDE,
                    SymbolLibraryPolicy.EXCLUDE,
                ),
            ),
            SymbolDiscoveryTarget.TextDeclarations(SymbolDiscoveryWord.parse("launchd").refined()),
            SymbolDiscoveryBudget(
                io.github.amichne.kast.kernel.ResourceBudget(
                    io.github.amichne.kast.kernel.ResultLimit.parse(10).refined(),
                    io.github.amichne.kast.kernel.WorkUnitLimit.parse(10).refined(),
                    io.github.amichne.kast.kernel.ElapsedTimeLimitMillis.parse(1000).refined(),
                ),
                SymbolDiscoveryByteLimit.parse(10000).refined(),
            ),
        )

    private fun match(context: String = "prefix launchd suffix", ownerEnd: Int = 40, line: Int = 1) =
        SymbolTextMatch.fromBoundary(
            SymbolDiscoveryWord.parse("launchd").refined(),
            lease(),
            CanonicalWorkspaceFilePath.fromCanonicalPath(lease().workspaceRoot, Path.of("/workspace/App.kt")).refined(),
            rawStartInclusive = 17,
            rawEndExclusive = 24,
            rawDeclarationStartInclusive = 0,
            rawDeclarationEndExclusive = ownerEnd,
            rawContext = context,
            rawContextStartInclusive = 10,
            rawLine = line,
        )

    private fun candidate(evidence: SymbolTextMatch, offset: Int = 0, path: String = "App.kt", generation: Long = 1) =
        SymbolDiscoveryCandidate.fromBoundary(
            SymbolDiscoveryKind.SYMBOL,
            "subject",
            lease(generation),
            Path.of("/workspace/$path"),
            "file:///workspace/$path",
            offset,
            textMatch = evidence,
        )

    private fun lease(generation: Long = 1) =
        SemanticReadLease(
            CanonicalWorkspaceRoot.fromCanonicalPath(Path.of("/workspace")).refined(),
            EvidenceGeneration.parse(generation).refined(),
        )

    private fun <T, F> Refinement<T, F>.refined(): T =
        when (this) {
            is Refinement.Refined -> value
            is Refinement.Rejected -> error(failure.toString())
        }

    private fun <T, F> Refinement<T, F>.rejected(): F =
        when (this) {
            is Refinement.Refined -> error(value.toString())
            is Refinement.Rejected -> failure
        }
}
