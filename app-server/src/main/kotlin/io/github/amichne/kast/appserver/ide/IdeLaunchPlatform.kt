package io.github.amichne.kast.appserver.ide

import io.github.amichne.kast.distribution.managed.ManagedHeadlessLaunch
import io.github.amichne.kast.distribution.managed.ManagedHeadlessLaunchFiles
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.IdeLifecycleFailure

internal interface IdeLaunchPlatform {
    fun executablePath(): String? = null

    fun startManaged(specification: ManagedHeadlessLaunch): Refinement<Process, IdeLifecycleFailure>

    data object Native : IdeLaunchPlatform {
        override fun executablePath(): String? = System.getenv("PATH")

        override fun startManaged(specification: ManagedHeadlessLaunch): Refinement<Process, IdeLifecycleFailure> {
            val runtime = specification.installation
            when (val written = ManagedHeadlessLaunchFiles.write(specification)) {
                is Refinement.Rejected -> return Refinement.Rejected(IdeLifecycleFailure.LAUNCH_FAILED)
                is Refinement.Refined -> Unit
            }
            return Refinement.Refined(
                ProcessBuilder(specification.arguments)
                    .apply {
                        environment().clear()
                        environment().putAll(specification.environment)
                        redirectOutput(ProcessBuilder.Redirect.appendTo(runtime.state.resolve("launcher.log").toFile()))
                        redirectErrorStream(true)
                    }
                    .start()
            )
        }
    }
}
