package io.github.amichne.kast.runtime.ide.read.composition

import io.github.amichne.kast.kernel.EvidenceGeneration
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.ProtocolCount
import io.github.amichne.kast.protocol.contract.ProtocolOffset
import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.protocol.contract.SymbolDiscoverRequest
import io.github.amichne.kast.protocol.contract.SymbolDiscoverTargetDocument
import io.github.amichne.kast.protocol.contract.SymbolDiscoveryMatchDocument
import io.github.amichne.kast.protocol.contract.SymbolNameKindDocument
import io.github.amichne.kast.protocol.contract.SymbolTextScopeDocument
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryTarget
import io.github.amichne.kast.symbol.contract.SymbolSearchScope
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import io.github.amichne.kast.workspace.contract.SemanticReadLease
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.nio.file.Path

class HostedDiscoveryProtocolTest {
    @Test
    fun `all published discovery targets refine to exact hosted domain authority`() {
        val lease = lease()
        val requests = listOf(
            request(
                SymbolDiscoverTargetDocument.Name(
                    text("Widget"),
                    SymbolNameKindDocument.SYMBOL,
                    SymbolDiscoveryMatchDocument.EXACT_NAME,
                ),
            ),
            request(SymbolDiscoverTargetDocument.Location(text("src/Widget.kt"), offset(17))),
            request(
                SymbolDiscoverTargetDocument.Text(
                    text("Widget"),
                    SymbolTextScopeDocument.Workspace,
                ),
            ),
            request(
                SymbolDiscoverTargetDocument.Text(
                    text("Widget"),
                    SymbolTextScopeDocument.File(text("src/Widget.kt")),
                ),
            ),
        )

        val admitted = requests.map { request ->
            assertInstanceOf(
                HostedDiscoveryRequestAdmission.Admitted::class.java,
                admitHostedDiscoveryRequest(lease, request),
            ).request
        }

        assertInstanceOf(SymbolDiscoveryTarget.Name::class.java, admitted[0].target)
        assertInstanceOf(SymbolDiscoveryTarget.Location::class.java, admitted[1].target)
        assertInstanceOf(SymbolSearchScope.ExactFile::class.java, admitted[1].scope.scope)
        assertInstanceOf(SymbolDiscoveryTarget.Text::class.java, admitted[2].target)
        assertInstanceOf(SymbolSearchScope.Workspace::class.java, admitted[2].scope.scope)
        assertInstanceOf(SymbolDiscoveryTarget.Text::class.java, admitted[3].target)
        assertInstanceOf(SymbolSearchScope.ExactFile::class.java, admitted[3].scope.scope)
    }

    @Test
    fun `outside workspace discovery paths fail closed before native search`() {
        val requests = listOf(
            request(SymbolDiscoverTargetDocument.Location(text("../cache/Widget.kt"), offset(1))),
            request(
                SymbolDiscoverTargetDocument.Text(
                    text("Widget"),
                    SymbolTextScopeDocument.File(text("../../build/Widget.kt")),
                ),
            ),
        )

        requests.forEach { request ->
            assertEquals(
                HostedDiscoveryRequestAdmission.Rejected,
                admitHostedDiscoveryRequest(lease(), request),
            )
        }
    }

    private fun lease() = SemanticReadLease(
        refined(CanonicalWorkspaceRoot.fromCanonicalPath(Path.of("/workspace/kast"))),
        refined(EvidenceGeneration.parse(7)),
    )

    private fun request(target: SymbolDiscoverTargetDocument) = SymbolDiscoverRequest(
        target,
        refined(ProtocolCount.parse(25)),
    )

    private fun text(raw: String): ProtocolText = refined(ProtocolText.parse(raw))
    private fun offset(raw: Int): ProtocolOffset = refined(ProtocolOffset.parse(raw))

    private fun <Value, Failure> refined(result: Refinement<Value, Failure>): Value = when (result) {
        is Refinement.Refined -> result.value
        is Refinement.Rejected -> error("fixture rejected: ${result.failure}")
    }
}
