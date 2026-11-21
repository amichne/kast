package io.github.amichne.kast.cli

import io.github.amichne.kast.cli.mcp.KastMcpMain
import io.github.amichne.kast.cli.publicshell.KastPublicCliMain
import io.github.amichne.kast.cli.publicshell.PublicJqWorker
import io.github.amichne.kast.cli.publicshell.nativeControlEnvironment
import io.github.amichne.kast.distribution.cli.ControlStage
import io.github.amichne.kast.distribution.cli.ControlStageOutcome
import io.github.amichne.kast.distribution.cli.ControlStageTrace
import io.github.amichne.kast.distribution.cli.main as runManagement
import io.github.amichne.kast.distribution.contract.NativeControlEntry
import io.github.amichne.kast.distribution.contract.admitNativeControlInvocation
import io.github.amichne.kast.kernel.Refinement
import kotlin.system.exitProcess

/**
 * The single JVM entry behind the installed Control executable. Each entry keeps its existing command and lifecycle
 * owner; the executable selects the entry and runs this class from `<installation>/lib`.
 */
object KastControlMain {
    @JvmStatic
    fun main(arguments: Array<String>) {
        val started = System.nanoTime()
        val environment = nativeControlEnvironment()
        ControlStageTrace.configure(environment)
        ControlStageTrace.record(ControlStage.PROCESS_START, ControlStageOutcome.CREATED, started)
        when (val admitted = admitNativeControlInvocation(arguments.toList())) {
            is Refinement.Rejected -> {
                System.err.println("kast: unsupported internal entrypoint")
                exitProcess(2)
            }
            is Refinement.Refined -> {
                val invocation = admitted.value
                val args = invocation.arguments.toTypedArray()
                when (invocation.entry) {
                    NativeControlEntry.MANAGEMENT -> {
                        val installation = coordinatedQueryInstallation(invocation.arguments, environment)
                        if (installation != null) KastPublicCliMain.coordinated(args, installation, environment)
                        runManagement(args)
                    }
                    NativeControlEntry.QUERY -> KastPublicCliMain.main(args)
                    NativeControlEntry.JQ -> PublicJqWorker.main(args)
                    NativeControlEntry.MCP -> KastMcpMain.main(args)
                    NativeControlEntry.SERVICE -> KastServiceMain.main(args)
                    NativeControlEntry.DAEMON -> KastDaemonMain.main(args)
                    NativeControlEntry.CODEX -> KastCodexMain.main(args)
                    NativeControlEntry.INSTALLER -> io.github.amichne.kast.cli.main(args)
                }
            }
        }
    }
}

private fun coordinatedQueryInstallation(
    args: List<String>,
    environment: Map<String, String>,
): java.nio.file.Path? {
    val namespace = args.firstOrNull()
    if (namespace != "workspace" && !io.github.amichne.kast.distribution.cli.isPublicQueryNamespace(namespace))
        return null
    return io.github.amichne.kast.distribution.cli.selectedPublicQueryInstallation(environment)
}
