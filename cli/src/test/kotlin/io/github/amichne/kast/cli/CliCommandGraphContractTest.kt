package io.github.amichne.kast.cli

import io.github.amichne.kast.cli.command.CliAction
import io.github.amichne.kast.cli.command.CliCommandGraphConstruction
import io.github.amichne.kast.cli.command.CliCommandGraphFactory
import io.github.amichne.kast.cli.command.CliCommandParsing
import io.github.amichne.kast.cli.command.CliRequestDocumentInput
import io.github.amichne.kast.cli.projection.CliLocalMetadata
import io.github.amichne.kast.cli.projection.CliLocalMetadataAdmission
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
import java.nio.file.Path
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CliCommandGraphContractTest {
    @Test
    fun `root and nested help complete locally without touching runtime boundaries`() {
        var boundaryTouched = false
        val cli = testCli { boundaryTouched = true }

        val rootHelp = cli.execute(listOf("-h"), Path.of("/missing"))
        val nestedHelp =
            cli.execute(
                listOf("symbol", "discover", "--help"),
                Path.of("/missing"),
            )

        assertTrue(rootHelp is CliExit.Complete)
        assertTrue(nestedHelp is CliExit.Complete)
        assertTrue(rootHelp.document.value.contains("workspace"))
        assertTrue(nestedHelp.document.value.contains("standard input"))
        assertFalse(boundaryTouched)
    }

    @Test
    fun `canonical discovery documents preserve existing typed requests`() {
        val cases =
            listOf(
                SymbolDiscoverRequest(
                    SymbolDiscoverTargetDocument.Name(
                        text("Example"),
                        SymbolNameKindDocument.SYMBOL,
                        SymbolDiscoveryMatchDocument.FUZZY,
                    ),
                    count(10),
                ),
                SymbolDiscoverRequest(
                    SymbolDiscoverTargetDocument.Location(text("A.kt"), offset(7)),
                    count(10),
                ),
                SymbolDiscoverRequest(
                    SymbolDiscoverTargetDocument.Text(
                        text("TODO"),
                        SymbolTextScopeDocument.Workspace,
                    ),
                    count(10),
                ),
                SymbolDiscoverRequest(
                    SymbolDiscoverTargetDocument.Text(
                        text("TODO"),
                        SymbolTextScopeDocument.File(text("A.kt")),
                    ),
                    count(10),
                ),
            )

        cases.forEach { expected ->
            val request =
                preparedRequest(
                        listOf("symbol", "discover"),
                        SymbolDiscoverRequest.serializer(),
                        expected,
                    )
                    .admittedWireRequest()
            assertEquals(
                WireDecoding.Decoded(expected),
                CanonicalOperationWireBindings.symbolDiscover.decodeRequest(request),
            )
        }
    }

    @Test
    fun `canonical change documents preserve existing closed request variants`() {
        val cases =
            listOf(
                ChangePlanRequest(ChangeIntentDocument.AddFile(text("A.kt"), text("class A"))),
                ChangePlanRequest(ChangeIntentDocument.AddDeclaration(text("target"), text("fun added()"))),
                ChangePlanRequest(ChangeIntentDocument.ReplaceDeclaration(text("target"), text("fun replaced()"))),
                ChangePlanRequest(ChangeIntentDocument.RenameSymbol(text("target"), text("renamed"))),
            )

        cases.forEach { expected ->
            val request =
                preparedRequest(
                        listOf("change", "plan"),
                        ChangePlanRequest.serializer(),
                        expected,
                    )
                    .admittedWireRequest()
            assertEquals(
                WireDecoding.Decoded(expected),
                CanonicalOperationWireBindings.changePlan.decodeRequest(request),
            )
        }
    }

    @Test
    fun `extra arguments and malformed request documents become deterministic usage data`() {
        val factory = commandGraphFactory()
        val duplicate =
            factory.parse(
                listOf("symbol", "discover", "unexpected"),
                CliRequestDocumentInput.Provided(
                    """{"target":{"type":"name","query":"Example","kind":"symbol","match":"fuzzy"},"limit":10}"""
                ),
            )
        assertTrue(duplicate is CliCommandParsing.Rejected)
        assertTrue((duplicate as CliCommandParsing.Rejected).diagnostic.value.contains("unexpected"))

        var boundaryTouched = false
        val exit = testCli {
            boundaryTouched = true
        }
            .execute(
                listOf("symbol", "discover"),
                Path.of("/missing"),
                CliRequestDocumentInput.Provided(
                    """{"target":{"type":"name","query":" ","kind":"symbol","match":"fuzzy"},"limit":10}"""
                ),
            )
        assertTrue(exit is CliExit.BoundaryRejected)
        assertEquals(CliBoundaryExitStatus.USAGE, (exit as CliExit.BoundaryRejected).status)
        assertTrue(exit.document.value.contains("\"reason\":\"arguments-rejected\""))
        assertTrue(exit.document.value.contains("\"diagnostic\":"))
        assertFalse(boundaryTouched)
    }

    private fun testCli(boundaryTouched: () -> Unit): KastCli =
        KastCli(
            commandGraphFactory = commandGraphFactory(),
            rootDiscovery =
                CanonicalRootDiscoverer {
                    boundaryTouched()
                    error("root discovery must not run")
                },
            localMetadata =
                when (
                    val admitted =
                        CliLocalMetadata.admit(
                            productVersion = "1.2.3",
                            schema = "{\"schemaVersion\":1}",
                        )
                ) {
                    is CliLocalMetadataAdmission.Admitted -> admitted.metadata
                    is CliLocalMetadataAdmission.Rejected -> error("metadata: ${admitted.failure}")
                },
            productVersion =
                (io.github.amichne.kast.protocol.contract.KastPluginVersion.parse("1.2.3")
                        as io.github.amichne.kast.kernel.Refinement.Refined)
                    .value,
        )

    private fun commandGraphFactory(): CliCommandGraphFactory =
        when (val construction = CliCommandGraphFactory.create(canonicalCliRequestPreparers())) {
            is CliCommandGraphConstruction.Created -> construction.factory
            is CliCommandGraphConstruction.Rejected -> error("command graph: ${construction.failures}")
        }

    private fun <Request> preparedRequest(
        argv: List<String>,
        serializer: KSerializer<Request>,
        request: Request,
    ): PreparedCliRequest {
        val document = Json {
            encodeDefaults = true
            classDiscriminator = "type"
        }
            .encodeToString(serializer, request)
        val parsed =
            commandGraphFactory()
                .parse(
                    argv,
                    CliRequestDocumentInput.Provided(document),
                )
        assertTrue(parsed is CliCommandParsing.Parsed, parsed.toString())
        val action = (parsed as CliCommandParsing.Parsed).action
        assertTrue(action is CliAction.Semantic, action.toString())
        return (action as CliAction.Semantic).request
    }

    private fun PreparedCliRequest.admittedWireRequest() =
        when (val admission = WireRequestEnvelope.admit(document)) {
            is WireRequestAdmission.Admitted -> admission.request
            is WireRequestAdmission.Rejected -> error("wire request: ${admission.failure}")
        }

    private fun text(value: String): ProtocolText = ProtocolText.parse(value).refinedValue()

    private fun count(value: Int): ProtocolCount = ProtocolCount.parse(value).refinedValue()

    private fun offset(value: Int): ProtocolOffset = ProtocolOffset.parse(value).refinedValue()

    private fun <Strong, Failure> Refinement<Strong, Failure>.refinedValue(): Strong =
        when (this) {
            is Refinement.Refined -> value
            is Refinement.Rejected -> error("refinement: $failure")
        }
}
