package io.github.amichne.kast.relation.intellij

import com.intellij.psi.search.impl.VirtualFileEnumeration
import io.github.amichne.kast.kernel.ReadLimits
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.symbol.contract.CanonicalWorkspaceFilePath
import io.github.amichne.kast.symbol.contract.SymbolSearchScope
import io.github.amichne.kast.workspace.intellij.read.IntellijReadCallOutcome
import io.github.amichne.kast.workspace.intellij.read.IntellijReadCounter
import io.github.amichne.kast.workspace.intellij.read.IntellijReadObservation
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

internal class EnumeratedRelationScopeTest : RelationFileEnumerationFixture() {
    @Test
    fun `project independent scopes retain their original reference search`() {
        val original =
            EnumeratedRelationScope(nativeScope { it === a }, prepare(plan()).complete(), IntellijReadObservation.None)
        assertSame(
            original,
            original
                .referencePartitions(
                    NativeRelationScopeAdmission(IntellijReadObservation.None) {
                        IntellijRelationProviderEnumerationAdmission.READY
                    }
                )
                .single(),
        )
    }

    @Test
    fun `compiled scope policies select only their admitted source roots before lookup`() {
        val file =
            (CanonicalWorkspaceFilePath.fromCanonicalPath(request.subject.lease.workspaceRoot, a.toNioPath())
                    as Refinement.Refined)
                .value
        val targets =
            listOf(
                sourceScope,
                SymbolSearchScope.Module(roots[0].module, sourceScope.sourceKinds, sourceScope.generatedSources),
                SymbolSearchScope.GradleProject(
                    roots[0].project,
                    sourceScope.sourceKinds,
                    sourceScope.generatedSources,
                ),
                SymbolSearchScope.ExactFile(file, sourceScope.sourceKinds, sourceScope.generatedSources),
            )
        for (target in targets) {
            val sdk = RelationScopeSdkFixture()
            val compiled =
                (IntellijRelationScopeCompiler().compile(sdk.project, request, model, target)
                        as IntellijRelationScopeCompilation.Compiled)
                    .scope
            val lookups = mutableListOf<Path>()
            val exact = target is SymbolSearchScope.ExactFile
            val refined =
                compiled.prepareFileEnumeration(
                    IntellijRelationAllowance { 0L },
                    ReadLimits.Default,
                    lookup = { expected ->
                        lookups.add(expected)
                        assertEquals(if (exact) a.toNioPath() else path, expected)
                        RelationNativeFileLookup.Found(if (exact) a else root)
                    },
                )
            assertEquals(1, lookups.size)
            assertArrayEquals(
                if (exact) intArrayOf(11) else intArrayOf(11, 12),
                VirtualFileEnumeration.extract(refined.nativeScope)!!.asArray().sortedArray(),
            )
            sdk.assertConsumed()
        }
    }

    @Test
    fun `production scope refinement retains ownership and original scope on declined inventory`() {
        val observation = EnumerationObservation()
        val sdk = RelationScopeSdkFixture(sourceContains = { it === a || it === b })
        val compiled =
            (IntellijRelationScopeCompiler()
                    .compile(
                        sdk.project,
                        request,
                        model,
                        sourceScope,
                        observation = observation,
                    ) as IntellijRelationScopeCompilation.Compiled)
                .scope
        val refined =
            compiled.prepareFileEnumeration(
                IntellijRelationAllowance { 0L },
                ReadLimits.Default,
                lookup = { expected ->
                    assertEquals(path, expected)
                    RelationNativeFileLookup.Found(root)
                },
            )
        assertSame(compiled.request, refined.request)
        assertSame(compiled.sourceRoots, refined.sourceRoots)
        assertTrue(refined.nativeScope.contains(a))
        assertArrayEquals(
            intArrayOf(11, 12),
            VirtualFileEnumeration.extract(refined.nativeScope)!!.asArray().sortedArray(),
        )
        val declined =
            compiled.prepareFileEnumeration(
                IntellijRelationAllowance { 0L },
                ReadLimits.Default,
                lookup = { RelationNativeFileLookup.Unavailable },
            )
        assertSame(compiled, declined)
        assertNull(VirtualFileEnumeration.extract(declined.nativeScope))
        sdk.assertConsumed()
    }

    @Test
    fun `complete inventory preserves both visibility predicates through repeated SDK intersections`() {
        val observation = EnumerationObservation()
        val prepared = prepare(plan(), observation = observation).complete()
        val model = nativeScope { it === a || it === b }
        val indexed = EnumeratedRelationScope(model, prepared, observation)
        val narrowed = indexed.intersectWith(nativeScope { it === b }).intersectWith(nativeScope { it === b })
        val enumeration = VirtualFileEnumeration.extract(narrowed)!!
        assertTrue(enumeration.contains(11)) // Complete superset; the actual intersected predicate excludes A.
        assertTrue(enumeration.contains(12))
        assertFalse(enumeration.contains(13))
        assertFalse(narrowed.contains(a))
        assertTrue(narrowed.contains(b))
        assertEquals(listOf(b), enumeration.filesIfCollection!!.toList())
        assertArrayEquals(intArrayOf(11, 12), enumeration.asArray().sortedArray())
        enumeration.asArray().fill(99)
        assertArrayEquals(intArrayOf(11, 12), enumeration.asArray().sortedArray())
        assertEquals(1L, observation.counts[IntellijReadCounter.RELATION_FILE_ENUMERATION_COMPLETE])
        assertEquals(2L, observation.counts[IntellijReadCounter.RELATION_SCOPE_FILE_IDS_ADMITTED])
        assertEquals(1L, observation.counts[IntellijReadCounter.RELATION_SCOPE_FILE_IDS_EXCLUDED])
        assertTrue(observation.finished.all { it.second == IntellijReadCallOutcome.RETURNED })
        assertThrows<UnsupportedOperationException> { (enumeration.filesIfCollection as MutableCollection).clear() }
    }

    @Test
    fun `union and a different read never acquire this read's closed subset identity`() {
        val scope = nativeScope { it === a }
        val first = EnumeratedRelationScope(scope, prepare(plan()).complete(), IntellijReadObservation.None)
        val second = EnumeratedRelationScope(scope, prepare(plan()).complete(), IntellijReadObservation.None)
        assertNotEquals(first, second)
        val outside = EnumerationTestFile(Path.of("/workspace/noise/src/N.kt"), 90)
        val union = first.uniteWith(nativeScope { it === outside })
        assertTrue(union.contains(outside))
        assertNull(VirtualFileEnumeration.extract(union))
    }
}
