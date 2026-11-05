package io.github.amichne.kast.cli.publicshell

import org.junit.jupiter.api.Tag

/** Run the identical empty-PATH, deadline and cancellation contract against the actual native image. */
@Tag("public-launcher")
class NativeJqWorkerProcessTest : PublicJqWorkerProcessTest() {
    override fun runtimeArguments(): List<String> =
        listOf(requiredExecutable("kast.publicLauncher").toString(), "-Xmx512m", "--internal-entrypoint=jq")
}
