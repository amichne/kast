package io.github.amichne.kast.runtime.hosted.lifecycle

import io.github.amichne.kast.kernel.ReadLimits
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.HostAutoReload
import io.github.amichne.kast.protocol.contract.IdeHostBuildIdentity
import io.github.amichne.kast.protocol.contract.IdeHostRuntimeIdentity
import io.github.amichne.kast.protocol.contract.IdeLifecycleCommand
import io.github.amichne.kast.protocol.contract.IdeLifecycleFailure
import io.github.amichne.kast.protocol.contract.IdeLifecycleResult
import io.github.amichne.kast.runtime.hosted.HostedFrames
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class HostRefreshRpcTest {
    private val json = Json { encodeDefaults = true }

    @Test
    fun `settlement captures the marker before replacement and refresh never rereads its scope`(
        @TempDir temporary: Path
    ) = runTest {
        val fixture = Fixture(temporary)
        assertEquals(1, fixture.scopeReads)
        assertEquals(HostRefreshStage.CACHE_PREPARATION, fixture.preparationObservations.single().stage)
        assertEquals(
            HostRefreshOutcome.MarkerPrepared(fixture.root.preparedMarker.snapshot),
            fixture.preparationObservations.single().outcome,
        )
        fixture.observed = fixture.observed.copy(plugin = temporary.resolve("replacement-scope"))
        val reply = fixture.reply()
        assertTrue(reply.result is IdeLifecycleResult.HostRefreshRequested)
        assertEquals(1, fixture.scopeReads)
    }

    @Test
    fun `a rejected frame budget never dispatches an SDK effect`(@TempDir temporary: Path) = runTest {
        val fixture = Fixture(temporary)
        val limits =
            (ReadLimits.resolve(
                    mapOf(
                        "KAST_READ_HOST_RESPONSE_BYTES" to "256",
                        "KAST_READ_SEMANTIC_RETURNED_BYTES" to "256",
                        "KAST_READ_SOURCE_RETURNED_BYTES" to "256",
                    )
                ) as Refinement.Refined)
                .value
        val output = ByteArrayOutputStream()
        val written =
            fixture.reply().write { result -> HostedFrames.write(output, json.encodeToString(result), limits) }
        assertEquals(
            Refinement.Rejected(io.github.amichne.kast.runtime.hosted.HostedEndpointFailure.RESULT_TOO_LARGE),
            written,
        )
        assertEquals(0, output.size())
        assertEquals(0, fixture.queued.size)
        assertEquals(0, fixture.root.calls)
    }

    @Test
    fun `loaded option alone never advertises the refresh operation`(@TempDir temporary: Path) {
        val fixture = Fixture(temporary)
        val adapter = HostBundleRefreshAdapter { Refinement.Refined(fixture.observed) }
        val capability =
            io.github.amichne.kast.protocol.contract.IdeLifecycleCapabilityName.HOST_PREPARED_RELOAD_REFRESH
        assertTrue(capability in applicationLifecycleCapabilities(fixture.identity, adapter))
        fixture.observed = fixture.observed.copy(plugin = temporary.resolve("foreign"))
        assertFalse(capability in applicationLifecycleCapabilities(fixture.identity, adapter))
        assertFalse(
            capability in
                applicationLifecycleCapabilities(
                    fixture.identity.copy(autoReload = HostAutoReload.DISABLED),
                    HostBundleRefreshAdapter { error("disabled option reached SDK scope admission") },
                )
        )
    }

    @Test
    fun `encoded refresh uses scoped SDK adapter only after its complete ACK`(@TempDir temporary: Path) = runTest {
        val fixture = Fixture(temporary)
        val reply = fixture.reply()
        assertEquals(0, fixture.root.calls)
        assertEquals(0, fixture.queued.size)
        reply.write { result ->
            val output = ByteArrayOutputStream()
            val written = HostedFrames.write(output, json.encodeToString(result))
            val input = DataInputStream(ByteArrayInputStream(output.toByteArray()))
            val size = input.readInt()
            assertEquals(size + Int.SIZE_BYTES, output.size())
            val encoded = json.parseToJsonElement(input.readNBytes(size).decodeToString()).jsonObject
            assertEquals("host_refresh_requested", encoded.getValue("type").jsonPrimitive.content)
            assertEquals(setOf("type", "host", "transaction", "runtimeIdentity"), encoded.keys)
            assertEquals("host", encoded.getValue("host").jsonPrimitive.content)
            assertEquals("update", encoded.getValue("transaction").jsonPrimitive.content)
            assertEquals(
                "1.2.3",
                encoded
                    .getValue("runtimeIdentity")
                    .jsonObject
                    .getValue("buildIdentity")
                    .jsonObject
                    .getValue("version")
                    .jsonPrimitive
                    .content,
            )
            fixture.trace += "ACK"
            written
        }
        assertEquals(listOf("ACK"), fixture.trace)
        assertEquals(1, fixture.queued.size)
        fixture.queued.removeAt(0).invoke()
        assertEquals(listOf("ACK", "SDK"), fixture.trace)
        assertEquals(1, fixture.root.calls)
        fixture.root.complete()
        assertEquals(
            listOf(
                HostRefreshOutcome.Admitted,
                HostRefreshOutcome.Started,
                HostRefreshOutcome.Submitted,
                HostRefreshOutcome.Completed,
            ),
            fixture.observations.map { it.outcome },
        )
        assertFalse(reply.result is IdeLifecycleResult.HostSettled)
    }

    @Test
    fun `SDK rejection stays finite after the acknowledgment`(@TempDir temporary: Path) = runTest {
        val fixture = Fixture(temporary)
        fixture.root.submission = HostRefreshSubmission.Rejected(IdeLifecycleFailure.HOST_REFRESH_FAILED)
        fixture.reply().write {
            fixture.trace += "ACK"
            Refinement.Refined(Unit)
        }
        fixture.queued.removeAt(0).invoke()
        assertEquals(1, fixture.root.calls)
        assertEquals(
            HostRefreshOutcome.Rejected(IdeLifecycleFailure.HOST_REFRESH_FAILED),
            fixture.observations.last().outcome,
        )
        assertFalse(fixture.observations.any { it.outcome == HostRefreshOutcome.Completed })
    }

    @Test
    fun `a failed ACK write never schedules a refresh`(@TempDir temporary: Path) = runTest {
        val fixture = Fixture(temporary)
        var failure: IOException? = null
        try {
            fixture.reply().write { throw IOException("owned transport failure") }
        } catch (caught: IOException) {
            failure = caught
        }
        assertInstanceOf(IOException::class.java, failure)
        assertEquals(0, fixture.queued.size)
        assertEquals(0, fixture.root.calls)
    }

    @Test
    fun `a resumed transaction cannot use an earlier refresh ticket`(@TempDir temporary: Path) = runTest {
        val fixture = Fixture(temporary)
        fixture.reply().write {
            fixture.trace += "ACK"
            Refinement.Refined(Unit)
        }
        assertEquals(
            IdeLifecycleResult.HostResumed("host", "update"),
            fixture.settlement.resume(IdeLifecycleCommand.ResumeHost("host", "update")),
        )
        fixture.queued.removeAt(0).invoke()
        assertEquals(0, fixture.root.calls)
        assertEquals(
            HostRefreshOutcome.Rejected(IdeLifecycleFailure.REQUEST_CONFLICT),
            fixture.observations.last().outcome,
        )
        assertEquals(HostRefreshStage.DISPATCH, fixture.observations.last().stage)
    }

    @Test
    fun `marker preparation failure rejects settlement before a Host acknowledgment`(@TempDir temporary: Path) =
        runTest {
            val fixture = Fixture(temporary, IdeLifecycleFailure.HOST_REFRESH_SCOPE_MISMATCH)
            assertEquals(IdeLifecycleResult.Blocked(IdeLifecycleFailure.HOST_REFRESH_SCOPE_MISMATCH), fixture.settled)
            assertEquals(
                HostRefreshOutcome.Rejected(IdeLifecycleFailure.HOST_REFRESH_SCOPE_MISMATCH),
                fixture.preparationObservations.single().outcome,
            )
            assertEquals(IdeLifecycleResult.Blocked(IdeLifecycleFailure.REQUEST_CONFLICT), fixture.reply().result)
            assertEquals(0, fixture.root.calls)
            assertEquals(0, fixture.queued.size)
            assertEquals(
                IdeLifecycleResult.Blocked(IdeLifecycleFailure.PROJECT_BUSY),
                fixture.settlement.stop(IdeLifecycleCommand.StopHost("host", "update")),
            )
        }

    @Test
    fun `a new settlement with the same transaction cannot consume its old ticket`(@TempDir temporary: Path) = runTest {
        val fixture = Fixture(temporary)
        fixture.reply().write { Refinement.Refined(Unit) }
        assertEquals(
            IdeLifecycleResult.HostResumed("host", "update"),
            fixture.settlement.resume(IdeLifecycleCommand.ResumeHost("host", "update")),
        )
        assertTrue(
            fixture.settlement.settleForReload(IdeLifecycleCommand.SettleHostForReload("host", "update"))
                is IdeLifecycleResult.HostSettled
        )
        fixture.queued.removeAt(0).invoke()
        assertEquals(0, fixture.root.calls)
        assertEquals(
            HostRefreshOutcome.Rejected(IdeLifecycleFailure.REQUEST_CONFLICT),
            fixture.observations.last().outcome,
        )
        assertEquals(2, fixture.scopeReads)
    }

    @Test
    fun `repeated settlement preserves the prepared marker without a new scope read`(@TempDir temporary: Path) {
        val fixture = Fixture(temporary)
        assertTrue(
            fixture.settlement.settle(IdeLifecycleCommand.SettleHost("host", "update"))
                is IdeLifecycleResult.HostSettled
        )
        assertEquals(1, fixture.scopeReads)
        assertTrue(fixture.reply().result is IdeLifecycleResult.HostRefreshRequested)
    }

    @Test
    fun `unknown encoded operation never reaches admission`(@TempDir temporary: Path) {
        val fixture = Fixture(temporary)
        val encoded = json.encodeToString<IdeLifecycleCommand>(IdeLifecycleCommand.RefreshHostBundle("host", "update"))
        val reply =
            decodeLifecycleReply(encoded.replace("refresh_host_bundle", "force_refresh"), json) {
                error("unknown operation reached effects")
            }
        assertEquals(IdeLifecycleResult.Blocked(IdeLifecycleFailure.INVALID_REQUEST), reply.result)
        assertEquals(0, fixture.root.calls)
    }

    @Test
    fun `typed stage observations preserve every finite failure without paths`() {
        for ((outcome, type) in
            listOf(
                HostRefreshOutcome.Admitted to "ADMITTED",
                HostRefreshOutcome.Started to "STARTED",
                HostRefreshOutcome.Submitted to "SUBMITTED",
                HostRefreshOutcome.Completed to "COMPLETED",
                HostRefreshOutcome.Rejected(IdeLifecycleFailure.HOST_REFRESH_FAILED) to "REJECTED",
            )) {
            val actual =
                json
                    .parseToJsonElement(json.encodeToString(HostRefreshObservation(HostRefreshStage.DISPATCH, outcome)))
                    .jsonObject
            assertEquals(setOf("stage", "outcome"), actual.keys)
            assertEquals("DISPATCH", actual.getValue("stage").jsonPrimitive.content)
            val value = actual.getValue("outcome").jsonObject
            assertEquals(type, value.getValue("type").jsonPrimitive.content)
            if (outcome is HostRefreshOutcome.Rejected) {
                assertEquals(setOf("type", "reason"), value.keys)
                assertEquals("REJECTED", value.getValue("type").jsonPrimitive.content)
                assertEquals("HOST_REFRESH_FAILED", value.getValue("reason").jsonPrimitive.content)
            } else assertEquals(setOf("type"), value.keys)
        }
        val rejected =
            json
                .parseToJsonElement(
                    json.encodeToString(
                        LifecycleReplyRejection(
                            io.github.amichne.kast.runtime.hosted.HostedEndpointFailure.RESULT_TOO_LARGE
                        )
                    )
                )
                .jsonObject
        assertEquals(setOf("failure"), rejected.keys)
        assertEquals("RESULT_TOO_LARGE", rejected.getValue("failure").jsonPrimitive.content)
    }

    private class Fixture(temporary: Path, private val preparationFailure: IdeLifecycleFailure? = null) {
        val trace = mutableListOf<String>()
        val queued = mutableListOf<() -> Unit>()
        val observations = mutableListOf<HostRefreshObservation>()
        val preparationObservations = mutableListOf<HostRefreshObservation>()
        val home = Files.createDirectory(temporary.toRealPath().resolve("idea"))
        val profile = Files.createDirectory(temporary.toRealPath().resolve("profile"))
        val plugins = Files.createDirectory(profile.resolve("plugins"))
        val plugin = Files.createDirectory(plugins.resolve("kast-ide-hosted"))
        val identity =
            IdeHostRuntimeIdentity(
                home.toString(),
                profile.toString(),
                42,
                100,
                "host",
                IdeHostBuildIdentity("1.2.3", "a".repeat(40), "b".repeat(40)),
                HostAutoReload.ENABLED,
            )
        var scopeReads = 0
        val root = QueueRoot(plugin, trace)
        var observed = HostBundleRefreshObservation(home, profile, plugins, plugin, root)
        val adapter = HostBundleRefreshAdapter {
            scopeReads++
            preparationFailure?.let { Refinement.Rejected(it) } ?: Refinement.Refined(observed)
        }
        val settlement =
            HostedApplicationSettlement(
                HostedApplicationAdmission(),
                identity,
                { HostNativeSettlement.Settled },
                { false },
                { adapter.admit(identity) },
                preparationObservations::add,
            )
        private val json = Json { encodeDefaults = true }

        val settled = settlement.settleForReload(IdeLifecycleCommand.SettleHostForReload("host", "update"))

        fun reply(): LifecycleReply {
            val command = IdeLifecycleCommand.RefreshHostBundle("host", "update")
            val encoded = json.encodeToString<IdeLifecycleCommand>(command)
            return decodeLifecycleReply(encoded, json) { decoded ->
                assertEquals(command, decoded)
                hostRefreshReply(
                    decoded as IdeLifecycleCommand.RefreshHostBundle,
                    settlement,
                    launch = { queued += it },
                    observe = observations::add,
                )
            }
        }
    }

    private class QueueRoot(override val path: Path, private val trace: MutableList<String>) : HostBundleRefreshRoot {
        override val preparedMarker =
            (HostRefreshMarker.admit(HostRefreshMarkerSnapshot(100, 100, 10, 10)) as Refinement.Refined).value
        var calls = 0
        var submission: HostRefreshSubmission = HostRefreshSubmission.Submitted
        private var completion: (() -> Unit)? = null

        override fun submit(completed: () -> Unit): HostRefreshSubmission {
            assertEquals(listOf("ACK"), trace)
            check(calls++ == 0) { "unexpected excess SDK effect" }
            trace += "SDK"
            if (submission == HostRefreshSubmission.Submitted) completion = completed
            return submission
        }

        fun complete() {
            checkNotNull(completion).invoke()
            completion = null
        }
    }
}
