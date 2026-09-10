package io.github.amichne.kast.workspace.intellij.read.hosted

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.IdeHostCompatibilityCandidate
import io.github.amichne.kast.protocol.contract.IdeHostCompatibilityPolicy
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.nio.file.Path
import java.util.Properties
import java.util.function.Consumer

/** Java-callable manual harness over the same endpoint implementation shipped by the plugin. */
class HostedQueryProbe private constructor(project: Project, binding: ProbeServiceBinding) {
    constructor(project: Project) : this(project, ProbeServiceBinding.Manual(HostedReadCheckpoint.Unobserved))
    constructor(project: Project, checkpoint: Consumer<String>) : this(project, ProbeServiceBinding.Manual(HostedReadCheckpoint {
        check(!ApplicationManager.getApplication().isReadAccessAllowed)
        checkpoint.accept("SEMANTIC_READ_DETACHED")
    }))
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val service = when (binding) {
        is ProbeServiceBinding.Manual -> HostedQueryService.observed(project, scope, binding.checkpoint).also {
            Disposer.register(project, it)
        }
        ProbeServiceBinding.Platform -> project.getService(HostedQueryService::class.java)
    }

    companion object {
        /** Manual transport over the actual platform-created light service and injected scope. */
        @JvmStatic fun installed(project: Project) = HostedQueryProbe(project, ProbeServiceBinding.Platform)
    }

    /** Result callback runs after read access has been released; caller owns transport and storage. */
    fun query(canonicalRoot: String, relativeFile: String, nameOffset: Int, result: Consumer<String>) {
        scope.launch {
            val path = try { Path.of(canonicalRoot) } catch (_: java.nio.file.InvalidPathException) {
                result.accept(HostedQueryWire.encode(HostedQueryResult.Rejected(HostedQueryFailure.INVALID_SELECTION)))
                return@launch
            }
            val root = when (val parsed = CanonicalWorkspaceRoot.fromCanonicalPath(path)) {
                is Refinement.Refined -> parsed.value
                is Refinement.Rejected -> {
                    result.accept(HostedQueryWire.encode(HostedQueryResult.Rejected(HostedQueryFailure.INVALID_SELECTION)))
                    return@launch
                }
            }
            val selection = when (val parsed = HostedKotlinSelection.parse(root, relativeFile, nameOffset)) {
                is Refinement.Refined -> parsed.value
                is Refinement.Rejected -> {
                    result.accept(HostedQueryWire.encode(HostedQueryResult.Rejected(parsed.failure)))
                    return@launch
                }
            }
            val metadata = Properties().apply {
                HostedQueryProbe::class.java.getResourceAsStream("/kast-hosted-query.properties").use { input ->
                    checkNotNull(input) { "Hosted plugin metadata is missing" }
                    load(input)
                }
            }
            val candidate = IdeHostCompatibilityCandidate(
                metadata.getProperty("ideBuild"), metadata.getProperty("kotlinBuild"),
                metadata.getProperty("version"), "kast.ide-hosted.runtime.v1",
                metadata.getProperty("registryDigest"), metadata.getProperty("schemaDigest"), emptyList(),
            )
            val policy = when (val defined = IdeHostCompatibilityPolicy.define(candidate)) {
                is Refinement.Refined -> defined.value
                is Refinement.Rejected -> error("Packaged compatibility metadata is invalid")
            }
            val answer = service.query(service.endpoint, selection, candidate, policy)
            check(!ApplicationManager.getApplication().isReadAccessAllowed)
            result.accept(HostedQueryWire.encode(answer))
        }
    }

    /** Manual acceptance controls; disposal and caller cancellation use the real original owner. */
    fun disposeOwner() { Disposer.dispose(service) }
    fun cancelRequest() { scope.cancel() }

    fun detach(result: Consumer<String>) {
        // Retirement must still run after caller cancellation or project-close disposal.
        val retirement = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        retirement.launch {
            try {
                service.detach()
                Disposer.dispose(service)
                result.accept("RETIRED")
            } finally {
                scope.cancel()
                retirement.cancel()
            }
        }
    }
}

private sealed interface ProbeServiceBinding {
    data class Manual(val checkpoint: HostedReadCheckpoint) : ProbeServiceBinding
    data object Platform : ProbeServiceBinding
}
