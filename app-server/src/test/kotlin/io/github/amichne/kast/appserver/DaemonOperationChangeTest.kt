package io.github.amichne.kast.appserver

import io.github.amichne.kast.appserver.ide.CanonicalRootDiscovery
import io.github.amichne.kast.appserver.ide.ExistingIdeExchange
import io.github.amichne.kast.appserver.ide.ExistingIdeFailure
import io.github.amichne.kast.appserver.ide.ExistingIdeOperation
import io.github.amichne.kast.appserver.ide.FilesystemCanonicalRootDiscovery
import io.github.amichne.kast.appserver.ide.HostedMutationOperation
import io.github.amichne.kast.appserver.runtime.WorkspaceDemand
import io.github.amichne.kast.appserver.runtime.WorkspaceDemandResult
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.CanonicalOperation
import io.github.amichne.kast.protocol.contract.ChangeApplyRequest
import io.github.amichne.kast.protocol.contract.ChangeIntentDocument
import io.github.amichne.kast.protocol.contract.ChangePlanRequest
import io.github.amichne.kast.protocol.contract.ChangeRecoverRequest
import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.protocol.contract.SymbolInspectRequest
import io.github.amichne.kast.protocol.contract.SymbolInspectTarget
import io.github.amichne.kast.protocol.wire.presentation.CanonicalJsonDocument
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class DaemonOperationChangeTest {
    @TempDir lateinit var directory: Path
    private val target = DaemonManagementTarget("installation", "epoch", "generation", "configuration")
    private val identity = "plan:${"a".repeat(64)}"
    private val assertion =
        Base64.getUrlEncoder().withoutPadding().encodeToString(Json.encodeToString(AssertionPayload).toByteArray()) +
            "." +
            Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(64))

    @Serializable private data object AssertionPayload

    @Serializable private data class Document(val value: String)

    @Serializable
    private data class ExpectedCanonicalRequest(
        val target: DaemonManagementTarget,
        val root: String,
        val selection: ExpectedCanonicalSelection,
        val version: Int,
    )

    @Serializable private data class ExpectedCanonicalSelection(val type: String, val read: ExpectedSymbolInspect)

    @Serializable private data class ExpectedSymbolInspect(val type: String, val request: SymbolInspectRequest)

    @Serializable
    private data class ExpectedPrepareRequest(
        val target: DaemonManagementTarget,
        val root: String,
        val selection: ExpectedPrepareSelection,
        val version: Int,
    )

    @Serializable private data class ExpectedPrepareSelection(val type: String, val action: ExpectedPrepare)

    @Serializable
    private data class ExpectedPrepare(
        val type: String,
        val kind: HostedMutationOperation,
        val identity: String,
    )

    @Serializable
    private data class ExpectedHosted(
        val type: String,
        val target: DaemonManagementTarget,
        val root: String,
        val document: Document,
    )

    private fun text(value: String) = (ProtocolText.parse(value) as Refinement.Refined).value

    private fun root(): Path = directory.also { Files.writeString(it.resolve("settings.gradle.kts"), "") }.toRealPath()

    private fun request(path: Path, action: DaemonChangeAction) =
        DaemonOperationRequest(target, path.toString(), DaemonOperationSelection.Change(action))

    @Test
    fun `typed canonical read is re-prepared before one native demand`() = runBlocking {
        val path = root()
        val selection =
            DaemonOperationSelection.Canonical(
                DaemonCanonicalRead.SymbolInspect(SymbolInspectRequest(SymbolInspectTarget.Exact(text("exact:opaque"))))
            )
        assertEquals(
            Json.encodeToJsonElement(
                ExpectedCanonicalRequest(
                    target,
                    path.toString(),
                    ExpectedCanonicalSelection(
                        "canonical",
                        ExpectedSymbolInspect(
                            "symbol_inspect",
                            (selection.read as DaemonCanonicalRead.SymbolInspect).request,
                        ),
                    ),
                    2,
                )
            ),
            DaemonOperationProtocol.json.encodeToJsonElement(
                DaemonOperationRequest.serializer(),
                DaemonOperationRequest(target, path.toString(), selection),
            ),
        )
        var calls = 0
        val service =
            DaemonOperation(
                target,
                { true },
                WorkspaceDemand { admittedRoot, operation ->
                    assertEquals(path, admittedRoot.path)
                    assertEquals(
                        CanonicalOperation.SYMBOL_INSPECT,
                        (operation as ExistingIdeOperation.Read).request.operation,
                    )
                    calls++
                    WorkspaceDemandResult.Native(ExistingIdeExchange.Rejected(ExistingIdeFailure.HOST_UNAVAILABLE))
                },
            )
        assertEquals(
            DaemonOperationFailure.Host(ExistingIdeFailure.HOST_UNAVAILABLE),
            (service.execute(DaemonOperationRequest(target, path.toString(), selection))
                    as DaemonOperationResponse.Rejected)
                .failure,
        )
        assertEquals(1, calls)
    }

    @Test
    fun `plan admits only the supported intent before native demand`() = runBlocking {
        val path = root()
        var calls = 0
        val service =
            DaemonOperation(
                target,
                { true },
                WorkspaceDemand { _, operation ->
                    assertEquals(
                        CanonicalOperation.CHANGE_PLAN,
                        (operation as ExistingIdeOperation.Plan).request.operation,
                    )
                    calls++
                    WorkspaceDemandResult.Native(ExistingIdeExchange.Rejected(ExistingIdeFailure.HOST_UNAVAILABLE))
                },
            )
        val supported =
            DaemonChangeAction.Plan(
                ChangePlanRequest(ChangeIntentDocument.AddDeclaration(text("exact:opaque"), text("fun added() = Unit")))
            )
        assertEquals(
            DaemonOperationFailure.Host(ExistingIdeFailure.HOST_UNAVAILABLE),
            (service.execute(request(path, supported)) as DaemonOperationResponse.Rejected).failure,
        )
        val unsupported =
            DaemonChangeAction.Plan(
                ChangePlanRequest(ChangeIntentDocument.AddFile(text("src/New.kt"), text("class New")))
            )
        assertEquals(
            DaemonOperationFailure.Host(ExistingIdeFailure.OPERATION_UNSUPPORTED),
            (service.execute(request(path, unsupported)) as DaemonOperationResponse.Rejected).failure,
        )
        assertEquals(1, calls)
    }

    @Test
    fun `approval preparation retains plan identity and rejects invalid input before native demand`() = runBlocking {
        val path = root()
        val admittedRoot = (FilesystemCanonicalRootDiscovery.discover(path) as CanonicalRootDiscovery.Discovered).root
        val challenge = CanonicalJsonDocument.generated(Document.serializer()).create(Document("challenge"))
        var calls = 0
        val service =
            DaemonOperation(
                target,
                { true },
                WorkspaceDemand { root, operation ->
                    assertEquals(admittedRoot, root)
                    val preparation = operation as ExistingIdeOperation.ApprovalPreparation
                    assertEquals(HostedMutationOperation.CHANGE_APPLY, preparation.kind)
                    assertEquals(identity, preparation.identity.value)
                    calls++
                    WorkspaceDemandResult.Native(ExistingIdeExchange.Received(challenge))
                },
            )
        val action = DaemonChangeAction.Prepare(HostedMutationOperation.CHANGE_APPLY, identity)
        val hosted =
            assertInstanceOf(
                DaemonOperationResponse.Hosted::class.java,
                service.execute(request(path, action)),
            )
        assertEquals(target, hosted.target)
        assertEquals(path.toString(), hosted.root)
        assertEquals(Json.encodeToJsonElement(Document("challenge")), hosted.document)
        val client =
            assertInstanceOf(
                DaemonOperationResult.Hosted::class.java,
                admitOperationResponse(hosted, target, admittedRoot, true),
            )
        assertEquals(challenge.value, client.document.value)
        assertEquals(
            DaemonOperationFailure.Host(ExistingIdeFailure.APPROVAL_REJECTED),
            (service.execute(request(path, DaemonChangeAction.Prepare(HostedMutationOperation.CHANGE_APPLY, "invalid")))
                    as DaemonOperationResponse.Rejected)
                .failure,
        )
        assertEquals(1, calls)
    }

    @Test
    fun `approval wire preserves required discriminators and rejects a challenge on a read call`() {
        val path = root()
        val admittedRoot = (FilesystemCanonicalRootDiscovery.discover(path) as CanonicalRootDiscovery.Discovered).root
        val action = DaemonChangeAction.Prepare(HostedMutationOperation.CHANGE_APPLY, identity)
        val expected =
            ExpectedPrepareRequest(
                target,
                path.toString(),
                ExpectedPrepareSelection(
                    "change",
                    ExpectedPrepare("prepare", HostedMutationOperation.CHANGE_APPLY, identity),
                ),
                2,
            )
        assertEquals(
            Json.encodeToJsonElement(expected),
            DaemonOperationProtocol.json.encodeToJsonElement(
                DaemonOperationRequest.serializer(),
                request(path, action),
            ),
        )
        val hosted =
            DaemonOperationResponse.Hosted(
                target,
                path.toString(),
                Json.encodeToJsonElement(Document("challenge")),
            )
        assertEquals(
            Json.encodeToJsonElement(ExpectedHosted("hosted", target, path.toString(), Document("challenge"))),
            DaemonOperationProtocol.json.encodeToJsonElement(DaemonOperationResponse.serializer(), hosted),
        )
        assertEquals(
            DaemonOperationClientFailure.RESPONSE_REJECTED,
            ((admitOperationResponse(hosted, target, admittedRoot) as DaemonOperationResult.Rejected).failure
                    as DaemonOperationClientRejection.Transport)
                .failure,
        )
    }

    @Test
    fun `approved mutations retain exact identity and assertion while invalid proof prevents demand`() = runBlocking {
        val path = root()
        var calls = 0
        val service =
            DaemonOperation(
                target,
                { true },
                WorkspaceDemand { _, operation ->
                    val approved = operation as ExistingIdeOperation.ApprovedMutation
                    assertEquals(identity, approved.identity.value)
                    assertEquals(assertion, approved.assertion.value)
                    assertTrue(
                        approved.kind in
                            setOf(HostedMutationOperation.CHANGE_APPLY, HostedMutationOperation.CHANGE_RECOVER)
                    )
                    calls++
                    WorkspaceDemandResult.Native(ExistingIdeExchange.Rejected(ExistingIdeFailure.HOST_UNAVAILABLE))
                },
            )
        val actions =
            listOf(
                DaemonChangeAction.Apply(ChangeApplyRequest(text(identity)), assertion),
                DaemonChangeAction.Recover(ChangeRecoverRequest(text(identity)), assertion),
            )
        for (action in actions) assertEquals(
            DaemonOperationFailure.Host(ExistingIdeFailure.HOST_UNAVAILABLE),
            (service.execute(request(path, action)) as DaemonOperationResponse.Rejected).failure,
        )
        assertEquals(2, calls)
        assertEquals(
            DaemonOperationFailure.Host(ExistingIdeFailure.APPROVAL_REJECTED),
            (service.execute(request(path, DaemonChangeAction.Apply(ChangeApplyRequest(text(identity)), "invalid")))
                    as DaemonOperationResponse.Rejected)
                .failure,
        )
        assertEquals(2, calls)
    }
}
