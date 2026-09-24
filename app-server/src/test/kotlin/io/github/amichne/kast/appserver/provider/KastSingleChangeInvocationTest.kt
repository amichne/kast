package io.github.amichne.kast.appserver.provider

import io.github.amichne.kast.appserver.core.BrokerInvocationContext
import io.github.amichne.kast.appserver.ide.CanonicalRoot
import io.github.amichne.kast.appserver.ide.CanonicalRootDiscoverer
import io.github.amichne.kast.appserver.ide.CanonicalRootDiscovery
import io.github.amichne.kast.appserver.ide.ExistingIdeClient
import io.github.amichne.kast.appserver.ide.ExistingIdeExchange
import io.github.amichne.kast.appserver.ide.ExistingIdeOperation
import io.github.amichne.kast.appserver.runtime.WorkspaceDemand
import io.github.amichne.kast.appserver.runtime.WorkspaceDemandResult
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.ChangeIntentDocument
import io.github.amichne.kast.protocol.contract.ChangeRequest
import io.github.amichne.kast.protocol.wire.presentation.CanonicalJsonDocument
import io.github.amichne.kast.protocol.wire.presentation.ProjectedOperationOutcome
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.security.KeyPairGenerator
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class KastSingleChangeInvocationTest {
    @TempDir lateinit var home: Path

    @Test
    fun `one broker call plans signs and returns verified receipt without controller approval`() = runBlocking {
        enroll()
        val observed = mutableListOf<String>()
        val invocation = invocation { operation ->
            when (operation) {
                is ExistingIdeOperation.Plan -> {
                    observed += "plan"
                    semantic(TestPlan())
                }
                is ExistingIdeOperation.ApprovalPreparation -> {
                    observed += "prepare-${operation.kind.name}"
                    ExistingIdeExchange.Received(document(challenge(operation.kind.name)))
                }
                is ExistingIdeOperation.ApprovedMutation -> {
                    observed += "write-${operation.kind.name}"
                    semantic(TestApplication())
                }
                else -> error("Unexpected operation")
            }
        }

        assertInstanceOf(io.github.amichne.kast.appserver.core.ProviderCall.Completed::class.java, invocation)
        val output = (invocation as io.github.amichne.kast.appserver.core.ProviderCall.Completed).value
        assertTrue(output.success)
        val body = output.document.getValue("document").jsonObject
        assertEquals("complete", body.getValue("status").jsonPrimitive.content)
        assertEquals(
            "receipt:verified",
            body.getValue("application").jsonObject.getValue("receiptIdentity").jsonPrimitive.content,
        )
        assertEquals(listOf("plan", "prepare-CHANGE_APPLY", "write-CHANGE_APPLY"), observed)
    }

    @Test
    fun `unverified write recovers inside the same broker call and returns failure evidence`() = runBlocking {
        enroll()
        val observed = mutableListOf<String>()
        val invocation = invocation { operation ->
            when (operation) {
                is ExistingIdeOperation.Plan -> {
                    observed += "plan"
                    semantic(TestPlan())
                }
                is ExistingIdeOperation.ApprovalPreparation -> {
                    observed += "prepare-${operation.kind.name}"
                    ExistingIdeExchange.Received(document(challenge(operation.kind.name)))
                }
                is ExistingIdeOperation.ApprovedMutation -> {
                    observed += "write-${operation.kind.name}"
                    if (operation.kind.name == "CHANGE_APPLY")
                        ExistingIdeExchange.Semantic(ProjectedOperationOutcome.Qualified(document(TestUnverified())))
                    else semantic(TestRecovery())
                }
                else -> error("Unexpected operation")
            }
        }
        assertInstanceOf(io.github.amichne.kast.appserver.core.ProviderCall.Completed::class.java, invocation)
        val output = (invocation as io.github.amichne.kast.appserver.core.ProviderCall.Completed).value
        assertTrue(!output.success)
        val body = output.document.getValue("document").jsonObject
        assertEquals("rejected", body.getValue("status").jsonPrimitive.content)
        val error = body.getValue("error").jsonObject
        assertEquals("APPLY_UNVERIFIED", error.getValue("code").jsonPrimitive.content)
        assertEquals("rolled_back", error.getValue("recovery").jsonObject.getValue("state").jsonPrimitive.content)
        assertEquals(
            listOf(
                "plan",
                "prepare-CHANGE_APPLY",
                "write-CHANGE_APPLY",
                "prepare-CHANGE_RECOVER",
                "write-CHANGE_RECOVER",
            ),
            observed,
        )
    }

    private suspend fun invocation(
        observe: (ExistingIdeOperation) -> ExistingIdeExchange
    ): io.github.amichne.kast.appserver.core.ProviderCall<KastInvocationOutput> {
        val root = CanonicalRoot(home.toRealPath())
        val options =
            KastProviderOptions(
                catalogSource = KastCatalogSource { error("Catalog not needed") },
                userHome = home,
                roots = CanonicalRootDiscoverer { CanonicalRootDiscovery.Discovered(root) },
                ideClient = ExistingIdeClient { _, _ -> error("Direct fallback forbidden") },
                workspaceDemand =
                    WorkspaceDemand { selected, operation ->
                        assertEquals(root, selected)
                        WorkspaceDemandResult.Native(observe(operation))
                    },
            )
        val context =
            (BrokerInvocationContext.admit("thread", "turn", "call", home.toRealPath()) as Refinement.Refined).value
        val target =
            (io.github.amichne.kast.protocol.contract.ProtocolText.parse("exact:test") as Refinement.Refined).value
        val source =
            (io.github.amichne.kast.protocol.contract.ProtocolText.parse("fun added() = Unit") as Refinement.Refined)
                .value
        return KastSingleChangeInvocation(options)
            .invoke(ChangeRequest(ChangeIntentDocument.AddDeclaration(target, source)), context)
    }

    private fun challenge(operation: String) =
        TestChallenge(
            operation = operation,
            root = home.toRealPath().toString(),
            host = UUID.randomUUID().toString(),
        )

    private fun enroll() {
        val directory = Files.createDirectories(home.resolve(".kast/approval"))
        Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwx------"))
        val keys = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        listOf("broker.pk8" to keys.private.encoded, "broker.pub" to keys.public.encoded).forEach { (name, bytes) ->
            Files.write(directory.resolve(name), bytes)
            Files.setPosixFilePermissions(directory.resolve(name), PosixFilePermissions.fromString("rw-------"))
        }
    }

    private inline fun <reified T> document(value: T): CanonicalJsonDocument =
        CanonicalJsonDocument.generated(kotlinx.serialization.serializer<T>()).create(value)

    private inline fun <reified T> semantic(value: T): ExistingIdeExchange =
        ExistingIdeExchange.Semantic(ProjectedOperationOutcome.Complete(document(value)))
}

@Serializable
private data class TestPlan(val status: String = "complete", val planIdentity: String = "plan:${"a".repeat(64)}")

@Serializable
private data class TestApplication(
    val status: String = "complete",
    val state: String = "verified",
    val receiptIdentity: String = "receipt:verified",
)

@Serializable
private data class TestUnverified(val status: String = "qualified", val state: String = "recovery_required")

@Serializable private data class TestRecovery(val status: String = "complete", val state: String = "rolled_back")

@Serializable
private data class TestChallenge(
    val version: Int = 1,
    val operation: String,
    val root: String,
    val host: String,
    val planId: String = "a".repeat(64),
    val challenge: String = "b".repeat(64),
    val preview: TestPreview = TestPreview(),
)

@Serializable
private data class TestPreview(val path: String = "src/Target.kt", val diff: String = "+fun added() = Unit")
