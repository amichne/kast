package io.github.amichne.kast.symbol.intellij

import com.intellij.openapi.vfs.VirtualFile
import io.github.amichne.kast.kernel.EvidenceGeneration
import io.github.amichne.kast.kernel.ResultLimit
import io.github.amichne.kast.symbol.contract.CompilerSymbolKind
import io.github.amichne.kast.symbol.contract.SemanticFilePartition
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryCandidateLocation
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryContainment
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryDeclarationKinds
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryMatch
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryOutcome
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryPackage
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryPackageConstraint
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryPattern
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryQualification
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryRequest
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryTarget
import io.github.amichne.kast.symbol.contract.SymbolNameDiscoveryKind
import io.github.amichne.kast.symbol.intellij.DirectoryDiscoveryPartitionTestFixture.Case
import io.github.amichne.kast.symbol.intellij.DirectoryDiscoveryPartitionTestFixture.StrictPartitions
import io.github.amichne.kast.symbol.intellij.DirectoryDiscoveryPartitionTestFixture.discoverPartitions
import io.github.amichne.kast.symbol.intellij.DirectoryDiscoveryPartitionTestFixture.expectedPartitions
import io.github.amichne.kast.symbol.intellij.DirectoryDiscoveryPartitionTestFixture.refined
import io.github.amichne.kast.symbol.intellij.IntellijDeclarationDiscoveryTestFixture.batch
import io.github.amichne.kast.symbol.intellij.IntellijDeclarationDiscoveryTestFixture.fixture
import io.github.amichne.kast.symbol.intellij.IntellijDeclarationDiscoveryTestFixture.outcome
import io.github.amichne.kast.symbol.intellij.IntellijDeclarationDiscoveryTestFixture.withParser
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import io.github.amichne.kast.workspace.contract.SemanticReadLease
import io.github.amichne.kast.workspace.contract.WorkspaceSourceRootKind
import io.github.amichne.kast.workspace.intellij.read.IntellijReadCall
import io.github.amichne.kast.workspace.intellij.read.IntellijReadCounter
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class DiscoveryDirectoryPartitionsTest {
    @Test
    fun `direct and descendant supply obey fixed identities and exact requests`(@TempDir home: Path) =
        withParser(home) { project ->
            val case = Case(project, home.resolve("repo"))
            for (traversal in SymbolDiscoveryContainment.entries) {
                for (row in
                    if (traversal == SymbolDiscoveryContainment.DIRECT) DirectoryDiscoveryTree.direct
                    else DirectoryDiscoveryTree.descendants) {
                    assertRow(case, traversal, row)
                }
            }
        }

    private fun assertRow(case: Case, traversal: SymbolDiscoveryContainment, row: DirectoryDiscoveryTree.Declaration) {
        val expected = expectedPartitions(traversal)
        val observation = RecordingDiscoveryObservation { 0L }
        val request = case.request(traversal, row.name)
        val scope = case.scope(request, observation)
        val supply = StrictPartitions(case, expected)
        val outcome =
            case.query(observation).discoverPartitions(case.project, scope, request, partitions = supply).outcome()
        supply.assertConsumed()
        assertTrue(outcome is SymbolDiscoveryOutcome.Complete)
        assertEquals(listOf(row.name), outcome.batch().candidates.map { it.name.value })
        val candidate = outcome.batch().candidates.single()
        assertEquals(case.root.resolve(row.path).toString(), candidate.location.file.stableValue)
        assertEquals(
            row.start,
            (candidate.location as SymbolDiscoveryCandidateLocation.Declaration).offset.value,
        )
        assertEquals(request.scope.lease, candidate.lease)
        assertEquals(
            if (traversal == SymbolDiscoveryContainment.DIRECT) 2 else 3,
            observation.calls[IntellijReadCall.PSI_FIND_FILE],
        )
        assertEquals(0, observation.calls[IntellijReadCall.NAMED_DECLARATION_INDEX] ?: 0)
        assertEquals(0, observation.calls[IntellijReadCall.SCOPED_FILE_INDEX] ?: 0)
    }

    @Test
    fun `overlapping roots walk once and excluded owners keep separately admitted descendants`(@TempDir home: Path) =
        withParser(home) { project ->
            val case = Case(project, home.resolve("repo"))
            val nested = "${DirectoryDiscoveryTree.SELECTED}/nested"
            val roots =
                listOf(
                    case.rootBoundary(DirectoryDiscoveryTree.MAIN),
                    case.rootBoundary(DirectoryDiscoveryTree.SELECTED),
                    case.rootBoundary(nested, WorkspaceSourceRootKind.TEST),
                    case.rootBoundary("$nested/reincluded"),
                )
            // The nested owner excludes TargetChild. Its separately admitted empty descendant must still expand.
            java.nio.file.Files.createDirectories(case.root.resolve("$nested/reincluded"))
            val request = case.request(SymbolDiscoveryContainment.DESCENDANTS, "Target", SymbolDiscoveryMatch.FUZZY)
            val observation = RecordingDiscoveryObservation { 0L }
            val scope = case.scope(request, observation, roots)
            val supply =
                StrictPartitions(
                    case,
                    listOf(
                        "find:${DirectoryDiscoveryTree.SELECTED}",
                        "children:${DirectoryDiscoveryTree.SELECTED}",
                        "find:${DirectoryDiscoveryTree.SELECTED}/Helpers.kt",
                        "find:${DirectoryDiscoveryTree.SELECTED}/Target.kt",
                        "find:$nested",
                        "children:$nested",
                        "find:$nested/reincluded",
                        "children:$nested/reincluded",
                    ),
                )
            val outcome =
                case.query(observation).discoverPartitions(project, scope, request, partitions = supply).outcome()
            supply.assertConsumed()
            assertTrue(outcome is SymbolDiscoveryOutcome.Complete)
            assertEquals(listOf("Target"), outcome.batch().candidates.map { it.name.value })
            assertEquals(2, observation.calls[IntellijReadCall.PSI_FIND_FILE])
        }

    @Test
    fun `package and kind exclusions do not spend eligible capacity`(@TempDir home: Path) =
        withParser(home) { project ->
            val case = Case(project, home.resolve("repo"))
            val base = case.request(SymbolDiscoveryContainment.DIRECT, "Target")
            for (constraints in
                listOf(
                    base.constraints.copy(
                        declarationKinds =
                            SymbolDiscoveryDeclarationKinds.from(setOf(CompilerSymbolKind.FUNCTION)).refined()
                    ),
                    base.constraints.copy(
                        packageName =
                            SymbolDiscoveryPackageConstraint(
                                SymbolDiscoveryPackage.parse("other").refined(),
                                SymbolDiscoveryContainment.DIRECT,
                            )
                    ),
                )) {
                val request =
                    SymbolDiscoveryRequest(
                        base.scope,
                        base.target as SymbolDiscoveryTarget.Name,
                        base.budget,
                        constraints,
                    )
                val observation = RecordingDiscoveryObservation { 0L }
                val supply = StrictPartitions(case, expectedPartitions(SymbolDiscoveryContainment.DIRECT))
                val result =
                    case
                        .query(observation)
                        .discoverPartitions(project, case.scope(request, observation), request, partitions = supply)
                        .outcome()
                supply.assertConsumed()
                assertTrue(result is SymbolDiscoveryOutcome.Complete)
                assertTrue(result.batch().candidates.isEmpty())
                assertEquals(0, observation.counters[IntellijReadCounter.CANDIDATES_COLLECTED] ?: 0)
            }
        }

    @Test
    fun `unavailable partition preserves its failed obligation without broad fallback`(@TempDir home: Path) =
        withParser(home) { project ->
            val case = Case(project, home.resolve("repo"))
            val base = case.request(SymbolDiscoveryContainment.DESCENDANTS, "Target")
            val observation = RecordingDiscoveryObservation { 0L }
            var lookups = 0
            val missing =
                object : IntellijDeclarationPartitionFiles {
                    override fun find(partition: SemanticFilePartition): VirtualFile? {
                        assertEquals(0, lookups++)
                        return null
                    }

                    override fun children(directory: VirtualFile): List<VirtualFile> =
                        throw AssertionError("Unavailable partition cannot expand")
                }
            val result =
                case
                    .query(observation)
                    .discoverPartitions(project, case.scope(base, observation), base, partitions = missing)
                    .outcome()
            assertEquals(1, lookups)
            assertTrue(result is SymbolDiscoveryOutcome.Qualified)
            assertTrue(
                SymbolDiscoveryQualification.PROVIDER_FAILURE in
                    (result as SymbolDiscoveryOutcome.Qualified).qualifications.values
            )
            assertEquals(0, observation.calls[IntellijReadCall.SCOPED_FILE_INDEX] ?: 0)
        }

    @Test
    fun `authority movement cannot issue a current compiler selector from an old candidate`() {
        val root = CanonicalWorkspaceRoot.fromCanonicalPath(Path.of("/repo")).refined()
        val expected = SemanticReadLease(root, EvidenceGeneration.parse(1).refined())
        val moved = SemanticReadLease(root, EvidenceGeneration.parse(2).refined())
        assertEquals(
            IntellijSymbolSelectorLeaseAdmission.Rejected(IntellijSymbolSelectorRejection.GENERATION_MOVED),
            admitSymbolSelectorLease(expected, moved),
        )
    }

    @Test
    fun `absent names drain all selected partitions and fuzzy preference survives later supply`(@TempDir home: Path) =
        withParser(home) { project ->
            val case = Case(project, home.resolve("repo"))
            for (pattern in listOf("Missing", "Target")) {
                val initial = case.request(SymbolDiscoveryContainment.DESCENDANTS, pattern, SymbolDiscoveryMatch.FUZZY)
                val request =
                    SymbolDiscoveryRequest(
                        initial.scope,
                        initial.target as SymbolDiscoveryTarget.Name,
                        initial.budget.copy(
                            resources =
                                org.junit.jupiter.api.Assertions.assertInstanceOf(
                                        io.github.amichne.kast.kernel.ResourceBudget.Bounded::class.java,
                                        initial.budget.resources,
                                    )
                                    .copy(resultLimit = ResultLimit.parse(1).refined())
                        ),
                        initial.constraints,
                    )
                val observation = RecordingDiscoveryObservation { 0L }
                val supply = StrictPartitions(case, expectedPartitions(SymbolDiscoveryContainment.DESCENDANTS))
                val result =
                    case
                        .query(observation)
                        .discoverPartitions(project, case.scope(request, observation), request, partitions = supply)
                        .outcome()
                supply.assertConsumed()
                assertEquals(
                    if (pattern == "Missing") emptyList<String>() else listOf("Target"),
                    result.batch().candidates.map { it.name.value },
                )
                if (pattern == "Missing") assertTrue(result is SymbolDiscoveryOutcome.Complete)
                else assertTrue(result is SymbolDiscoveryOutcome.Qualified)
            }
        }

    @Test
    fun `later exact fuzzy preference replaces the earlier weaker directory match`(@TempDir home: Path) =
        withParser(home) { project ->
            val source =
                fixture(
                    project,
                    home.resolve("ranking"),
                    mapOf("selected/A.kt" to "class ZTarget", "selected/Z.kt" to "class Target"),
                    directory = "selected",
                )
            val base = source.request(1, 10_000, 100_000, null)
            val request =
                SymbolDiscoveryRequest(
                    base.scope,
                    SymbolDiscoveryTarget.Name(
                        io.github.amichne.kast.symbol.contract.SymbolNameDiscoveryKind.CLASS,
                        SymbolDiscoveryPattern.parse("Target").refined(),
                        SymbolDiscoveryMatch.FUZZY,
                    ),
                    base.budget,
                    base.constraints.copy(declarationKinds = null),
                )
            val observation = RecordingDiscoveryObservation { 0L }
            val outcome =
                IntellijNativeDiscoveryQuery(
                        environmentState = { IntellijDiscoveryEnvironmentState.READY },
                        cancellationCheck = {},
                        clock = IntellijReadNanoClock { 0L },
                        observation = observation,
                    )
                    .discoverPartitions(project, source.scope, request)
                    .outcome()
            assertEquals(listOf("Target"), outcome.batch().candidates.map { it.name.value })
            assertEquals(1, observation.counters[IntellijReadCounter.LEXICAL_CANDIDATES_REPLACED])
        }

    @Test
    fun `excluded class containers still supply supported local functions`(@TempDir home: Path) =
        withParser(home) { project ->
            val source =
                fixture(
                    project,
                    home.resolve("locals"),
                    mapOf("selected/A.kt" to "class Box { fun outer() { fun Target() = 1 } }"),
                    kinds = setOf(CompilerSymbolKind.FUNCTION),
                    directory = "selected",
                )
            val base = source.request(20, 10_000, 100_000, null)
            val request =
                SymbolDiscoveryRequest(
                    base.scope,
                    SymbolDiscoveryTarget.Name(
                        io.github.amichne.kast.symbol.contract.SymbolNameDiscoveryKind.SYMBOL,
                        SymbolDiscoveryPattern.parse("Target").refined(),
                        SymbolDiscoveryMatch.EXACT_NAME,
                    ),
                    base.budget,
                    base.constraints,
                )
            val observation = RecordingDiscoveryObservation { 0L }
            val result =
                IntellijNativeDiscoveryQuery(
                        environmentState = { IntellijDiscoveryEnvironmentState.READY },
                        cancellationCheck = {},
                        clock = IntellijReadNanoClock { 0L },
                        observation = observation,
                    )
                    .discoverPartitions(project, source.scope, request)
                    .outcome()
            assertTrue(result is SymbolDiscoveryOutcome.Complete)
            assertEquals(listOf("Target"), result.batch().candidates.map { it.name.value })
            assertEquals(
                26,
                (result.batch().candidates.single().location as SymbolDiscoveryCandidateLocation.Declaration)
                    .offset
                    .value,
            )
        }
}
