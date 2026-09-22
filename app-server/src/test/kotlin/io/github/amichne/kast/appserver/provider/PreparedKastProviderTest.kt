@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package io.github.amichne.kast.appserver.provider

import io.github.amichne.kast.appserver.core.Broker
import io.github.amichne.kast.appserver.core.BrokerDispatch
import io.github.amichne.kast.appserver.core.BrokerDispatchRequest
import io.github.amichne.kast.appserver.core.BrokerFailure
import io.github.amichne.kast.appserver.core.BrokerInvocationContext
import io.github.amichne.kast.appserver.core.BrokerLimits
import io.github.amichne.kast.appserver.core.ProviderNamespace
import io.github.amichne.kast.appserver.core.ToolAddress
import io.github.amichne.kast.appserver.core.ToolName
import io.github.amichne.kast.appserver.ide.ExistingIdeClient
import io.github.amichne.kast.appserver.installedKastCatalogFixture
import io.github.amichne.kast.appserver.protocol.codex.BrokerFailureDocument
import io.github.amichne.kast.appserver.protocol.codex.InvocationCertainty
import io.github.amichne.kast.appserver.protocol.codex.certainty
import io.github.amichne.kast.appserver.query.PublicToolSearchClasses
import io.github.amichne.kast.appserver.runtime.PreparedWorkspaceDemand
import io.github.amichne.kast.appserver.runtime.WorkspaceDemandCause
import io.github.amichne.kast.appserver.runtime.WorkspaceDemandFailure
import io.github.amichne.kast.appserver.runtime.WorkspacePreparations
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.Validation
import io.github.amichne.kast.protocol.contract.IdeLifecycleFailure
import io.github.amichne.kast.protocol.contract.IdeLifecycleResult
import io.github.amichne.kast.protocol.contract.ProtocolText
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class PreparedKastProviderTest {
    @Test
    fun `provider preserves preparation identity and blocker as a known pre-execution failure`(
        @TempDir directory: Path
    ) = runTest {
        val root = directory.toRealPath()
        Files.writeString(root.resolve("settings.gradle.kts"), "")
        val owner = WorkspacePreparations(this, { IdeLifecycleResult.Blocked(IdeLifecycleFailure.TRUST_REQUIRED) })
        val demand =
            PreparedWorkspaceDemand(owner, { error("unexpected inspect") }, { _, _ -> error("unexpected query") })
        val options =
            KastProviderOptions(
                RecordingCatalogSource(installedKastCatalogFixture()),
                ideClient = ExistingIdeClient { _, _ -> error("unexpected fallback") },
                ioDispatcher = StandardTestDispatcher(testScheduler),
                workspaceDemand = demand,
            )
        val qualified = KastProviderQualifier.qualify(options) as KastProviderQualification.Qualified
        val broker =
            (Broker.create(listOf(qualified.registration), BrokerLimits.defaults()) as Validation.Validated).value
        val result = broker.dispatch(request(root)) as BrokerDispatch.Rejected
        val failure =
            assertInstanceOf(
                BrokerFailure.WorkspacePreparationRejected::class.java,
                result.failure,
                result.failure.toString(),
            )
        val cause = failure.cause as WorkspaceDemandFailure.Operation
        assertEquals(root, cause.root.path)
        assertEquals(WorkspaceDemandCause.Lifecycle(IdeLifecycleFailure.TRUST_REQUIRED), cause.cause)
        assertEquals(InvocationCertainty.KNOWN, failure.certainty())
        val encoded =
            Json.encodeToJsonElement(BrokerFailureDocument.serializer(), BrokerFailureDocument.from(failure)).jsonObject
        assertEquals(setOf("failure", "workspace"), encoded.keys)
        assertEquals("WORKSPACE_PREPARATION_REJECTED", encoded.getValue("failure").jsonPrimitive.content)
        val workspace = encoded.getValue("workspace").jsonObject
        assertEquals(setOf("type", "requestId", "root", "cause"), workspace.keys)
        assertEquals(cause.id.value.toString(), workspace.getValue("requestId").jsonPrimitive.content)
        assertEquals("TRUST_REQUIRED", workspace.getValue("cause").jsonObject.getValue("reason").jsonPrimitive.content)
        owner.close()
    }

    private fun request(root: Path): BrokerDispatchRequest =
        BrokerDispatchRequest(
            ToolAddress(
                (ProviderNamespace.admit("kast") as Refinement.Refined).value,
                (ToolName.admit("search_classes") as Refinement.Refined).value,
            ),
            Json.encodeToJsonElement(
                PublicToolSearchClasses.serializer(),
                PublicToolSearchClasses((ProtocolText.parse("Order") as Refinement.Refined).value, null, null),
            ),
            (BrokerInvocationContext.admit("thread", "turn", "call", root) as Refinement.Refined).value,
        )
}
