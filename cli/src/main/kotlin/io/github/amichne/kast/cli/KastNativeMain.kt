package io.github.amichne.kast.cli

import io.github.amichne.kast.cli.mcp.KastMcpMain
import io.github.amichne.kast.cli.publicshell.KastPublicCliMain
import io.github.amichne.kast.cli.publicshell.PublicJqWorker
import io.github.amichne.kast.cli.rpc.KastToolRpcMain
import io.github.amichne.kast.distribution.cli.ControlStage
import io.github.amichne.kast.distribution.cli.ControlStageOutcome
import io.github.amichne.kast.distribution.cli.ControlStageTrace
import io.github.amichne.kast.distribution.cli.main as runManagement
import io.github.amichne.kast.distribution.contract.NativeControlEntry
import io.github.amichne.kast.distribution.contract.admitNativeControlInvocation
import io.github.amichne.kast.kernel.Refinement
import kotlin.system.exitProcess

/** One image owns the installed process entries. Each entry keeps its existing command and lifecycle owner. */
object KastNativeMain {
    @JvmStatic
    fun main(arguments: Array<String>) {
        val started = System.nanoTime()
        ControlStageTrace.configure(System.getenv())
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
                        val installation = coordinatedQueryInstallation(invocation.arguments)
                        if (installation != null) KastPublicCliMain.coordinated(args, installation)
                        runManagement(args)
                    }
                    NativeControlEntry.QUERY -> KastPublicCliMain.main(args)
                    NativeControlEntry.JQ -> PublicJqWorker.main(args)
                    NativeControlEntry.MCP -> KastMcpMain.main(args)
                    NativeControlEntry.RPC -> KastToolRpcMain.main(args)
                    NativeControlEntry.SERVICE -> KastServiceMain.main(args)
                    NativeControlEntry.DAEMON -> KastDaemonMain.main(args)
                    NativeControlEntry.CODEX -> KastCodexMain.main(args)
                    NativeControlEntry.INSTALLER -> io.github.amichne.kast.cli.main(args)
                }
            }
        }
    }
}

private fun coordinatedQueryInstallation(args: List<String>): java.nio.file.Path? {
    if (System.getProperty("org.graalvm.nativeimage.imagecode") != "runtime") return null
    if (args.firstOrNull() != "query" || args.drop(1).none { it == "--name" || it.startsWith("--name=") })
        return null
    return io.github.amichne.kast.distribution.cli.selectedPublicQueryInstallation(System.getenv())
}
