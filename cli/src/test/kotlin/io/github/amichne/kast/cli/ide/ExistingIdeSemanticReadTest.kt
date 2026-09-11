package io.github.amichne.kast.cli.ide

import io.github.amichne.kast.cli.*
import io.github.amichne.kast.cli.command.CliAction
import io.github.amichne.kast.cli.command.CliRequestDocumentInput
import io.github.amichne.kast.cli.projection.CanonicalReadCliDocuments
import io.github.amichne.kast.kernel.*
import io.github.amichne.kast.protocol.contract.*
import io.github.amichne.kast.protocol.wire.*
import java.nio.file.Path
import java.util.UUID
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ExistingIdeSemanticReadTest {
    private val root = CanonicalRoot(Path.of("/workspace"))
    private val descriptor = ExistingIdeDescriptor(123, UUID.fromString("00000000-0000-0000-0000-000000000001"))
    private val live =
        EvidenceBasis.Live(
            LiveReadEvidence.create(
                    root.path.toString(),
                    descriptor.host,
                    7,
                    LiveReadContentView.SAVED_PSI_COMMITTED,
                    1,
                )
                .refined()
        )
    private val published = EvidenceBasis.Published(EvidenceGeneration.parse(7).refined())
    private val requests =
        listOf(
            Triple(
                "query run",
                ExistingIdeReadOperation.QUERY_RUN,
                """{"type":"QUERY","from":{"type":"SEARCH","query":"Example"}}""",
            ),
            Triple(
                "symbol discover",
                ExistingIdeReadOperation.SYMBOL_DISCOVER,
                """{"target":{"type":"name","query":"Example","kind":"symbol","match":"fuzzy"},"limit":10}""",
            ),
            Triple(
                "symbol inspect",
                ExistingIdeReadOperation.SYMBOL_INSPECT,
                """{"target":{"type":"exact","selector":"exact:v2:e30:44136fa355b3678a1146ad16f7e8649e94fb4fc21fe77e8310c060f61caaff8a"}}""",
            ),
            Triple(
                "source read",
                ExistingIdeReadOperation.SOURCE_READ,
                """{"anchor":{"type":"symbol","selector":"exact:v2:e30:44136fa355b3678a1146ad16f7e8649e94fb4fc21fe77e8310c060f61caaff8a"},"region":{"type":"anchor"},"entities":{"type":"none"},"text":{"type":"complete"},"entityLimit":10,"textByteLimit":4096,"page":{"type":"first"}}""",
            ),
            Triple(
                "relation read",
                ExistingIdeReadOperation.RELATION_READ,
                """{"exactSelector":"exact:v2:e30:44136fa355b3678a1146ad16f7e8649e94fb4fc21fe77e8310c060f61caaff8a","relation":"references","limit":10}""",
            ),
            Triple(
                "traversal run",
                ExistingIdeReadOperation.TRAVERSAL_RUN,
                """{"exactSelector":"exact:v2:e30:44136fa355b3678a1146ad16f7e8649e94fb4fc21fe77e8310c060f61caaff8a","relation":"references","maximumDepth":1,"maximumResults":10}""",
            ),
            Triple("diagnostic check", ExistingIdeReadOperation.DIAGNOSTIC_CHECK, """{"path":"src","limit":10}"""),
        )

    @Test
    fun `all canonical reads parse before the sole existing-host capability is invoked`() {
        for ((command, operation, document) in
            requests.flatMap { (command, operation, document) ->
                listOf(Triple(command, operation, document), Triple("-- $command", operation, document))
            }) {
            var calls = 0
            val result =
                executeExistingIdeCli(
                    command.split(' '),
                    root.path,
                    CanonicalRootDiscoverer { CanonicalRootDiscovery.Discovered(root) },
                    ExistingIdeClient { admittedRoot, admitted ->
                        assertSame(root, admittedRoot)
                        assertEquals(operation, (admitted as ExistingIdeOperation.Read).kind)
                        calls++
                        ExistingIdeExchange.Rejected(ExistingIdeFailure.HOST_UNAVAILABLE)
                    },
                    CliRequestDocumentInput.Provided(document),
                )
            assertEquals(1, calls, "$command: ${result.document.value}")
            assertTrue(result.document.value.contains("ide-host-unavailable"), command)
        }
    }

    @Test
    fun `read help and invalid parsing perform no stdin root or host effects`() {
        val roots = CanonicalRootDiscoverer { fail("Unexpected root discovery") }
        val client = ExistingIdeClient { _, _ -> fail("Unexpected host access") }
        for ((command) in requests) {
            val help =
                executeExistingIdeCli(
                    command.split(' ') + "--help",
                    root.path,
                    roots,
                    client,
                    CliRequestDocumentInput.Deferred { fail("Help read stdin") },
                )
            assertEquals(0, help.code, command)
            val invalid =
                executeExistingIdeCli(
                    command.split(' ') + "unexpected",
                    root.path,
                    roots,
                    client,
                    CliRequestDocumentInput.Deferred { fail("Invalid arguments read stdin") },
                )
            assertNotEquals(0, invalid.code, command)
            assertNotEquals(
                0,
                executeExistingIdeCli(
                        command.split(' '),
                        root.path,
                        roots,
                        client,
                        CliRequestDocumentInput.Provided("{}"),
                    )
                    .code,
                command,
            )
        }
        for (operation in
            CanonicalOperation.entries.filter { candidate ->
                ExistingIdeReadOperation.entries.none { it.canonical == candidate }
            }) assertEquals(
            Refinement.Rejected(ExistingIdeFailure.OPERATION_UNSUPPORTED),
            ExistingIdeReadOperation.admit(operation),
        )
    }

    @Test
    fun `each successful read binding requires the original live host and rejects published evidence`() {
        val replies: List<Pair<ExistingIdeReadOperation, (EvidenceBasis) -> String>> =
            listOf(
                ExistingIdeReadOperation.QUERY_RUN to
                    { basis ->
                        encoded(CanonicalOperationWireBindings.queryRun, QueryRunResult(empty(), empty()), basis)
                    },
                ExistingIdeReadOperation.SYMBOL_DISCOVER to
                    { basis ->
                        encoded(CanonicalOperationWireBindings.symbolDiscover, SymbolDiscoverResult(empty()), basis)
                    },
                ExistingIdeReadOperation.RELATION_READ to
                    { basis ->
                        encoded(CanonicalOperationWireBindings.relationRead, RelationReadResult(empty()), basis)
                    },
                ExistingIdeReadOperation.TRAVERSAL_RUN to
                    { basis ->
                        encoded(
                            CanonicalOperationWireBindings.traversalRun,
                            TraversalRunResult(text("/workspace"), empty()),
                            basis,
                        )
                    },
                ExistingIdeReadOperation.DIAGNOSTIC_CHECK to
                    { basis ->
                        encoded(CanonicalOperationWireBindings.diagnosticCheck, DiagnosticCheckResult(empty()), basis)
                    },
            )
        for ((operation, encode) in replies) {
            assertEquals(
                Refinement.Refined(Unit),
                operation.admitOutcome(encode(live), root, descriptor),
                operation.name,
            )
            assertEquals(
                Refinement.Rejected(ExistingIdeFailure.RESPONSE_REJECTED),
                operation.admitOutcome(encode(published), root, descriptor),
                operation.name,
            )
            assertEquals(
                Refinement.Rejected(ExistingIdeFailure.RESPONSE_REJECTED),
                operation.admitOutcome(
                    encode(live),
                    root,
                    ExistingIdeDescriptor(123, UUID.fromString("00000000-0000-0000-0000-000000000002")),
                ),
                operation.name,
            )
        }
        val query = replies.first().second(live)
        for (operation in ExistingIdeReadOperation.entries.filter { it != ExistingIdeReadOperation.QUERY_RUN }) {
            assertEquals(
                Refinement.Rejected(ExistingIdeFailure.RESPONSE_REJECTED),
                operation.admitOutcome(query, root, descriptor),
            )
        }
    }

    @Test
    fun `pre-authority hosted rejection preserves its reason for all read operations`() {
        val rejection =
            """{"schemaVersion":1,"outcome":"rejected","failure":"DIRTY_DOCUMENTS","detail":"saved content required","stage":"EPOCH_OBSERVATION"}"""
        for (kind in ExistingIdeReadOperation.entries) {
            val operation =
                ExistingIdeOperation.Read.admit(
                        PreparedCliRequest(
                            kind.canonical,
                            HostedRuntimeDemand.Operation(kind.canonical),
                            "{}",
                        ) {
                            fail("Rejection projected as semantic success")
                        }
                    )
                    .refined()
            val answer = ExistingIdeDocuments.response(rejection.toByteArray(), root, operation, descriptor)
            assertTrue(answer is ExistingIdeExchange.HostRejected, kind.name)
            assertTrue((answer as ExistingIdeExchange.HostRejected).document.value.contains("DIRTY_DOCUMENTS"))
            val exit =
                executeExistingIdeAction(
                    CliAction.Local.ExistingIde(
                        operation,
                        io.github.amichne.kast.cli.command.ide.ExistingIdeRootSelection.CurrentDirectory,
                    ),
                    root.path,
                    CanonicalRootDiscoverer { CanonicalRootDiscovery.Discovered(root) },
                    ExistingIdeClient { _, _ -> answer },
                )
            assertTrue(exit is CliExit.OperationRejected, kind.name)
            assertEquals(0, exit.code)
            assertEquals(rejection, exit.document.value)
            assertTrue(
                ExistingIdeDocuments.response(
                    rejection.replace("DIRTY_DOCUMENTS", "UNKNOWN").toByteArray(),
                    root,
                    operation,
                    descriptor,
                ) is ExistingIdeExchange.Rejected
            )
        }
    }

    @Test
    fun `live traversal projection retains the same basis at envelope and graph snapshot`() {
        val outcome =
            OperationOutcome.Complete(
                EvidenceEnvelope(
                    CanonicalOperation.TRAVERSAL_RUN.id,
                    live,
                    TraversalRunResult(text("/workspace"), empty()),
                )
            )
        val projected = CanonicalReadCliDocuments.projectTraversal(outcome) as ProjectedCliOutcome.Complete
        val document = projected.document.value.let(Json::parseToJsonElement).jsonObject
        val snapshot = document.getValue("graph").jsonObject.getValue("snapshot").jsonObject
        assertEquals(document["live"], snapshot["live"])
        assertFalse(document.containsKey("generation"))
        assertFalse(snapshot.containsKey("generation"))
    }

    @Test
    fun `source host admission rejects foreign moved and published nested evidence`() {
        val binding = CanonicalOperationWireBindings.sourceRead
        val document = Json.parseToJsonElement(encoded(binding, sourceResult(live), live)).jsonObject
        assertEquals(
            Refinement.Refined(Unit),
            ExistingIdeReadOperation.SOURCE_READ.admitOutcome(document.toString(), root, descriptor),
        )
        val foreign =
            EvidenceBasis.Live(
                LiveReadEvidence.create(
                        "/workspace",
                        UUID.fromString("00000000-0000-0000-0000-000000000002"),
                        7,
                        LiveReadContentView.SAVED_PSI_COMMITTED,
                        1,
                    )
                    .refined()
            )
        val moved =
            EvidenceBasis.Live(
                LiveReadEvidence.create(
                        "/workspace",
                        descriptor.host,
                        8,
                        LiveReadContentView.SAVED_PSI_COMMITTED,
                        1,
                    )
                    .refined()
            )
        for (basis in listOf(published, foreign, moved)) {
            val nested =
                Json.parseToJsonElement(encoded(binding, sourceResult(basis), basis))
                    .jsonObject
                    .getValue("body")
                    .jsonObject
                    .getValue("result")
                    .jsonObject
                    .getValue("snapshot")
            val body = document.getValue("body").jsonObject
            val result = body.getValue("result").jsonObject
            val conflicting =
                JsonObject(
                    document + ("body" to JsonObject(body + ("result" to JsonObject(result + ("snapshot" to nested)))))
                )
            assertEquals(
                Refinement.Rejected(ExistingIdeFailure.RESPONSE_REJECTED),
                ExistingIdeReadOperation.SOURCE_READ.admitOutcome(conflicting.toString(), root, descriptor),
            )
        }
    }

    private fun sourceResult(basis: EvidenceBasis): SourceReadResult {
        val snapshot =
            SourceSnapshotDocument(
                text("/workspace"),
                when (basis) {
                    is EvidenceBasis.Published ->
                        SourceSnapshotContextDocument.Published(basis.generation, text("source-state"))
                    is EvidenceBasis.Live -> SourceSnapshotContextDocument.Live(basis.evidence)
                },
                text("src/Empty.kt"),
                text("text-digest"),
                SourceCoordinateUnitDocument.UTF16_CODE_UNIT,
                SourceLengthDocument.parse(0).refined(),
            )
        val zero = ProtocolOffset.parse(0).refined()
        val selection =
            SourceSelectionDocument(
                text("source-selector-v1:fixture"),
                SourceSelectionRangeDocument.create(zero, zero).refined(),
            )
        return SourceReadResult(
            snapshot,
            SourceRegionDocument(SourceRegionKindDocument.FILE, selection),
            empty(),
            SourceTextProjectionDocument.NotRequested,
        )
    }

    private fun <R : OperationResult, Q : OperationQualification, F : OperationRejection> encoded(
        binding: OperationWireBinding<*, R, Q, F>,
        result: R,
        basis: EvidenceBasis,
    ): String =
        (binding.encodeOutcome(OperationOutcome.Complete(EvidenceEnvelope(binding.operation.id, basis, result)))
                as WireEncoding.Encoded)
            .document

    private fun text(value: String) = ProtocolText.parse(value).refined()

    private fun <T> empty(): BoundedProtocolList<T> = BoundedProtocolList.create(emptyList<T>()).refined()

    private fun <T, F> Refinement<T, F>.refined(): T = (this as Refinement.Refined).value
}
