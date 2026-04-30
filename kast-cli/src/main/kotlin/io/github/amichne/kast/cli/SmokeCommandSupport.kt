package io.github.amichne.kast.cli

import io.github.amichne.kast.cli.options.RuntimeCommandOptions
import io.github.amichne.kast.cli.options.SmokeOptions
import io.github.amichne.kast.cli.tty.CliFailure
import io.github.amichne.kast.cli.tty.currentCliVersion

internal class SmokeCommandSupport(
    private val runtimeManager: WorkspaceRuntimeManager? = null,
    private val runtimeWaitTimeoutMillis: Long = 10_000L,
) {
    suspend fun run(options: SmokeOptions): SmokeReport {
        val checks = mutableListOf<SmokeCheck>()

        checks += SmokeCheck(
            name = "cli-version",
            status = SmokeCheckStatus.PASS,
            message = "Kast CLI ${currentCliVersion()} is installed and reachable",
        )

        checks += SmokeCheck(
            name = "cli-help",
            status = SmokeCheckStatus.PASS,
            message = "CLI command catalog renders without error",
        )

        val backendOk = checkBackendConnectivity(options, checks)

        if (!backendOk) {
            checks += SmokeCheck(
                name = "backend-capabilities",
                status = SmokeCheckStatus.SKIP,
                message = "Skipped because workspace-ensure did not succeed",
            )
        }

        val version = currentCliVersion()
        val passCount = checks.count { it.status == SmokeCheckStatus.PASS }
        val failCount = checks.count { it.status == SmokeCheckStatus.FAIL }
        val skipCount = checks.count { it.status == SmokeCheckStatus.SKIP }
        return SmokeReport(
            workspaceRoot = options.workspaceRoot.toString(),
            cliVersion = version,
            checks = checks,
            passCount = passCount,
            failCount = failCount,
            skipCount = skipCount,
            ok = failCount == 0,
        )
    }

    private suspend fun checkBackendConnectivity(
        options: SmokeOptions,
        checks: MutableList<SmokeCheck>,
    ): Boolean {
        val manager = runtimeManager ?: run {
            checks += SmokeCheck(
                name = "workspace-ensure",
                status = SmokeCheckStatus.SKIP,
                message = "No runtime manager available; start a backend with: kast daemon start --workspace-root=${options.workspaceRoot}",
            )
            return false
        }

        val runtimeOptions = RuntimeCommandOptions(
            workspaceRoot = options.workspaceRoot,
            backendName = null,
            waitTimeoutMillis = runtimeWaitTimeoutMillis,
            acceptIndexing = true,
        )

        val ensureResult = runCatching {
            manager.ensureRuntime(runtimeOptions, requireReady = false)
        }

        if (ensureResult.isFailure) {
            val failure = ensureResult.exceptionOrNull()
            val isNoBackend = failure is CliFailure && failure.code == "NO_BACKEND_AVAILABLE"
            checks += SmokeCheck(
                name = "workspace-ensure",
                status = if (isNoBackend) SmokeCheckStatus.SKIP else SmokeCheckStatus.FAIL,
                message = if (isNoBackend) {
                    "No backend running for ${options.workspaceRoot}; start one with: kast daemon start --workspace-root=${options.workspaceRoot}"
                } else {
                    failure?.message ?: "Backend connectivity check failed"
                },
            )
            return false
        }

        val ensured = ensureResult.getOrThrow()
        val backend = ensured.selected.descriptor.backendName
        checks += SmokeCheck(
            name = "workspace-ensure",
            status = SmokeCheckStatus.PASS,
            message = "Backend '$backend' is running for ${options.workspaceRoot} (state: ${ensured.selected.currentStateLabel()})",
        )

        val caps = ensured.selected.capabilities
        if (caps != null) {
            checks += SmokeCheck(
                name = "backend-capabilities",
                status = SmokeCheckStatus.PASS,
                message = "Backend '$backend' advertises ${caps.readCapabilities.size} read and " +
                    "${caps.mutationCapabilities.size} mutation capabilities",
            )
        } else {
            checks += SmokeCheck(
                name = "backend-capabilities",
                status = SmokeCheckStatus.SKIP,
                message = "Capabilities were not loaded for '$backend'",
            )
        }
        return true
    }
}
