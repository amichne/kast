package io.github.amichne.kast.cli

import io.github.amichne.kast.cli.projection.CliLocalMetadata
import io.github.amichne.kast.cli.projection.CliLocalMetadataAdmission
import io.github.amichne.kast.cli.command.CliCommandGraphConstruction
import io.github.amichne.kast.cli.command.CliCommandGraphFactory
import io.github.amichne.kast.cli.command.CliAction
import io.github.amichne.kast.cli.command.CliCommandParsing
import io.github.amichne.kast.cli.projection.canonicalCliRequestPreparers
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.ChangeIntentDocument
import io.github.amichne.kast.protocol.contract.ChangePlanRequest
import io.github.amichne.kast.protocol.contract.ProtocolCount
import io.github.amichne.kast.protocol.contract.ProtocolOffset
import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.protocol.contract.SymbolDiscoverRequest
import io.github.amichne.kast.protocol.contract.SymbolDiscoverTargetDocument
import io.github.amichne.kast.protocol.contract.SymbolDiscoveryMatchDocument
import io.github.amichne.kast.protocol.contract.SymbolNameKindDocument
import io.github.amichne.kast.protocol.contract.SymbolTextScopeDocument
import io.github.amichne.kast.protocol.wire.CanonicalOperationWireBindings
import io.github.amichne.kast.protocol.wire.WireDecoding
import io.github.amichne.kast.protocol.wire.WireRequestAdmission
import io.github.amichne.kast.protocol.wire.WireRequestEnvelope
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path

class CliCommandGraphContractTest {
    @Test
    fun `root and nested help complete locally without touching runtime boundaries`() {
        var boundaryTouched = false
        val cli = testCli { boundaryTouched = true }

        val rootHelp = cli.execute(listOf("-h"), Path.of("/missing"))
        val nestedHelp = cli.execute(
            listOf("symbol", "discover", "--help"),
            Path.of("/missing"),
        )

        assertTrue(rootHelp is CliExit.Complete)
        assertTrue(nestedHelp is CliExit.Complete)
        assertTrue(rootHelp.document.value.contains("workspace"))
        assertTrue(nestedHelp.document.value.contains("--query"))
        assertFalse(boundaryTouched)
    }

    @Test
    fun `discovery modes accept equals syntax and preserve existing typed requests`() {
        val cases = listOf(
            listOf(
                "symbol", "discover", "--mode=name", "--query=Example", "--limit=10",
            ) to SymbolDiscoverRequest(
                SymbolDiscoverTargetDocument.Name(
                    text("Example"),
                    SymbolNameKindDocument.SYMBOL,
                    SymbolDiscoveryMatchDocument.FUZZY,
                ),
                count(10),
            ),
            listOf(
                "symbol", "discover", "--mode=location", "--file=A.kt", "--offset=7",
                "--limit=10",
            ) to SymbolDiscoverRequest(
                SymbolDiscoverTargetDocument.Location(text("A.kt"), offset(7)),
                count(10),
            ),
            listOf(
                "symbol", "discover", "--mode=structure", "--file=A.kt", "--limit=10",
            ) to SymbolDiscoverRequest(
                SymbolDiscoverTargetDocument.Structure(text("A.kt")),
                count(10),
            ),
            listOf(
                "symbol", "discover", "--mode=text", "--query=TODO", "--scope=workspace",
                "--limit=10",
            ) to SymbolDiscoverRequest(
                SymbolDiscoverTargetDocument.Text(
                    text("TODO"),
                    SymbolTextScopeDocument.Workspace,
                ),
                count(10),
            ),
            listOf(
                "symbol", "discover", "--mode=text", "--query=TODO", "--scope=file",
                "--file=A.kt", "--limit=10",
            ) to SymbolDiscoverRequest(
                SymbolDiscoverTargetDocument.Text(
                    text("TODO"),
                    SymbolTextScopeDocument.File(text("A.kt")),
                ),
                count(10),
            ),
        )

        cases.forEach { (argv, expected) ->
            val request = preparedRequest(argv).admittedWireRequest()
            assertEquals(
                WireDecoding.Decoded(expected),
                CanonicalOperationWireBindings.symbolDiscover.decodeRequest(request),
            )
        }
    }

    @Test
    fun `change intents preserve existing closed request variants`() {
        val cases = listOf(
            listOf(
                "change", "plan", "--intent=add-file", "--path=A.kt", "--content=class A",
            ) to ChangePlanRequest(ChangeIntentDocument.AddFile(text("A.kt"), text("class A"))),
            listOf(
                "change", "plan", "--intent=add-declaration", "--target=target",
                "--declaration=fun added()",
            ) to ChangePlanRequest(
                ChangeIntentDocument.AddDeclaration(text("target"), text("fun added()")),
            ),
            listOf(
                "change", "plan", "--intent=replace-declaration", "--target=target",
                "--replacement=fun replaced()",
            ) to ChangePlanRequest(
                ChangeIntentDocument.ReplaceDeclaration(text("target"), text("fun replaced()")),
            ),
            listOf(
                "change", "plan", "--intent=rename-symbol", "--target=target",
                "--new-name=renamed",
            ) to ChangePlanRequest(
                ChangeIntentDocument.RenameSymbol(text("target"), text("renamed")),
            ),
        )

        cases.forEach { (argv, expected) ->
            val request = preparedRequest(argv).admittedWireRequest()
            assertEquals(
                WireDecoding.Decoded(expected),
                CanonicalOperationWireBindings.changePlan.decodeRequest(request),
            )
        }
    }

    @Test
    fun `duplicates and mismatched options become deterministic usage data`() {
        val factory = commandGraphFactory()
        val duplicate = factory.parse(
            listOf(
                "symbol", "discover", "--query", "Example", "--limit", "10", "--limit", "20",
            ),
        )
        assertTrue(duplicate is CliCommandParsing.Rejected)
        assertTrue((duplicate as CliCommandParsing.Rejected).diagnostic.value.contains("exactly once"))

        var boundaryTouched = false
        val exit = testCli { boundaryTouched = true }.execute(
            listOf("symbol", "discover", "--mode", "location", "--query", "Example", "--limit", "10"),
            Path.of("/missing"),
        )
        assertTrue(exit is CliExit.BoundaryRejected)
        assertEquals(CliBoundaryExitStatus.USAGE, (exit as CliExit.BoundaryRejected).status)
        assertTrue(exit.document.value.contains("\"reason\":\"arguments-rejected\""))
        assertTrue(exit.document.value.contains("\"diagnostic\":"))
        assertFalse(boundaryTouched)
    }

    private fun testCli(boundaryTouched: () -> Unit): KastCli = KastCli(
        commandGraphFactory = commandGraphFactory(),
        rootDiscovery = CanonicalRootDiscoverer {
            boundaryTouched()
            error("root discovery must not run")
        },
        endpointLocator = RuntimeEndpointLocator {
            boundaryTouched()
            error("endpoint lookup must not run")
        },
        runtimeDemander = RuntimeDemander { _, _ ->
            boundaryTouched()
            error("runtime demand must not run")
        },
        wireClient = WireClient { _, _ ->
            boundaryTouched()
            error("wire exchange must not run")
        },
        localMetadata = when (
            val admitted = CliLocalMetadata.admit(
                productVersion = "1.2.3",
                runtimeIdentity = "sha256:${"a".repeat(64)}",
                schema = "{\"schemaVersion\":1}",
            )
        ) {
            is CliLocalMetadataAdmission.Admitted -> admitted.metadata
            is CliLocalMetadataAdmission.Rejected -> error("metadata: ${admitted.failure}")
        },
        lifecycle = ExactRootRuntimeLifecycle(),
    )

    private fun commandGraphFactory(): CliCommandGraphFactory = when (
        val construction = CliCommandGraphFactory.create(canonicalCliRequestPreparers())
    ) {
        is CliCommandGraphConstruction.Created -> construction.factory
        is CliCommandGraphConstruction.Rejected -> error(
            "command graph: ${construction.failures}",
        )
    }

    private fun preparedRequest(argv: List<String>): PreparedCliRequest {
        val parsed = commandGraphFactory().parse(argv)
        assertTrue(parsed is CliCommandParsing.Parsed, parsed.toString())
        val action = (parsed as CliCommandParsing.Parsed).action
        assertTrue(action is CliAction.Semantic, action.toString())
        return (action as CliAction.Semantic).request
    }

    private fun PreparedCliRequest.admittedWireRequest() = when (
        val admission = WireRequestEnvelope.admit(document)
    ) {
        is WireRequestAdmission.Admitted -> admission.request
        is WireRequestAdmission.Rejected -> error("wire request: ${admission.failure}")
    }

    private fun text(value: String): ProtocolText = ProtocolText.parse(value).refinedValue()

    private fun count(value: Int): ProtocolCount = ProtocolCount.parse(value).refinedValue()

    private fun offset(value: Int): ProtocolOffset = ProtocolOffset.parse(value).refinedValue()

    private fun <Strong, Failure> Refinement<Strong, Failure>.refinedValue(): Strong = when (this) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> error("refinement: $failure")
    }
}
