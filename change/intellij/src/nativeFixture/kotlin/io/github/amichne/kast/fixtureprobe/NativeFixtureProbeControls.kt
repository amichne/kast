package io.github.amichne.kast.fixtureprobe

import com.intellij.ide.plugins.DynamicPlugins
import com.intellij.ide.plugins.PluginMainDescriptor
import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Document
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.intellij.platform.ide.progress.ModalTaskOwner
import com.intellij.platform.ide.progress.TaskCancellation
import com.intellij.platform.ide.progress.runWithModalProgressBlocking
import java.util.concurrent.atomic.AtomicReference
import kotlinx.serialization.json.Json

internal class NativeFixtureProbeControls(private val project: Project, private val sandbox: ProbeSandbox) :
    Disposable {
    private val barrier = AtomicReference<ProbeBarrierOwnership>(ProbeBarrierOwnership.Available)

    fun execute(request: ProbeRequest, document: Document, evidence: ProbeEvidence): ProbeExecution =
        when (request.command) {
            ProbeCommand.ARM_POST_SAVE_BARRIER -> arm(request, document, evidence)
            ProbeCommand.UNLOAD_PRODUCTION_PLUGIN -> unload(evidence)
            else -> ProbeExecution.Rejected(ProbeFailure.UNKNOWN_COMMAND)
        }

    private fun arm(request: ProbeRequest, document: Document, evidence: ProbeEvidence): ProbeExecution {
        val images =
            request.images as? ProbeExpectedImages.Changed
                ?: return ProbeExecution.Rejected(ProbeFailure.IMAGE_GUARD_REQUIRED)
        val owned =
            ProbeSaveBarrier(
                project = project,
                sandbox = sandbox,
                id = request.id,
                images = images,
                document = document,
            )
        if (!barrier.compareAndSet(ProbeBarrierOwnership.Available, ProbeBarrierOwnership.Armed(owned))) {
            owned.dispose()
            return ProbeExecution.Rejected(ProbeFailure.BARRIER_ALREADY_ARMED)
        }
        return when (val installed = owned.install()) {
            is ProbeResult.Accepted -> ProbeExecution.BarrierArmed(evidence, request.id)
            is ProbeResult.Rejected -> {
                owned.dispose()
                ProbeExecution.Rejected(installed.failure)
            }
        }
    }

    private fun unload(evidence: ProbeEvidence): ProbeExecution {
        if (!sandbox.valid(project)) return ProbeExecution.Rejected(ProbeFailure.SANDBOX_REJECTED)
        val id = PluginId.getId(PRODUCTION_PLUGIN_ID)
        val descriptor =
            PluginManagerCore.getPluginSet().findEnabledPlugin(id) as? PluginMainDescriptor
                ?: return ProbeExecution.Rejected(ProbeFailure.PLUGIN_UNAVAILABLE)
        return completeProbePluginUnload(
            evidence,
            check = { checkUnload(descriptor) },
            unload = { unload(descriptor, id) },
            observe = { observation ->
                Logger.getInstance(NativeFixtureProbeControls::class.java)
                    .info(
                        "kast_fixture_unload " + Json.encodeToString(ProbeUnloadObservation.serializer(), observation)
                    )
            },
        )
    }

    private fun checkUnload(descriptor: PluginMainDescriptor): ProbeResult<Unit> =
        try {
            // The platform preflight can block; the modal bridge pumps EDT while it runs on BGT.
            runWithModalProgressBlocking(
                ModalTaskOwner.project(project),
                "Checking Kast fixture plugin unload",
                cancellation = TaskCancellation.nonCancellable(),
            ) {
                ApplicationManager.getApplication().assertIsNonDispatchThread()
                if (DynamicPlugins.checkCanUnloadWithoutRestart(descriptor)) ProbeResult.Accepted(Unit)
                else ProbeResult.Rejected(ProbeFailure.PLUGIN_UNLOAD_UNSUPPORTED)
            }
        } catch (cancelled: ProcessCanceledException) {
            throw cancelled
        } catch (_: Exception) {
            ProbeResult.Rejected(ProbeFailure.PLUGIN_UNLOAD_REJECTED)
        }

    private fun unload(descriptor: PluginMainDescriptor, id: PluginId): ProbeResult<Unit> {
        return try {
            ApplicationManager.getApplication().assertIsDispatchThread()
            if (!sandbox.valid(project)) return ProbeResult.Rejected(ProbeFailure.SANDBOX_REJECTED)
            val options =
                DynamicPlugins.UnloadPluginOptions()
                    .withSave(false)
                    .withDisable(false)
                    .withRequireMemorySnapshot(false)
                    .withWaitForClassloaderUnload(true)
                    .withUnloadWaitTimeout(PLUGIN_UNLOAD_WAIT_MILLIS)
            val unloaded = DynamicPlugins.unloadPlugin(descriptor, options)
            if (unloaded && !PluginManagerCore.getPluginSet().isPluginEnabled(id)) {
                ProbeResult.Accepted(Unit)
            } else ProbeResult.Rejected(ProbeFailure.PLUGIN_UNLOAD_REJECTED)
        } catch (cancelled: ProcessCanceledException) {
            throw cancelled
        } catch (_: Exception) {
            ProbeResult.Rejected(ProbeFailure.PLUGIN_UNLOAD_REJECTED)
        }
    }

    override fun dispose() {
        when (val owned = barrier.getAndSet(ProbeBarrierOwnership.Disposed)) {
            ProbeBarrierOwnership.Available,
            ProbeBarrierOwnership.Disposed -> Unit
            is ProbeBarrierOwnership.Armed -> owned.barrier.dispose()
        }
    }
}

private sealed interface ProbeBarrierOwnership {
    data object Available : ProbeBarrierOwnership

    data object Disposed : ProbeBarrierOwnership

    data class Armed(val barrier: ProbeSaveBarrier) : ProbeBarrierOwnership
}

private const val PRODUCTION_PLUGIN_ID = "io.github.amichne.kast.ide-hosted"
private const val PLUGIN_UNLOAD_WAIT_MILLIS = 5000
