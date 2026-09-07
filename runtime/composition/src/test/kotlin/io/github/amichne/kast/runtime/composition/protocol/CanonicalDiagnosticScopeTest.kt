package io.github.amichne.kast.runtime.composition.protocol

import io.github.amichne.kast.diagnostic.contract.DiagnosticBatch
import io.github.amichne.kast.diagnostic.contract.DiagnosticCompilation
import io.github.amichne.kast.diagnostic.contract.DiagnosticCompilerPort
import io.github.amichne.kast.diagnostic.contract.DiagnosticScope
import io.github.amichne.kast.diagnostic.contract.DiagnosticScopeResolver
import io.github.amichne.kast.diagnostic.contract.DiagnosticScopeResolutionFailure
import io.github.amichne.kast.diagnostic.service.DiagnosticService
import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.DiagnosticCheckRequest
import io.github.amichne.kast.protocol.contract.DiagnosticCheckRejection
import io.github.amichne.kast.protocol.contract.ProtocolCount
import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.runtime.composition.InstalledSymbolProtocolFixture
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class CanonicalDiagnosticScopeTest {
    @Test
    fun `a directory resolves every source file under the same lease before diagnostics`(@TempDir temporary: Path) = runBlocking {
        val root = Files.createDirectory(temporary.resolve("repo")).toRealPath()
        val fixture = InstalledSymbolProtocolFixture.create(root)
        var analyzed: List<String> = emptyList()
        val operations = DiagnosticService(fixture.workspace, DiagnosticCompilerPort { scope ->
            analyzed = scope.files.map { it.value }
            DiagnosticCompilation.complete(DiagnosticBatch.empty(scope))
        })
        val handler = CanonicalDiagnosticCheckHandler(fixture.workspace, operations, CanonicalProtocolAuthority(), DiagnosticScopeResolver { query ->
            assertEquals(root.resolve("src"), query.path)
            val admitted = DiagnosticScope.fromCanonicalPaths(query.lease, listOf(query.path.resolve("B.kt"), query.path.resolve("A.kt")))
            when (admitted) {
                is Refinement.Refined -> admitted
                is Refinement.Rejected -> error(admitted.failure)
            }
        })

        assertInstanceOf(OperationOutcome.Complete::class.java, handler.execute(request("src")))
        assertEquals(listOf(root.resolve("src/A.kt").toString(), root.resolve("src/B.kt").toString()), analyzed)
    }

    @Test
    fun `scope admission failures never invoke compiler diagnostics`(@TempDir temporary: Path) = runBlocking {
        val root = Files.createDirectory(temporary.resolve("repo")).toRealPath()
        val fixture = InstalledSymbolProtocolFixture.create(root)
        val operations = DiagnosticService(fixture.workspace, DiagnosticCompilerPort { error("rejected scope reached compiler") })
        for ((reason, expected) in listOf(
            DiagnosticScopeResolutionFailure.EMPTY to DiagnosticCheckRejection.SCOPE_EMPTY,
            DiagnosticScopeResolutionFailure.LIMIT_EXCEEDED to DiagnosticCheckRejection.SCOPE_LIMIT_EXCEEDED,
            DiagnosticScopeResolutionFailure.UNAVAILABLE to DiagnosticCheckRejection.SCOPE_UNAVAILABLE,
        )) {
            val handler = CanonicalDiagnosticCheckHandler(fixture.workspace, operations, CanonicalProtocolAuthority(), DiagnosticScopeResolver { Refinement.Rejected(reason) })
            assertEquals(OperationOutcome.Rejected(expected), handler.execute(request("src")))
        }
    }

    private fun request(scope: String) = DiagnosticCheckRequest(
        (ProtocolText.parse(scope) as Refinement.Refined).value,
        (ProtocolCount.parse(1) as Refinement.Refined).value,
    )
}
