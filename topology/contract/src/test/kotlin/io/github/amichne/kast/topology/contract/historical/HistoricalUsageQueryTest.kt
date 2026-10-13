package io.github.amichne.kast.topology.contract.historical

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.symbol.contract.CompilerSymbolIdentity
import io.github.amichne.kast.symbol.contract.CompilerSymbolKind
import io.github.amichne.kast.symbol.contract.ExactDeclarationTextRange
import io.github.amichne.kast.workspace.contract.WorkspaceSourceContentHash
import io.github.amichne.kast.workspace.contract.WorkspaceSourcePath
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertInstanceOf

class HistoricalUsageQueryTest {
    @Test
    fun `incoming scope includes modules outside target dependency closure`() {
        val snapshot = fixture()
        val query = query(snapshot, setOf(module("feature.main"), module("feature.test"))).value()
        assertEquals(listOf("feature.main", "feature.test"), query.sourceModules.map { it.value })
        assertInstanceOf<HistoricalUsageCoverage.Complete>(query.coverage)
        assertEquals(snapshot.identity, query.snapshot)
        assertEquals("contracts/Api.kt", query.targets.single().declaration.file.value)
    }

    @Test
    fun `source modules outside captured scope reject instead of shrinking the query`() {
        val rejected =
            assertInstanceOf<Refinement.Rejected<HistoricalUsageFailure>>(
                query(fixture(), setOf(module("feature.main"), module("absent.main")))
            )
        assertEquals(HistoricalUsageFailure.SOURCE_OUTSIDE_CAPTURE, rejected.failure)
    }

    @Test
    fun `structural profile cannot answer a reference question`() {
        val rejected =
            assertInstanceOf<Refinement.Rejected<HistoricalUsageFailure>>(
                query(fixture(profile = HistoricalProfile.STRUCTURE_V1), setOf(module("feature.main")))
            )
        assertEquals(HistoricalUsageFailure.REFERENCES_NOT_CAPTURED, rejected.failure)
    }

    @Test
    fun `qualified empty query preserves the exact source coverage`() {
        val snapshot = fixture(qualified = true)
        val query = query(snapshot, setOf(module("feature.test"))).value()
        val qualified = assertInstanceOf<HistoricalUsageCoverage.Qualified>(query.coverage)
        assertEquals(listOf("feature.test"), qualified.qualifications.map { it.module.value })
        assertEquals(
            setOf(HistoricalLimitation.UNRESOLVED_TARGET),
            assertInstanceOf<HistoricalCoverageStatus.Qualified>(qualified.qualifications.single().status).reasons,
        )
    }

    @Test
    fun `foreign snapshot references reject before the storage effect`() {
        val snapshot = fixture()
        val foreign = fixture(qualified = true)
        val rejected =
            assertInstanceOf<Refinement.Rejected<HistoricalUsageFailure>>(
                HistoricalUsageQuery.admit(
                    HistoricalSnapshotInventory.from(snapshot),
                    setOf(module("feature.main")),
                    setOf(foreign.reference(foreign.declarations.first().id).value()),
                    HistoricalPageSize.parse(1).value(),
                )
            )
        assertEquals(HistoricalUsageFailure.SNAPSHOT_MISMATCH, rejected.failure)
    }

    @Test
    fun `cursor binds snapshot source scope targets and ordering`() {
        val snapshot = fixture()
        val first = query(snapshot, setOf(module("feature.main"))).value()
        val cursor = first.cursorAfter(HistoricalRowOrdinal.parse(3).value())
        val same = query(snapshot, setOf(module("feature.main")), HistoricalUsagePosition.After(cursor)).value()
        assertEquals(first.identity, same.identity)
        val changed =
            assertInstanceOf<Refinement.Rejected<HistoricalUsageFailure>>(
                query(snapshot, setOf(module("feature.test")), HistoricalUsagePosition.After(cursor))
            )
        assertEquals(HistoricalUsageFailure.CURSOR_MISMATCH, changed.failure)
    }

    @Test
    fun `query rejects empty source and target domains and invalid page sizes`() {
        assertEquals(
            HistoricalUsageFailure.EMPTY_SOURCE_DOMAIN,
            assertInstanceOf<Refinement.Rejected<HistoricalUsageFailure>>(query(fixture(), emptySet())).failure,
        )
        val snapshot = fixture()
        assertEquals(
            HistoricalUsageFailure.EMPTY_TARGET_DOMAIN,
            assertInstanceOf<Refinement.Rejected<HistoricalUsageFailure>>(
                    HistoricalUsageQuery.admit(
                        HistoricalSnapshotInventory.from(snapshot),
                        setOf(module("feature.main")),
                        emptySet(),
                        HistoricalPageSize.parse(1).value(),
                    )
                )
                .failure,
        )
        listOf(0, -1, 1001).forEach {
            assertInstanceOf<Refinement.Rejected<HistoricalReadValueFailure>>(HistoricalPageSize.parse(it))
        }
    }

    @Test
    fun `oversized source and target domains reject before membership enumeration`() {
        val snapshot = fixture()
        val sources = (0..HistoricalUsageQuery.MAXIMUM_SOURCE_MODULES).map { module("source-$it") }.toSet()
        assertEquals(
            HistoricalUsageFailure.SOURCE_DOMAIN_LIMIT,
            assertInstanceOf<Refinement.Rejected<HistoricalUsageFailure>>(query(snapshot, sources)).failure,
        )
        val targets =
            (0..HistoricalUsageQuery.MAXIMUM_TARGET_DECLARATIONS)
                .map {
                    HistoricalDeclarationReference.fromStored(
                        snapshot.identity,
                        snapshot.declarations
                            .first()
                            .copy(id = snapshot.declarations.first().id.copy(file = path("Target$it.kt"))),
                    )
                }
                .toSet()
        assertEquals(
            HistoricalUsageFailure.TARGET_DOMAIN_LIMIT,
            assertInstanceOf<Refinement.Rejected<HistoricalUsageFailure>>(
                    HistoricalUsageQuery.admit(
                        HistoricalSnapshotInventory.from(snapshot),
                        setOf(module("feature.main")),
                        targets,
                        HistoricalPageSize.parse(1).value(),
                    )
                )
                .failure,
        )
    }

    @Test
    fun `stored metadata rejects fabricated structural completion and missing inventory`() {
        val snapshot = fixture(profile = HistoricalProfile.STRUCTURE_V1)
        val inventory = HistoricalSnapshotInventory.from(snapshot)
        fun restored(coverage: List<HistoricalCoverage>) =
            HistoricalSnapshotInventory.fromStored(
                snapshot.identity,
                snapshot.header,
                snapshot.modules,
                coverage,
                inventory.counts,
            )
        assertEquals(
            HistoricalReadValueFailure.PROFILE_MISMATCH,
            assertInstanceOf<Refinement.Rejected<HistoricalReadValueFailure>>(
                    restored(
                        snapshot.coverage.map {
                            if (it.family == HistoricalFactFamily.REFERENCES)
                                it.copy(status = HistoricalCoverageStatus.Complete)
                            else it
                        }
                    )
                )
                .failure,
        )
        assertEquals(
            HistoricalReadValueFailure.INCOMPLETE_INVENTORY,
            assertInstanceOf<Refinement.Rejected<HistoricalReadValueFailure>>(
                    restored(
                        snapshot.coverage.map {
                            if (it.family == HistoricalFactFamily.FILES)
                                it.copy(
                                    status = HistoricalCoverageStatus.Qualified(HistoricalLimitation.MODEL_INCOMPLETE)
                                )
                            else it
                        }
                    )
                )
                .failure,
        )
    }

    @Test
    fun `admitted selection plan rejects a missing exact lookup result`() {
        val snapshot = fixture()
        val plan =
            HistoricalUsageQuery.prepare(
                    HistoricalSnapshotInventory.from(snapshot),
                    setOf(module("feature.main")),
                    setOf(snapshot.declarations.first().id),
                    HistoricalPageSize.parse(1).value(),
                )
                .value()
        assertEquals(
            HistoricalUsageFailure.TARGET_SELECTION_MISMATCH,
            assertInstanceOf<Refinement.Rejected<HistoricalUsageFailure>>(plan.complete(emptySet())).failure,
        )
    }

    private fun query(
        snapshot: HistoricalSnapshot,
        sources: Set<HistoricalModuleId>,
        position: HistoricalUsagePosition = HistoricalUsagePosition.First,
    ) =
        HistoricalUsageQuery.admit(
            HistoricalSnapshotInventory.from(snapshot),
            sources,
            setOf(snapshot.reference(snapshot.declarations.first().id).value()),
            HistoricalPageSize.parse(1).value(),
            position,
        )

    private fun fixture(
        qualified: Boolean = false,
        profile: HistoricalProfile = HistoricalProfile.REFERENCES_V1,
    ): HistoricalSnapshot {
        val modules =
            listOf("contracts.main", "feature.main", "feature.test", "empty.main", "rootless").map { id ->
                HistoricalModule(
                    module(id),
                    name(id.substringBefore('.')),
                    name(id),
                    if (id == "rootless") HistoricalSourceSet.Rootless
                    else HistoricalSourceSet.Named(name(id.substringAfter('.'))),
                )
            }
        val file =
            HistoricalFile(
                path("contracts/Api.kt"),
                WorkspaceSourceContentHash.parse("a".repeat(64)).value(),
                module("contracts.main"),
                HistoricalFileProvenance.Tracked(GitObjectId.parse("b".repeat(40)).value()),
            )
        val declaration =
            HistoricalDeclaration(
                HistoricalDeclarationId(
                    CompilerSymbolIdentity.parse("api(Int)").value(),
                    file.path,
                    ExactDeclarationTextRange.parse(16, 46).value(),
                ),
                name("api"),
                name("api(Int):Int"),
                CompilerSymbolKind.FUNCTION,
            )
        val coverage = coverage(modules, profile, qualified)
        return HistoricalSnapshotInput(
                fixtureHeader(profile),
                modules,
                listOf(HistoricalDependency(module("feature.main"), module("contracts.main"))),
                listOf(file),
                listOf(declaration),
                emptyList(),
                coverage,
            )
            .admit()
            .value()
    }

    private fun fixtureHeader(profile: HistoricalProfile) =
        HistoricalHeader(
            HistoricalRevision(
                HistoricalRepositoryId.parse("fixture:usage").value(),
                GitObjectId.parse("1".repeat(40)).value(),
                GitObjectId.parse("2".repeat(40)).value(),
            ),
            HistoricalContext(digest("3"), digest("4"), digest("5")),
            profile,
            name("oracle-1"),
        )

    private fun coverage(modules: List<HistoricalModule>, profile: HistoricalProfile, qualified: Boolean) =
        modules.flatMap { owner ->
            HistoricalFactFamily.entries.map { family ->
                HistoricalCoverage(
                    owner.id,
                    family,
                    when {
                        family == HistoricalFactFamily.REFERENCES && profile == HistoricalProfile.STRUCTURE_V1 ->
                            HistoricalCoverageStatus.NotCollected
                        family == HistoricalFactFamily.REFERENCES && owner.id == module("feature.test") && qualified ->
                            HistoricalCoverageStatus.Qualified(HistoricalLimitation.UNRESOLVED_TARGET)
                        else -> HistoricalCoverageStatus.Complete
                    },
                )
            }
        }

    private fun module(raw: String) = HistoricalModuleId.parse(raw).value()

    private fun name(raw: String) = HistoricalName.parse(raw).value()

    private fun path(raw: String) = WorkspaceSourcePath.parse(raw).value()

    private fun digest(raw: String) = HistoricalDigest.parse(raw.repeat(64)).value()

    private fun <T, F> Refinement<T, F>.value(): T = assertInstanceOf<Refinement.Refined<T>>(this).value
}
