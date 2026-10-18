package io.github.amichne.kast.symbol.intellij

import com.intellij.navigation.NavigationItem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.testFramework.LightVirtualFile
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.WorkUnitLimit
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryCandidateLocation
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryMatch
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryOutcome
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryRequest
import io.github.amichne.kast.symbol.intellij.IntellijDeclarationDiscoveryTestFixture.batch
import io.github.amichne.kast.symbol.intellij.IntellijDeclarationDiscoveryTestFixture.outcome
import io.github.amichne.kast.symbol.intellij.IntellijDeclarationDiscoveryTestFixture.withParser
import java.nio.file.Path
import org.jetbrains.kotlin.idea.KotlinFileType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class DiscoveryDirectoryEnumerationWorkTest {
    @Test
    fun `fuzzy directory dispatcher never requests broad providers as unrelated files grow`(@TempDir home: Path) =
        withParser(home) { project ->
            val fixture = ScopedDiscoveryDispatcherFixture(project, home.resolve("source"))
            val base = fixture.request
            val request =
                SymbolDiscoveryRequest(
                    base.scope,
                    (base.target as io.github.amichne.kast.symbol.contract.SymbolDiscoveryTarget.Name).copy(
                        match = SymbolDiscoveryMatch.FUZZY
                    ),
                    base.budget.copy(
                        resources =
                            base.budget.resources.copy(
                                workUnitLimit = (WorkUnitLimit.parse(10_000) as Refinement.Refined).value
                            )
                    ),
                    base.constraints,
                )
            val violations = mutableListOf<String>()
            for (noise in listOf(0, 100, 100_000)) {
                val observation = RecordingDiscoveryObservation { 0L }
                val scope = fixture.scope(observation)
                var namedCalls = 0
                var fileCalls = 0
                val indexes =
                    object : IntellijDeclarationIndexes {
                        override fun candidateFiles(
                            name: IntellijIndexedDeclarationName,
                            scope: GlobalSearchScope,
                            accept: (VirtualFile) -> Boolean,
                        ): Boolean = throw AssertionError("Unexpected word index")

                        override fun named(
                            index: IntellijNamedDeclarationIndex,
                            name: String,
                            scope: GlobalSearchScope,
                            accept: (NavigationItem) -> Boolean,
                        ): Boolean {
                            assertEquals(IntellijNamedDeclarationIndex.CLASS, index)
                            assertEquals("Target", name)
                            namedCalls++
                            // These are the actual inputs of the requested provider, not injected membership visits.
                            return listOf(fixture.excludedFile.declarations.single(), fixture.target).all {
                                !scope.contains(it.containingFile.virtualFile) || accept(it)
                            }
                        }

                        override fun kotlinFiles(scope: GlobalSearchScope, accept: (VirtualFile) -> Boolean): Boolean {
                            fileCalls++
                            val files = sequence {
                                yield(fixture.excludedFile.virtualFile)
                                repeat(noise) { yield(NoiseFile(home.resolve("source/src/other/Noise$it.kt"))) }
                                yieldAll(fixture.admittedFiles.map { it.virtualFile })
                            }
                            return files.all { !scope.contains(it) || accept(it) }
                        }

                        override fun packageFiles(
                            packageName: String,
                            scope: GlobalSearchScope,
                            accept: (VirtualFile) -> Boolean,
                        ): Boolean = throw AssertionError("Unexpected package index")
                    }
                val result =
                    fixture.query(observation).discoverNative(project, scope, request, indexes = indexes).outcome()
                assertTrue(result is SymbolDiscoveryOutcome.Complete)
                assertEquals(listOf("Target"), result.batch().candidates.map { it.name.value })
                val candidate = result.batch().candidates.single()
                assertEquals(
                    home.resolve("source/src/narrow/File0.kt").toRealPath().toString(),
                    candidate.location.file.stableValue,
                )
                assertEquals(0, (candidate.location as SymbolDiscoveryCandidateLocation.Declaration).offset.value)
                assertEquals(fixture.request.scope.lease, candidate.lease)
                println(
                    "directory-proof noise=$noise named=$namedCalls files=$fileCalls " +
                        "calls=${observation.calls} counters=${observation.counters}"
                )
                if (namedCalls != 0 || fileCalls != 0) violations += "noise=$noise named=$namedCalls files=$fileCalls"
            }
            assertEquals(emptyList<String>(), violations, "A directory request must not enter broad providers")
        }

    private class NoiseFile(private val source: Path) :
        LightVirtualFile(source.fileName.toString(), KotlinFileType.INSTANCE, "class Noise") {
        override fun getPath(): String = source.toString()

        override fun toNioPath(): Path = source
    }
}
