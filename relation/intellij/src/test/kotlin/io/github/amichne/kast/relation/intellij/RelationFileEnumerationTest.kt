package io.github.amichne.kast.relation.intellij

import io.github.amichne.kast.kernel.ReadLimitParameter
import io.github.amichne.kast.kernel.ReadLimits
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.RelationMeaning
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryConstraints
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryContainment
import io.github.amichne.kast.symbol.contract.SymbolLibraryPolicy
import io.github.amichne.kast.symbol.contract.SymbolSearchScope
import io.github.amichne.kast.workspace.intellij.read.IntellijReadCall
import io.github.amichne.kast.workspace.intellij.read.IntellijReadCallOutcome
import io.github.amichne.kast.workspace.intellij.read.IntellijReadCounter
import io.github.amichne.kast.workspace.intellij.read.IntellijReadObservation
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

internal class RelationFileEnumerationTest : RelationFileEnumerationFixture() {
    @Test
    fun `directory constraints and root ownership precede VFS lookup and file capacity`() {
        val selected =
            RelationFileEnumerationPlan.compile(
                sourceScope,
                roots.filter { it.project == roots[0].project },
                "/workspace",
                directory("app/src", SymbolDiscoveryContainment.DIRECT),
            ) {
                it == a.toNioPath()
            }
        val observed = EnumerationObservation()
        val allowance = IntellijRelationAllowance { 0L }
        val limits =
            (ReadLimits.resolve(properties = mapOf(ReadLimitParameter.DISCOVERY_FILES.propertyKey to "1"))
                    as Refinement.Refined)
                .value
        val universe = prepare(selected, allowance, limits, observed).complete()
        assertArrayEquals(intArrayOf(11), universe.ids())
        assertEquals(6L, allowance.examined) // One root lookup, three nodes, two child admissions.
        assertEquals(3L, observed.counts[IntellijReadCounter.RELATION_FILE_ENUMERATION_NODES])
        assertEquals(1L, observed.counts[IntellijReadCounter.RELATION_FILE_ENUMERATION_FILES])
    }

    @Test
    fun `direct inventory skips descendant containers without inspecting their children`() {
        val nested =
            EnumerationTestFile(
                path.resolve("nested"),
                30,
                listOf(EnumerationTestFile(path.resolve("nested/N.kt"), 31)),
            )
        val directRoot = EnumerationTestFile(path, 10, listOf(a, nested))
        val selected =
            RelationFileEnumerationPlan.compile(
                sourceScope,
                roots.take(1),
                "/workspace",
                directory("app/src", SymbolDiscoveryContainment.DIRECT),
            ) {
                true
            }
        assertArrayEquals(intArrayOf(11), prepare(selected, lookupRoot = directRoot).complete().ids())
        assertEquals(0, nested.childrenCalls)
    }

    @Test
    fun `ineligible broad and library scopes decline without any external lookup`() {
        for ((libraries, constraints, reason) in
            listOf(
                Triple(
                    SymbolLibraryPolicy.INCLUDE,
                    directory("app/src", SymbolDiscoveryContainment.DESCENDANTS),
                    RelationFileEnumerationDecline.LIBRARIES_INCLUDED,
                ),
                Triple(
                    SymbolLibraryPolicy.EXCLUDE,
                    SymbolDiscoveryConstraints.None,
                    RelationFileEnumerationDecline.WORKSPACE_UNBOUNDED,
                ),
            )) {
            val selected =
                RelationFileEnumerationPlan.compile(
                    SymbolSearchScope.Workspace(sourceScope.sourceKinds, sourceScope.generatedSources, libraries),
                    roots,
                    "/workspace",
                    constraints,
                ) {
                    error("Unexpected path admission")
                }
            val observation = EnumerationObservation()
            val allowance = IntellijRelationAllowance { 0L }
            val outcome =
                CompleteRelationFileUniverse.prepare(
                    selected,
                    request.budget.resources,
                    allowance,
                    ReadLimits.Default,
                    observation,
                    lookup = { error("Unexpected lookup") },
                )
            assertEquals(RelationFileEnumerationPreparation.Declined(reason), outcome)
            assertEquals(0L, allowance.examined)
            assertEquals(mapOf(reason.counter() to 1L), observation.counts)
        }
    }

    @Test
    fun `failed inventory preserves spent work and never publishes partial IDs`() {
        val limits =
            (ReadLimits.resolve(properties = mapOf(ReadLimitParameter.DISCOVERY_FILES.propertyKey to "1"))
                    as Refinement.Refined)
                .value
        val allowance = IntellijRelationAllowance { 0L }
        val observation = EnumerationObservation()
        assertEquals(
            RelationFileEnumerationPreparation.Declined(RelationFileEnumerationDecline.FILE_CAPACITY),
            prepare(plan(), allowance, limits, observation),
        )
        assertEquals(6L, allowance.examined)
        assertFalse(IntellijReadCounter.RELATION_FILE_ENUMERATION_COMPLETE in observation.counts)
        assertEquals(1L, observation.counts[IntellijReadCounter.RELATION_FILE_ENUMERATION_FILE_CAPACITY])
    }

    @Test
    fun `empty admitted universe is complete and distinct from an unavailable root`() {
        val emptyRoot = EnumerationTestFile(path, 10, emptyList())
        assertArrayEquals(intArrayOf(), prepare(plan(), lookupRoot = emptyRoot).complete().ids())
        val result =
            CompleteRelationFileUniverse.prepare(
                plan(),
                request.budget.resources,
                IntellijRelationAllowance { 0L },
                ReadLimits.Default,
                IntellijReadObservation.None,
                lookup = { RelationNativeFileLookup.Unavailable },
            )
        assertEquals(
            RelationFileEnumerationPreparation.Declined(RelationFileEnumerationDecline.ROOT_UNAVAILABLE),
            result,
        )
    }

    @Test
    fun `invalid paths roots and IDs have closed decline reasons`() {
        val cases =
            listOf(
                EnumerationTestFile(path, 10, listOf(a), valid = false) to RelationFileEnumerationDecline.FILE_INVALID,
                EnumerationTestFile(Path.of("/workspace/other"), 10, listOf(a)) to
                    RelationFileEnumerationDecline.PATH_MISMATCH,
                EnumerationTestFile(path, 10) to RelationFileEnumerationDecline.ROOT_KIND_MISMATCH,
                EnumerationTestFile(path, 10, listOf(EnumerationTestFile(path.resolve("Bad.kt"), 0))) to
                    RelationFileEnumerationDecline.FILE_ID_UNAVAILABLE,
                EnumerationTestFile(path, 10, listOf(a, EnumerationTestFile(path.resolve("Duplicate.kt"), 11))) to
                    RelationFileEnumerationDecline.FILE_ID_COLLISION,
                EnumerationTestFile(path, 10, listOf(EnumerationTestFile(Path.of("/workspace/escaped.kt"), 20))) to
                    RelationFileEnumerationDecline.PATH_MISMATCH,
            )
        for ((observedRoot, reason) in cases) {
            assertEquals(
                RelationFileEnumerationPreparation.Declined(reason),
                prepare(plan(), lookupRoot = observedRoot),
            )
        }
    }

    @Test
    fun `optional work uses a quarter of remaining work and the original semantic deadline`() {
        val small = RelationReadTest().request(RelationMeaning.References, workLimit = 8)
        val allowance = IntellijRelationAllowance { 0L }
        allowance.examine()
        val outcome =
            CompleteRelationFileUniverse.prepare(
                plan(),
                small.budget.resources,
                allowance,
                ReadLimits.Default,
                IntellijReadObservation.None,
                lookup = { RelationNativeFileLookup.Found(root) },
            )
        assertEquals(RelationFileEnumerationPreparation.Declined(RelationFileEnumerationDecline.WORK_LIMIT), outcome)
        assertEquals(2L, allowance.examined)
        var now = 0L
        val timed = IntellijRelationAllowance { now }
        now = 1_000_000_000L
        val expired =
            CompleteRelationFileUniverse.prepare(
                plan(),
                request.budget.resources,
                timed,
                ReadLimits.Default,
                IntellijReadObservation.None,
                lookup = { error("Expired lookup") },
            )
        assertEquals(RelationFileEnumerationPreparation.Declined(RelationFileEnumerationDecline.TIME_LIMIT), expired)
        assertEquals(0L, timed.examined)
    }

    @Test
    fun `cancellation retains the native exceptional exit and no completed inventory`() {
        val observation = EnumerationObservation()
        val cancelled = com.intellij.openapi.progress.ProcessCanceledException()
        assertSame(
            cancelled,
            assertThrows<com.intellij.openapi.progress.ProcessCanceledException> {
                CompleteRelationFileUniverse.prepare(
                    plan(),
                    request.budget.resources,
                    IntellijRelationAllowance { 0L },
                    ReadLimits.Default,
                    observation,
                    lookup = { throw cancelled },
                )
            },
        )
        assertEquals(
            listOf(
                IntellijReadCall.VFS_FIND_FILE to IntellijReadCallOutcome.CANCELLED,
                IntellijReadCall.RELATION_FILE_ENUMERATION_PREPARATION to IntellijReadCallOutcome.CANCELLED,
            ),
            observation.finished,
        )
        assertFalse(IntellijReadCounter.RELATION_FILE_ENUMERATION_COMPLETE in observation.counts)
    }
}
