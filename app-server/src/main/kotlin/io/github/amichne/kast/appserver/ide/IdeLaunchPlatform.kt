package io.github.amichne.kast.appserver.ide

import io.github.amichne.kast.distribution.managed.ManagedHeadlessLaunch
import io.github.amichne.kast.distribution.managed.ManagedHeadlessLaunchFiles
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.IdeLifecycleFailure
import java.io.File

internal interface IdeLaunchPlatform {
    fun executablePath(): String? = null

    fun startManaged(specification: ManagedHeadlessLaunch): Refinement<Process, IdeLifecycleFailure>

    data object Native : IdeLaunchPlatform {
        override fun executablePath(): String? = System.getenv("PATH")

        override fun startManaged(specification: ManagedHeadlessLaunch): Refinement<Process, IdeLifecycleFailure> {
            when (val written = ManagedHeadlessLaunchFiles.write(specification)) {
                is Refinement.Rejected -> return Refinement.Rejected(IdeLifecycleFailure.LAUNCH_FAILED)
                is Refinement.Refined -> Unit
            }
            return Refinement.Refined(
                ProcessBuilder(specification.arguments)
                    .directory(specification.installation.state.toFile())
                    .redirectInput(ProcessBuilder.Redirect.from(File("/dev/null")))
                    .apply {
                        environment().clear()
                        environment().putAll(specification.environment)
                        redirectOutput(ProcessBuilder.Redirect.appendTo(specification.launcherLog.toFile()))
                        redirectErrorStream(true)
                    }
                    .start()
            )
        }
    }
}
