package io.github.amichne.kast.parity

import io.github.amichne.kast.api.contract.query.DiagnosticsQuery
import io.github.amichne.kast.api.contract.FilePosition
import io.github.amichne.kast.api.contract.query.ReferencesQuery
import io.github.amichne.kast.api.contract.query.RenameQuery
import io.github.amichne.kast.api.contract.query.SymbolQuery
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertNull

/**
 * Parity tests that run the same queries against two live backends
 * (standalone and IntelliJ) and compare their responses structurally.
 *
 * Requires two environment variables:
 *   KAST_STANDALONE_SOCKET — path to the standalone backend UDS
 *   KAST_INTELLIJ_SOCKET   — path to the IntelliJ backend UDS
 *
 * Both backends must be serving the same workspace for results to be comparable.
 *
 * Run with: `./gradlew :parity-tests:test -PincludeTags=parity`
 * (excluded by default via `-PexcludeTags=parity`)
 */
@Tag("parity")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BackendParityTest {

    private lateinit var standalone: ParityRpcClient
    private lateinit var intellij: ParityRpcClient
    private val json = Json { ignoreUnknownKeys = true }

    @BeforeAll
    fun setUp() {
        val standalonePath = System.getenv("KAST_STANDALONE_SOCKET")
        val intellijPath = System.getenv("KAST_INTELLIJ_SOCKET")
        assumeTrue(
            standalonePath != null && intellijPath != null,
            "Parity tests require KAST_STANDALONE_SOCKET and KAST_INTELLIJ_SOCKET env vars",
        )
        standalone = ParityRpcClient(Path.of(standalonePath), json)
        intellij = ParityRpcClient(Path.of(intellijPath), json)
    }

    // --- Read-only operations ---

    @Test
    fun `capabilities - structural parity`() {
        val comparator = ParityComparator.Structural(
            ignoredKeys = setOf("backendName", "backendVersion", "schemaVersion"),
        )
        assertParity("capabilities", comparator) {
            it.rawCall("capabilities")
        }
    }

    @Test
    fun `health - structural parity`() {
        val comparator = ParityComparator.Structural(
            ignoredKeys = setOf("backendName", "backendVersion"),
        )
        assertParity("health", comparator) {
            it.rawCall("health")
        }
    }

    @Test
    fun `resolveSymbol - exact parity`() {
        val position = fixtureFilePosition()
        val query = SymbolQuery(position = position)
        val comparator = ParityComparator.Structural()
        assertParity("symbol/resolve", comparator) {
            it.rawCall("symbol/resolve", json.encodeToJsonElement(query))
        }
    }

    @Test
    fun `findReferences - structural parity with unordered references`() {
        val position = fixtureFilePosition()
        val query = ReferencesQuery(position = position, includeDeclaration = true)
        val comparator = ParityComparator.Structural(
            unorderedArrayKeys = setOf("references"),
        )
        assertParity("references", comparator) {
            it.rawCall("references", json.encodeToJsonElement(query))
        }
    }

    // Note: callHierarchy and typeHierarchy are omitted because the IntelliJ
    // backend does not advertise CALL_HIERARCHY or TYPE_HIERARCHY capabilities.
    // Add parity tests here when those capabilities are implemented.

    @Test
    fun `diagnostics - structural parity with unordered diagnostics`() {
        val brokenFile = System.getenv("KAST_PARITY_BROKEN_FILE")
        assumeTrue(brokenFile != null, "KAST_PARITY_BROKEN_FILE env var required")
        val query = DiagnosticsQuery(filePaths = listOf(brokenFile!!))
        val comparator = ParityComparator.Structural(
            unorderedArrayKeys = setOf("diagnostics"),
        )
        assertParity("diagnostics", comparator) {
            it.rawCall("diagnostics", json.encodeToJsonElement(query))
        }
    }

    @Test
    fun `rename - structural parity`() {
        val position = fixtureFilePosition()
        val query = RenameQuery(position = position, newName = "welcome")
        val comparator = ParityComparator.Structural(
            ignoredKeys = setOf("schemaVersion", "searchScope", "fileHashes"),
            unorderedArrayKeys = setOf("edits", "affectedFiles"),
        )
        assertParity("rename", comparator) {
            it.rawCall("rename", json.encodeToJsonElement(query))
        }
    }

    // --- Helpers ---

    /**
     * Sends the same request to both backends and asserts the responses match
     * according to the given comparator.
     */
    private fun assertParity(
        label: String,
        comparator: ParityComparator,
        call: (ParityRpcClient) -> kotlinx.serialization.json.JsonElement,
    ) {
        val standaloneResult = call(standalone)
        val intellijResult = call(intellij)
        val diff = comparator.compare(standaloneResult, intellijResult)
        assertNull(diff, "Parity mismatch for '$label':\n$diff")
    }

    private fun fixtureFilePosition(): FilePosition {
        val filePath = requireEnv("KAST_PARITY_USAGE_FILE")
        val offset = requireEnv("KAST_PARITY_USAGE_OFFSET").toInt()
        return FilePosition(filePath = filePath, offset = offset)
    }

    private fun requireEnv(name: String): String {
        val value = System.getenv(name)
        assumeTrue(value != null, "$name env var required for parity tests")
        return value!!
    }
}
