package io.github.amichne.kast.symbol.intellij

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.WorkUnitLimit
import io.github.amichne.kast.symbol.contract.CompilerSymbolKind
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryQualification
import io.github.amichne.kast.symbol.intellij.IntellijDeclarationDiscoveryTestFixture.fixture
import io.github.amichne.kast.symbol.intellij.IntellijDeclarationDiscoveryTestFixture.withParser
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class LocalSupplementFileAdmissionTest {
    @Test
    fun `unowned Kotlin source cannot consume local supplement file capacity`(@TempDir home: Path) =
        withParser(home) { project ->
            val fixture =
                fixture(
                    project,
                    home.resolve("source-membership"),
                    mapOf(
                        "other/Noise.kt" to "fun noise() {}",
                        "selected/Locals.kt" to "fun outer() { val shared = 1 }",
                    ),
                    setOf(CompilerSymbolKind.PROPERTY),
                    sourceRootDirectory = "selected",
                )
            val qualifications = mutableListOf<SymbolDiscoveryQualification>()
            val limit =
                when (val parsed = WorkUnitLimit.parse(1)) {
                    is Refinement.Refined -> parsed.value
                    is Refinement.Rejected -> error(parsed.failure.toString())
                }
            val files =
                ScopedKotlinFileCollection(fixture.scope, limit, { true }, qualifications::add, localSourceOnly = true)
            fixture.files.forEach { files.accept(it.virtualFile) }
            assertEquals(listOf("Locals.kt"), files.values.map { it.name })
            assertEquals(ScopedFileCollectionStop.NONE, files.stop)
            assertEquals(emptyList<SymbolDiscoveryQualification>(), qualifications)
        }
}
