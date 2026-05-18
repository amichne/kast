package io.github.amichne.kast.parity

import io.github.amichne.kast.api.client.kastConfigHome
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
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertNull

/**
 * Parity tests that run the same queries against two live backends
 * (standalone and IntelliJ) and compare their responses structurally.
 *
 * Reads parity inputs from a temp-scoped config.toml fixture. To run against
 * live backends, add a [parity] section to the configured Kast config home.
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
    private lateinit var parityConfig: BackendParityConfig
    private lateinit var parityConfigFile: Path
    private val json = Json { ignoreUnknownKeys = true }

    @BeforeAll
    fun setUp(@TempDir tempConfigHome: Path) {
        parityConfigFile = BackendParityConfigFixture.materialize(
            configHome = tempConfigHome,
            sourceConfigHome = kastConfigHome(),
        )
        parityConfig = BackendParityConfigFixture.load(tempConfigHome)
        assumeTrue(
            Files.exists(parityConfig.standaloneSocket) && Files.exists(parityConfig.intellijSocket),
            "Parity tests require live backend socket paths configured in $parityConfigFile",
        )
        standalone = ParityRpcClient(parityConfig.standaloneSocket, json)
        intellij = ParityRpcClient(parityConfig.intellijSocket, json)
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
        assertParity("raw/resolve", comparator) {
            it.rawCall("raw/resolve", json.encodeToJsonElement(query))
        }
    }

    @Test
    fun `findReferences - structural parity with unordered references`() {
        val position = fixtureFilePosition()
        val query = ReferencesQuery(position = position, includeDeclaration = true)
        val comparator = ParityComparator.Structural(
            unorderedArrayKeys = setOf("references"),
        )
        assertParity("raw/references", comparator) {
            it.rawCall("raw/references", json.encodeToJsonElement(query))
        }
    }

    // Note: callHierarchy and typeHierarchy are omitted because the IntelliJ
    // backend does not advertise CALL_HIERARCHY or TYPE_HIERARCHY capabilities.
    // Add parity tests here when those capabilities are implemented.

    @Test
    fun `diagnostics - structural parity with unordered diagnostics`() {
        val brokenFile = parityConfig.brokenFile
        assumeTrue(
            brokenFile != null && Files.isRegularFile(brokenFile),
            "diagnostics parity requires broken-file in $parityConfigFile",
        )
        val query = DiagnosticsQuery(filePaths = listOf(brokenFile.toString()))
        val comparator = ParityComparator.Structural(
            unorderedArrayKeys = setOf("diagnostics"),
        )
        assertParity("raw/diagnostics", comparator) {
            it.rawCall("raw/diagnostics", json.encodeToJsonElement(query))
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
        assertParity("raw/rename", comparator) {
            it.rawCall("raw/rename", json.encodeToJsonElement(query))
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
        val usageFile = parityConfig.usageFile
        val usageOffset = parityConfig.usageOffset
        assumeTrue(
            usageFile != null && Files.isRegularFile(usageFile) && usageOffset != null,
            "symbol parity requires usage-file and usage-offset in $parityConfigFile",
        )
        return FilePosition(filePath = requireNotNull(usageFile).toString(), offset = requireNotNull(usageOffset))
    }
}
