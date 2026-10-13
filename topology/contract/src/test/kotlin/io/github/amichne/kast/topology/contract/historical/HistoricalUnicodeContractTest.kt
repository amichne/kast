package io.github.amichne.kast.topology.contract.historical

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.symbol.contract.CompilerSymbolIdentity
import io.github.amichne.kast.symbol.contract.CompilerSymbolKind
import io.github.amichne.kast.symbol.contract.ExactDeclarationTextRange
import io.github.amichne.kast.workspace.contract.WorkspaceSourceContentHash
import io.github.amichne.kast.workspace.contract.WorkspaceSourcePath
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertInstanceOf

class HistoricalUnicodeContractTest {
    private val malformed =
        listOf("x\uD800", "x\uD801", "x\uDC00", "x\uD800x", "x\uD800\uD801", "x\uDC00\uD800", "x\uD800\uDC00\uDC01")

    @Test
    fun `distinct malformed producers cannot issue identical snapshot identities`() {
        val results =
            listOf("x\uD800", "x\uD801").map { raw ->
                when (val name = HistoricalName.parse(raw)) {
                    is Refinement.Refined -> {
                        val input = fixture("compiler")
                        input.copy(header = input.header.copy(producer = name.value)).admit()
                    }
                    is Refinement.Rejected -> name
                }
            }
        results.filterIsInstance<Refinement.Refined<HistoricalSnapshot>>().forEach {
            println("malformed producer admitted identity: ${it.value.identity.value}")
        }
        results.forEach { assertInstanceOf<Refinement.Rejected<*>>(it) }
    }

    @Test
    fun `historical text parsers reject malformed Unicode`() {
        for (raw in malformed) {
            assertEquals(HistoricalValueFailure.INVALID_TEXT, HistoricalName.parse(raw).failure())
            assertEquals(HistoricalValueFailure.INVALID_TEXT, HistoricalModuleId.parse(raw).failure())
            assertEquals(HistoricalValueFailure.INVALID_TEXT, HistoricalRepositoryId.parse("fixture:$raw").failure())
        }
    }

    @Test
    fun `snapshot admission rejects malformed compiler identities`() {
        for (raw in malformed) {
            val result = fixture(raw).admit()
            if (result is Refinement.Refined)
                println("malformed compiler admitted identity: ${result.value.identity.value}")
            assertEquals("INVALID_TEXT", result.failure().name)
        }
    }

    @Test
    fun `valid supplementary characters preserve text and UTF16 coordinates`() {
        val first = fixture("id\uD800\uDC00").admit().value()
        val second = fixture("id\uD800\uDC01").admit().value()
        assertNotEquals(first.identity, second.identity)
        assertEquals("id\uD800\uDC00", first.declarations.single().id.compiler.value)
        assertEquals(4, first.declarations.single().id.range.endExclusive)
        listOf("plain", "caf\u00E9", "e\u0301", "\uD800\uDC00", "\uDBFF\uDFFF").forEach { raw ->
            assertEquals(raw, HistoricalName.parse(raw).value().value)
            assertEquals(raw, HistoricalModuleId.parse(raw).value().value)
            assertEquals("fixture:$raw", HistoricalRepositoryId.parse("fixture:$raw").value().value)
        }
    }

    @Test
    fun `raw canonical fields reject malformed Unicode before hashing`() {
        for (raw in malformed) {
            assertInstanceOf<Refinement.Rejected<*>>(HistoricalCanonicalEncoding.digest(sequenceOf(listOf(raw))))
        }
    }

    @Test
    fun `query admission rejects malformed compiler identities before selection`() {
        val input = fixture("compiler")
        val snapshot =
            input
                .copy(
                    header = input.header.copy(profile = HistoricalProfile.REFERENCES_V1),
                    coverage = input.coverage.map { it.copy(status = HistoricalCoverageStatus.Complete) },
                )
                .admit()
                .value()
        for (raw in malformed) {
            val target = snapshot.declarations.single().id.copy(compiler = CompilerSymbolIdentity.parse(raw).value())
            val plan =
                HistoricalUsageQuery.prepare(
                    HistoricalSnapshotInventory.from(snapshot),
                    setOf(snapshot.modules.single().id),
                    setOf(target),
                    HistoricalPageSize.parse(1).value(),
                )
            assertEquals("INVALID_TEXT", plan.failure().name)
        }
    }

    @Test
    fun `rejected canonical row leaves digest unchanged`() {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        assertEquals(
            HistoricalValueFailure.INVALID_TEXT,
            HistoricalCanonicalEncoding.update(digest, listOf("valid", "x\uD800")).failure(),
        )
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            digest.digest().joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') },
        )
    }

    private fun fixture(compiler: String): HistoricalSnapshotInput {
        val module = HistoricalModuleId.parse("api").value()
        val path = WorkspaceSourcePath.parse("Api.kt").value()
        return HistoricalSnapshotInput(
            HistoricalHeader(
                HistoricalRevision(
                    HistoricalRepositoryId.parse("fixture:unicode").value(),
                    GitObjectId.parse("1".repeat(40)).value(),
                    GitObjectId.parse("2".repeat(40)).value(),
                ),
                HistoricalContext(digest("3"), digest("4"), digest("5")),
                HistoricalProfile.STRUCTURE_V1,
                name("fixture"),
            ),
            listOf(HistoricalModule(module, name(":"), name("api"), HistoricalSourceSet.Named(name("main")))),
            emptyList(),
            listOf(
                HistoricalFile(
                    path,
                    WorkspaceSourceContentHash.parse("a".repeat(64)).value(),
                    module,
                    HistoricalFileProvenance.Tracked(GitObjectId.parse("b".repeat(40)).value()),
                )
            ),
            listOf(
                HistoricalDeclaration(
                    HistoricalDeclarationId(
                        CompilerSymbolIdentity.parse(compiler).value(),
                        path,
                        ExactDeclarationTextRange.parse(0, 4).value(),
                    ),
                    name("api"),
                    name("api()"),
                    CompilerSymbolKind.FUNCTION,
                )
            ),
            emptyList(),
            HistoricalFactFamily.entries.map {
                HistoricalCoverage(
                    module,
                    it,
                    if (it == HistoricalFactFamily.REFERENCES) HistoricalCoverageStatus.NotCollected
                    else HistoricalCoverageStatus.Complete,
                )
            },
        )
    }

    private fun name(raw: String) = HistoricalName.parse(raw).value()

    private fun digest(raw: String) = HistoricalDigest.parse(raw.repeat(64)).value()

    private fun <T, F> Refinement<T, F>.value(): T = assertInstanceOf<Refinement.Refined<T>>(this).value

    private fun <T, F> Refinement<T, F>.failure(): F = assertInstanceOf<Refinement.Rejected<F>>(this).failure
}
