package io.github.amichne.kast.appserver.runtime

import io.github.amichne.kast.appserver.ide.CanonicalRoot
import io.github.amichne.kast.appserver.ide.ExistingIdeClassName
import io.github.amichne.kast.appserver.ide.ExistingIdeExchange
import io.github.amichne.kast.appserver.ide.ExistingIdeOperation
import io.github.amichne.kast.appserver.protocol.codex.ProtocolRouting
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.HostedCompatibilityDocument
import io.github.amichne.kast.protocol.contract.HostedContractDocument
import io.github.amichne.kast.protocol.contract.IdeLifecycleResult
import io.github.amichne.kast.protocol.contract.IdeLifecycleStage
import io.github.amichne.kast.protocol.contract.IdeProjectDescription
import io.github.amichne.kast.protocol.contract.IdeProjectOwnership
import io.github.amichne.kast.protocol.contract.IdeProjectTarget
import io.github.amichne.kast.protocol.contract.WorkspaceLifecycleRequest
import io.github.amichne.kast.protocol.wire.presentation.CanonicalJsonDocument
import io.github.amichne.kast.protocol.wire.presentation.ProjectedOperationOutcome
import java.nio.file.Path
import kotlinx.serialization.Serializable
import kotlinx.serialization.serializer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue

/** Detached typed witnesses and a strict lifecycle effect script; no native or resource fixtures. */
internal class WorkspaceRecoveryNativeFixture {
    val root = CanonicalRoot(Path.of("/workspace"))
    val target =
        IdeProjectTarget(
            "00000000-0000-0000-0000-000000000001",
            "00000000-0000-0000-0000-000000000002",
            "/workspace",
        )
    private val document =
        CanonicalJsonDocument.generated(serializer<NativeQueryResult>())
            .create(NativeQueryResult("M1", listOf("Subject")))
    val native = ExistingIdeExchange.Semantic(ProjectedOperationOutcome.Complete(document))
    val reply = ProtocolRouting.ReplyUpstream(document.value)
    val readOperation =
        ExistingIdeOperation.Classes((ExistingIdeClassName.parse("Subject") as Refinement.Refined).value)

    fun inspected() =
        IdeLifecycleResult.Inspected(
            target.host,
            "/idea",
            "IU-262.1",
            listOf(IdeProjectDescription(target, IdeProjectOwnership.MANAGED, 1)),
            HostedCompatibilityDocument(
                "262.1.1",
                "262.1.1-IJ",
                "0.50.0",
                HostedContractDocument(
                    runtimeProtocolIdentity = "kast.ide-hosted.runtime.v3",
                    operationRegistryDigest = "a".repeat(64),
                    wireSchemaDigest = "b".repeat(64),
                    capabilities = listOf("query.run"),
                ),
            ),
        )

    fun pending(number: Int) =
        IdeLifecycleResult.Pending(
            id(number).value.toString(),
            IdeLifecycleStage.IMPORTING,
            target.host,
        )

    fun open(number: Int) = WorkspaceLifecycleRequest.Open(root.path.toString(), id(number).value.toString())

    fun status(number: Int) = WorkspaceLifecycleRequest.Status(target.host, id(number).value.toString())

    fun id(number: Int) =
        checkNotNull(WorkspacePreparationId.admit("00000000-0000-0000-0000-${number.toString().padStart(12, '0')}"))

    data class Step(val request: WorkspaceLifecycleRequest, val result: suspend () -> IdeLifecycleResult)

    class Script(steps: List<Step>) {
        private val remaining = ArrayDeque(steps)

        suspend fun exchange(request: WorkspaceLifecycleRequest): IdeLifecycleResult {
            assertTrue(remaining.isNotEmpty(), "unexpected lifecycle exchange")
            val step = remaining.removeFirst()
            assertEquals(step.request, request)
            return step.result()
        }

        fun assertDrained() = assertEquals(0, remaining.size, "unconsumed lifecycle exchanges")
    }

    @Serializable private data class NativeQueryResult(val admittedEpoch: String, val symbols: List<String>)
}

internal fun Refinement<WorkspacePreparation, WorkspacePreparationFailure>.recoveryEntry() =
    (this as Refinement.Refined).value
